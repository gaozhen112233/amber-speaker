package com.amber.speaker.sensor;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 在场停留监视器：把「不可靠的 PIR 运动脉冲」合成「稳定的在场信号」。
 *
 * <p>这颗 hum_sensor（PIR 人体红外）只在检测到<b>运动</b>时发一个 DOWN，约 3 秒后自动
 * 回 UP。人静止坐着时它完全静默。所以它无法单独回答「人是不是还在旁边」——而这正是
 * 契约 §4.2「在场停留搭话」需要的：持续在场满 5 分钟才主动搭一次话。
 *
 * <p>本类解决这个问题：PIR 运动之后，用摄像头周期性地做一次硬件人脸检测（每次 ≤2 秒、
 * 不取帧不落盘，守 §16.1），把「运动信号」和「画面里有没有人」两个证据合并成一个
 * <b>稳定的在场布尔</b>，喂给 {@code WakeState#onPresence}。
 *
 * <pre>
 * PIR 运动 ──┐
 *            ├─ 合并 ──> 稳定「在场/离开」 ──> wake.onPresence(bool)
 * 摄像头周期确认 ──┘
 * </pre>
 *
 * <p>判定规则（务实折中，硬件极限内尽量不误判）：
 * <ul>
 *   <li>任一「运动」或「确认到人脸」都刷新「在场时刻」；</li>
 *   <li>距「在场时刻」超过 {@link #ABSENT_AFTER_MS} 都没再刷新，才判「人走了」。</li>
 * </ul>
 *
 * <p>这样三种情况都覆盖：
 * <ul>
 *   <li>人坐着微动 → PIR 持续刷新 → 在场；</li>
 *   <li>人坐着不动但正对摄像头 → 摄像头确认到人脸刷新 → 在场；</li>
 *   <li>人真走了 → 一段时间后 PIR 无运动、摄像头也确认不到 → 判离开。</li>
 * </ul>
 * 唯一覆盖不了的是「人一动不动且背对摄像头」——这是 PIR + 人脸检测的硬件极限，接受它。
 *
 * <p>摄像头只在「PIR 触发过、疑似在场」期间周期性开（间隔 {@link #CHECK_INTERVAL_MS}），
 * 每次确认完立即关，不是常开。熄屏（睡眠状态）时外部调 {@link #stop()} 整体停掉。
 */
public final class PresenceWatch {

    private static final String TAG = "PresenceWatch";

    /** 摄像头确认间隔：每隔这么久开一次摄像头确认「人还在不在」。20 秒，折中省电与及时。 */
    private static final long CHECK_INTERVAL_MS = 20000;

    /**
     * 连续这么久都没有「运动 / 人脸」信号，判「人走了」。
     *
     * <p>60 秒太短：人坐着不动、又恰好没正对摄像头时，三轮确认都看不到脸就判离开，
     * 在场计时被清零，5 分钟的「主动搭话」永远攒不够。改成 180 秒（9 个确认周期），
     * 给「坐着没动但人还在」留足余量；真走了也只是晚 3 分钟才低头，代价很小。
     */
    private static final long ABSENT_AFTER_MS = 180000;

    /** 在场状态变化回调。near=true 表示稳定判定「人在场」，false 表示「人离开」。 */
    public interface Listener {
        void onPresence(boolean near);
    }

    /**
     * 人脸框宽度回调（§8.7 touch 换语义用）：每轮摄像头确认到脸时回调，带最大人脸框的
     * 归一化宽度（±1000 归一化，脸越大越近）。检测不到脸时 width = -1。
     */
    public interface FaceListener {
        void onFaceWidth(float width);
    }

    private final Listener listener;
    private final FaceListener faceListener;
    private final FaceConfirm faceConfirm;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 当前稳定判定的在场状态。 */
    private boolean present = false;
    /** 最后一次「运动 / 人脸」刷新时刻。 */
    private long lastSeenAt = -1;
    /** 摄像头确认是否正在跑（FaceConfirm 幂等，这里自己再挡一道省得空转）。 */
    private boolean checking = false;

    private final Runnable checkTask = new Runnable() {
        @Override
        public void run() {
            scheduleCheck();
        }
    };

    /**
     * @param listener     稳定在场状态回调（喂给 {@code WakeState#onPresence}）
     * @param faceConfirm  复用同一个 {@code FaceConfirm} 做摄像头人脸确认（单次 ≤2s）
     */
    public PresenceWatch(Listener listener, FaceConfirm faceConfirm) {
        this(listener, null, faceConfirm);
    }

    /**
     * @param listener     稳定在场状态回调（喂给 {@code WakeState#onPresence}）
     * @param faceListener 人脸框宽度回调（§8.7 估距离用），可为 {@code null}
     * @param faceConfirm  复用同一个 {@code FaceConfirm} 做摄像头人脸确认（单次 ≤2s）
     */
    public PresenceWatch(Listener listener, FaceListener faceListener, FaceConfirm faceConfirm) {
        this.listener = listener;
        this.faceListener = faceListener;
        this.faceConfirm = faceConfirm;
    }

    /**
     * PIR 检测到一次运动（有人活动）。刷新「在场」，并（若尚未在场）进入在场状态。
     *
     * <p><b>不在上升沿立刻开摄像头</b>：人重新走近时，走近唤醒（proximity）需要先做一次
     * 摄像头二次确认，和本类的周期确认共用同一个 FaceConfirm（单实例、单摄像头）。如果这里
     * 抢先 scheduleCheck 占用摄像头，走近唤醒的 confirm 会被 FaceConfirm 的幂等保护直接
     * 「忽略」，导致确认真人的回调永远收不到、走近唤醒失效（这就是「靠近没反应」的根因）。
     *
     * <p>所以上升沿只置状态、不碰摄像头，把摄像头让给走近确认；走近确认结束后由调用方调
     * {@link #startConfirming()} 再启动周期确认。已持续在场时（非上升沿）才直接刷新确认。
     */
    public synchronized void onMotion() {
        touch();
        if (!present) {
            setPresent(true);
            // 上升沿：不在这里 scheduleCheck，把摄像头让给走近确认；
            // 走近确认结束后由调用方调 startConfirming() 再启动周期确认。
            return;
        }
        // 已持续在场：这次只是「还在活动」的刷新，周期确认照常跑（若在确认中则不重复）。
    }

    /**
     * 走近唤醒确认结束后调用：真正启动「在场」周期确认。
     *
     * <p>由 MainActivity 在走近确认的 onConfirmed 回调里调（无论成功失败），因为这时
     * 摄像头已释放，可以安全开始周期确认了。幂等：已在确认中则忽略。
     */
    public synchronized void startConfirming() {
        if (present) {
            main.removeCallbacks(checkTask);
            scheduleCheck();
        }
    }

    /**
     * 主动探测一次「画面里有没有人」（无 PIR 运动时的兜底）。
     *
     * <p>为什么需要：这颗 PIR 只对横向运动敏感，人从侧面走近、或者一直坐着，它可能
     * 一个事件都不发。真机上的表现就是「走到面前了她也没反应」。所以由 MainActivity
     * 的心跳在 **idle + 亮屏** 时低频（60 秒）调一次这里，用相机硬件人脸检测补上
     * 「有人来了」的判定。仍然只检测、不取帧、不落盘，单次 ≤5 秒（§16.1）。
     *
     * <p>只在「当前判定为不在场」时探测；已经在场就交给正常的 20 秒周期确认。
     */
    public synchronized void probe() {
        if (present || checking || faceConfirm == null) {
            return;
        }
        Log.i(TAG, "主动探测：PIR 没动静，开一次摄像头看人在不在");
        checking = true;
        faceConfirm.confirmSized(new FaceConfirm.SizedListener() {
            @Override
            public void onConfirmed(final boolean human, final float width) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (human) {
                            touch();
                            if (faceListener != null) {
                                faceListener.onFaceWidth(width);
                            }
                            // 直接切到「在场」：会触发 onPresence(true) 上升沿，
                            // MainActivity 据此做走近唤醒。周期确认由它接着启动。
                            setPresent(true);
                        } else if (faceListener != null) {
                            faceListener.onFaceWidth(-1);
                        }
                    }
                });
            }
        });
    }

    /** 刷新「在场时刻」。任何「人还在」的证据都调这个。 */
    private void touch() {
        lastSeenAt = android.os.SystemClock.elapsedRealtime();
    }

    /** 切换稳定在场状态，只在下游状态真正变化时通知（避免重复喂 onPresence 刷屏）。 */
    private void setPresent(boolean near) {
        if (near == present) {
            return;
        }
        present = near;
        Log.i(TAG, near ? "稳定判定：人在场" : "稳定判定：人离开");
        if (listener != null) {
            listener.onPresence(near);
        }
        if (!near) {
            // 人离开：停掉周期确认。
            main.removeCallbacks(checkTask);
            checking = false;
        }
        // 进入在场时<b>不</b>在这里开摄像头（scheduleCheck），那是走近唤醒确认的活；
        // 由调用方在走近确认结束后调 startConfirming() 启动周期确认。
    }

    /**
     * 刷新「人还在」的证据，但<b>不制造上升沿</b>。
     *
     * <p>用于「她正在跟人说话」这种间接证据：有人在对话，当然算在场。但如果是定时播报
     * 触发的 /say（屋里可能没人），调用 {@link #onMotion()} 会凭空造出一个「人刚走近」，
     * 于是她反手又来一句「你来啦」——所以这里只在已经判为在场时续命，不在场就什么都不做。
     */
    public synchronized void refresh() {
        if (present) {
            touch();
        }
    }

    /** 安排一次摄像头确认（若上一次还没结束就跳过，等下一个周期）。 */
    private void scheduleCheck() {
        if (checking) {
            return;
        }
        // 先把可能已经排队的周期任务撤掉：每次 PIR 运动都会调 startConfirming，
        // 不撤的话队列里会越堆越多，摄像头被连续打开，既费电又和视线追踪抢。
        main.removeCallbacks(checkTask);
        checking = true;
        if (faceListener != null) {
            // 带人脸框宽度的确认：把「脸多大」也交出去，供 §8.7 估距离。
            faceConfirm.confirmSized(new FaceConfirm.SizedListener() {
                @Override
                public void onConfirmed(final boolean human, final float width) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            checking = false;
                            if (human) {
                                touch();  // 确认到人脸：人还在
                            }
                            faceListener.onFaceWidth(human ? width : -1);
                            // 无论确认到与否，先判是否该「离开」，再安排下一次确认
                            reevaluate();
                        }
                    });
                }
            });
        } else {
            faceConfirm.confirm(new FaceConfirm.Listener() {
                @Override
                public void onConfirmed(final boolean human) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            checking = false;
                            if (human) {
                                touch();  // 确认到人脸：人还在
                            }
                            // 无论确认到与否，先判是否该「离开」，再安排下一次确认
                            reevaluate();
                        }
                    });
                }
            });
        }
    }

    /** 确认回调后：判断是否超时该判离开；还在场则安排下一次确认。 */
    private void reevaluate() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (present && lastSeenAt >= 0 && now - lastSeenAt >= ABSENT_AFTER_MS) {
            setPresent(false);  // 太久没「运动/人脸」证据，判离开
            return;
        }
        if (present) {
            main.removeCallbacks(checkTask);
            main.postDelayed(checkTask, CHECK_INTERVAL_MS);  // 继续周期确认
        }
    }

    /**
     * 熄屏 / 睡眠时整体停掉：清状态、停周期确认。之后再有运动会重新开始。
     */
    public synchronized void stop() {
        main.removeCallbacks(checkTask);
        checking = false;
        if (present) {
            setPresent(false);
        }
        lastSeenAt = -1;
    }
}

package com.amber.speaker.sensor;

import android.os.SystemClock;
import android.util.Log;

/**
 * §8.7 触碰（touch）在音箱上的换语义：把「摸头 / 盯着看 / 拎起来」换成
 * 「走近 / 注视 / 离开」，用人体感应 + 摄像头人脸框估算距离来驱动她的反应。
 *
 * <p>桌面端靠鼠标落点，全息音箱摸不到；但音箱有摄像头 + 人体感应，能「感觉到你在不在、
 * 离多近、看多久」。这正是契约 §8.7 定的替换语义：
 *
 * <pre>
 * 桌面（鼠标）     音箱（感应/摄像头）      她做什么
 * ─────────────────────────────────────────────────
 * 摸头            走近到 1 米内            抬头看你，慢慢眨眼
 * 盯着看          注视超过 3 秒            歪头、脸红
 * 拎起来          离开超过 3 米            低头继续做手里的事
 * </pre>
 *
 * <h3>信号来源（关键：不自己开摄像头）</h3>
 * 本类<b>不开摄像头</b>——摄像头是单实例单颗（Camera 0），FaceConfirm / GazeTracker /
 * PresenceWatch / Snapshot 都在抢，再开一个必然撞车（那是「靠近没反应」的老坑）。
 * 它只<b>消费</b> {@link PresenceWatch} 已有的周期确认结果：PresenceWatch 每 20 秒开一次
 * 摄像头确认「画面里还有没有人」，正好把这个确认升级成带人脸框宽度的 {@code confirmSized}，
 * 把「看到脸了吗」+「脸多大（多近）」一起喂给本类。
 *
 * <h3>距离估算</h3>
 * 硬件人脸检测给的 {@code rect.width()} 是画面半宽归一化到 ±1000 的<b>脸框宽度</b>，
 * 脸越大说明离摄像头越近。用两个可调阈值把它分成「近 / 中 / 远」三档，真机标定后收敛。
 *
 * <h3>三个动作</h3>
 * <ul>
 *   <li><b>走近（近）</b>：单轮确认到脸且框宽 ≥ 近阈值 → 抬头 + 半睁眼（慢眨眼）。
 *       只做一次，之后的每轮确认不重复（有冷却）。</li>
 *   <li><b>注视（中）</b>：连续 {@link #STARE_ROUNDS} 轮都确认到脸（中等距离）→ 歪头 + 脸红。
 *       这是「持续盯着看」的近似——每轮 20 秒，连续 N 轮 = 稳定注视。</li>
 *   <li><b>离开</b>：{@link #onLeave()}（人走了）→ 低头，回到做手里事的样子。</li>
 * </ul>
 *
 * <p>所有反应都走 body 命令（{@link Sink#send}），最终落到 {@code gl.command()}。命令只
 * 驱动物理输入（{@code FaceAngleX/Y/Z}，会被 MapParam 映射到 ParamAngleX/Y/Z 并在物理之后
 * 生效）和非物理参数（{@code ParamEyeROpen/LOpen}、表情），不碰物理输出。
 */
public final class Touch {

    private static final String TAG = "Touch";

    /**
     * 脸框宽度（±1000 归一化）三档阈值。真机标定前先给个粗略值，实测后调。
     * 脸框宽度随距离增大而缩小：1 米内大致 ≥ 800，3 米外大致 ≤ 300（待真机确认）。
     */
    private static final float WIDTH_NEAR = 700f;   // 脸框 ≥ 这个值 → 很近（1 米内）
    private static final float WIDTH_FAR = 250f;    // 脸框 ≤ 这个值 → 很远（3 米外）

    /** 注视判定：连续这么多轮都确认到脸（中等距离），算「盯着看」。每轮约 20 秒。 */
    private static final int STARE_ROUNDS = 2;

    /** 走近动作（抬头看 + 慢眨眼）的冷却，避免每轮确认都重复触发。 */
    private static final long APPROACH_COOLDOWN_MS = 60000;

    /** 命令出口。可从任意线程调（最终落到 gl.command，内部加锁入队）。 */
    public interface Sink {
        void send(String command);
    }

    private final Sink sink;

    /** 连续确认到脸（中等距离）的轮数计数。 */
    private int seenRounds = 0;
    /** 上一次触发「走近动作」的时刻（冷却用）。 */
    private long lastApproachAt = 0;
    /** 是否已经处于「注视反应」状态（避免重复歪头/脸红）。 */
    private boolean staring = false;

    public Touch(Sink sink) {
        this.sink = sink;
    }

    /**
     * PresenceWatch 每轮确认到脸时回调（带人脸框宽度）。这是 Touch 唯一的「看到人」输入。
     *
     * @param width 最大人脸框的归一化宽度（≥0）；-1 表示这轮没检测到脸
     */
    public synchronized void onFaceSeen(float width) {
        if (width < 0) {
            // 这轮没脸：注视计数清零（人可能转头/走开）
            if (seenRounds > 0) {
                seenRounds = 0;
            }
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (width >= WIDTH_NEAR) {
            // 很近：走近动作（抬头看 + 慢眨眼），带冷却
            seenRounds = 0;  // 走近打断「注视」计时
            if (now - lastApproachAt >= APPROACH_COOLDOWN_MS) {
                lastApproachAt = now;
                doApproach();
            }
            return;
        }

        if (width <= WIDTH_FAR) {
            // 很远：既不算走近也不算注视，重置注视计数
            seenRounds = 0;
            return;
        }

        // 中等距离：注视计数累加，连续 STARE_ROUNDS 轮触发「歪头 + 脸红」
        seenRounds++;
        if (seenRounds >= STARE_ROUNDS && !staring) {
            staring = true;
            doStare();
        }
    }

    /**
     * 人离开了（{@code PresenceWatch} 稳定判定离场）。低头，回到做手里事的样子。
     */
    public synchronized void onLeave() {
        seenRounds = 0;
        if (staring) {
            // 离开时把「注视」的表情（脸红、歪头）收掉
            staring = false;
            sink.send("exp 脸红 0");
            sink.send("set FaceAngleZ 0");
        }
        doLookDown();
    }

    /** 走近：抬头看你 + 半睁眼（慢眨眼的近似）。 */
    private void doApproach() {
        Log.i(TAG, "走近动作：抬头看 + 慢眨眼");
        sink.send("set FaceAngleY 8");       // 抬头（FaceAngleY 正数=抬头）
        // 半睁眼必须**自己收回去**，不能 set 了就不管：set 在原生层是「按住」，
        // 只有 clear 才松开，而 Touch 走的是直通 gl.command，不经过 Body 的通道回收。
        // 踩过的坑：走近触发一次之后两只眼睛就永远 0.4 半睁着（12:36 实测右眼 0.40 常驻）。
        // 用 anim 走 0.4 → 1：一条短轨道，播完自动丢弃，不留按住值。
        // 值 1+v，所以 -0.6 就是 0.4。
        sink.send("anim ParamEyeROpen 0:-0.6 1.6:-0.6 2.4:0");
        sink.send("anim ParamEyeLOpen 0:-0.6 1.6:-0.6 2.4:0");
    }

    /** 注视：歪头 + 脸红。 */
    private void doStare() {
        Log.i(TAG, "注视动作：歪头 + 脸红");
        sink.send("set FaceAngleZ 6");       // 歪头
        sink.send("exp 脸红");
    }

    /** 离开：低头，继续做手里的事。 */
    private void doLookDown() {
        Log.i(TAG, "离开动作：低头");
        sink.send("set FaceAngleY -8");      // 低头（FaceAngleY 负数=低头）
        // 兜底：旧版把半睁眼用 set 按住且从不松手，升级后遗留的按住值在这儿清掉。
        sink.send("clear ParamEyeROpen");
        sink.send("clear ParamEyeLOpen");
    }
}

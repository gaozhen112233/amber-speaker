package com.amber.speaker;

import com.amber.speaker.fn.Suppliers.Consumer;
import com.amber.speaker.fn.Suppliers.IntSupplier;
import com.amber.speaker.fn.Suppliers.LongSupplier;
import com.amber.speaker.fn.Suppliers.BooleanSupplier;

/**
 * 唤醒应答的状态机骨架。
 *
 * <p>管理「待机 → 被喊醒 → 应一声 → 连续对话 → 结束」这条链路的相位，
 * 以及契约 §6 / §9 里最容易翻车的几条硬约束：
 * <ul>
 *   <li><b>说话时必须停 KWS</b>（防自激：她听到自己的声音会无限自唤醒）</li>
 *   <li><b>同一个唤醒词 3 秒冷却</b>（不重复触发）</li>
 *   <li><b>连续对话</b>（像小爱同学：一次唤醒、多轮对话，不用反复喊）</li>
 *   <li><b>相位超时兜底</b>（15 秒无新对话、或大模型一直不回复，自动结束本轮）</li>
 *   <li><b>在场停留搭话</b>（人一直待旁边，持续在场超时后她主动搭话，见 {@link #onPresence}）</li>
 * </ul>
 *
 * <p>这里<b>只管相位和约束</b>，不碰 KWS 引擎、不碰麦克风、不碰大模型。KWS 真正接入
 * 是音响到货后的事——现在留一个 {@link #onWake(String)} 入口，将来 KWS 回调、
 * 人体感应触发、物理按键都从这里进，相位逻辑不需要再改。
 *
 * <p><b>三种主动唤醒 + 一种被动唤醒</b>：{@link #onWake(String, String)} 收语音/走近/按键
 * （都是「有人主动触发」）；{@link #onPresence} + {@link #tick} 收「在场停留」——人只是坐着，
 * 她察觉到你一直在旁边，超时后主动搭话。两者共用同一套相位流转。
 *
 * <h3>为什么是纯 Java</h3>
 * 状态机不 import 任何 {@code android.*}：时钟用注入的 {@link LongSupplier} 拿，
 * 日志用注入的 {@link Consumer} 写。这样它就能像 {@code Inbox} 一样在 PC 上跑测试——
 * 「冷却期内再喊一次到底拦不拦」这种问题不用真机、不用等 3 秒就能验证。
 *
 * <h3>相位流转（连续对话）</h3>
 * <pre>
 *   IDLE --onWake--> WAKING --onSpeakingStart--> SPEAKING(唤醒应答「小鱼在的」)
 *    ^                                             | onSpeakingEnd
 *    |                                             v
 *    |       ┌── SPEAKING(大模型回复) <── LISTENING(开麦等用户说) ──┐
 *    |       │        │ onSpeakingEnd                               │ 用户继续说
 *    |       │        └────────── 又回到 LISTENING（连续对话）───────┘
 *    |       │
 *    +───────┴── 15 秒静音超时 / onFarewell(说「再见」) 结束本轮 ──→ IDLE
 * </pre>
 * 关键：{@code onSpeakingEnd} 之后<b>永远回 LISTENING</b>（不管是唤醒应答还是大模型回复），
 * 这样一次唤醒就能连续多轮对话。只有两条路回 IDLE：<b>15 秒无新对话</b>（超时）或
 * <b>用户说「再见」</b>（{@link #onFarewell()}）。告别词的判断见 {@link #isFarewell(String)}。
 */
public final class WakeState {

    /** 相位回调：状态机只通知「发生了什么」，具体做什么（播预录 / 开麦）由调用方决定。 */
    public interface Listener {
        /**
         * 被喊醒了，该「应一声」了。
         *
         * @param keyword 命中词（语音是「大肥鱼」；人体感应/按键可能是空的或固定词）
         * @param source  唤醒来源（见 {@link #SOURCE_VOICE} 等），调用方据此选不同开场白
         * @param nowMs   唤醒时刻
         */
        void onAwake(String keyword, String source, long nowMs);
    }

    /** 相位：见契约 §6。 */
    public static final int PHASE_IDLE = 0;
    public static final int PHASE_WAKING = 1;     // 刚被喊醒，正要应一声
    public static final int PHASE_SPEAKING = 2;   // 正在说话（KWS 必须停）
    public static final int PHASE_LISTENING = 3;  // 唤醒后收音中（等用户说下一句）

    /** 唤醒来源，对齐契约 §3 的 source 字段。 */
    public static final String SOURCE_VOICE = "voice";          // 语音唤醒（喊「大肥鱼」）
    public static final String SOURCE_PROXIMITY = "proximity";  // 人体感应（走近唤醒）
    public static final String SOURCE_PRESENCE = "presence";    // 在场停留（人一直待旁边，主动搭话）
    public static final String SOURCE_BUTTON = "button";        // 物理按键
    public static final String SOURCE_SCHEDULE = "schedule";    // 定时播报（到点主动说）

    /** 同一个唤醒词的最短间隔，防误触/防自激后立刻再触发。 */
    static final long COOLDOWN_MS = 3000;

    /**
     * 各相位的最长停留时间。超时自动回 IDLE，防止链路断在任何一环时她卡死在中间态。
     * <ul>
     *   <li>WAKING：应一声本该是零延迟的预录，给它 5 秒兜底足够宽。</li>
     *   <li>SPEAKING：说话时长不固定（有长有短），但一句不会超过 30 秒。</li>
     *   <li>LISTENING：连续对话里「等用户下一句」的静音上限，默认 15 秒，
     *       可在调参台改（{@link Settings#listenSec()}）。</li>
     * </ul>
     */
    static final long WAKING_TIMEOUT_MS = 5000;
    static final long SPEAKING_TIMEOUT_MS = 30000;

    // 在场搭话的阈值 / 冷却 / 时段、定时播报的时刻，2026-11-26 起全部搬到
    // Settings（默认值与原来的常量逐字相同）。放在那里是为了能在调参台改，
    // 不用为了「把搭话间隔从 5 分钟改成 10 分钟」重新编译一遍 APK。

    private final Listener listener;
    private final LongSupplier clock;
    private final Consumer<String> log;
    /** 墙钟「当天第几分钟」提供者（0–1439）。null 表示不启用时段限制和定时播报。 */
    private final IntSupplier minuteOfDay;
    /** 人体感应是否启用（走近唤醒 / 在场搭话）。null 表示恒启用。熄屏休眠时外部传 false。 */
    private final BooleanSupplier sensorEnabled;

    private int phase = PHASE_IDLE;
    private long lastWakeAt = 0;
    private boolean everWoke = false;
    private long phaseSince = 0;

    /** 人体感应器当前是否「在场」（near）。由 {@link #onPresence} 更新（稳定化后的信号）。 */
    private boolean present = false;
    /** 进入「在场」的时刻（在场持续计时起点）；-1 表示当前不在场。 */
    private long presentSince = -1;
    /** 上次主动搭话（presence 唤醒）的时刻，用于长冷却。 */
    private long lastPresenceAt = 0;
    /** 是否触发过 presence 唤醒（配合 lastPresenceAt 判断冷却，避免 0 值歧义）。 */
    private boolean everPresence = false;
    /** 上次定时播报的「当天第几分钟」，-1 表示还没播过。用于「每天每时刻只播一次」。 */
    private int lastScheduleMinute = -1;

    /**
     * @param listener 唤醒回调，可为 {@code null}
     * @param clock    时钟来源（Android 端传 {@code SystemClock::elapsedRealtime}）
     * @param log      日志出口，可为 {@code null}（Android 端传 {@code msg -> Log.i(TAG, msg)}）
     */
    public WakeState(Listener listener, LongSupplier clock, Consumer<String> log) {
        this(listener, clock, log, null);
    }

    /**
     * @param listener    唤醒回调，可为 {@code null}
     * @param clock       时钟来源（Android 端传 {@code SystemClock::elapsedRealtime}）
     * @param log         日志出口，可为 {@code null}
     * @param minuteOfDay 墙钟「当天第几分钟」提供者（0–1439），同时用于两处：
     *                    ① 在场搭话只在白天时段（10:00–23:00）触发；
     *                    ② 定时播报（08:40 / 23:00 / 23:59 各一句）。传 {@code null} 则两者都不启用。
     *                    Android 端传 {@code () -> { Calendar c = Calendar.getInstance();
     *                    return c.get(HOUR_OF_DAY) * 60 + c.get(MINUTE); }}
     */
    public WakeState(Listener listener, LongSupplier clock, Consumer<String> log, IntSupplier minuteOfDay) {
        this(listener, clock, log, minuteOfDay, null);
    }

    /**
     * @param listener      唤醒回调，可为 {@code null}
     * @param clock         时钟来源（Android 端传 {@code SystemClock::elapsedRealtime}）
     * @param log           日志出口，可为 {@code null}
     * @param minuteOfDay   墙钟「当天第几分钟」提供者（0–1439），同上一构造
     * @param sensorEnabled 人体感应是否启用（走近唤醒 / 在场搭话）。熄屏休眠时外部传
     *                      {@code false} 以关闭人体感应、只留语音唤醒词。传 {@code null}
     *                      表示恒启用（不限制）。
     */
    public WakeState(Listener listener, LongSupplier clock, Consumer<String> log,
                     IntSupplier minuteOfDay, BooleanSupplier sensorEnabled) {
        this.listener = listener;
        this.clock = clock;
        this.log = log;
        this.minuteOfDay = minuteOfDay;
        this.sensorEnabled = sensorEnabled;
    }

    public int phase() {
        return phase;
    }

    /** 当前是否稳定判为「人在场」（给 /state 排查用）。 */
    public synchronized boolean presentNow() {
        return present;
    }

    /** 已经在场多少秒；不在场返回 -1（给 /state 排查用）。 */
    public synchronized long presentForSec() {
        if (!present || presentSince < 0) {
            return -1;
        }
        return Math.max(0, (now() - presentSince) / 1000);
    }

    /** 当前时间（毫秒），从注入的时钟拿。 */
    private long now() {
        return clock == null ? 0 : clock.getAsLong();
    }

    private void log(String msg) {
        if (log != null) {
            log.accept(msg);
        }
    }

    /**
     * 人体感应（走近唤醒 + 在场搭话）是否启用。未注入 sensorEnabled 时恒启用。
     */
    private boolean sensorAllowed() {
        return sensorEnabled == null || sensorEnabled.getAsBoolean();
    }

    /** 这个唤醒来源是不是人体感应类（走近 / 在场搭话）。熄屏时这两类要关，语音/按键不受影响。 */
    private static boolean isSensorSource(String source) {
        return SOURCE_PROXIMITY.equals(source) || SOURCE_PRESENCE.equals(source);
    }

    /**
     * 当前是否在「在场搭话」允许的时间窗内（10:00–23:00，含首尾）。
     * 未注入 minuteOfDay 时恒为 true（不限制时段）。
     */
    private boolean inPresenceWindow() {
        if (minuteOfDay == null) {
            return true;
        }
        int m = minuteOfDay.getAsInt();
        if (m < 0) {
            // 墙钟未知（RTC 无电池，开机停在 1977 年）：判断不了是不是深夜，
            // 但被动搭话被打断的代价很小，误在深夜搭话的代价很大，所以按「不搭话」处理。
            return false;
        }
        int h = m / 60;
        return h >= Settings.presenceHourStart() && h <= Settings.presenceHourEnd();
    }

    /**
     * 定时播报：到点（08:40 / 23:00 / 23:59）主动说一句固定台词。
     *
     * <p>约束：
     * <ul>
     *   <li><b>每天每时刻只播一次</b>：用 {@link #lastScheduleMinute} 记录上次触发的分钟数，
     *       这一分钟内每帧都会跑 tick，但只第一次触发。跨天靠分钟值自然回绕（1439 → 0），
     *       不会因为日期变了而重复或漏播。</li>
     *   <li><b>只在 idle 播</b>：她正在说话/收音时不插嘴，这次播报直接跳过（不补播）。</li>
     * </ul>
     *
     * @param nowMs 当前时间（毫秒，与相位判断共用的时钟）
     */
    private void tickSchedule(long nowMs) {
        if (minuteOfDay == null || !Settings.scheduleEnabled()) {
            return;
        }
        int m = minuteOfDay.getAsInt();
        if (m < 0) {
            return;  // 墙钟未知：定时播报整个停掉，否则会在错误的时刻开口
        }
        String line = null;
        if (m == Settings.scheduleMorningMin()) {
            line = "早上好呀主人";
        } else if (m == Settings.scheduleNightMin()) {
            line = "好困啊~我去睡了~晚安主人";
        }
        if (line == null || lastScheduleMinute == m) {
            return;  // 没到点，或这个时刻已经播过了
        }
        lastScheduleMinute = m;
        if (phase == PHASE_IDLE) {
            doWake(line, SOURCE_SCHEDULE, nowMs);
        } else {
            log("定时播报跳过：当前相位 " + phaseName() + "（非 idle）");
        }
    }

    /**
     * 判断一句话是不是「告别词」。ASR 把用户的话转成文字后，先用这个判断要不要
     * 结束本轮连续对话，是的话调 {@link #onFarewell()}，而不是再把「再见」送去大模型。
     *
     * <p>匹配是规整后<b>精确命中</b>，不做模糊：告别词就那几个，宽了反而会把
     * 「再见啦今天过得不错」这种话误判成结束。
     */
    public static boolean isFarewell(String text) {
        if (text == null) {
            return false;
        }
        String s = text.trim();
        // 去掉结尾语气词和标点，只看核心告别词
        s = s.replaceAll("[，。！？!?~～\\s]+$", "");
        return "再见".equals(s)
                || "拜拜".equals(s)
                || "掰掰".equals(s)
                || "拜拜啦".equals(s)
                || "再见啦".equals(s)
                || "我走了".equals(s)
                || "先这样".equals(s)
                || "睡了".equals(s)
                || "睡觉了".equals(s)
                || "晚安".equals(s);
    }

    /**
     * 唤醒入口。KWS 命中、人体感应、物理按键都调这里。
     *
     * <p>默认按语音唤醒（{@link #SOURCE_VOICE}）。人体感应/按键走
     * {@link #onWake(String, String)} 显式传来源。
     *
     * @param keyword 命中词（如「大肥鱼」），仅用于日志和上报，不参与匹配
     * @return 是否真的触发了一次唤醒（冷却期或说话中会返回 false）
     */
    public synchronized boolean onWake(String keyword) {
        return onWake(keyword, SOURCE_VOICE);
    }

    /**
     * 唤醒入口，带来源。
     *
     * @param keyword 命中词（语音是「大肥鱼」；人体感应/按键可传空串或固定词）
     * @param source  唤醒来源（{@link #SOURCE_VOICE} / {@link #SOURCE_PROXIMITY} / {@link #SOURCE_BUTTON}）
     * @return 是否真的触发了一次唤醒（冷却期或说话中会返回 false）
     */
    public synchronized boolean onWake(String keyword, String source) {
        long nowMs = now();
        // 熄屏休眠时，人体感应关闭：走近唤醒（proximity）不响应，只留语音唤醒词。
        if (!sensorAllowed() && isSensorSource(source)) {
            log("人体感应关闭，忽略唤醒（" + source + "）");
            return false;
        }
        // 说话中不接受唤醒：她还在念上一句，这时喊她不该打断（也防自激）
        if (phase == PHASE_SPEAKING) {
            return false;
        }
        // 语音对话进行中（刚应声 / 正在等她接话），人体感应类唤醒不要再插一脚。
        // 真机踩过：她刚应声「小鱼在的」，PIR 又触发一次 proximity，播一段 3 秒的
        // 「你来啦」，麦克风整段被占住——人紧接着说的「现在几点」就这么丢了，
        // 表象是「第一遍没反应，第二遍才听到」。走近/在场搭话只在 idle 才有意义。
        if (isSensorSource(source)
                && (phase == PHASE_LISTENING || phase == PHASE_WAKING)) {
            log("对话进行中，忽略人体感应唤醒（" + source + "）");
            return false;
        }
        // 冷却：同一唤醒词 3 秒内不重复触发。用 everWoke 而不是 lastWakeAt!=0 判断，
        // 因为时钟可能从 0 开始（测试里），lastWakeAt=0 不能当「没唤醒过」的哨兵。
        if (everWoke && nowMs - lastWakeAt < COOLDOWN_MS) {
            return false;
        }
        everWoke = true;
        lastWakeAt = nowMs;
        return doWake(keyword, source, nowMs);
    }

    /**
     * 真正执行一次唤醒：进 WAKING 相位 + 通知 listener。不带冷却/说话中判断——
     * 那些由调用方（{@link #onWake}）把关。presence 搭话走这里，因为它有自己的长冷却。
     */
    private boolean doWake(String keyword, String source, long nowMs) {
        enter(PHASE_WAKING, nowMs);
        log("唤醒：" + keyword + "（" + source + "）");
        if (listener != null) {
            listener.onAwake(keyword, source, nowMs);
        }
        return true;
    }

    /**
     * 人体感应（稳定在场信号）状态入口。
     *
     * <p>注意：这里接收的是<b>已经稳定化</b>的「在场」布尔——由 {@code PresenceWatch}
     * 把不可靠的 PIR 运动脉冲 + 摄像头周期确认合并成稳定信号后再喂进来。所以这里不需要
     * 再做去抖，near=true 就是「人在场」，near=false 就是「人走了」。
     *
     * <p>「走近唤醒」（{@link #SOURCE_PROXIMITY}）只在 absent→near 的<b>上升沿</b>触发一次，
     * 由返回值通知调用方（调用方据此决定要不要开摄像头二次确认）。
     *
     * <p>「在场停留」（{@link #SOURCE_PRESENCE}）由 {@link #tick(long)} 判断——持续在场
     * 超过 {@link #PRESENCE_TIMEOUT_MS} 主动搭一次话。
     *
     * @param near 稳定后的在场状态：true=人在场，false=人已离开
     * @return 这次调用是否构成一次「absent→near 上升沿」（即人重新走近，值得做一次走近唤醒确认）
     */
    public synchronized boolean onPresence(boolean near) {
        long nowMs = now();
        if (near) {
            boolean edge = !present;   // 之前不在场、现在在场 → 上升沿
            present = true;
            if (edge) {
                presentSince = nowMs;   // 重新进入在场，计时从这一刻起
            }
            // 已经持续在场（edge=false）时 presentSince 保持不变，停留计时不被打断
            return edge;
        } else {
            // 人离开：清计时。人走了再回来，重新计「停留时长」。
            present = false;
            presentSince = -1;
            return false;
        }
    }

    /** 她开始说话了（不管是预录应答还是大模型回复）。KWS 必须停。 */
    public synchronized void onSpeakingStart() {
        enter(PHASE_SPEAKING, now());
    }

    /**
     * 她说完了。
     *
     * <p>连续对话语义：无论这轮是唤醒应答还是大模型回复，说完都回 LISTENING
     * （开麦等用户下一句），一次唤醒能连着说好几轮。结束只靠两条路——
     * 15 秒静音超时、或用户说「再见」（{@link #onFarewell()}）。
     */
    public synchronized void onSpeakingEnd() {
        if (phase == PHASE_SPEAKING) {
            enter(PHASE_LISTENING, now());
        }
    }

    /**
     * 用户说「再见」了，结束本轮连续对话，回 IDLE。从 LISTENING 或 SPEAKING 都能进——
     * 常见的是在 LISTENING 阶段 ASR 认出告别词，直接在这里收尾。
     */
    public synchronized void onFarewell() {
        if (phase != PHASE_IDLE) {
            log("告别，结束本轮：" + phaseName() + " -> idle");
            enter(PHASE_IDLE, now());
        }
    }

    /** 收音结束（用户说完了 / 大模型回复也发完了），回到待机。 */
    public synchronized void onListeningEnd() {
        if (phase == PHASE_LISTENING) {
            enter(PHASE_IDLE, now());
        }
    }

    /**
     * 主动结束一轮（比如用户走了、要打断整条唤醒链路），从任意相位直接回 IDLE。
     */
    public synchronized void reset() {
        enter(PHASE_IDLE, now());
    }

    /**
     * 超时兜底的驱动器。调用方（通常是渲染主循环）每帧调一次，
     * 状态机自己判断当前相位是不是停太久了，超时就回 IDLE。
     *
     * <p>不用内部线程：状态机不该自己起线程（那是 KWS / 麦克风 / 播放器的事），
     * 而且 tick 驱动让测试能精确控制时间流逝。
     */
    public synchronized void tick() {
        tick(now());
    }

    /** 同上，显式传入时间（测试用）。 */
    public synchronized void tick(long nowMs) {
        // 定时播报：独立于「人在不在场」，到点就主动说一句（只在 idle 播，忙则跳过不补）。
        tickSchedule(nowMs);

        // 在场停留搭话：独立于相位判断，因为它在 IDLE 下也会触发（人坐着，她待机）。
        // 熄屏休眠时人体感应关闭，这里直接不搭话（proximity/presence 都不触发）。
        // 这里接收的 present 已是 PresenceWatch 稳定化后的信号，无需再去抖。
        if (present && presentSince >= 0 && sensorAllowed() && Settings.presenceEnabled()) {
            long since = nowMs - presentSince;
            // 停留超阈值，且不在长冷却期内，才主动搭话。
            final long after = Settings.presenceAfterMs();
            boolean cooled = everPresence && (nowMs - lastPresenceAt < Settings.presenceCoolMs());
            boolean inWindow = inPresenceWindow();
            if (since >= after && !cooled && inWindow) {
                everPresence = true;
                lastPresenceAt = nowMs;
                presentSince = nowMs; // 重计：这次搭话后，进入下一段停留计时
                if (phase == PHASE_IDLE) {
                    // 只在待机时搭话。她正在说话/收音时，不插嘴。
                    doWake("", SOURCE_PRESENCE, nowMs);
                } else {
                    log("在场搭话跳过：当前相位 " + phaseName() + "（非 idle）");
                }
            } else if (since >= after && !inWindow) {
                // 已满足停留时长但不在白天时段：重计计时，避免一到 10:00 就立刻爆发。
                presentSince = nowMs;
            }
        }

        if (phase == PHASE_IDLE) {
            return;
        }
        long limit;
        switch (phase) {
            case PHASE_WAKING:
                limit = WAKING_TIMEOUT_MS;
                break;
            case PHASE_SPEAKING:
                limit = SPEAKING_TIMEOUT_MS;
                break;
            case PHASE_LISTENING:
                limit = Settings.listenMs();
                break;
            default:
                return;
        }
        if (nowMs - phaseSince >= limit) {
            log("相位超时：" + phaseName() + " -> idle");
            enter(PHASE_IDLE, nowMs);
        }
    }

    private void enter(int next, long nowMs) {
        phase = next;
        phaseSince = nowMs;
    }

    /** 当前相位名，给 /state 用。 */
    public String phaseName() {
        switch (phase) {
            case PHASE_WAKING:
                return "waking";
            case PHASE_SPEAKING:
                return "speaking";
            case PHASE_LISTENING:
                return "listening";
            default:
                return "idle";
        }
    }
}


package com.amber.speaker;

import com.amber.speaker.fn.Suppliers.BooleanSupplier;
import com.amber.speaker.fn.Suppliers.Consumer;
import com.amber.speaker.fn.Suppliers.IntSupplier;

/**
 * 定时熄屏休眠的状态机。纯 Java（不 import android.*），PC 可测。
 *
 * <p>目标：00:00–08:00 熄屏休眠（画面 + 实体 LED 灯带全黑），其余时间正常显示。
 * 这是<b>轻度休眠</b>——熄屏只关显示，唤醒链路仍在线，但<b>人体感应关闭</b>：
 * 凌晨有人在屋里走动不触发（走近唤醒 / 在场搭话都停），只有主动喊「大肥鱼 /
 * 小鱼小鱼」这个语音唤醒词能叫醒她，叫醒后她亮屏应答，答完回到待机若还在
 * 休眠区间就重新熄屏。
 *
 * <p>职责边界（和 {@link WakeState} 分工）：
 * <ul>
 *   <li>这里<b>只管「屏幕该亮还是黑」</b>，不碰相位、不碰唤醒词、不碰说话逻辑。</li>
 *   <li>通过注入的 {@code busy} 回调感知「她是否在交互中」（非 idle），交互中强制亮屏。</li>
 *   <li>真正关背光 / 关 LED 灯带是硬件适配的事，这里只产出「应该熄屏」的布尔结论，
 *       交给调用方（MainActivity）执行。</li>
 * </ul>
 *
 * <h3>状态语义</h3>
 * <pre>
 *   minuteOfDay ∈ [0, 480)  即 00:00–08:00  → 「休眠区间内」
 *       区间内 + 无交互（idle）    → 熄屏 OFF
 *       区间内 + 交互中（非 idle） → 亮屏 ON（语音唤醒词叫醒她了）
 *   minuteOfDay ∉ 区间        → 正常显示 ON，永不熄屏
 * </pre>
 *
 * <p>为什么用「区间」而不是「到点边沿触发」：熄屏是持续状态，不是一次性动作。
 * 设备在 00:00 前开机、00:30 开机、或半夜重启，都要能正确落在「该熄屏」上；
 * 用区间判断天然幂等，什么时候进 tick 都对。
 */
public final class SleepState {

    /**
     * 默认休眠区间（当天第几分钟，含起点不含终点）。00:00 → 08:00。
     *
     * <p>2026-11-26 起实际生效的值来自 {@link Settings}（可在调参台改，还支持跨午夜，
     * 比如 23:30 → 08:00）。这两个常量只作为默认值留档。
     */
    public static final int SLEEP_START_MINUTE = 0;          // 00:00
    public static final int SLEEP_END_MINUTE = 8 * 60;        // 08:00（不含）

    /** 屏幕应该熄掉还是亮着。 */
    public enum Screen {
        /** 正常显示。 */
        ON,
        /** 熄屏（画面 + LED 灯带全黑）。 */
        OFF,
    }

    /**
     * 熄屏 / 亮屏的回调。只在状态<b>发生变化</b>时通知（ON→OFF 或 OFF→ON），
     * 不在每次 tick 重复通知——避免每帧都去刷背光。
     */
    public interface Listener {
        /** @param screen 新的屏幕状态。调用方据此关/开背光与 LED 灯带。 */
        void onScreen(Screen screen);
    }

    /** 墙钟「当天第几分钟」提供者（0–1439）。null 表示不启用熄屏（恒亮）。 */
    private final IntSupplier minuteOfDay;
    /** 交互感知：返回她是否「交互中」（WakeState 非 idle）。null 视为不忙。 */
    private final BooleanSupplier busy;
    private final Listener listener;
    private final Consumer<String> log;

    /** 当前屏幕状态（对外可见）。 */
    private Screen screen = Screen.ON;

    /**
     * @param minuteOfDay 墙钟分钟（0–1439）；null 则不启用熄屏
     * @param busy        当前是否「交互中」（非 idle）；null 视为不忙
     * @param listener    熄屏/亮屏回调，可为 null
     * @param log         日志出口，可为 null
     */
    public SleepState(IntSupplier minuteOfDay, BooleanSupplier busy,
                      Listener listener, Consumer<String> log) {
        this.minuteOfDay = minuteOfDay;
        this.busy = busy;
        this.listener = listener;
        this.log = log;
    }

    /** 当前屏幕状态。 */
    public Screen screen() {
        return screen;
    }

    /**
     * 外部强行改了屏幕状态之后，把这里的记录同步过去（不触发回调）。
     *
     * <p>为什么必须有这一步：{@link #tick()} 是「算出该有的状态 → 和记录比 → 不一样才动作」。
     * 只要有别的地方绕开它直接去点/熄屏（副屏强制亮屏、AstrBot 的 {@code /control display}），
     * 记录就和事实不一致了：事实已经是亮的，记录还是「熄着」，于是 tick 算出「该熄」时
     * 发现和记录一样，<b>直接 return，永远不再熄屏</b>。
     * 真机现象就是「半夜投了个视频，切回大肥鱼以后一直亮着，怎么等都不黑」。
     */
    public synchronized void sync(boolean off) {
        screen = off ? Screen.OFF : Screen.ON;
    }

    /**
     * 每帧驱动（和 WakeState.tick 一起在渲染主循环调）。
     * 幂等：根据「当前是否在休眠区间 + 是否交互中」算出该有的屏幕状态，变了才通知。
     */
    public synchronized void tick() {
        Screen next = compute();
        if (next == screen) {
            return;
        }
        screen = next;
        log("屏幕 " + (next == Screen.OFF ? "熄屏" : "亮屏"));
        if (listener != null) {
            listener.onScreen(next);
        }
    }

    private Screen compute() {
        if (minuteOfDay == null) {
            return Screen.ON;  // 没接墙钟就不熄屏，恒亮
        }
        if (!inSleepWindow()) {
            return Screen.ON;  // 不在休眠区间，永远亮
        }
        // 在休眠区间内：交互中（被语音唤醒词叫醒）就亮屏，否则熄屏。
        boolean busyNow = busy != null && busy.getAsBoolean();
        return busyNow ? Screen.ON : Screen.OFF;
    }

    /**
     * 当前分钟是否落在 [00:00, 08:00) 休眠区间内。
     *
     * <p>{@code m < 0} 表示墙钟未知（{@link Clock} 发现系统时间不可信）。这时候
     * <b>绝不熄屏</b>：宁可白天多亮一会儿，也不要因为 RTC 坏掉、开机时间停在 1977 年，
     * 就把灯带和屏幕当成「凌晨」全关掉。真机上的表现就是「用着用着灯灭了」。
     */
    private boolean inSleepWindow() {
        if (!Settings.sleepEnabled()) {
            return false;  // 用户关掉了自动熄屏：恒亮
        }
        int m = minuteOfDay.getAsInt();
        if (m < 0) {
            return false;
        }
        final int start = Settings.sleepStartMin();
        final int end = Settings.sleepEndMin();
        if (start <= end) {
            return m >= start && m < end;
        }
        // 跨午夜：23:30 → 08:00 这种写法，落在 [start, 1440) ∪ [0, end)
        return m >= start || m < end;
    }

    private void log(String msg) {
        if (log != null) {
            log.accept(msg);
        }
    }
}

package com.amber.speaker;

/**
 * 行为类设置的**纯 Java 缓存**。
 *
 * <p>为什么单独有这一层：{@link WakeState} / {@link SleepState} 是纯 Java 类，
 * 桌面测试（{@code test/}）直接 new 出来跑断言，不能 import android.*。
 * 所以「读设置」这件事在这里只表现为静态字段，真正的 SharedPreferences 读写
 * 放在 {@link SettingsStore} 里，由 MainActivity 在启动时灌一次。
 *
 * <p>所有默认值都和改造前写死在代码里的常量**逐字一致**，升级不会改变任何行为。
 * 每一项都能通过 {@code POST /settings} 改，改完立刻生效：
 * 这些字段是 volatile，状态机每帧/每次心跳都会重新读。
 *
 * <p>时间统一用「当天第几分钟」（0–1439），和 {@link Clock#minuteOfDay()} 同一个口径。
 * 这样界面上填 23:30 就是 1410，跨天比较也不会出错。
 */
public final class Settings {

    /** 作息：{@code true} = 到点熄屏休眠。关掉就恒亮（调试、展厅场景常用）。 */
    private static volatile boolean sleepOn = true;
    /** 休眠起点（含）。默认 00:00 = 0。 */
    private static volatile int sleepStartMin = 0;
    /** 休眠终点（不含）。默认 08:00 = 480。 */
    private static volatile int sleepEndMin = 8 * 60;

    /** 定时播报：到点主动说固定台词（早上好 / 晚安）。 */
    private static volatile boolean scheduleOn = true;
    /** 早间播报时刻，默认 08:40 = 520。 */
    private static volatile int scheduleMorningMin = 8 * 60 + 40;
    /** 晚间播报时刻，默认 23:59 = 1439。 */
    private static volatile int scheduleNightMin = 23 * 60 + 59;

    /** 在场搭话：人一直待旁边时她主动理你。 */
    private static volatile boolean presenceOn = true;
    /** 连续在场多久才搭话（秒）。默认 300（5 分钟）。 */
    private static volatile int presenceAfterSec = 300;
    /** 搭话冷却（秒）。默认 3000（50 分钟）。 */
    private static volatile int presenceCoolSec = 3000;
    /** 允许搭话的起始小时（含）。默认 10。 */
    private static volatile int presenceHourStart = 10;
    /** 允许搭话的结束小时（含）。默认 23。 */
    private static volatile int presenceHourEnd = 23;

    /** 接话窗口：唤醒后等她下一句的静音上限（秒）。默认 15。 */
    private static volatile int listenSec = 15;

    /** 视线追踪：说话/听的时候眼睛跟着镜头里的人。 */
    private static volatile boolean gazeOn = true;

    private Settings() {
    }

    // ---------------------------------------------------------------- 作息

    public static boolean sleepEnabled() {
        return sleepOn;
    }

    public static int sleepStartMin() {
        return sleepStartMin;
    }

    public static int sleepEndMin() {
        return sleepEndMin;
    }

    // ------------------------------------------------------------ 定时播报

    public static boolean scheduleEnabled() {
        return scheduleOn;
    }

    public static int scheduleMorningMin() {
        return scheduleMorningMin;
    }

    public static int scheduleNightMin() {
        return scheduleNightMin;
    }

    // ------------------------------------------------------------ 在场搭话

    public static boolean presenceEnabled() {
        return presenceOn;
    }

    public static long presenceAfterMs() {
        return presenceAfterSec * 1000L;
    }

    public static long presenceCoolMs() {
        return presenceCoolSec * 1000L;
    }

    public static int presenceHourStart() {
        return presenceHourStart;
    }

    public static int presenceHourEnd() {
        return presenceHourEnd;
    }

    // ---------------------------------------------------------------- 交互

    public static long listenMs() {
        return listenSec * 1000L;
    }

    public static int listenSec() {
        return listenSec;
    }

    // ---------------------------------------------------------------- 视觉

    public static boolean gazeEnabled() {
        return gazeOn;
    }

    // ------------------------------------------------------------ 写入入口

    /**
     * 按名字改一项。名字用点分小写，和 {@code /settings} 的字段一一对应。
     *
     * @param value 字符串形式：开关收 true/false/1/0/on/off；时间是 0–1439 的分钟数
     *              （也收 {@code "08:40"} 这种写法，界面上更好填）
     * @return null 表示成功，否则是给人看的错误原因
     */
    public static String set(String key, String value) {
        if (key == null || value == null) {
            return "缺 key 或 value";
        }
        final String k = key.trim();
        final String v = value.trim();
        try {
            if ("sleep_on".equals(k)) {
                sleepOn = bool(v);
            } else if ("sleep_start".equals(k)) {
                sleepStartMin = minute(v);
            } else if ("sleep_end".equals(k)) {
                sleepEndMin = minute(v);
            } else if ("schedule_on".equals(k)) {
                scheduleOn = bool(v);
            } else if ("schedule_morning".equals(k)) {
                scheduleMorningMin = minute(v);
            } else if ("schedule_night".equals(k)) {
                scheduleNightMin = minute(v);
            } else if ("presence_on".equals(k)) {
                presenceOn = bool(v);
            } else if ("presence_after".equals(k)) {
                presenceAfterSec = clampInt(v, 10, 86400);
            } else if ("presence_cool".equals(k)) {
                presenceCoolSec = clampInt(v, 60, 86400);
            } else if ("presence_hour_start".equals(k)) {
                presenceHourStart = clampInt(v, 0, 23);
            } else if ("presence_hour_end".equals(k)) {
                presenceHourEnd = clampInt(v, 0, 23);
            } else if ("listen_sec".equals(k)) {
                listenSec = clampInt(v, 3, 120);
            } else if ("gaze_on".equals(k)) {
                gazeOn = bool(v);
            } else {
                return "不认识的设置项：" + k;
            }
        } catch (RuntimeException e) {
            return "值不合法：" + k + "=" + v;
        }
        // 起止时间反了就换回来，免得「00:00–08:00」被填成「08:00–00:00」后整天都熄屏
        if (sleepStartMin == sleepEndMin) {
            sleepEndMin = (sleepStartMin + 60) % 1440;
        }
        if (presenceHourStart > presenceHourEnd) {
            int t = presenceHourStart;
            presenceHourStart = presenceHourEnd;
            presenceHourEnd = t;
        }
        return null;
    }

    /** 回到出厂默认值。 */
    public static void reset() {
        sleepOn = true;
        sleepStartMin = 0;
        sleepEndMin = 8 * 60;
        scheduleOn = true;
        scheduleMorningMin = 8 * 60 + 40;
        scheduleNightMin = 23 * 60 + 59;
        presenceOn = true;
        presenceAfterSec = 300;
        presenceCoolSec = 3000;
        presenceHourStart = 10;
        presenceHourEnd = 23;
        listenSec = 15;
        gazeOn = true;
    }

    /** 给 {@code /state} 与 {@code /settings} 用的一份快照（手写 JSON，不引 org.json）。 */
    public static String toJson() {
        return "{\"sleep_on\":" + sleepOn
                + ",\"sleep_start\":" + sleepStartMin
                + ",\"sleep_end\":" + sleepEndMin
                + ",\"schedule_on\":" + scheduleOn
                + ",\"schedule_morning\":" + scheduleMorningMin
                + ",\"schedule_night\":" + scheduleNightMin
                + ",\"presence_on\":" + presenceOn
                + ",\"presence_after\":" + presenceAfterSec
                + ",\"presence_cool\":" + presenceCoolSec
                + ",\"presence_hour_start\":" + presenceHourStart
                + ",\"presence_hour_end\":" + presenceHourEnd
                + ",\"listen_sec\":" + listenSec
                + ",\"gaze_on\":" + gazeOn + "}";
    }

    // ------------------------------------------------------------ 解析工具

    private static boolean bool(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v)
                || "yes".equalsIgnoreCase(v) || "开".equals(v);
    }

    /** 收 {@code "520"}、{@code "08:40"}、{@code "23:59"} 三种写法。 */
    private static int minute(String v) {
        int colon = v.indexOf(':');
        if (colon > 0) {
            int h = Integer.parseInt(v.substring(0, colon).trim());
            int m = Integer.parseInt(v.substring(colon + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                throw new NumberFormatException(v);
            }
            return h * 60 + m;
        }
        int n = Integer.parseInt(v);
        if (n < 0 || n > 1439) {
            throw new NumberFormatException(v);
        }
        return n;
    }

    private static int clampInt(String v, int lo, int hi) {
        int n = Integer.parseInt(v);
        return n < lo ? lo : (n > hi ? hi : n);
    }
}

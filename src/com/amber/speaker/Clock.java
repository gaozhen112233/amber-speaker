package com.amber.speaker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 墙钟。解决一个很具体的硬件问题：<b>音箱没有 RTC 电池</b>。
 *
 * <p>通电开机后系统时间从出厂基准起算（实测固定在 {@code 1977-10-30 05:0x} 附近），
 * 断网时自动对时（{@code auto_time=1}）也拉不到 NTP，于是「现在几点」永远是凌晨 5 点。
 * 这个坏时间会把所有基于墙钟的逻辑一起带歪：
 * <ul>
 *   <li>休眠状态机以为在 00:00–08:00 → 主动熄屏 + 灯带断电（表现是「用着用着灯灭了」）</li>
 *   <li>在场搭话以为在深夜 → 永不搭话</li>
 *   <li>08:40 / 23:59 的定点播报 → 永不触发（或触发在错误的时刻）</li>
 * </ul>
 *
 * <p>两道防线：
 * <ol>
 *   <li><b>闸门</b>：{@link #trusted()} 为 false 时，{@link #minuteOfDay()} 返回 -1，
 *       下游把它当成「不知道几点」——不熄屏、不搭话、不播报。宁可常亮，也不要半夜关灯。</li>
 *   <li><b>校正</b>：{@link #sync(long)} 由外部（AstrBot / PC 探针）推一个真实时间过来，
 *       内部只记「偏移量」，不改系统时间（改系统时间要 root，且 Magisk 静默拒绝）。
 *       偏移量存 SharedPreferences——因为这台机器的开机基准时间是固定的，
 *       所以跨重启依然有效。</li>
 * </ol>
 *
 * <p>反向兜底：如果哪天联网把系统时间校正好了（raw ≥ {@link #TRUST_FLOOR}），
 * 就直接用系统时间并丢弃旧偏移，免得两者叠加把时间推到 2050 年。
 */
public final class Clock {

    private static final String TAG = "AmberClock";
    private static final String PREFS = "amber_clock";
    private static final String KEY_OFFSET = "offset_ms";

    /**
     * 可信下界：2020-01-01 00:00:00 UTC。
     * 这台机器的错误时间是 1977 年，差着四十多年，用「年份」当判据足够稳，也不会误伤。
     */
    private static final long TRUST_FLOOR = 1577836800000L;

    /** 加到 {@code System.currentTimeMillis()} 上的校正量。 */
    private static volatile long offset;

    /**
     * 上一次成功对时的时刻（{@code elapsedRealtime}，0 = 本次进程内还没对过）。
     *
     * <p>为什么要记这个：没有 RTC 电池，系统时间全靠晶振从开机基准往前跑，一天能漂几秒到
     * 几十秒。以前只在「时间不可信」时才对时，于是开机对上一次之后，这台机器可能连着几周
     * 都不再校——表现是「闹钟越来越晚」「凌晨熄屏点往后挪」。现在心跳会按 {@link #stale}
     * 定期重校一次。
     */
    private static volatile long lastSyncAt;

    private Clock() {
    }

    /** 启动时调一次：读上次的校正量（系统时间本身可信时丢弃它）。 */
    public static void init(Context context) {
        Context app = context.getApplicationContext();
        final long raw = System.currentTimeMillis();
        if (raw >= TRUST_FLOOR) {
            // 系统时间已经对了，不需要校正
            offset = 0;
            app.getSharedPreferences(PREFS, 0).edit().remove(KEY_OFFSET).apply();
            Log.i(TAG, "系统时间可信：" + format(raw) + "，不用校正");
            return;
        }
        offset = app.getSharedPreferences(PREFS, 0).getLong(KEY_OFFSET, 0);
        if (offset == 0) {
            Log.w(TAG, "系统时间不可信：" + format(raw) + "（RTC 无电池 / 未联网对时），"
                    + "休眠与定点播报暂停，等 /clock 同步");
        } else {
            Log.i(TAG, "系统时间不可信：" + format(raw) + "，沿用上次校正 +" + offset / 1000
                    + "s → " + format(now()));
        }
    }

    /** 现在（毫秒，已校正）。 */
    public static long now() {
        final long raw = System.currentTimeMillis();
        if (raw >= TRUST_FLOOR) {
            return raw;
        }
        return raw + offset;
    }

    /** 现在的时间可信吗（年份 ≥ 2020）。 */
    public static boolean trusted() {
        return now() >= TRUST_FLOOR;
    }

    /**
     * 当天第几分钟（0–1439）；<b>时间不可信时返回 -1</b>。
     * 下游（{@link SleepState} / {@link WakeState}）把 -1 当作「不知道几点」。
     */
    public static int minuteOfDay() {
        if (!trusted()) {
            return -1;
        }
        // 用 Calendar 而不是自己算模除：要跟随系统时区（Asia/Shanghai）。
        final java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(now());
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    /**
     * 用外部给的 epoch 毫秒校正。
     *
     * @return 校正后的时间是否可信（外部给的时间太旧就拒收）
     */
    public static boolean sync(Context context, long epochMs) {
        if (epochMs < TRUST_FLOOR) {
            Log.w(TAG, "拒收不可信的时间：" + format(epochMs));
            return false;
        }
        final long raw = System.currentTimeMillis();
        if (raw >= TRUST_FLOOR) {
            // 系统时间已经对了，不接受覆盖（避免推来一个更差的时间）。
            // 但仍然记一次「刚刚确认过」——否则 /state 的 clock_age 永远是 -1，
            // 心跳会以为从没对过时，每 5 分钟白问一次网络。
            lastSyncAt = android.os.SystemClock.elapsedRealtime();
            Log.i(TAG, "系统时间已可信，忽略同步：" + format(raw));
            return true;
        }
        offset = epochMs - raw;
        context.getApplicationContext().getSharedPreferences(PREFS, 0)
                .edit().putLong(KEY_OFFSET, offset).apply();
        lastSyncAt = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, "时间已同步 → " + format(now()) + "（偏移 +" + offset / 1000 + "s）");
        return true;
    }

    /**
     * 距上次成功对时过了多久（毫秒）；本次进程内还没对过返回 -1。
     *
     * <p>注意系统时间本身可信（有真 RTC 或联网校正过）时不需要对时，调用方应先看
     * {@link #trusted()}——但可信也照样可以定期校一次，成本只是一个 HTTP 头。
     */
    public static long syncAgeMs() {
        final long at = lastSyncAt;
        if (at == 0) {
            return -1;
        }
        return android.os.SystemClock.elapsedRealtime() - at;
    }

    /** 该重新对时了吗：本进程从没对过、或距上次超过 {@code maxAgeMs}。 */
    public static boolean stale(long maxAgeMs) {
        final long age = syncAgeMs();
        return age < 0 || age > maxAgeMs;
    }

    private static String format(long ms) {
        final java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
        return f.format(new java.util.Date(ms));
    }
}

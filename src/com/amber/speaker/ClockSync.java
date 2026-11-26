package com.amber.speaker;

import android.content.Context;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 从网络拿时间。
 *
 * <p>为什么不用系统 NTP：这台机器的 {@code auto_time=1} 是开着的，WiFi 也能上外网
 * （实测 ping 通 223.5.5.5），但 {@code NetworkTimeUpdateService} 在 Android 5.1 上
 * 只在「网络状态变化」时试一次，失败后要等很久才重试，实测重连 WiFi 也拉不回来。
 * 与其和一个厂商实现较劲，不如自己发一个 HTTP 请求读 {@code Date} 响应头——这个头部
 * 由 RFC 7231 强制要求，任何正经服务器都会带，精度到秒，对我们「现在几点」的用途足够。
 *
 * <p>探测地址优先用手机厂商的连通性检测端点（国内可达、返回 204、几乎不耗流量），
 * 拿不到再退到百度首页。**不校验返回码**——404 也有 Date 头，能读到时间就算成功。
 */
public final class ClockSync {

    private static final String TAG = "AmberClock";

    /**
     * 按顺序试，第一个能给 Date 头的就用。
     *
     * <p>第一项是<b>局域网地址</b>（作者家里 NAS 上的 AstrBot）：几十毫秒就回，而且断外网
     * 也照样有。别人的网络里这个地址不存在，所以给它单独设一个 1.2 秒的短超时——
     * 连不上就立刻换下一个，不会白等 4 秒。想改成自己家的地址就改这一行。
     */
    private static final String[] PROBES = {
            "http://192.168.1.100:6185",   // 局域网兜底：改成你 NAS/主机的地址，用不上就删掉这行
            "http://connect.rom.miui.com/generate_204",
            "http://www.baidu.com",
    };

    private static final int TIMEOUT_MS = 4000;
    /** 局域网探测的超时：同网段要么几十毫秒回，要么根本不存在，不需要等 4 秒。 */
    private static final int LOCAL_TIMEOUT_MS = 1200;

    private ClockSync() {
    }

    /**
     * 试一轮。**会阻塞**，必须在子线程调。
     *
     * @return 现在的时间是否已经可信（本来就可信也算成功）
     */
    public static boolean tryOnce(Context context) {
        return tryOnce(context, false);
    }

    /**
     * 试一轮。
     *
     * @param force 时间已经可信时也照样去拿一次。用于「定期重校」——没有 RTC 电池，
     *              晶振会漂，不重校就会越走越偏（见 {@link Clock#stale}）。
     * @return 时间是否可信
     */
    public static boolean tryOnce(Context context, boolean force) {
        if (Clock.trusted() && !force) {
            return true;
        }
        for (int i = 0; i < PROBES.length; i++) {
            final Long epoch = fetchDateHeader(PROBES[i], i == 0 ? LOCAL_TIMEOUT_MS : TIMEOUT_MS);
            if (epoch == null) {
                continue;
            }
            if (Clock.sync(context, epoch)) {
                Log.i(TAG, "从网络拿到时间：" + PROBES[i]);
                return true;
            }
        }
        Log.w(TAG, "探测地址都没给出可用时间（时间仍不可信，作息功能保持暂停）");
        return false;
    }

    private static Long fetchDateHeader(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.getResponseCode();   // 必须先触发请求，头才拿得到；码不重要
            final String date = conn.getHeaderField("Date");
            return date == null ? null : parseHttpDate(date);
        } catch (Exception e) {
            Log.w(TAG, "取时间失败 " + url + "：" + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 解析 RFC 1123（`Sun, 22 Nov 2026 05:41:00 GMT`），也容忍 RFC 850 那种老格式。 */
    static Long parseHttpDate(String raw) {
        if (raw == null) {
            return null;
        }
        final String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
        };
        for (String pattern : patterns) {
            final SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            final Date parsed = format.parse(raw, new ParsePosition(0));
            if (parsed != null) {
                return parsed.getTime();
            }
        }
        return null;
    }
}

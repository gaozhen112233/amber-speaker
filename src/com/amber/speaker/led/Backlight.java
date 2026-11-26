package com.amber.speaker.led;

import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * LCD 背光的真正开关：写 {@code /sys/class/leds/lcd-backlight/brightness}。
 *
 * <p>之前熄屏只做「画面清黑 + 隐藏 SurfaceView + clearFlags(FLAG_KEEP_SCREEN_ON)」，
 * 但那是软件层的东西——LCD 背光（屏幕本身发的那层光）还亮着，看起来就是「黑屏但发灰光」，
 * 不是真熄屏。要真熄屏，得把背光（brightness）直接写 0。
 *
 * <p>这颗屏的背光走 sysfs 节点 {@code /sys/class/leds/lcd-backlight/brightness}（device 指向
 * {@code qcom,mdss_fb_primary.123}，即主 LCD 面板），范围 0~255（{@code max_brightness}=255）。
 * 写 0 = 背光全灭（真熄屏），255 = 全亮。
 *
 * <p><b>权限</b>：这个节点的 owner 是 {@code system}，权限 644，应用（{@code u0_a28}）默认
 * 写不了。靠 Magisk {@code service.d} 开机脚本把它 {@code chmod 666}（和 hum_sensor 的
 * {@code /dev/input/event5} 一样），应用才能直接写。脚本见 {@code tools/backlight_perms.sh}。
 * 脚本没跑起来时，写会静默失败（IOException 兜住），退化成「画面清黑但背光还亮」，
 * 不影响其它功能。
 */
public final class Backlight {

    private static final String TAG = "AmberBacklight";

    /** 背光 sysfs 节点。真机固定（见类注释），别硬编码成别的路径。 */
    private static final String NODE = "/sys/class/leds/lcd-backlight/brightness";

    /** 当前屏幕亮度 0~255。默认 255（满亮），可用 /control brightness 调。 */
    private volatile int level = 255;

    /** 单例。背光节点是全局唯一的，谁都能写，别各自 new 一份去抢。 */
    private static Backlight instance;

    public static synchronized Backlight get() {
        if (instance == null) {
            instance = new Backlight();
        }
        return instance;
    }

    private Backlight() {
    }

    /**
     * 设背光：off=true 熄灭（真熄屏），false 点亮到当前 {@link #level}。
     *
     * <p>写失败（权限没放、节点不存在）只打一行日志、不抛异常——背光关不掉不该让
     * 整个熄屏流程崩掉，退化成软件层「画面清黑」即可。
     */
    public void set(boolean off) {
        write(off ? 0 : level, off ? "背光熄灭（真熄屏）" : "背光点亮");
    }

    /**
     * 设屏幕亮度 0~255（立即生效，亮屏状态下才看得见；熄屏时只记住值）。
     * 0 等价于熄屏，1~255 是不同亮度。
     */
    public void setLevel(int value) {
        level = Math.max(0, Math.min(255, value));
        write(level, "屏幕亮度 " + level);
    }

    public int level() {
        return level;
    }

    private void write(int value, String message) {
        final String text = Integer.toString(Math.max(0, Math.min(255, value)));
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(NODE);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            Log.i(TAG, message);
        } catch (IOException e) {
            Log.w(TAG, "写背光失败（" + e.getMessage() + "）—— 退回「画面清黑但背光还在」");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}

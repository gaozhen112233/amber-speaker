package com.amber.speaker;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * {@link Settings} 的持久化层（唯一 import android.* 的地方）。
 *
 * <p>存法很朴素：一个键一个字符串，前缀 {@code set.}。为什么不用整段 JSON：
 * 单项修改只写一个键，不会因为并发（调参台连续拖动滑块）互相覆盖整段配置；
 * 而且坏掉一个值也只影响那一项。
 *
 * <p>兼容性：从来没存过（{@code contains} 全 false）时什么都不写，
 * 直接用 {@link Settings} 的默认值 —— 老用户升级后行为一字不变。
 */
public final class SettingsStore {

    private static final String PREFS = "amber";
    private static final String PREFIX = "set.";

    /** 所有可持久化的设置项，顺序就是 /settings 展示的顺序。 */
    public static final String[] KEYS = {
            "sleep_on", "sleep_start", "sleep_end",
            "schedule_on", "schedule_morning", "schedule_night",
            "presence_on", "presence_after", "presence_cool",
            "presence_hour_start", "presence_hour_end",
            "listen_sec", "gaze_on",
    };

    private SettingsStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    /** 启动时灌一次。任何一项缺失就跳过，保留 {@link Settings} 的默认值。 */
    public static void load(Context c) {
        SharedPreferences p = sp(c);
        Settings.reset();
        for (String k : KEYS) {
            String v = p.getString(PREFIX + k, null);
            if (v != null) {
                Settings.set(k, v);
            }
        }
    }

    /**
     * 改一项并落盘。
     *
     * @return null 表示成功，否则是错误说明（{@link Settings#set} 的原文）
     */
    public static String apply(Context c, String key, String value) {
        String err = Settings.set(key, value);
        if (err != null) {
            return err;
        }
        for (String k : KEYS) {
            if (k.equals(key)) {
                sp(c).edit().putString(PREFIX + k, value.trim()).apply();
                return null;
            }
        }
        return "不认识的设置项：" + key;
    }

    /** 全部恢复默认（同时清掉落盘的旧值）。 */
    public static void reset(Context c) {
        Settings.reset();
        SharedPreferences.Editor e = sp(c).edit();
        for (String k : KEYS) {
            e.remove(PREFIX + k);
        }
        e.apply();
    }
}

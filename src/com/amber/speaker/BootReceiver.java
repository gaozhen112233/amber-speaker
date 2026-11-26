package com.amber.speaker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启。
 *
 * <p>为什么需要它：音箱的电源接在米家智能插座上，关机就是断电、开机就是通电。
 * 通电之后系统从头启动，如果没有这个 receiver，应用不会自己起来，
 * 米家里点「开机」看上去毫无反应（2026-11-24 加的）。
 *
 * <p>为什么用 BOOT_COMPLETED 而不是 Magisk 脚本：这是系统给应用的正式入口，
 * 不用管 root 和启动顺序。另外还有一个 Magisk 兜底脚本
 * （/data/adb/service.d/amber_autostart.sh），两道路都走，任意一条成功就能活。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AmberBoot";

    /** 部分国产 ROM 的快启动广播，值就是下面这个字符串，系统没有常量。 */
    private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !QUICKBOOT.equals(action)) {
            return;
        }
        Log.i(TAG, "开机广播：" + action + "，把主界面拉起来");
        try {
            Intent i = new Intent(context, MainActivity.class);
            // 广播接收器里没有 Activity 栈，必须 NEW_TASK
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            // 起不来不算失败：Magisk 兜底脚本 45 秒后还会再拉一次
            Log.w(TAG, "启动主界面失败：" + e);
        }
    }
}

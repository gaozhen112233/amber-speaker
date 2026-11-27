package com.amber.speaker.sensor;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/**
 * 相机守护进程自愈：QCamera 卡死时把 {@code qcamerasvr} 重启一遍。
 *
 * <h3>为什么需要这东西</h3>
 * 这台机器的相机 HAL 跑在 {@code /system/bin/mm-qcamera-daemon}（init 服务名 {@code qcamerasvr}，
 * 是独立 init 服务，不是 mediaserver 的一部分）。它会偶发「假活」：
 *
 * <ul>
 *   <li>进程还在，{@code ps | grep camera} 能看到；</li>
 *   <li>{@code dumpsys media.camera} 也正常：设备在线、{@code No active camera clients}；</li>
 *   <li>但任何 {@code Camera.open(0)} 都抛 {@code Fail to connect to camera service}。</li>
 * </ul>
 *
 * <p>表现到用户那边就是：主人问「你看看这是什么」，她答「摄像头打不开」；走近唤醒也不再确认人脸。
 *
 * <p><b>实测</b>：{@code stop qcamerasvr; start qcamerasvr} 之后立刻恢复，不用重启设备。
 * 所以这里直接 su 重启它——总比让用户跑一趟 adb 强。
 *
 * <h3>代价</h3>
 * 需要 Magisk 给本应用 su 授权（首次会弹一次框）；重启会打断正在跑的视线追踪几百毫秒。
 * 因此只在「连续三次 open 都失败」时调用，且 60 秒内最多一次。
 */
public final class CameraHealer {

    private static final String TAG = "CameraHealer";

    /** 两次自愈之间的最小间隔，防止反复弹 su / 反复打断相机。 */
    private static final long MIN_INTERVAL_MS = 60000;

    private static final Object LOCK = new Object();
    private static long lastResetAt = 0;

    private CameraHealer() {
    }

    /**
     * 重启相机守护进程。**会阻塞**（最长约 4 秒，等 su 和 init 响应），别在主线程直接调。
     *
     * @return true = 命令已执行（不代表相机一定恢复，调用方要再 open 一次验证）
     */
    public static boolean reset(Context ctx) {
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (lastResetAt != 0 && now - lastResetAt < MIN_INTERVAL_MS) {
                Log.i(TAG, "刚重启过（" + ((now - lastResetAt) / 1000) + " 秒前），这次跳过");
                return false;
            }
            lastResetAt = now;
        }
        Process p = null;
        try {
            // 注意：Magisk 对未授权 uid 会弹「超级用户请求」，没人点就会卡住——所以给 4 秒上限。
            p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "stop qcamerasvr; start qcamerasvr"
            });
            if (!waitFor(p, 4000)) {
                Log.w(TAG, "su 超时（可能弹了授权框没人点）");
                return false;
            }
            Log.i(TAG, "已重启 qcamerasvr，等它起来");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "重启相机守护进程失败（多半是没有 su 授权）：" + e.getMessage());
            return false;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /** 轮询等进程结束（API 22 没有 Process.waitFor(timeout)）。 */
    private static boolean waitFor(Process p, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException running) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}

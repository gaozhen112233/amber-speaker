package com.amber.speaker.sensor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;

/**
 * 唤醒态视线追踪（契约 §4.5）。
 *
 * <p>唤醒之后、对话进行中，让虚拟人「眼神跟着正在说话的人走」——她看着你站的方向。
 * 和 {@link FaceConfirm} 共用同一颗后摄（Camera 0）和同一条「离屏 SurfaceTexture +
 * 硬件人脸检测」的骨架，但<b>语义相反</b>：
 *
 * <ul>
 *   <li>{@link FaceConfirm}：单次确认，命中或超时立即关摄像头（「是不是真人」）。</li>
 *   <li>本类：<b>持续追踪</b>，唤醒态期间摄像头保持开启，每收到一次人脸回调就更新眼球
 *       朝向，直到回到 idle 才关（「看着你」）。</li>
 * </ul>
 *
 * <h3>只检测、不识别（§16.1 红线）</h3>
 * 只取人脸包围盒坐标（脸在画面哪个位置），不认人、不比对身份、不取帧不落盘、不上传。
 * 连 {@code setPreviewCallback} 都不挂，纯靠 HAL 的人脸回调拿框，一帧都不往应用侧取。
 *
 * <h3>坐标映射（§4.5.1，白送的好处）</h3>
 * {@link Camera.Face#rect} 以画面中心为原点、按画面尺寸归一化到 ±1000：
 * <pre>
 *   ParamEyeBallX  ←  rect.centerX() / 1000f   // 直接 -1~+1
 *   ParamEyeBallY  ←  rect.centerY() / 1000f
 * </pre>
 * 硬件检测实测命中率 97~98%、更新 6.2fps、CPU ≈ 0（ISP 流水线内），不抢 Live2D 算力。
 *
 * <h3>丢失容忍（§4.5 保底处理）</h3>
 * 检测不到脸（人走开 / 转头）时<b>保持上一帧位置</b>，连续 200ms 仍无脸才平滑回正视——
 * 绝不把眼球僵在死角，也不因一两次漏检就频繁抽搐。
 *
 * <p>命令经 {@link #sink} 发出去。它最终落到 {@code gl.command()}，而后者可以从任意线程
 * 调（内部加锁入队），所以本类的 camera 回调线程可以直接发，无需 post 回主线程。
 */
public final class GazeTracker {

    private static final String TAG = "GazeTracker";

    /** 连续多少毫秒检测不到脸，才平滑回正视（§4.5 定的 200ms）。 */
    private static final long LOST_RESET_MS = 200;

    /** 平滑回正的总时长。 */
    private static final long RECENTER_MS = 400;

    /** 眼球目标值的低通系数（每帧 0~1，越小越跟手但越抖）。 */
    private static final float EYE_SMOOTH = 0.25f;

    /** 头随俯仰跟随的系数（FaceAngleY 只跟 y，不跟 x——左右转头会显得僵硬）。 */
    private static final float HEAD_Y_GAIN = 0.5f;

    /** 命令出口。见类的说明：可从任意线程调。 */
    public interface Sink {
        /** 发一条 body 命令（如 {@code set ParamEyeBallX 0.3}）。 */
        void send(String command);
    }

    private final Context app;
    private final Sink sink;

    /** 离屏 preview 目标。没有它摄像头不出帧，人脸检测就没有数据（关键坑，§4.5.1）。 */
    private SurfaceTexture previewTexture;
    private Camera camera;

    /** 平滑器自己跑的节奏（独立于相机回调的 6.2fps，让眼球过渡是连续的）。 */
    private final java.util.Timer timer = new java.util.Timer("amber-gaze", true);
    private java.util.TimerTask smoother;
    private boolean running;

    /** 当前实际发出的眼球/头值，用来算「往目标走一步」。 */
    private float curX;
    private float curY;
    private float curHeadY;
    /** 当前目标（有脸时 = 脸位置，丢脸后 = 回正 0）。 */
    private float targetX;
    private float targetY;
    /** 上次看到脸的时刻（用 {@code SystemClock.elapsedRealtime}，和丢脸判断一致）。 */
    private long lastSeenAt = -1;
    /** 丢脸后回正的起点时间戳。 */
    private long recenterStart = -1;

    public GazeTracker(Context app, Sink sink) {
        this.app = app.getApplicationContext();
        this.sink = sink;
    }

    /** 是否在追踪中（摄像头开着）。 */
    public synchronized boolean isTracking() {
        return camera != null;
    }

    /**
     * 开始追踪。幂等：已在追踪就忽略。
     */
    public synchronized void start() {
        if (camera != null) {
            return;
        }
        running = true;
        // 平滑器每秒跑 30 次，够让 6.2fps 的检测更新显得连续。
        if (smoother == null) {
            smoother = new java.util.TimerTask() {
                @Override
                public void run() {
                    step();
                }
            };
            timer.scheduleAtFixedRate(smoother, 0, 33);
        }
        Camera cam = null;
        try {
            cam = Camera.open(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "开摄像头失败：" + e.getMessage());
            return;
        }
        camera = cam;
        try {
            previewTexture = new SurfaceTexture(0);
            cam.setPreviewTexture(previewTexture);

            Camera.Parameters p = cam.getParameters();
            p.set("face-detection", "on");
            cam.setParameters(p);
            cam.startPreview();
            cam.setFaceDetectionListener(new Camera.FaceDetectionListener() {
                @Override
                public void onFaceDetection(Camera.Face[] faces, Camera camera) {
                    onFaces(faces);
                }
            });
            cam.startFaceDetection();
            Log.i(TAG, "开始追踪");
        } catch (Exception e) {
            Log.w(TAG, "追踪起不来：" + e.getMessage());
            stop();
        }
    }

    /** 关摄像头，停止追踪。可重入。 */
    public synchronized void stop() {
        running = false;
        lastSeenAt = -1;
        recenterStart = -1;
        Camera cam = camera;
        camera = null;
        if (cam != null) {
            try {
                cam.stopFaceDetection();
                cam.stopPreview();
                cam.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (previewTexture != null) {
            previewTexture.release();
            previewTexture = null;
        }
        // 关掉时把眼球/头收归正视，别停在刚才看的方向；cur 也归零，下次 start 从正视起。
        curX = 0f;
        curY = 0f;
        curHeadY = 0f;
        setEye(0f, 0f);
        Log.i(TAG, "停止追踪");
    }

    /**
     * 相机回调（camera 线程）。只更新目标和「上次看到脸」的时间戳，真正的平滑在
     * {@link #step} 里做——两者解耦，回调频率和渲染节奏互不拖累。
     */
    private void onFaces(Camera.Face[] faces) {
        if (faces == null || faces.length == 0) {
            return;
        }
        // 多脸时挑最靠画面中心、最大的那张（简单起见取第一张最大的）
        Camera.Face best = null;
        for (Camera.Face f : faces) {
            if (f == null || f.rect == null) {
                continue;
            }
            if (best == null || f.rect.width() > best.rect.width()) {
                best = f;
            }
        }
        if (best == null) {
            return;
        }
        targetX = best.rect.centerX() / 1000f;
        targetY = best.rect.centerY() / 1000f;
        lastSeenAt = android.os.SystemClock.elapsedRealtime();
        recenterStart = -1;
    }

    /** 平滑器心跳：往当前目标走一步；丢脸超时则把目标收回正视。 */
    private void step() {
        if (!running) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastSeenAt >= 0 && now - lastSeenAt >= LOST_RESET_MS) {
            // 连续 200ms 没脸：平滑回正
            if (recenterStart < 0) {
                recenterStart = now;
                targetX = 0f;
                targetY = 0f;
            }
            long since = now - recenterStart;
            if (since >= RECENTER_MS) {
                // 回正完成，进入「无脸」稳态（不再每帧往 0 靠，省得发重复命令）
                lastSeenAt = -1;
                recenterStart = -1;
            }
        }
        float gx = EYE_SMOOTH;
        curX += (targetX - curX) * gx;
        curY += (targetY - curY) * gx;
        curHeadY += (targetY * HEAD_Y_GAIN - curHeadY) * gx;
        // 值没变就不发（无脸静止时目标恒 0，避免每秒刷 90 条重复命令）
        if (Math.abs(curX - sentX) < 0.0005f
                && Math.abs(curY - sentY) < 0.0005f
                && Math.abs(curHeadY - sentHeadY) < 0.0005f) {
            return;
        }
        setEye(curX, curY);
    }

    /** 上次实际发出去的值，做去重。 */
    private float sentX;
    private float sentY;
    private float sentHeadY;

    /** 发眼球 + 头跟随命令。 */
    private void setEye(float x, float y) {
        sentX = x;
        sentY = y;
        sentHeadY = curHeadY;
        sink.send("set ParamEyeBallX " + fmt(x));
        sink.send("set ParamEyeBallY " + fmt(y));
        sink.send("set FaceAngleY " + fmt(curHeadY));
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }
}

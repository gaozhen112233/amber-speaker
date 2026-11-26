package com.amber.speaker.sensor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 走近唤醒的摄像头二次确认。
 *
 * <p>契约 §4.1 的判定链路第二段：人体感应器先触发「可能有人」，再由摄像头做一次
 * 「是不是真人」的确认，避免宠物路过 / 误报就唤醒。判定逻辑：
 *
 * <pre>
 * 人体感应器检测到有人靠近
 *    └─> 短暂开摄像头（单次 ≤2 秒，不取帧不存盘）做二次确认
 *           ├─ 确认是真人 ──> 上报 proximity 唤醒
 *           └─ 确认不是人 ──> 丢弃
 * </pre>
 *
 * <p><b>为什么用硬件人脸检测</b>（契约 §4.5.1，真机实测）：用 {@link Camera#setFaceDetectionListener}
 * 调高通 HAL 的 ISP 内置人脸检测，正脸/侧脸/低头命中率 97~98%、更新 6.2fps、
 * CPU ≈ 0（在 ISP 流水线里，不抢 Live2D）。而 {@code android.media.FaceDetector}（软件 Haar）
 * 正脸只有 60%、侧脸≈0%、还吃单核 48%。所以这里绝不回退到软件检测——
 * 检测不到人脸就按「不是人」丢弃，下次人体感应再触发时再试。
 *
 * <p><b>摄像头绝不常开</b>：只在人体感应触发的确认窗口内开，命中或超时立即关。
 * 这守住 §16.1「摄像头默认关、只检测不识别、不取帧不落盘」的底线——这里连
 * {@code setPreviewCallback} 都不挂，纯靠 HAL 回调拿人脸框，一帧都不往应用侧取。
 *
 * <p>启用顺序（踩过坑，不能乱，见契约 §4.5.1）：先 setParameters 开 face-detection，
 * 再 startPreview，然后 setFaceDetectionListener，最后 startFaceDetection。
 */
public final class FaceConfirm {

    private static final String TAG = "FaceConfirm";

    /** 确认窗口：从开摄像头到放弃，最长 5 秒（原 2 秒不够摄像头冷启动 + preview 起流 + 人脸检测生效）。 */
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    /** 结果回调。在确认线程上回调（不是主线程），调用方自己 post。 */
    public interface Listener {
        /** 确认是真人（检测到人脸），或不是人（超时 / 检测不到）。 */
        void onConfirmed(boolean human);
    }

    /**
     * 带「人脸框」的结果回调（§8.7 touch 换语义用）。
     *
     * <p>除了是不是真人，还把<b>最大人脸框的归一化宽度</b>（{@code rect.width()}，画面
     * 半宽归一化到 ±1000，脸越大越靠近）带出来，供调用方估算「人离摄像头多近」。
     * 检测不到人脸时 width = -1。
     */
    public interface SizedListener {
        /** 确认结果。human=false 时 width 恒为 -1。 */
        void onConfirmed(boolean human, float width);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 离屏 preview 目标。没有它摄像头不出帧，人脸检测就没有数据可检测（关键坑）。 */
    private SurfaceTexture previewTexture;

    private Camera camera;
    private final Runnable giveUp = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "2 秒没检测到人脸，按「不是人」丢弃");
            finish(false);
        }
    };

    private Listener pending;
    private SizedListener sizedPending;
    /** 本次确认里最大人脸框的归一化宽度（画面半宽归一化到 ±1000）。-1 表示还没检测到。 */
    private float maxWidth = -1;

    public FaceConfirm(Context app) {
        this.app = app.getApplicationContext();
    }

    /**
     * 开始一次确认。幂等：上一次还没结束就再触发，直接忽略（避免摄像头开开关关）。
     *
     * @param listener 结果回调（非主线程回调）
     */
    public synchronized void confirm(Listener listener) {
        if (camera != null) {
            Log.i(TAG, "上一次确认还没结束，本次按「不是人」返回（不能让调用方干等）");
            final Listener l = listener;
            if (l != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        l.onConfirmed(false);
                    }
                });
            }
            return;
        }
        pending = listener;
        sizedPending = null;
        main.post(new Runnable() {
            @Override
            public void run() {
                open();
            }
        });
    }

    /**
     * 带人脸框宽度的确认（§8.7 用）。语义同 {@link #confirm(Listener)}，但回调会多带一个
     * 最大人脸框的归一化宽度，用于估算距离。
     *
     * @param listener 结果回调（非主线程回调）
     */
    public synchronized void confirmSized(SizedListener listener) {
        if (camera != null) {
            Log.i(TAG, "上一次确认还没结束，本次按「不是人」返回（不能让调用方干等）");
            final SizedListener l = listener;
            if (l != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        l.onConfirmed(false, -1f);
                    }
                });
            }
            return;
        }
        pending = null;
        sizedPending = listener;
        main.post(new Runnable() {
            @Override
            public void run() {
                open();
            }
        });
    }

    private void open() {
        Camera cam = null;
        try {
            cam = Camera.open(0);  // 后摄（契约 §4.5.1：Camera 0，Orientation 180，API 1）
        } catch (RuntimeException e) {
            Log.w(TAG, "开摄像头失败：" + e.getMessage() + " —— 按「不是人」处理");
            finish(false);
            return;
        }
        camera = cam;
        try {
            // 关键：先给摄像头一个离屏 surface 当 preview 目标。没有它，startPreview 之后
            // 摄像头不真正出帧（帧没地方渲），硬件人脸检测就永远等不到数据。SurfaceTexture
            // 是离屏的，不显示到屏幕，也不取帧不落盘——守住隐私，只当 preview 的「排水口」。
            previewTexture = new SurfaceTexture(0);
            cam.setPreviewTexture(previewTexture);

            Camera.Parameters p = cam.getParameters();
            p.set("face-detection", "on");       // 1. 先写参数
            cam.setParameters(p);
            cam.startPreview();                  // 2. preview 起来之后
            cam.setFaceDetectionListener(new Camera.FaceDetectionListener() {
                @Override
                public void onFaceDetection(Camera.Face[] faces, Camera camera) {
                    if (faces != null && faces.length > 0) {
                        // 记下最大脸的框宽（归一化，脸越大越近），供 §8.7 估距离。
                        float w = -1;
                        for (Camera.Face f : faces) {
                            if (f != null && f.rect != null && f.rect.width() > w) {
                                w = f.rect.width();
                            }
                        }
                        maxWidth = w;
                        Log.i(TAG, "检测到人脸（" + faces.length + " 张），确认是真人，框宽 " + w);
                        finish(true);
                    }
                }
            });                                  // 3. 注册 listener
            cam.startFaceDetection();            // 4. 最后启动
        } catch (RuntimeException e) {
            Log.w(TAG, "人脸检测启不起来：" + e.getMessage() + " —— 按「不是人」处理");
            finish(false);
            return;
        } catch (Exception e) {
            // setPreviewTexture 抛 IOException，其它预览相关的 checked 异常也一并兜住。
            Log.w(TAG, "preview 起不来：" + e.getMessage() + " —— 按「不是人」处理");
            finish(false);
            return;
        }
        // 兜底：期间没等到人脸回调就放弃
        main.postDelayed(giveUp, CONFIRM_TIMEOUT_MS);
    }

    /** 收尾：关摄像头、摘回调、回结果。可重入（open 失败和超时都会走到这）。 */
    private synchronized void finish(boolean human) {
        Camera cam = camera;
        camera = null;
        main.removeCallbacks(giveUp);
        if (cam != null) {
            try {
                cam.stopFaceDetection();
                cam.stopPreview();
                cam.release();
            } catch (RuntimeException ignored) {
                // release 失败无所谓，反正要关了
            }
        }
        if (previewTexture != null) {
            previewTexture.release();
            previewTexture = null;
        }
        Listener listener = pending;
        pending = null;
        SizedListener sized = sizedPending;
        sizedPending = null;
        float width = human ? maxWidth : -1;
        maxWidth = -1;
        if (listener != null) {
            listener.onConfirmed(human);
        }
        if (sized != null) {
            sized.onConfirmed(human, width);
        }
    }
}

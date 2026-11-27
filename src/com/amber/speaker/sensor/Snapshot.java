package com.amber.speaker.sensor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 「眼睛」工具（契约 §7.1 camera_snapshot）的取帧实现。
 *
 * <p>机器人主动想看主人在做什么时，调音箱摄像头取<b>一帧</b> JPEG 画面，交 AstrBot 侧的
 * 视觉模型分析。和 {@link FaceConfirm} / {@link GazeTracker} 用同一颗后摄（Camera 0），但
 * 语义不同——那两个只做硬件人脸<b>检测</b>（不取帧），本类是真的<b>取一帧</b>画面：
 *
 * <ul>
 *   <li>{@link FaceConfirm} / {@link GazeTracker}：纯靠 HAL 人脸回调，一帧不往应用侧取。</li>
 *   <li>本类：{@code takePicture} 拿 JPEG 字节流，只在内存里过一遍（base64 发给视觉模型），
 *      不落盘、不上传原图、不识别身份。</li>
 * </ul>
 *
 * <h3>隐私铁律（§16.1 / §7.1）</h3>
 * 只取一帧、不录像不连续取帧；画面只喂视觉模型、分析完立即丢弃；不落盘、不做身份识别、
 * 不读情绪脸。取帧单次窗口 ≤ 2 秒，超时放弃。
 *
 * <h3>实现要点（API 1，踩过坑）</h3>
 * <ol>
 *   <li>先 {@code setPreviewTexture(离屏 SurfaceTexture)} 再 {@code startPreview}——没有离屏
 *       surface 摄像头不出帧（同 FaceConfirm 的坑）。</li>
 *   <li>{@code takePicture} 用 shutter + raw(null) + jpeg 三个回调，只取 jpeg。raw 回调传
 *       {@code null}（API 1 里传 null 表示不取 raw，省内存）。</li>
 *   <li>JPEG 拿到后立即 {@code stopPreview + release}，摄像头单次开启窗口尽量短。</li>
 * </ol>
 *
 * <p>结果回调在相机线程上（不是主线程），调用方自己 post / 转网络。
 */
public final class Snapshot {

    private static final String TAG = "Snapshot";

    /** 取帧窗口：开摄像头 + 起流 + takePicture，含失败重试与守护进程自愈，最长 9 秒。 */
    private static final long SNAPSHOT_TIMEOUT_MS = 9000;

    /** 摄像头被别的使用者（视线追踪 / 走近确认 / 在场探测）占着时的重试次数与间隔。 */
    private static final int OPEN_RETRIES = 3;
    private static final long OPEN_RETRY_DELAY_MS = 300;

    /** 重启 qcamerasvr 之后等它起来的时间。 */
    private static final long HEAL_WAIT_MS = 2500;

    /** 结果回调。非主线程回调。 */
    public interface Listener {
        /** 成功取到一帧（JPEG 字节）。 */
        void onFrame(byte[] jpeg, int width, int height);

        /** 失败（开摄像头失败 / 超时 / 拍照异常）。 */
        void onError(String reason);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    private SurfaceTexture previewTexture;
    private Camera camera;
    private Listener pending;

    private final Runnable giveUp = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "取帧超时，放弃");
            finishError("timeout");
        }
    };

    public Snapshot(Context app) {
        this.app = app.getApplicationContext();
    }

    /** 是否正在取帧。 */
    public synchronized boolean busy() {
        return camera != null;
    }

    /**
     * 取一帧。幂等：上一次还没结束就再触发，直接忽略（避免摄像头开开关关）。
     */
    public synchronized void capture(Listener listener) {
        if (camera != null) {
            Log.i(TAG, "上一次取帧还没结束，忽略这次");
            if (listener != null) {
                listener.onError("busy");
            }
            return;
        }
        pending = listener;
        main.post(new Runnable() {
            @Override
            public void run() {
                open();
            }
        });
    }

    private void open() {
        openAttempt(0);
    }

    /**
     * 第 {@code attempt} 次开摄像头；失败就延迟重试，最多 {@link #OPEN_RETRIES} 次。
     *
     * <p>这台机器上摄像头是<b>单实例</b>资源：视线追踪（交互期间常开）、走近确认（PIR 触发）、
     * 在场探测（每 60 秒一次）都会临时 {@code Camera.open(0)}。撞上了就抛
     * {@code Fail to connect to camera service}——以前这里直接放弃，主人问「你看看这个」
     * 得到的是「摄像头打不开」。等一两百毫秒通常就释放了，所以重试比报错有用。
     *
     * <p>用 {@code postDelayed} 而不是 {@code Thread.sleep}：这里跑在主线程，睡会把画面卡住。
     */
    private void openAttempt(final int attempt) {
        if (pending == null) {
            return;  // 已被超时收走，别再往下走
        }
        Camera cam = null;
        try {
            cam = Camera.open(0);  // 后摄（契约 §4.5.1：Camera 0，Orientation 180，API 1）
        } catch (RuntimeException e) {
            Log.w(TAG, "开摄像头失败（第 " + (attempt + 1) + " 次）：" + e.getMessage());
            if (attempt < OPEN_RETRIES - 1) {
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        openAttempt(attempt + 1);
                    }
                }, OPEN_RETRY_DELAY_MS);
            } else if (attempt == OPEN_RETRIES - 1) {
                // 连试三次都「连不上相机服务」——不是被占，是守护进程假活了，重启它再试一次。
                healAndRetry(attempt + 1);
            } else {
                finishError("camera_open_failed");
            }
            return;
        }
        camera = cam;
        try {
            previewTexture = new SurfaceTexture(0);
            cam.setPreviewTexture(previewTexture);
            cam.startPreview();
            // 分辨率：取个够视觉模型看的中等尺寸，别用默认最大（JPEG 太大，base64 后 HTTP 也撑）
            Camera.Parameters p = cam.getParameters();
            try {
                android.hardware.Camera.Size best = pickSize(p);
                if (best != null) {
                    p.setPictureSize(best.width, best.height);
                    cam.setParameters(p);
                }
            } catch (RuntimeException sizeIgnored) {
                // 某些设备不认这个尺寸，退回默认
            }
            cam.takePicture(new Camera.ShutterCallback() {
                @Override
                public void onShutter() {
                    // 快门声可忽略（API 1 无法静音，设备级快门声由系统处理）
                }
            }, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    if (data == null || data.length == 0) {
                        finishError("empty_frame");
                        return;
                    }
                    // 拿当前 camera 的 picture size 回宽高（takePicture 前已 setParameters）
                    int w = 0, h = 0;
                    Camera c = Snapshot.this.camera;
                    if (c != null) {
                        try {
                            Camera.Size s = c.getParameters().getPictureSize();
                            w = s.width;
                            h = s.height;
                        } catch (RuntimeException ignored) {
                        }
                    }
                    Log.i(TAG, "取到一帧 JPEG " + data.length + " 字节"
                            + (w > 0 ? " " + w + "x" + h : ""));
                    finishFrame(data, w, h);
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "拍照起不来：" + e.getMessage());
            finishError("take_picture_failed");
            return;
        } catch (Exception e) {
            Log.w(TAG, "preview 起不来：" + e.getMessage());
            finishError("preview_failed");
            return;
        }
        main.postDelayed(giveUp, SNAPSHOT_TIMEOUT_MS);
    }

    /** 挑一个中等 picture size：宽约 800~1600，尽量 4:3 或接近。 */
    private static android.hardware.Camera.Size pickSize(Camera.Parameters p) {
        java.util.List<android.hardware.Camera.Size> sizes = p.getSupportedPictureSizes();
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        android.hardware.Camera.Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (android.hardware.Camera.Size s : sizes) {
            if (s.width < 640 || s.height < 480) {
                continue;  // 太小，视觉模型看不清
            }
            // 目标 ~1280 宽，4:3 理想。分数 = |log(宽/1280)| 加权 + 偏离 4:3 的惩罚
            double ratio = (double) s.width / (double) s.height;
            double targetRatio = 4.0 / 3.0;
            double ratioPenalty = Math.abs(ratio - targetRatio) * 4.0;
            double sizeScore = Math.abs(Math.log((double) s.width / 1280.0));
            double score = sizeScore + ratioPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /**
     * 三次 open 都失败之后的最后一招：后台重启相机守护进程，再 open 一次。
     *
     * <p>不放主线程：{@code su} 没授权时会弹框等人点，主线程被卡住就是画面定格。
     *
     * @param nextAttempt 重试序号（用来让 {@link #openAttempt} 知道这是最后一枪）
     */
    private void healAndRetry(final int nextAttempt) {
        Log.i(TAG, "连续开不起来，尝试重启相机守护进程");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean healed = CameraHealer.reset(app);
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (pending == null) {
                            return;  // 已经超时收工
                        }
                        if (healed) {
                            openAttempt(nextAttempt);
                        } else {
                            finishError("camera_open_failed");
                        }
                    }
                }, healed ? HEAL_WAIT_MS : 0);
            }
        }, "amber-camera-heal").start();
    }

    private synchronized void finishFrame(byte[] jpeg, int width, int height) {        releaseCamera();
        Listener listener = pending;
        pending = null;
        if (listener != null) {
            listener.onFrame(jpeg, width, height);
        }
    }

    private synchronized void finishError(String reason) {
        releaseCamera();
        Listener listener = pending;
        pending = null;
        if (listener != null) {
            listener.onError(reason);
        }
    }

    private void releaseCamera() {
        main.removeCallbacks(giveUp);
        Camera cam = camera;
        camera = null;
        if (cam != null) {
            try {
                cam.stopPreview();
                cam.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (previewTexture != null) {
            previewTexture.release();
            previewTexture = null;
        }
    }
}

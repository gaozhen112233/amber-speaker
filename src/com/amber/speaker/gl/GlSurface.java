package com.amber.speaker.gl;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.util.ArrayDeque;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 她身体的那一半画面。
 *
 * <p>分成两段加载，原因很实际：整套资源 19MB，MSM8909 上解析要好几秒。
 * 如果全塞进 GL 线程，那几秒里 {@code onPause} 会卡在等 GL 线程上，
 * 用户一按 Home 就是 ANR。所以
 * <b>CPU 那段（moc3/物理/表情/动作）放在后台线程</b>，
 * <b>只有建纹理和渲染器留在 GL 线程</b>——GL 那段是短的。
 *
 * <p>另外两件事：
 * <ul>
 *   <li>模型只能在上下文活着的时候建。切出去再回来 onSurfaceCreated 会再来一次，
 *       旧贴图全失效，必须整个重建，否则画出来的是一堆已删除的纹理编号，屏上只剩黑。</li>
 *   <li>命令可能比模型先到。先攒在 Java 这边，就绪之后一次灌进去，
 *       不然她开场第一句话会没有动作。</li>
 * </ul>
 */
public final class GlSurface extends GLSurfaceView implements GLSurfaceView.Renderer {

    private static final String TAG = "AmberGL";
    /** 默认模型目录（大肥鱼）。切换后这个值跟着变，启动时从 SharedPreferences 读回来。 */
    public static final String DEFAULT_MODEL = "model/";

    /** 就绪通知，参数是这次到底成没成。 */
    public interface Ready {
        void onReady(boolean ok);
    }

    /** 帧率探针的累加器（只在 GL 线程读写，不用同步）。 */
    private long fpsMark;
    private long lastFrameAt;
    private int fpsFrames;

    /** 累计帧数（进程内单调递增）。看门狗的「线程还活着」判据之一，也给 /state 回读。 */
    private volatile long totalFrames;
    /** 上一次在画面上真读到非空像素的时刻（elapsedRealtime，0 = 还没采过）。 */
    private volatile long lastContentAt;
    private long lastProbeAt;
    /** 内容采样的间隔。10 秒一次足够：全黑要连续 90 秒才判故障。 */
    private static final long PROBE_MS = 10000;
    /** 采样缓冲（16×16 RGBA，复用，别每帧 new）。 */
    private final java.nio.ByteBuffer probeBuf =
            java.nio.ByteBuffer.allocateDirect(16 * 16 * 4);
    private final int[] probePixels = new int[16 * 16];
    private long slowestMs;

    /** 渲染分辨率降采样倍率（1 = 原分辨率）。构造时就要定，因为 surface 尺寸只在建 surface 前能改。 */
    private final int renderScale;

    /**
     * 摆台（模型位置/缩放），见 {@link #setFit}。
     *
     * <p>放在 Java 侧而不是只记在原生层：换模型时原生对象是新建的，值会归零；
     * 命令队列也会被清空。这里留着值，每次建完纹理补发一次，位置才不会「一切模型就跳回去」。
     */
    private float fitX, fitY, fitScale = 1f;
    private boolean hasFit;

    /** 诊断用：不透明 surface（见构造函数的 opaque 参数）。 */
    private final boolean opaque;

    /** 诊断用：只清屏不画模型，用来把「渲染内容」和「窗口/驱动」彻底分开。 */
    private boolean bare;

    /**
     * 熄屏休眠开关。true 时 onDrawFrame 只清屏（透明）、不画模型，surface 也不销毁。
     *
     * <p>为什么不用 onPause()：GLSurfaceView 的 onPause 会释放 EGL surface，恢复时走
     * onSurfaceCreated → 重新解析模型（几百毫秒），语音唤醒「大肥鱼」后应答会被这段
     * 重载拖慢。而这里只是「半夜熄屏」，surface 和模型都留着，置个标志跳过绘制即可，
     * 亮屏时把标志清掉就立刻恢复，零重载。
     */
    private volatile boolean hidden;


    private final AssetManager assets;
    private final ArrayDeque<String> waiting = new ArrayDeque<String>();
    private final Object lock = new Object();

    private long handle;       // 后台线程写，GL 线程读
    private boolean glReady;
    private Boolean notified;  // null = 还没通知过；用包装类型是因为「没通知」和「通知了失败」要分开
    private int width;
    private int height;
    private Ready ready;
    /** 画面左右镜像。琥珀音箱那块屏要开（详见 Native.setFlipX），普通手机调试关掉。 */
    private boolean mirror = true;

    /**
     * 当前要用的模型目录（{@code model/}、{@code model_miku/} ……）。
     *
     * <p>volatile：切换从主线程发起，加载线程和 GL 线程都要读它判断「我忙活的还是不是最新的」。
     */
    private volatile String modelDirectory;
    /** 切换还没做完时又收到的一条请求，只记最后一个，做完当前这次再接着切。 */
    private volatile String pendingModel;

    /**
     * 副屏（视频）。不是 null 且 {@link VideoStage#active()} 时，这一帧只画视频、不画模型 ——
     * 模型不销毁、不重载，所以「切到副屏 / 切回大肥鱼」是零成本的，不像换模型要 3.5 秒。
     */
    private volatile VideoStage video;
    /** 上下文是否已经建好（决定 setVideo 时要不要立刻补一次纹理初始化）。 */
    private volatile boolean surfaceReady;
    /** 有没有切换正在进行：防止两条 HTTP 命令同时拆模型，把句柄搅成一团。 */
    private volatile boolean switching;

    public GlSurface(Context context) {
        this(context, 1, false);
    }

    /**
     * @param renderScale 渲染分辨率降采样倍率（1 = 原分辨率，2 = 半宽半高）。
     * @param opaque      <b>只用于诊断</b>：用不带 alpha 的 surface（RGB565 + 不透明清屏）。
     *                    真机产物必须透明——琥珀音箱靠反射镜透出实体 LED 灯带的光，
     *                    纯黑画面会像一块幕布把舞台光挡死。这个开关是用来分辨
     *                    「画得慢」和「透明合成慢」的：实测整帧 700ms，但渲染 CPU 只有
     *                    50ms、GPU 20ms，怀疑时间全耗在透明 surface 的合成上。
     */
    public GlSurface(Context context, int renderScale, boolean opaque) {
        this(context, renderScale, opaque, false);
    }

    public GlSurface(Context context, int renderScale, boolean opaque, boolean bare) {
        this(context, renderScale, opaque, bare, DEFAULT_MODEL);
    }

    /**
     * @param modelDirectory APK assets 里的模型目录（末尾带斜杠）。启动时用上一次选的那个，
     *                       换模型不必重启 App —— 见 {@link #switchModel}。
     */
    public GlSurface(Context context, int renderScale, boolean opaque, boolean bare, String modelDirectory) {
        super(context);
        this.renderScale = Math.max(1, renderScale);
        this.modelDirectory = (modelDirectory == null || modelDirectory.length() == 0)
                ? DEFAULT_MODEL : modelDirectory;
        assets = context.getAssets();
        setEGLContextClientVersion(2);
        if (opaque) {
            // 没有 alpha 通道：合成端可以直接走 HWC 直通，不必每帧做全屏混合
            setEGLConfigChooser(5, 6, 5, 0, 16, 0);
            getHolder().setFormat(PixelFormat.RGB_565);
        } else {
            // 带 alpha 的配置：琥珀音箱靠反射镜透出实体 LED 灯带的光，画面必须透明，
            // 否则纯黑会像幕布挡住真实的舞台光。RGBA8888 + 半透明 surface。
            // 注意：不能 setZOrderOnTop——那会把 surface 浮到字幕之上，盖住气泡。
            setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            getHolder().setFormat(PixelFormat.TRANSLUCENT);
        }
        this.opaque = opaque;
        this.bare = bare;
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        // 渲染分辨率降采样：surface 按 1/scale 的尺寸分配，由显示硬件放大回全屏。
        final int scale = renderScale > 1 ? renderScale : 1;
        if (scale > 1) {
            final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            getHolder().setFixedSize(dm.widthPixels / scale, dm.heightPixels / scale);
        }
        startParsing(modelDirectory);
    }

    public void setReady(Ready ready) {
        this.ready = ready;
    }

    /**
     * 接上副屏。可以从任何线程调：上下文已经建好就立刻去 GL 线程建外部纹理，
     * 还没建就等 {@link #onSurfaceCreated} 里那次。
     */
    public void setVideo(final VideoStage stage) {
        this.video = stage;
        if (stage != null) {
            stage.setGlBootstrap(new Runnable() {
                @Override
                public void run() {
                    queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            if (surfaceReady) {
                                stage.prepareGL();
                            }
                        }
                    });
                }
            });
            if (surfaceReady) {
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        stage.prepareGL();
                    }
                });
            }
        }
    }

    /**
     * 熄屏休眠开关（见字段说明）。置 true 后下一帧起只清屏不画模型。
     * volatile 字段，GL 线程每帧读，无需同步。
     */
    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    /**
     * 左右镜像。可以从任何线程调：记下值，再往 GL 线程塞一次应用。
     *
     * <p>不这么绕不行——{@link Native#setFlipX} 改的是渲染器每帧读的那个标志，
     * 换线程写会和 Draw 撞车；而且模型没就绪时句柄是 0，只能等 ensureGl 时再补一次。
     */
    public void setMirror(final boolean mirror) {
        this.mirror = mirror;
        queueEvent(new Runnable() {
            @Override
            public void run() {
                final long current;
                synchronized (lock) {
                    current = handle;
                }
                if (current != 0 && glReady) {
                    Native.setFlipX(current, mirror);
                }
            }
        });
    }

    /** 可以从任何线程调：就绪了就直接进队，没好就先攒着。 */
    public void command(String line) {
        synchronized (lock) {
            if (glReady) {
                Native.command(handle, line);
                return;
            }
            if (waiting.size() < 64) {   // 攒太多没意义，开场也不需要几百条
                waiting.add(line);
            }
        }
    }

    /**
     * 模型在画面里的位置与缩放（摆台）。可以从任何线程调。
     *
     * <p>不能只发一条 {@code fit} 命令就完事：换模型会把命令队列清空（旧模型的表情名、
     * 动作名对新模型没意义），而且退出重进时模型是新建的，原生层的值回到 0/0/1。
     * 所以这里把值留在 Java 侧，每次 {@link #ensureGl} 建完纹理都重新下发一次 ——
     * 和镜像 {@link #setMirror} 是同一套道理。
     */
    public void setFit(float x, float y, float scale) {
        synchronized (lock) {
            fitX = x;
            fitY = y;
            fitScale = scale;
            hasFit = true;
        }
        queueEvent(new Runnable() {
            @Override
            public void run() {
                applyFit();
            }
        });
    }

    /** 只在 GL 线程调用。 */
    private void applyFit() {
        final long current;
        final boolean ready;
        float x, y, s;
        synchronized (lock) {
            current = handle;
            ready = glReady;
            x = fitX;
            y = fitY;
            s = fitScale;
            if (!hasFit) return;
        }
        if (current != 0 && ready) {
            // 三个小数位就够：NDC 0.001 在 1024 宽的屏上等于半个像素
            String line = String.format(java.util.Locale.US, "fit %.4f %.4f %.4f", x, y, s);
            Native.command(current, line);
        }
    }

    /**
     * 换模型。可以从任何线程调。
     *
     * <p>顺序是硬要求：<b>先在 GL 线程拆掉旧模型</b>（贴图和 FBO 只能在 GL 线程释放，
     * 在别处 destroy 就是给 Adreno 喂野纹理编号），<b>再在后台线程解析新的</b>
     * （9.5MB 的 moc3 要几秒，放 GL 线程会把 onPause 一起卡住），
     * <b>最后回 GL 线程建纹理</b>。中间那段屏上是空的，所以调用方要自己写一行字幕说明。
     *
     * <p>不做「两套模型同时活着再换」：这台机器总共 915MB 内存，两个 16MB 级模型
     * 加上各自的六张 2048 贴图（一张 RGBA 就是 16MB）叠在一起必然被 LMK 杀掉。
     *
     * @return false = 正在切换中，这次请求没接（别连着发，等前一次的回调）。
     */
    public boolean switchModel(final String directory, final Ready callback) {
        if (directory == null || directory.length() == 0) return false;
        if (switching) {
            Log.w(TAG, "正在切换模型，忽略：" + directory);
            return false;
        }
        if (directory.equals(modelDirectory) && glReady) {
            if (callback != null) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onReady(true);
                    }
                });
            }
            return true;
        }

        switching = true;
        modelDirectory = directory;
        if (callback != null) this.ready = callback;
        Log.i(TAG, "切换模型 -> " + directory);

        queueEvent(new Runnable() {
            @Override
            public void run() {
                final long stale;
                synchronized (lock) {
                    stale = handle;
                    handle = 0;
                    waiting.clear();   // 旧模型的命令（表情名、动作名）对新模型没意义
                }
                glReady = false;
                notified = null;
                if (stale != 0) {
                    Native.destroy(stale);
                    Log.i(TAG, "旧模型已释放");
                }
                startParsing(directory);
            }
        });
        return true;
    }

    /** 当前模型目录，给界面和 HTTP 回包用。 */
    public String modelDirectory() {
        return modelDirectory;
    }

    private void startParsing(final String directory) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                String target = directory;
                while (true) {
                    final long start = System.currentTimeMillis();
                    final long prepared = Native.prepare(assets, target);
                    Log.i(TAG, "解析 " + target + " " + (System.currentTimeMillis() - start)
                            + " ms，句柄 " + prepared);
                    if (prepared == 0) {
                        switching = false;
                        notifyReady(false);
                        return;
                    }
                    final boolean current;
                    synchronized (lock) {
                        current = target.equals(modelDirectory);
                        if (current) handle = prepared;
                    }
                    if (!current) {
                        // 解析这几秒里又切到别的模型了：这个还没建过 GL（没有纹理），
                        // 删掉不留痕，直接接着解析最新的那个，省得两次切换互相拆台。
                        Native.destroy(prepared);
                        target = modelDirectory;
                        continue;
                    }
                    queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            ensureGl();
                        }
                    });
                    return;
                }
            }
        }, "amber-load");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    /** 只在 GL 线程调用。 */
    private void ensureGl() {
        if (glReady) return;
        final long current;
        synchronized (lock) {
            current = handle;
        }
        if (current == 0 || width <= 0 || height <= 0) return;

        final long start = System.currentTimeMillis();
        // 先定方向再建：createGl 之后的每一帧都会读这个标志
        Native.setFlipX(current, mirror);
        glReady = Native.createGl(current, width, height);
        Log.i(TAG, "建纹理 " + (System.currentTimeMillis() - start) + " ms -> " + glReady
                + "，镜像 " + (mirror ? "开" : "关"));

        synchronized (lock) {
            if (glReady) {
                while (!waiting.isEmpty()) {
                    Native.command(current, waiting.poll());
                }
            }
        }
        // 命令队列里没有摆放值（换模型时队列被清了），这里补一次 —— 位置是「每个模型一份」的，
        // 换过来必须按新模型的值摆，不能沿用上一个的。
        applyFit();
        // 成没成都得告诉界面，不然失败时一直停在「加载模型…」上，看着像卡死
        notifyReady(glReady);
    }

    private void notifyReady(final boolean ok) {
        switching = false;
        if (notified != null && notified == ok) return;
        notified = ok;
        final Ready target = ready;
        if (target != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    target.onReady(ok);
                }
            });
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 上下文重建：旧句柄指向的纹理和 FBO 全没了，只能整个重来
        final long stale;
        synchronized (lock) {
            stale = handle;
            handle = 0;
        }
        glReady = false;
        notified = null;
        surfaceReady = true;
        // 副屏的外部纹理也一起作废：先重建它，再让正在播的那段收工
        // （旧 MediaPlayer 的 Surface 已经指向死人，硬撑只会「有声音没画面」）
        final VideoStage stage = video;
        if (stage != null) {
            stage.prepareGL();
            stage.noteGlReset();
        }
        if (stale != 0) {
            Log.w(TAG, "上下文重建，丢弃旧模型");
            Native.destroy(stale);
            startParsing(modelDirectory);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = h;
        if (glReady) {
            Native.resize(handle, w, h);
        } else {
            ensureGl();
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (hidden) {
            // 熄屏休眠：只清成透明，不画模型。surface 还活着，亮屏时立即恢复，无需重载。
            gl.glClearColor(0f, 0f, 0f, 0f);
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT);
            return;
        }
        final VideoStage stage = video;
        if (stage != null && stage.active()) {
            // 副屏：只画视频。先清成透明（灯带的光要从画面外的留白里透上来），
            // 再走我们自己的投影路径画那一张外部纹理 —— 镜像、透明背景和模型是同一套。
            gl.glViewport(0, 0, width, height);
            gl.glDisable(GL10.GL_SCISSOR_TEST);
            gl.glClearColor(0f, 0f, 0f, 0f);
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT);
            stage.drawFrame(width, height, mirror);
            countFrame();
            probeContent(width, height);
            return;
        }
        if (glReady && !bare) {
            Native.drawFrame(handle);
        } else {
            // 没模型也要清，否则残影会留在屏上
            gl.glClearColor(0f, 0f, 0f, opaque ? 1f : 0f);
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT);
        }
        // 空渲染（诊断）时也要计数，否则看不到「只清屏能跑多少帧」这个上限
        if (glReady) {
            countFrame();
            probeContent(width, height);
        }
    }

    /** 累计画过的帧数（进程内单调递增）。看门狗用它判断 GL 线程还在不在转。 */
    public long frames() {
        return totalFrames;
    }

    /**
     * 上一次在画面上真读到非空像素距今几秒；还没采到过返回 -1。
     *
     * <p>为什么不能只看帧数：着色器偶发编译失败时，进程活着、帧率正常、GL 线程照转，
     * 但 <b>一个部件都没画上去</b>（日志里「program 0 个」）。这种「全黑但没死」只有
     * 真去读像素才能发现。
     */
    public long contentAgeSec() {
        final long at = lastContentAt;
        if (at == 0) {
            return -1;
        }
        return (android.os.SystemClock.elapsedRealtime() - at) / 1000;
    }

    /**
     * GL 线程还活着吗？往它的事件队列里塞一个空任务，看多久被执行。
     *
     * <p>这是唯一可信的「线程没卡死」判据：帧数会受渲染模式影响（待机时帧循环可能停），
     * 而 queueEvent 的任务只要 GL 线程在跑就一定会被取走。**必须在非主线程调**（会阻塞）。
     *
     * @param timeoutMs 最长等多久
     */
    public boolean pingGL(long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        try {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    latch.countDown();
                }
            });
        } catch (RuntimeException e) {
            return false;
        }
        try {
            return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 采一点像素，看画面上到底有没有东西。
     *
     * <p>取左中右三块 16×16（角色通常占中间；副屏视频是全屏），任意一块里有 alpha 或颜色
     * 超过阈值的像素就算「有内容」。每 {@link #PROBE_MS} 采一次，平时一律跳过，
     * 一次只有 768 个像素的读回，对 7fps 的渲染没有可感知影响。
     */
    private void probeContent(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastProbeAt < PROBE_MS) {
            return;
        }
        lastProbeAt = now;
        final int size = 16;
        final int y = Math.max(0, h / 2 - size / 2);
        final int[] xs = {w / 2 - size / 2, w / 6 - size / 2, w * 5 / 6 - size / 2};
        final int[] pixels = probePixels;
        for (int k = 0; k < xs.length; k++) {
            final int x = xs[k];
            if (x < 0 || x + size > w) {
                continue;
            }
            probeBuf.clear();
            android.opengl.GLES20.glReadPixels(x, y, size, size,
                    android.opengl.GLES20.GL_RGBA,
                    android.opengl.GLES20.GL_UNSIGNED_BYTE, probeBuf);
            probeBuf.rewind();
            probeBuf.asIntBuffer().get(pixels, 0, size * size);
            for (int i = 0; i < size * size; i++) {
                // 空画面清成 (0,0,0,0)，四个字节全零；只要有任何一个字节非零就是画上了东西
                // （角色像素 alpha=255，视频黑帧也带 alpha=255）。这样不用关心字节序。
                if (probePixels[i] != 0) {
                    lastContentAt = now;
                    return;
                }
            }
        }
    }

    /**
     * 帧率探针。为什么要在 GL 线程自己数：Cubism 是逐 drawable 发 draw call 的，
     * 715 个部件就是 715 次 call/帧，Adreno 304 上极易掉到个位数 —— 而掉帧在观感上
     * 会表现成「她慢动作」，很容易被误判成动作数据的问题。
     *
     * <p>每 5 秒一行，含最慢的那一帧。稳态运行会一直打，方便随时看有没有退化。
     */
    private void countFrame() {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (fpsMark == 0) {
            fpsMark = now;
            lastFrameAt = now;   // 不置初值的话第一帧会被算成「最慢一帧 = 开机至今」
            return;
        }
        fpsFrames++;
        totalFrames++;
        final long gap = now - lastFrameAt;
        if (gap > slowestMs) {
            slowestMs = gap;
        }
        lastFrameAt = now;
        final long span = now - fpsMark;
        if (span < 5000) {
            return;
        }
        final float fps = fpsFrames * 1000f / span;
        Log.i(TAG, String.format(java.util.Locale.US,
                "帧率 %.1f fps，最慢一帧 %d ms", fps, slowestMs));
        fpsMark = now;
        fpsFrames = 0;
        slowestMs = 0;
    }
}

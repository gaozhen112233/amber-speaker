package com.amber.speaker.gl;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * 副屏：把一段视频画进她自己那块 GL 画布。
 *
 * <h3>为什么不装第三方投屏/播放器</h3>
 * 这块屏是「系统转 180° + 光路上下翻」，我们自己在投影矩阵里补了一次左右翻才正过来
 * （见 {@link Native#setFlipX}）。任何第三方 App 都不会补这一次，画面在实体屏上是
 * <b>左右镜像</b>的；更要紧的是窗口背景——别的播放器只会给一块不透明的黑底，
 * 而琥珀音箱的立体感全靠背后实体 LED 灯带的光透过来。
 *
 * <p>所以视频直接解到一张外部纹理（SurfaceTexture）上，用我们自己的投影/清屏路径画：
 * <ul>
 *   <li>背景天然透明（glClear 是 0,0,0,0），灯带的光从画面外和抠像处透上来；</li>
 *   <li>镜像和模型共用同一个开关，不会出现「模型正、视频镜」；</li>
 *   <li>切换只是「这一帧画谁」，模型不销毁、不重载，来回是零成本。</li>
 * </ul>
 *
 * <h3>背景透明的两条路</h3>
 * <ol>
 *   <li><b>画面外留白</b>：视频宽高比和屏不一致时，四周留透明（不是黑边）。永远成立。</li>
 *   <li><b>抠像</b>：把跳舞视频渲成纯绿/纯蓝背景，这里按色差抠掉。见 {@link #setKey}。</li>
 * </ol>
 *
 * <p>线程：{@link #prepareGL()} / {@link #drawFrame} 只能在 GL 线程调；
 * {@link #play} / {@link #stop} 在主线程调（MediaPlayer 的回调需要 Looper）。
 */
public final class VideoStage {

    private static final String TAG = "AmberVideo";

    /** 状态回调，全部在主线程。 */
    public interface Listener {
        /** 开始出画（已拿到首帧尺寸）。 */
        void onStarted(String source, int width, int height);

        /** 播完了 / 被停掉。err 为 null 表示正常结束。 */
        void onStopped(String reason, String err);
    }

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

    private static final String VS =
            "attribute vec4 aPos;\n"
            + "attribute vec2 aTex;\n"
            + "uniform mat4 uST;\n"
            + "varying vec2 vTex;\n"
            + "void main() {\n"
            + "  gl_Position = aPos;\n"
            + "  vTex = (uST * vec4(aTex, 0.0, 1.0)).xy;\n"
            + "}\n";

    /**
     * 输出预乘 alpha：EGL surface 是 RGBA8888 + TRANSLUCENT，合成端按预乘解释，
     * 所以这里必须写 {@code rgb*a, a}；直接写 {@code vec4(c.rgb, a)} 会在半透明处发亮。
     */
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vTex;\n"
            + "uniform samplerExternalOES uTex;\n"
            + "uniform vec3 uKey;\n"
            + "uniform float uThreshold;\n"
            + "uniform float uKeyOn;\n"
            + "void main() {\n"
            + "  vec4 c = texture2D(uTex, vTex);\n"
            + "  if (uKeyOn > 0.5) {\n"
            + "    float d = distance(c.rgb, uKey);\n"
            + "    float a = smoothstep(uThreshold, uThreshold + 0.10, d);\n"
            + "    c.a *= a;\n"
            // 溢色压制：抠像边缘往往留一圈键色，把它往灰度拉一点，边缘才不会发绿/发蓝
            + "    c.rgb = mix(c.rgb, vec3(dot(c.rgb, vec3(0.3333))), (1.0 - a) * 0.35);\n"
            + "  }\n"
            + "  gl_FragColor = vec4(c.rgb * c.a, c.a);\n"
            + "}\n";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Listener listener;

    // ---- GL 侧（只在 GL 线程读写） ----
    private int texId;
    private SurfaceTexture surfaceTexture;
    private int program;
    private int aPos, aTex, uST, uTex, uKey, uThreshold, uKeyOn;
    private FloatBuffer quad;

    // ---- 主线程侧 ----
    private MediaPlayer player;
    private volatile boolean active;      // GL 线程每帧读：这一帧该不该画视频
    private String source = "";
    private String state = "idle";        // idle / loading / playing / ended / error
    private int videoWidth, videoHeight;
    private float gain = 1f;
    private boolean looping = true;

    // 抠像参数（任何线程写、GL 线程读，都是小原子值）
    private volatile boolean keyOn;
    private float keyR, keyG, keyB = 1f;
    private volatile float threshold = 0.30f;
    private volatile String keyName = "none";

    private int retries;

    public VideoStage(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ================================================================ GL 线程

    /** 建外部纹理 + 着色器。必须在 GL 线程、上下文 current 时调（GlSurface.onSurfaceCreated）。 */
    public void prepareGL() {
        releaseGL();
        final int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

        surfaceTexture = new SurfaceTexture(texId);
        surfaceTexture.setDefaultBufferSize(1280, 720);

        program = build();
        if (program == 0) {
            Log.e(TAG, "着色器建不起来，副屏不可用");
            return;
        }
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uST = GLES20.glGetUniformLocation(program, "uST");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uKey = GLES20.glGetUniformLocation(program, "uKey");
        uThreshold = GLES20.glGetUniformLocation(program, "uThreshold");
        uKeyOn = GLES20.glGetUniformLocation(program, "uKeyOn");

        ByteBuffer buf = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder());
        quad = buf.asFloatBuffer();
        Log.i(TAG, "副屏外部纹理就绪，texture=" + texId);
    }

    private int build() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "链接着色器失败：" + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "编译着色器失败：" + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    /**
     * 画这一帧。调用方（{@link GlSurface#onDrawFrame}）已经清成透明。
     *
     * @param viewW/viewH surface 尺寸（像素）
     * @param mirror    和模型同一个镜像开关
     */
    public void drawFrame(int viewW, int viewH, boolean mirror) {
        if (!active || surfaceTexture == null || program == 0 || quad == null) {
            return;
        }
        try {
            surfaceTexture.updateTexImage();
        } catch (RuntimeException e) {
            // surface 被 release 过（换 context）：这一帧跳过，下一帧重建
            Log.w(TAG, "updateTexImage 失败：" + e.getMessage());
            return;
        }
        final float[] st = new float[16];
        surfaceTexture.getTransformMatrix(st);

        // 等比装进画面（contain）；四周留出来的地方是透明的，灯带的光从那里透上来。
        float sx = 1f, sy = 1f;
        if (videoWidth > 0 && videoHeight > 0 && viewW > 0 && viewH > 0) {
            final float va = (float) videoWidth / videoHeight;
            final float da = (float) viewW / viewH;
            if (va > da) {
                sy = da / va;
            } else {
                sx = va / da;
            }
        }
        // 镜像靠换 uv，不动几何：几何一动，观感上就成了「视频往左、模型往右」。
        float u0 = 0f, u1 = 1f;
        if (mirror) {
            u0 = 1f;
            u1 = 0f;
        }
        quad.clear();
        quad.put(new float[] {
                -sx, -sy, u0, 0f,
                sx, -sy, u1, 0f,
                -sx, sy, u0, 1f,
                sx, sy, u1, 1f,
        });
        quad.position(0);

        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniformMatrix4fv(uST, 1, false, st, 0);
        GLES20.glUniform3f(uKey, keyR, keyG, keyB);
        GLES20.glUniform1f(uThreshold, threshold);
        GLES20.glUniform1f(uKeyOn, keyOn ? 1f : 0f);

        quad.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
        quad.position(0);
    }

    /** 释放 GL 资源。GL 线程。 */
    public void releaseGL() {
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (texId != 0) {
            GLES20.glDeleteTextures(1, new int[] { texId }, 0);
            texId = 0;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
    }

    /** surface 尺寸 / buffer 尺寸变化时重设一次，避免首帧拉伸。 */
    public void setDefaultBufferSize(int w, int h) {
        if (surfaceTexture != null && w > 0 && h > 0) {
            surfaceTexture.setDefaultBufferSize(w, h);
        }
    }

    // ================================================================ 主线程

    public boolean active() {
        return active;
    }

    public String state() {
        return state;
    }

    public String source() {
        return source;
    }

    public int videoWidth() {
        return videoWidth;
    }

    public int videoHeight() {
        return videoHeight;
    }

    public int position() {
        final MediaPlayer p = player;
        if (p == null) return 0;
        try {
            return p.getCurrentPosition();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public int duration() {
        final MediaPlayer p = player;
        if (p == null) return 0;
        try {
            return p.getDuration();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public String keyName() {
        return keyName;
    }

    public void setGain(float g) {
        gain = Math.max(0f, Math.min(1f, g));
        final MediaPlayer p = player;
        if (p != null) {
            try {
                p.setVolume(gain, gain);
            } catch (RuntimeException ignored) {
                // 播放器正在换源，下一次设也一样
            }
        }
    }

    /**
     * 抠像。{@code spec} 取：
     * <pre>
     *   none            不抠（默认），视频原样盖上来（画面外仍然是透明的）
     *   green / blue    纯绿 / 纯蓝背景，阈值 0.30
     *   black           纯黑背景（注意：模型自己的暗部也会被吃掉一部分）
     *   0x00FF00:0.25   指定颜色 + 阈值（阈值 0.05~0.95，越大抠得越狠）
     * </pre>
     */
    public boolean setKey(String spec) {
        final String raw = spec == null ? "" : spec.trim().toLowerCase(Locale.US);
        if (raw.length() == 0 || "none".equals(raw) || "off".equals(raw) || "0".equals(raw)) {
            keyOn = false;
            keyName = "none";
            Log.i(TAG, "抠像：关");
            return true;
        }
        String color = raw;
        float thr = 0.30f;
        final int colon = raw.indexOf(':');
        if (colon > 0) {
            color = raw.substring(0, colon).trim();
            try {
                thr = Float.parseFloat(raw.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                Log.w(TAG, "抠像阈值不认识，用 0.30：" + raw);
            }
        }
        thr = Math.max(0.05f, Math.min(0.95f, thr));
        float r, g, b;
        if ("green".equals(color)) {
            r = 0f; g = 1f; b = 0f;
        } else if ("blue".equals(color)) {
            r = 0f; g = 0f; b = 1f;
        } else if ("black".equals(color)) {
            r = 0f; g = 0f; b = 0f;
        } else if (color.startsWith("0x") || color.startsWith("#")) {
            try {
                final int v = Integer.parseInt(color.substring(2), 16);
                r = ((v >> 16) & 0xff) / 255f;
                g = ((v >> 8) & 0xff) / 255f;
                b = (v & 0xff) / 255f;
            } catch (NumberFormatException e) {
                Log.w(TAG, "抠像颜色不认识：" + raw);
                return false;
            }
        } else {
            Log.w(TAG, "抠像只认 none/green/blue/black/0xRRGGBB，收到：" + raw);
            return false;
        }
        keyR = r;
        keyG = g;
        keyB = b;
        threshold = thr;
        keyOn = true;
        keyName = String.format(Locale.US, "%s:%.2f", color, thr);
        Log.i(TAG, "抠像：" + keyName);
        return true;
    }

    /** 播一个视频（HTTP URL 或本地路径）。主线程。 */
    public boolean play(String src, boolean loop) {
        if (src == null || src.trim().length() == 0) {
            return false;
        }
        stop("换片");
        source = src.trim();
        looping = loop;
        retries = 0;
        openWhenReady();
        return true;
    }

    /**
     * 外部纹理是 GL 线程建的，可能还没好（App 刚起来就收到播放请求）。
     * 没好的话让 GL 线程建一次，120ms 后重试。
     */
    private void openWhenReady() {
        if (surfaceTexture == null) {
            if (retries++ > 25) {
                fail("画面还没准备好（GL 未初始化）");
                return;
            }
            if (onGlNeeded != null) {
                onGlNeeded.run();
            }
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    openWhenReady();
                }
            }, 120);
            return;
        }
        open();
    }

    /** GlSurface 用它把「去 GL 线程建一次纹理」这件事接过去。 */
    private Runnable onGlNeeded;

    public void setGlBootstrap(Runnable r) {
        this.onGlNeeded = r;
    }

    private void open() {
        final MediaPlayer p = new MediaPlayer();
        player = p;
        state = "loading";
        try {
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            p.setDataSource(source);
            p.setSurface(new Surface(surfaceTexture));
            // ★ 不要用 setLooping(true)：这台机器（Android 5.1 / MSM8909）上它会让
            //   MediaPlayer 在第一帧之后就丢一个 completion —— 实测「loop=true 一秒内结束、
            //   loop=false 完整播 3.9 秒」。循环改成 completion 里自己 seekTo(0)+start()。
            p.setLooping(false);
            p.setVolume(gain, gain);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    if (player != mp) {
                        return;
                    }
                    try {
                        mp.start();
                    } catch (RuntimeException e) {
                        fail("起播失败：" + e.getMessage());
                        return;
                    }
                    active = true;
                    state = "playing";
                    Log.i(TAG, "开播 " + source + "（" + videoWidth + "x" + videoHeight + "）");
                    final Listener l = listener;
                    if (l != null) {
                        l.onStarted(source, videoWidth, videoHeight);
                    }
                }
            });
            p.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer mp, int w, int h) {
                    videoWidth = w;
                    videoHeight = h;
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (player != mp) {
                        return;
                    }
                    if (looping) {
                        // 副屏的语义就是「一块一直在放的屏」。自己接上，不用 setLooping（见上面）。
                        try {
                            mp.seekTo(0);
                            mp.start();
                            Log.i(TAG, "循环重播");
                        } catch (RuntimeException e) {
                            fail("重播失败：" + e.getMessage());
                        }
                        return;
                    }
                    // 单次播完就回大肥鱼，别留最后一张静止画面
                    stop("播完");
                    final Listener l = listener;
                    if (l != null) {
                        l.onStopped("播完", null);
                    }
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    fail("解码失败 what=" + what + " extra=" + extra);
                    return true;
                }
            });
            p.prepareAsync();
        } catch (Exception e) {
            fail("打不开：" + e.getMessage());
        }
    }

    private void fail(String err) {
        Log.e(TAG, err + "（" + source + "）");
        stop("出错");
        state = "error";
        final Listener l = listener;
        if (l != null) {
            l.onStopped("出错", err);
        }
    }

    /** 停止并回到模型（幂等）。主线程。 */
    public void stop(String why) {
        final MediaPlayer p = player;
        player = null;
        active = false;
        videoWidth = 0;
        videoHeight = 0;
        if (p != null) {
            try {
                if (p.isPlaying()) {
                    p.stop();
                }
            } catch (RuntimeException ignored) {
                // 已经停了 / 还没 prepared，stop 会抛，忽略
            }
            try {
                p.release();
            } catch (RuntimeException ignored) {
                // 释放失败不影响回模型
            }
            Log.i(TAG, "停播（" + why + "）");
        }
        if (state.equals("playing") || state.equals("loading")) {
            state = "idle";
        }
    }

    /** 当前有没有在播（给 /state 用）。 */
    public boolean busy() {
        return player != null || active;
    }

    /**
     * EGL 上下文被重建了（切后台再回来、换分辨率）。
     *
     * <p>旧的外部纹理和 SurfaceTexture 全作废，MediaPlayer 那个 Surface 已经指向死人，
     * 继续播只会得到「有声音没画面」。这里直接收工回大肥鱼，比让她卡在一张静止帧上好。
     */
    public void noteGlReset() {
        if (!busy()) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!busy()) {
                    return;
                }
                stop("画面重建");
                final Listener l = listener;
                if (l != null) {
                    l.onStopped("画面重建", null);
                }
            }
        });
    }

    /** 存下来的视频放这里，方便 adb 直接翻。 */
    public static File videoDir(Context context) {
        final File dir = new File("/sdcard/amber/video");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }
}

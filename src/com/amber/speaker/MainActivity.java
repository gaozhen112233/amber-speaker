package com.amber.speaker;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.amber.speaker.cue.Body;
import com.amber.speaker.cue.Cues;
import com.amber.speaker.cue.Inbox;
import com.amber.speaker.cue.Line;
import com.amber.speaker.cue.Mouth;
import com.amber.speaker.cue.Sink;
import com.amber.speaker.gl.GlSurface;
import com.amber.speaker.gl.VideoStage;
import com.amber.speaker.led.Backlight;
import com.amber.speaker.led.StageLight;
import com.amber.speaker.net.SayServer;
import com.amber.speaker.sensor.CameraHealer;
import com.amber.speaker.sensor.FaceConfirm;
import com.amber.speaker.sensor.GazeTracker;
import com.amber.speaker.sensor.HumSensor;
import com.amber.speaker.sensor.PresenceWatch;
import com.amber.speaker.sensor.Snapshot;
import com.amber.speaker.sensor.Touch;
import com.amber.speaker.sound.Voice;
import com.amber.speaker.sound.WakeReply;

import java.io.IOException;
import java.util.List;

/**
 * 音箱这一端的最小闭环：收 /say、解析〔〕、按时间轴出字、把动作命令打给她身体。
 *
 * <p>层次从下往上：黑底窗口 → GL 画她的模型 → 字幕。字幕是**唯一的同步参照**：
 * 字出到第几个、身体该在哪一刻动，全靠这条时间轴。
 *
 * <p>动作命令一份打到 {@link GlSurface}（真的动），一份打到 logcat（tag {@code AmberCue}，
 * 格式和桌宠完全一致，可以用 {@code adb logcat} 跟 PC 上的输出逐行对着看）。
 */
public final class MainActivity extends Activity implements SayServer.Listener, Choreographer.FrameCallback {

    private static final String TAG = "AmberCue";

    /**
     * 模型别名 -> APK assets 里的目录。
     *
     * <p>用短名而不是中文名做键：这条命令是从 AstrBot 那边用查询串/JSON 发过来的，
     * 中文在 URL 里要编码一次，出错时很难看出来是「没编码」还是「名字不对」。
     * 中文别名也收着，方便人拿 curl 直接试。
     */
    private static final String[][] MODEL_ALIASES = {
            { "dafeiyu", "model/" },
            { "大肥鱼", "model/" },
            { "default", "model/" },
            { "miku", "model_miku/" },
            { "sakura", "model_sakura/" },
            { "樱花", "model_sakura/" },
            { "樱花miku", "model_sakura/" },
    };

    private SubtitleView view;
    private GlSurface gl;
    private SayServer server;
    private Body body;
    /**
     * 副屏（无线投屏）：把电脑推过来的视频画在她这块画布上。
     *
     * <p>不是另开一个 App / Activity，而是<b>同一张 GL 画布换画一个人</b>：
     * <ul>
     *   <li>模型不销毁、不重载 → 切回来是零成本（换模型要 3.5 秒，切副屏只要一帧）；</li>
     *   <li>背景天然透明，背后的实体 LED 灯带照样透上来（第三方播放器只会给一块黑底）；</li>
     *   <li>镜像和模型共用同一个开关（这块屏要靠应用侧补一次左右翻才是正的，
     *       别的 App 不会补，画面在实体屏上就是左右反的）。</li>
     * </ul>
     */
    private VideoStage video;
    /** 副屏是否在播。语音链路整套让路：不开麦、不应答、不熄屏。 */
    private boolean videoMode;
    /** 待机小剧场：桌宠 skits.txt 的无声版（道具 + 动作，不出声）。 */
    private SkitPlayer skit;
    /** 说话时嘴的开合。每帧推一次，值没变就不发。 */
    private final Mouth mouth = new Mouth();
    /** 上一帧是不是在说话。只为了在「开口/闭嘴」这两个转折点各打一行日志。 */
    private boolean mouthTalking;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 等着说的句子。一行一个气泡，说完一行接下一行；带 ttl 的和会被打断的都在里面管。 */
    private final Inbox inbox = new Inbox();
    /** 她的嗓子。一句一换，没有配音的句子就不用它。 */
    private Voice voice;
    private Line current;
    /** 已经取出来、但配音还没备好的一句。等不到也得说，所以它只是暂存。 */
    private Inbox.Speech pending;
    private long pendingSince;
    /** 当前这句有没有在放配音。有的话时间轴跟着音频走，而不是跟着墙上的钟。 */
    private boolean voiced;
    /** 配音一直卡在第 0 毫秒的时刻，用来判断「播放其实没起来」。 */
    private long voiceZeroAt;
    /**
     * 上一段配音真正结束的时刻（elapsedRealtime）。{@link #busyForListen()} 用它做尾音保护。
     *
     * <p>为什么需要它：真机日志里「她说完 1.4 秒后麦克风录到『查了一下』」——喇叭尾音和
     * 播放结束之间有一小段，光看 {@code current == null} 判断不出来。尾音被录进去之后，
     * 插件会把它当成用户说的话去识别，识别结果又被丢弃，白白吃掉一轮，人就觉得
     * 「她说完之后我说什么都没反应」。
     */
    private long lastAudioEndAt;
    /** 播放结束后多久内仍然拒绝 /listen（毫秒）。80ms 只盖住喇叭最后一个采样和 HAL 切换。 */
    private static final long LISTEN_TAIL_GUARD_MS = 80;
    /**
     * 收到 /say 或 /wake 之后多久内拒绝 /listen。500ms 用来盖住「请求已到、但主线程
     * 还没把第一句放进播放队列」的那一小段空档——这段时间设备看起来是空闲的，
     * 插件如果抢在这时开麦，就会在她开口的瞬间录到第一句的自己。
     * 不要再放大：这条窗口会直接推迟「她说完后我接话」的第一次收音。
     */
    private static final long SPEECH_COMMAND_GUARD_MS = 500;
    private long lastSpeechCommandAt;
    private long lineStartNanos;
    private int received;
    private boolean frameScheduled;
    /** 唤醒应答状态机。KWS 接上之前，由 POST /wake 模拟喊醒。 */
    private WakeState wake;
    /** 定时熄屏休眠状态机。00:00–08:00 熄屏，语音唤醒词可叫醒。 */
    private SleepState sleep;
    /** 根容器，熄屏时隐藏（角色 + 字幕一起消失）。 */
    private FrameLayout root;
    /** 舞台灯带。三档亮度 + 说话时跟着电平起伏，详见 {@link StageLight}。 */
    private StageLight stage;
    /** 人体感应器（hum_sensor）：读到「有人/没人」边沿后驱动走近唤醒 + 在场搭话。 */
    private HumSensor hum;
    /** 摄像头二次确认：人体感应触发后短暂开摄像头确认是不是真人。 */
    private FaceConfirm faceConfirm;
    /** 在场停留监视器：把 PIR 运动脉冲 + 摄像头周期确认合成稳定「在场」信号，喂给状态机。 */
    private PresenceWatch presenceWatch;
    /** 唤醒态视线追踪：唤醒后交互期间持续开摄像头，让她「眼神跟着人走」。 */
    private GazeTracker gaze;
    /** 「眼睛」工具（§7.1）：主动取一帧画面交视觉模型分析。 */
    private Snapshot snapshot;
    /** §8.7 触碰换语义：走近/注视/离开 驱动她抬头看、歪头脸红、低头。 */
    private com.amber.speaker.sensor.Touch touch;
    /** 「眼睛」工具额度闸门：上次取帧的墙钟时刻（epoch 毫秒），0 表示从没取过。 */
    private long lastSnapshotAt = 0;
    /** 「眼睛」工具额度闸门：当天取帧次数（按墙钟日期归零，见 {@link #snapshotDayKey()}）。 */
    private int snapshotDayCount = 0;
    private String snapshotDayKey = "";
    /** 上一帧的相位，用来检测「进/出唤醒态」的边沿，驱动视线追踪的开与关。 */
    private int lastPhase = WakeState.PHASE_IDLE;
    /** 灯带线程读的两个瞬时量：休眠中 / 当前说话电平。volatile 就够，一帧的滞后看不出来。 */
    private volatile boolean ledAsleep;
    private volatile float ledMouth;
    /** 当前窗口屏幕亮度 0~1；1 = 满亮。 */
    private float screenBrightness = 1f;
    /** 当前是否已熄屏（记录状态，避免每帧重复开关）。 */
    private boolean screenOff;
    /**
     * 当前模型的摆台（x, y, scale），只用于 /state 回显给调参台。
     * 真正的值在 SharedPreferences（每个模型一份）和原生投影矩阵里。
     */
    private float[] currentFit = new float[]{0f, 0f, 1f};
    /**
     * AstrBot 是否用 /control display 手动覆盖了屏幕状态。覆盖期间跳过 sleep.tick 的
     * 自动亮/熄屏，直到收到相反的 display 指令才恢复自动逻辑。这样「白天手动 display:off」
     * 不会被下一帧的定时熄屏判断立刻亮回来。
     */
    private boolean manualDisplayOverride;

    /**
     * 她的音量增益（0~1）。顶部左右触摸键直接改这个值。
     *
     * <p>为什么不用系统音量：这台机器的 framework 根本不处理音量键——`input keyevent 25`
     * 在桌面和本应用里都不改 `STREAM_MUSIC`，`dumpsys audio` 一直是 15/15。而顶部五个
     * 触摸键在 Android 里是 KEY_F1~F5（实测：左=F1、上=F2、中=F3、右=F4、下=F5），
     * 厂商 Unity 应用自己处理，我们的 Activity 不接就完全没反应。所以这里自己接键、
     * 自己调 {@link Voice} 的播放增益；不依赖系统音量通路。
     */
    private float volumeGain = 1.0f;
    /** 每按一次音量键的步进。0~1 分 10 档，够细也不会按到手酸。 */
    private static final float VOLUME_STEP = 0.1f;
    /** 麦克风是否被顶部中间的「静音闭麦键」关掉。关掉时 /listen 一律返回忙。 */
    private volatile boolean micMuted;
    /** 按键提示音。失败就算了，不影响功能。 */
    private ToneGenerator tone;

    /**
     * 低频心跳。每 20 秒跑一次定时判断（熄屏 / 亮屏）。
     *
     * <p>为什么不能只靠渲染帧：{@code doFrame} 在她待机、没人说话时会停
     * （{@code frameScheduled = false}），而「到点熄屏」「时间同步后重判」都必须在
     * 静止状态下也能发生。没有这个心跳，凌晨 00:00 永远等不到熄屏；反过来，被误判
     * 熄屏之后也永远亮不回来——真机上看到的就是「灯灭了再也亮不起来」。
     */
    private final android.os.Handler heartbeat =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            // 副屏期间不许自动熄屏：人在看画面（半夜投个视频两分钟就黑掉很莫名其妙）。
            // 这里和 doFrame 里那条必须一致 —— 少了这个判断，20 秒心跳就会在播放中途把屏熄掉。
            if (sleep != null && !manualDisplayOverride && !videoMode) {
                sleep.tick();
            }
            // 时间不可信就定期回头再试一次（WiFi 可能是后连上的，开机那一刻还没有网，
            // 第一次必然失败）。时间可信也别停：没有 RTC 电池，晶振会漂，隔几周就能偏几分钟，
            // 所以超过 CLOCK_REFRESH_MS 也强制重校一次。
            if (android.os.SystemClock.elapsedRealtime() - lastClockSyncAt > CLOCK_RETRY_MS
                    && (!Clock.trusted() || Clock.stale(CLOCK_REFRESH_MS))) {
                syncClock();
            }
            // 在场兜底：PIR 是运动检测型，人不动/侧向走近时可能完全没有事件。
            // 只在亮屏、待机、且当前判为不在场时，60 秒开一次摄像头确认人在不在。
            if (sensorOnFlag && !screenOff && wake != null
                    && wake.phase() == WakeState.PHASE_IDLE
                    && presenceWatch != null
                    && android.os.SystemClock.elapsedRealtime() - lastProbeAt > PRESENCE_PROBE_MS) {
                lastProbeAt = android.os.SystemClock.elapsedRealtime();
                presenceWatch.probe();
            }
            // 待机小剧场：随机间隔到了、且闸门允许（idle/有人/白天）就演一段
            if (skit != null) {
                skit.maybeStart();
            }
            heartbeat.postDelayed(this, HEARTBEAT_MS);
        }
    };
    private static final long HEARTBEAT_MS = 20000;
    /** 网络对时失败后的重试间隔。5 分钟够用：连通性变化不会比这更慢。 */
    private static final long CLOCK_RETRY_MS = 300000;
    /**
     * 时间可信时也定期重校的间隔。
     *
     * <p>6 小时：这台机器没有 RTC 电池，系统时间靠晶振从开机基准往前跑，实测一天能漂十几秒。
     * 不重校的话，几个月后「早上 8:40 的播报」会偏到 8:43，凌晨熄屏也会跟着挪。
     * 代价只是每 6 小时一次 HTTP 请求。
     */
    private static final long CLOCK_REFRESH_MS = 6 * 3600 * 1000L;
    private long lastClockSyncAt;
    /**
     * 无 PIR 运动时的在场兜底探测：每 60 秒开一次摄像头（单次 ≤5 秒、只做硬件人脸检测、
     * 不取帧不落盘），补上「人坐着没动 / 从侧面走近，PIR 不发事件」的缺口。
     */
    private static final long PRESENCE_PROBE_MS = 60000;
    private long lastProbeAt;
    /**
     * 人体感应是否启用（亮屏 true / 熄屏 false）。
     *
     * <p>原来是一个局部 {@code boolean[]}，心跳（字段匿名类）读不到，所以在心跳里做
     * 在场兜底探测时只能另开一个字段。统一成字段后，状态机和心跳读的是同一个值。
     */
    private volatile boolean sensorOnFlag = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 墙钟要先立起来：这块机器没有 RTC 电池，开机时间停在 1977 年，
        // 不校正的话休眠状态机会以为「现在是凌晨」→ 用着用着就熄屏关灯。
        Clock.init(this);
        // 行为类设置（作息 / 定时播报 / 在场搭话 / 接话窗口 / 视线跟随）从 SharedPreferences
        // 灌进 Settings 的静态缓存。必须在 SleepState / WakeState 建起来之前读，
        // 否则第一帧还会按默认值跑一次。
        SettingsStore.load(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 窗口全透明：琥珀音箱的舞台是实体 LED 灯带，画面若带黑底会挡住反射镜透进来的光
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        getWindow().setFormat(PixelFormat.TRANSLUCENT);

        // 她身后的舞台是实体 LED 灯带，不能画纯黑挡住。窗口背景透明，
        // 让灯带的光从 GL 清屏的透明处透过来，角色才会「浮」在发光舞台上。
        root = new FrameLayout(this);
        root.setBackgroundColor(0x00000000);

        // 渲染分辨率降采样倍率，默认 1（原分辨率 1024x600）。
        // 曾降到 2 想换帧率，实测完全无效（瓶颈在 CPU 逻辑不在填充），只会让画面变糊，
        // 所以保持原分辨率。诊断时可用 --ei scale 2 复现。
        final int renderScale = getIntent() == null ? 1 : getIntent().getIntExtra("scale", 1);
        // 诊断开关：--ez opaque true 用不透明 surface，用来分辨「渲染慢」还是「透明合成慢」
        final boolean opaque = getIntent() != null && getIntent().getBooleanExtra("opaque", false);
        final boolean bare = getIntent() != null && getIntent().getBooleanExtra("bare", false);
        // 上次选的模型。存的是 assets 目录名而不是「第几个」：以后往里加模型
        // 只要目录名字对得上就能用，不用再改一次映射表。
        // 目录被删掉（换了 APK）时 resolveModelDir 返回 null，退回默认的大肥鱼。
        final String savedModel = getSharedPreferences("amber", MODE_PRIVATE)
                .getString("model_dir", GlSurface.DEFAULT_MODEL);
        final String startModel = resolveModelDir(savedModel) == null
                ? GlSurface.DEFAULT_MODEL : resolveModelDir(savedModel);
        gl = new GlSurface(this, renderScale, opaque, bare, startModel);
        // 摆台：模型在画面里的大小和位置，每个模型存一份（见 applyFit）。
        // 必须紧跟着建完就下发 —— 原生层是新建的，不推一次就是 0,0,1。
        applyFit(startModel);
        root.addView(gl, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        view = new SubtitleView(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // 副屏：视频走同一块 GL 画布（外部纹理），所以透明背景和镜像都天然一致
        video = new VideoStage(this);
        video.setListener(new VideoStage.Listener() {
            @Override
            public void onStarted(String source, int w, int h) {
                Log.i(TAG, "副屏开播 " + source + " " + w + "x" + h);
            }

            @Override
            public void onStopped(String reason, String err) {
                if (videoMode) {
                    exitVideoMode(reason, err);
                }
            }
        });
        gl.setVideo(video);
        // 屏幕亮度：默认 1.0（满亮）；用户可用 /control brightness 调低，重启后保留。
        applyScreenBrightness(getSharedPreferences("amber", MODE_PRIVATE)
                .getFloat("screen_brightness", 1f));

        // 画面方向：系统档位 2 是对的（`user_rotation=2` + `accelerometer_rotation=0`，别动），
        // 但应用侧还要再补**一次**整体左右镜像——真机定标（2026-11-22 16:02）：
        // 不翻的时候整体是镜像的，翻一次就正。
        //
        // 关键：这个「一次」必须是**整层翻一次**。模型走 GL 投影矩阵翻一次；字幕走
        // onDraw 的 canvas.scale(-1,1) 翻一次。字幕里**不能再逐字翻**——那样翻两次
        // 相乘等于没翻，屏幕上字是正的，人眼再过一遍光路就成了镜子里的字。
        // 这一条是 v1.24~v1.26 折腾一整轮的根因，别再往里加翻转。
        final boolean mirror = getIntent() == null || getIntent().getBooleanExtra("mirror", true);
        gl.setMirror(mirror);
        view.setMirror(mirror);

        voice = new Voice(this);
        // 上次按到的音量要记住：拔电重启后不该突然变回 100%。
        volumeGain = Math.max(0f, Math.min(1f,
                getSharedPreferences("amber", MODE_PRIVATE).getFloat("volume", 1.0f)));
        voice.setGain(volumeGain);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 55);
        } catch (RuntimeException e) {
            Log.w(TAG, "提示音初始化失败（不影响按键）：" + e.getMessage());
        }

        // 墙钟「当天第几分钟」（0–1439），唤醒状态机和熄屏状态机共用同一个源。
        // 走 Clock 而不是直接读 Calendar：时间不可信时它返回 -1，下游据此「不熄屏、不播报」。
        final com.amber.speaker.fn.Suppliers.IntSupplier minuteOfDay =
                new com.amber.speaker.fn.Suppliers.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return Clock.minuteOfDay();
                    }
                };

        // 人体感应开关：熄屏休眠时关掉（只留语音唤醒词），亮屏时打开。
        // 放成字段 {@link #sensorOnFlag}：唤醒状态机、睡眠回调、心跳探测三处都要读。

        // 唤醒状态机：被喊醒 → 应一声。KWS 到货前用 POST /wake 触发。
        // 时钟走 elapsedRealtime（和驱动时间轴的是同一块钟），日志走 AmberCue tag。
        // 第四个参数是墙钟「当天第几分钟」，用于「在场搭话只在 10:00–23:00 触发」
        // 和「定时播报（08:40 / 23:00）」；第五个参数是人体感应开关（熄屏时 false）。
        wake = new WakeState(new WakeState.Listener() {
            @Override
            public void onAwake(final String keyword, final String source, final long nowMs) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        // 本地应声马上要开始播了（走近/在场/定时/按键都走这里）。
                        // 让 /listen 在这几百毫秒内看到忙，别抢在开口前开麦。
                        lastSpeechCommandAt = SystemClock.elapsedRealtime();
                        if (skit != null) {
                            skit.stop();
                        }
                        // 应一声：按唤醒来源选台词。
                        // 定时播报（schedule）用 keyword 里带好的固定台词（如「早上好呀主人」），
                        // 其余来源随机挑开场白。语音喊「大肥鱼/小鱼小鱼」
                        // →「小鱼在的/小鱼收到」里随机一条；走近唤醒（人体感应+摄像头确认）
                        // →「你来啦/怎么了/你终于回来啦…」里随机一条（契约 §4.1）；
                        // 在场搭话（presence）→「你在干嘛/要不要我陪陪你…」里随机一条。
                        // 命中预录库（WakeReply），直接播内置音频，不等 TTS——这才是「应一声」该有的零延迟。
                        String opening = "schedule".equals(source)
                                ? keyword
                                : WakeReply.openingFor(source);
                        List<String> one = new java.util.ArrayList<String>();
                        one.add(opening);
                        // 唤醒应答不走 /say 的命中替换，这里直接问预录库要地址，
                        // 命中就给 asset:// 地址，让 Voice 读 APK 内置音频而不是等网络。
                        String replyAudio = WakeReply.match(opening);
                        inbox.offer(one, replyAudio, 0, true, nowMs);
                        received++;
                        if (!frameScheduled) {
                            frameScheduled = true;
                            Choreographer.getInstance().postFrameCallback(MainActivity.this);
                        }
                    }
                });
            }
        }, new com.amber.speaker.fn.Suppliers.LongSupplier() {
            @Override
            public long getAsLong() {
                return SystemClock.elapsedRealtime();
            }
        }, new com.amber.speaker.fn.Suppliers.Consumer<String>() {
            @Override
            public void accept(String msg) {
                Log.i(TAG, msg);
            }
        }, minuteOfDay, new com.amber.speaker.fn.Suppliers.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return sensorOnFlag;
            }
        });

        // 摄像头二次确认：人体感应触发后，短暂开摄像头做硬件人脸检测（只检测不识别，
        // 不取帧不落盘）。确认真人 → 走近唤醒；不是人 / 检测不到 → 丢弃。
        faceConfirm = new FaceConfirm(this);

        // 唤醒态视线追踪（契约 §4.5）：唤醒后交互期间持续开摄像头做硬件人脸检测，
        // 只取「脸在画面哪个位置」驱动眼珠朝向，让她看着正在说话的人；回 idle 即关。
        // 命令走 body 的 sink，最终落到 gl.command()（任意线程安全），所以相机回调线程能直接发。
        gaze = new GazeTracker(this, new GazeTracker.Sink() {
            @Override
            public void send(String command) {
                gl.command(command);
            }
        });

        // 「眼睛」工具（契约 §7.1）：主动取一帧 JPEG，交 AstrBot 侧视觉模型分析。
        snapshot = new Snapshot(this);

        // §8.7 触碰换语义：走近/注视/离开 的反应（抬头看+慢眨眼 / 歪头+脸红 / 低头）。
        // 不开摄像头，只消费 PresenceWatch 周期确认的人脸框宽度 + 离开信号。
        touch = new Touch(new Touch.Sink() {
            @Override
            public void send(String command) {
                gl.command(command);
            }
        });

        // 在场停留监视器：PIR 运动脉冲不可靠（静止时静默），这里用摄像头周期确认把它
        // 合成稳定的「在场/离开」信号，喂给状态机做在场搭话（presence）计时。
        presenceWatch = new PresenceWatch(new PresenceWatch.Listener() {
            @Override
            public void onPresence(final boolean near) {
                // 稳定在场信号喂状态机；返回 true 表示「人重新出现」的上升沿，值得走近唤醒。
                final boolean approach = wake.onPresence(near);
                if (near && approach && sensorOnFlag) {
                    // 人重新出现 → 直接走近唤醒（不再摄像头二次确认）。熄屏时人体感应关，这里再挡一道。
                    // 说明：之前「靠近没反应」的一大根因是「5 秒内必须正对摄像头才认」，太苛刻，
                    // 人走近时脸常常没对准 → 被当「不是人」丢弃。用户定案：去掉人脸确认，直接应声。
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            wake.onWake("", WakeState.SOURCE_PROXIMITY);
                        }
                    });
                    // 走近唤醒之后也要把「在场」周期确认跑起来：去掉人脸确认后，周期确认的启动
                    // 只剩这一处了。确认是 FaceConfirm 幂等的，会自己避开唤醒态 GazeTracker 的摄像头。
                    presenceWatch.startConfirming();
                } else if (near && !approach) {
                    // 已持续在场（非上升沿）：确保周期确认在跑，为「在场搭话」计时兜底。
                    presenceWatch.startConfirming();
                }
                if (!near && touch != null) {
                    // 人离开 → 她低头继续做手里的事（§8.7）。
                    touch.onLeave();
                }
            }
        }, new PresenceWatch.FaceListener() {
            @Override
            public void onFaceWidth(float width) {
                // 每轮确认到脸的人脸框宽度，交给 Touch 估距离、判「走近/注视」。
                touch.onFaceSeen(width);
            }
        }, faceConfirm);

        // 人体感应器（PIR）：读到「有人运动」的脉冲，喂给在场监视器做「人在场」刷新。
        // 走近唤醒、在场搭话的判断都不在这里，交给 PresenceWatch + WakeState。
        hum = new HumSensor(new HumSensor.Listener() {
            @Override
            public void onPresence(final boolean near) {
                if (near && sensorOnFlag) {
                    // PIR 检测到运动 → 刷新「在场」。是否触发走近/搭话由 PresenceWatch 判定。
                    // 熄屏休眠时人体感应是关的，这里再挡一道，别让凌晨的走动把在场计时打开。
                    presenceWatch.onMotion();
                }
            }
        });
        hum.start();

        // 熄屏休眠状态机：00:00–08:00 熄屏，语音唤醒词可叫醒。
        // busy = 她当前非 idle（在说话/收音/应一声），交互中强制亮屏。
        sleep = new SleepState(minuteOfDay, new com.amber.speaker.fn.Suppliers.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return wake.phase() != WakeState.PHASE_IDLE;
            }
        }, new SleepState.Listener() {
            @Override
            public void onScreen(SleepState.Screen screen) {
                // 熄屏时关掉人体感应，只留语音唤醒词；亮屏时恢复。
                sensorOnFlag = (screen == SleepState.Screen.ON);
                // 熄屏时停掉在场监视（摄像头周期确认），亮屏时等下一次 PIR 运动再自动重启。
                if (!sensorOnFlag && presenceWatch != null) {
                    presenceWatch.stop();
                }
                applyScreen(screen == SleepState.Screen.OFF);
            }
        }, new com.amber.speaker.fn.Suppliers.Consumer<String>() {
            @Override
            public void accept(String msg) {
                Log.i(TAG, msg);
            }
        });

        // 舞台灯带。契约 §4.4 一直写着「实体 LED 灯带由设备端控制」，之前硬件那半是空的，
        // 灯全靠厂商 zhcctrl 听上层命令。现在直控驱动：待机留底光、唤醒点亮、说话跟着电平起伏、
        // 熄屏断电。它自己带 25Hz 节奏，不占主帧循环——待机时主循环是停的，灯带还得呼吸。
        stage = new StageLight(new StageLight.Source() {
            @Override
            public boolean asleep() {
                return ledAsleep;
            }

            @Override
            public boolean awake() {
                return wake.phase() != WakeState.PHASE_IDLE;
            }

            @Override
            public float mouth() {
                return ledMouth;
            }

            /**
             * 灯带的「情绪」。优先级按观感排：副屏最低调，说话最抢，其次是在听，最后是待机。
             *
             * <p>为什么「在说」要看 {@code ledMouth} 而不是只看 {@code current}：
             * {@code current} 是「这一句还没播完」，句间换气的那零点几秒它可能是 null，
             * 但人耳朵里她还在说，灯不能在这时候跳回待机呼吸。
             */
            @Override
            public int mode() {
                if (videoMode) {
                    return StageLight.MODE_VIDEO;
                }
                if ((current != null && !current.done()) || ledMouth > 0.03f) {
                    return StageLight.MODE_SPEAK;
                }
                final int phase = wake == null ? WakeState.PHASE_IDLE : wake.phase();
                if (phase == WakeState.PHASE_LISTENING || phase == WakeState.PHASE_WAKING) {
                    return StageLight.MODE_LISTEN;
                }
                return StageLight.MODE_IDLE;
            }
        });
        // 亮度是用户亲手调过的值，必须跨重启保住：调参台每次改都会写进来。
        stage.setScale(getSharedPreferences("amber", MODE_PRIVATE).getFloat("stage_light", 0.65f));
        stage.start();

        body = new Body(new Sink() {
            @Override
            public void send(String command) {
                // 一份给她身体，一份留给人看
                gl.command(command);
                Log.d(TAG, command);
            }
        });

        // 待机小剧场：只在 idle + 有人 + 白天时随机演，说话/唤醒立刻停。
        // 道具和动作都走同一条 gl.command 通路，不经过 TTS，也不占大模型 token。
        skit = new SkitPlayer(this, body, new Sink() {
            @Override
            public void send(String command) {
                gl.command(command);
                Log.d(TAG, command);
            }
        }, new SkitPlayer.Gate() {
            @Override
            public boolean canPlay() {
                if (screenOff || wake == null) {
                    return false;
                }
                if (wake.phase() != WakeState.PHASE_IDLE || !wake.presentNow()) {
                    return false;
                }
                if (current != null || pending != null || inbox.size() > 0 || micMuted) {
                    return false;
                }
                final int minute = Clock.minuteOfDay();
                // 只在 10:00–23:00 演，和在场搭话同一个窗口；深夜不自己动
                return minute >= 600 && minute <= 1380;
            }
        });

        view.show("加载模型…");
        gl.setReady(new GlSurface.Ready() {
            @Override
            public void onReady(final boolean ok) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) view.clear();
                        else view.show("模型加载失败，看 logcat 里的 AmberGL");
                    }
                });
            }
        });

        server = new SayServer(this);
        try {
            server.start();
            Log.i(TAG, "服务已启动，监听 " + server.port());
        } catch (IOException e) {
            Log.e(TAG, "服务起不来：" + e.getMessage(), e);
            view.show("端口 8788 被占用，她听不见了");
        }

        // 定时判断不能挂在渲染帧上：她待机时帧循环是停的（见 HEARTBEAT_MS 的注释）
        heartbeat.postDelayed(beat, HEARTBEAT_MS);
        // 看门狗：自己盯着「GL 线程还在不在转」「画面上还有没有东西」，出事自己重启。
        // 这两个故障以前都只能人肉处理：GL 卡死要 adb force-stop，着色器编译失败全黑
        // 也是 force-stop。人在外面上班时，她就一直黑着。
        startWatchdog();
        // 开机就试一次网络对时。系统自己的 NTP 在这台机器上拉不动（见 ClockSync 注释），
        // 所以她自己读 HTTP 的 Date 头。**时间已经可信也要对一次**：开机基准被 SP 里存下的
        // 旧偏移校正过，但那个偏移可能是几周前算的，晶振早就漂了。
        syncClock();
    }

    /**
     * 起一个后台线程做网络对时。失败不重试（由心跳按 {@link #CLOCK_RETRY_MS} 再来）。
     */
    private void syncClock() {
        lastClockSyncAt = android.os.SystemClock.elapsedRealtime();
        // 时间已经可信时走「强制」：不然 tryOnce 会看到可信就立刻返回，永远不重校，
        // 晶振漂移就没人管了。
        final boolean force = Clock.trusted();
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!ClockSync.tryOnce(MainActivity.this, force)) {
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        // 时间刚变准，之前可能正被误判成「凌晨」熄着屏，立刻重判
                        if (sleep != null && !manualDisplayOverride && !videoMode) {
                            sleep.tick();
                        }
                        Log.i(TAG, "网络对时完成：现在 " + Clock.minuteOfDay() + " 分钟，屏幕 "
                                + (screenOff ? "熄屏" : "亮屏"));
                    }
                });
            }
        }, "amber-clock").start();
    }

    // ---------------------------------------------------------------- 看门狗

    /**
     * 看门狗线程的句柄与状态。
     *
     * <p>它负责三件事，都跟「人不在家时她别死掉」有关：
     * <ol>
     *   <li><b>GL 线程还活着吗</b>：往渲染线程塞空任务，6 秒不执行就重启。
     *       GL 卡死时 HTTP 还是好的（/state 照答），从外面完全看不出来。</li>
     *   <li><b>画面上还有东西吗</b>：连续 90 秒读不到一个非空像素就重启。
     *       着色器偶发编译失败的典型表现就是「进程活着、帧率正常、整块黑」。</li>
     *   <li><b>写心跳文件</b>：{@code /sdcard/amber/beat}，给 Magisk 侧的
     *       {@code amber_keepalive.sh} 兜底——它能在整个进程消失后把应用拉起来。</li>
     * </ol>
     *
     * <p>重启用「先挂一个闹钟再自杀」：闹钟存在系统服务里，进程死了照样会触发，
     * 1.5 秒后把 Activity 重新拉起来。直接 killProcess 而不挂闹钟，就没人拉得动了。
     */
    private Thread watchdog;
    private volatile boolean watchdogStop;
    /** 已经放弃自愈（重启太频繁），等冷静期过了再恢复。 */
    private volatile boolean watchdogHalted;
    private long haltedAt;
    private int restartCount;
    private long restartWindowAt;
    /** 进程起来的时刻，用来给「刚开机还在加载模型」留豁免期。 */
    private final long startedAt = SystemClock.elapsedRealtime();
    private static final long WATCHDOG_TICK_MS = 30000;
    private static final long BLACK_LIMIT_S = 90;
    private static final long GL_PING_MS = 6000;

    private void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdogStop = false;
        watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                watchdogLoop();
            }
        }, "amber-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Log.i(TAG, "看门狗已启动：每 30 秒自检 GL 线程与画面内容");
    }

    private void watchdogLoop() {
        while (!watchdogStop) {
            try {
                Thread.sleep(WATCHDOG_TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (watchdogStop) {
                return;
            }
            try {
                tickWatchdog();
            } catch (Throwable t) {
                // 看门狗自己不许把进程带崩
                Log.w(TAG, "看门狗自检出错：" + t);
            }
        }
    }

    private void tickWatchdog() {
        writeBeat();
        final long now = SystemClock.elapsedRealtime();
        // 放弃自愈之后**要继续写心跳**：Magisk 侧的 keepalive 只看心跳，心跳停了它就会
        // 一直把我们拉起来，跟这里的退避互相打架。半小时后再给一次机会。
        if (watchdogHalted) {
            if (now - haltedAt > 1800000) {
                watchdogHalted = false;
                restartCount = 0;
                Log.i(TAG, "看门狗：冷静期结束，恢复自愈");
            }
            return;
        }
        final GlSurface surface = gl;
        if (surface == null) {
            return;
        }
        if (!surface.pingGL(GL_PING_MS)) {
            restartSelf("GL 线程 " + GL_PING_MS + "ms 没响应");
            return;
        }
        // 刚开机/刚切模型时画面本来就是空的，给 3 分钟豁免
        if (now - startedAt < 180000) {
            return;
        }
        if (screenOff || ledAsleep || videoMode) {
            // 熄屏是「故意不画」，副屏走的是另一条绘制路径，都不参与内容判定
            return;
        }
        final long age = surface.contentAgeSec();
        if (age >= BLACK_LIMIT_S) {
            restartSelf("画面连续 " + age + " 秒没有一个像素（着色器或模型挂了）");
        }
    }

    /**
     * 写心跳文件。Magisk 侧的 keepalive 脚本靠它的修改时间判断进程还活着。
     *
     * <p>内容顺带写相位与帧数，排障时 {@code cat /sdcard/amber/beat} 一眼看出她当时在干嘛。
     * 写失败不算错（没挂 sdcard 也不影响主功能），所以整段吞异常。
     */
    private void writeBeat() {
        try {
            final java.io.File dir = new java.io.File("/sdcard/amber");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            final java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "beat"), false);
            w.write(SystemClock.elapsedRealtime() / 1000
                    + " " + (wake == null ? "idle" : wake.phaseName())
                    + " " + (gl == null ? -1 : gl.frames()) + "\n");
            w.close();
        } catch (Throwable ignored) {
            // 没 sdcard 权限也不影响看门狗本体
        }
    }

    /**
     * 重启自己。
     *
     * <p>10 分钟内最多 3 次：如果是「换成坏模型/坏着色器」这种必然复现的故障，
     * 无限重启只会把设备拖成砖，还不如停手留下日志等人看。
     */
    private void restartSelf(String why) {
        final long now = SystemClock.elapsedRealtime();
        if (now - restartWindowAt > 600000) {
            restartWindowAt = now;
            restartCount = 0;
        }
        restartCount++;
        if (restartCount > 3) {
            Log.e(TAG, "看门狗：10 分钟内已重启 3 次，进入冷静期（避免重启循环），原因：" + why);
            watchdogHalted = true;
            haltedAt = now;
            return;
        }
        Log.e(TAG, "看门狗：重启应用（本窗口第 " + restartCount + " 次），原因：" + why);
        try {
            final android.content.Intent intent =
                    new android.content.Intent(this, MainActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            final android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 4104, intent, android.app.PendingIntent.FLAG_CANCEL_CURRENT);
            final android.app.AlarmManager am = (android.app.AlarmManager)
                    getSystemService(android.content.Context.ALARM_SERVICE);
            if (am != null) {
                am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "看门狗：排重启闹钟失败：" + t);
        }
        watchdogStop = true;
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gl != null) gl.onResume();
    }

    @Override
    protected void onPause() {
        if (gl != null) gl.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        watchdogStop = true;
        heartbeat.removeCallbacks(beat);
        if (skit != null) {
            skit.stop();
        }
        if (gaze != null) {
            gaze.stop();
        }
        if (presenceWatch != null) {
            presenceWatch.stop();
        }
        if (hum != null) {
            hum.stop();
        }
        if (stage != null) {
            stage.stop();
        }
        if (voice != null) {
            voice.release();
        }
        if (tone != null) {
            try {
                tone.release();
            } catch (RuntimeException ignored) {
                // 提示音释放失败不影响退出
            }
            tone = null;
        }
        if (server != null) {
            server.stop();
        }
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 顶部触摸键

    /**
     * 顶部五个触摸键在 Android 里是 KEY_F1~F5（真机实测，别按物理位置猜）：
     *
     * <pre>
     *   左（音量-）  = F1
     *   上（配网）   = F2
     *   中（静音闭麦）= F3
     *   右（音量+）  = F4
     *   下（唤醒）   = F5
     * </pre>
     *
     * <p>厂商应用是 Unity 写的，按键在 C# 层处理；我们的 Activity 不接就完全没反应，
     * 系统音量键通路在这台机器上又是坏的（`input keyevent 25` 改不了 STREAM_MUSIC）。
     * 所以自己接、自己调播放增益。
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0) {
            // 长按不连发：音量按一下走一档，避免一次长按把音量拉到底
            return isTouchKey(keyCode) || super.onKeyDown(keyCode, event);
        }
        // 副屏期间顶部按键就是「退出键」：这台机器没有触摸屏手感、也没有状态灯，
        // 人要看回大肥鱼，最顺手的就是按一下她头顶那几个键。音量键保留原本的用途。
        if (videoMode) {
            if (keyCode == KeyEvent.KEYCODE_F1 || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                adjustVolume(-1, "副屏左键");
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_F4 || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                adjustVolume(+1, "副屏右键");
                return true;
            }
            if (isTouchKey(keyCode)) {
                exitVideoMode("按键", null);
                return true;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_F1:
                adjustVolume(-1, "左键");
                return true;
            case KeyEvent.KEYCODE_F4:
                adjustVolume(+1, "右键");
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                adjustVolume(-1, "系统音量键");
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                adjustVolume(+1, "系统音量键");
                return true;
            case KeyEvent.KEYCODE_F3:
                toggleMicMute();
                return true;
            case KeyEvent.KEYCODE_F5:
                buttonWake();
                return true;
            case KeyEvent.KEYCODE_F2:
                Log.i(TAG, "配网键：这台机器的配网走 POST /wifi（原厂是二维码配网），"
                        + "这个键暂不触发系统设置，避免竖屏界面打乱镜像");
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isTouchKey(keyCode)) {
            return true;   // 吃掉，别让系统再补一声「无效按键」提示音
        }
        return super.onKeyUp(keyCode, event);
    }

    private static boolean isTouchKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_F1 || keyCode == KeyEvent.KEYCODE_F2
                || keyCode == KeyEvent.KEYCODE_F3 || keyCode == KeyEvent.KEYCODE_F4
                || keyCode == KeyEvent.KEYCODE_F5
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    /** 改播放增益。只影响她的声音（全部走 {@link Voice}），不碰系统音量。 */
    private void adjustVolume(int delta, String why) {
        float next = Math.max(0f, Math.min(1f, volumeGain + delta * VOLUME_STEP));
        if (Math.abs(next - volumeGain) < 0.001f) {
            Log.i(TAG, "音量键（" + why + "）：已经到" + (delta > 0 ? "最大" : "最小")
                    + " " + Math.round(volumeGain * 100) + "%");
            return;
        }
        setVolumeGain(next, why);
    }

    /** 把增益设到指定值（0~1），立即作用到正在播的那句，并记住给下一次启动。 */
    private void setVolumeGain(float value, String why) {
        volumeGain = Math.max(0f, Math.min(1f, value));
        if (voice != null) {
            voice.setGain(volumeGain);
        }
        // 副屏的音量走 MediaPlayer 自己的增益：系统音量键在这台机器上是坏的
        // （keyevent 25 改不了 STREAM_MUSIC），只能靠自己这一层
        if (video != null) {
            video.setGain(volumeGain);
        }
        getSharedPreferences("amber", MODE_PRIVATE).edit()
                .putFloat("volume", volumeGain).apply();
        Log.i(TAG, "音量（" + why + "）：" + Math.round(volumeGain * 100) + "%");
    }

    /** 中间键：静音闭麦。关着的时候 /listen 直接返回忙，她不会听也不会接话。 */
    private void toggleMicMute() {
        micMuted = !micMuted;
        Log.i(TAG, micMuted ? "麦克风已静音（/listen 暂停，语音唤醒也听不到）"
                : "麦克风已恢复");
        beep(micMuted ? ToneGenerator.TONE_PROP_NACK : ToneGenerator.TONE_PROP_ACK);
    }

    /** 下方键：物理按键唤醒，和喊名字同一个入口，只是来源是 button。 */
    private void buttonWake() {
        Log.i(TAG, "按键唤醒（下方触摸键 F5）");
        if (presenceWatch != null) {
            presenceWatch.onMotion();   // 有人按她，当然算在场
        }
        if (wake != null) {
            wake.onWake("", WakeState.SOURCE_BUTTON);
        }
        beep(ToneGenerator.TONE_PROP_ACK);
    }

    /** 短提示音。设备没有屏幕可看状态，按键至少给一声反馈。失败不抛。 */
    private void beep(int kind) {
        final ToneGenerator t = tone;
        if (t == null) {
            return;
        }
        try {
            t.startTone(kind, 90);
        } catch (RuntimeException e) {
            Log.w(TAG, "提示音失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- /say

    @Override
    public void onSay(final String text, final String id, final String audioUrl,
                      final boolean interrupt, final long ttlMs) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (videoMode) {
                    // 副屏在放视频，谁让她说话都不说：一说就把视频的声音和画面节奏全打乱
                    Log.i(TAG, "副屏中，丢弃这句：" + (text == null ? "" : text));
                    return;
                }
                // 有人让她说话，说明人就在旁边。刷新「在场时刻」但**不制造上升沿**：
                // 定时播报/主动搭话也会走 /say，不能因此反手再喊一句「你来啦」。
                lastSpeechCommandAt = SystemClock.elapsedRealtime();
                // 正式说话优先：小剧场立刻停、摘道具、收姿势，别和这句话抢动作
                if (skit != null) {
                    skit.stop();
                }
                if (presenceWatch != null) {
                    presenceWatch.refresh();
                }
                // 预录库命中：这句原文正好是「小鱼在的」这类预录好的高频短句，就改用内置音频，
                // 不等网络 TTS——唤醒应答是延迟敏感度最高的场景，等她合成完人已经说下一句了。
                String effectiveAudio = audioUrl;
                if (effectiveAudio == null) {
                    effectiveAudio = WakeReply.match(text);
                    if (effectiveAudio != null) {
                        Log.i(TAG, "预录命中：" + Text.trim(text));
                    }
                }

                // 兜底：有的模型会把几句话用「／」或「 / 」连成一行
                List<String> lines = Line.split(text);
                if (lines.isEmpty() && !Text.isBlank(text)) {
                    List<String> one = new java.util.ArrayList<String>();
                    one.add(Text.trim(text));
                    lines = one;
                }
                long now = SystemClock.elapsedRealtime();
                Inbox.Offer offer = inbox.offer(lines, effectiveAudio, ttlMs, interrupt, now);
                received++;

                if (effectiveAudio != null) {
                    // 现在就下，别等她开口才下——那几百毫秒会变成一句没声音的话
                    voice.prepare(effectiveAudio);
                }

                if (interrupt) {
                    // 打断不只是清排队：正在说的那句也要当场作废，字幕立刻换掉。
                    // 身体保持现在的姿势（契约 §15），只是不再往下演——她已经把该做的做了。
                    current = null;
                    pending = null;
                    voice.stop();
                    voiced = false;
                    voiceZeroAt = 0;
                    lastAudioEndAt = SystemClock.elapsedRealtime();
                    view.clear();
                    Log.i(TAG, "打断：丢弃待说 " + offer.dropped + " 句");
                }

                List<Cues.Unread> unread = Cues.unread(text);
                StringBuilder note = new StringBuilder();
                for (Cues.Unread one : unread) {
                    if (note.length() > 0) {
                        note.append('、');
                    }
                    note.append(one.like == null ? one.word : one.word + "（是不是「" + one.like + "」）");
                }
                if (note.length() > 0) {
                    // 契约要求：认不出的词不能让她整句话失败，跳过继续播，同时记下来告诉她
                    Log.w(TAG, "note: 身体做不出 " + note);
                }
                view.setCue(unread.isEmpty() ? "〔〕全部认识" : "不认识：" + note);
                view.setReceived(received);

                if (!frameScheduled) {
                    frameScheduled = true;
                    Choreographer.getInstance().postFrameCallback(MainActivity.this);
                }
            }
        });
    }

    @Override
    public void onWake(final String keyword, final String source) {
        // 副屏在播：不应答。应答会抢音频通路、把视频声音掐掉，
        // 而且人此刻显然在看屏幕，不是来喊她的。
        if (videoMode) {
            Log.i(TAG, "副屏中，忽略唤醒（" + source + "）");
            return;
        }
        // 应声马上要开始播，先让 /listen 忙起来（和 /say 同理）
        lastSpeechCommandAt = SystemClock.elapsedRealtime();
        // 唤醒优先：小剧场停掉，别在「小鱼在的」之前还戴着单片眼镜演侦探
        if (skit != null) {
            skit.stop();
        }
        // 语音/按键唤醒是「有人在」的直接证据：刷新在场并制造上升沿（如果之前判离开）。
        // 这样走近唤醒失败、但人喊了一嗓子时，在场计时也能接上。
        if (presenceWatch != null) {
            presenceWatch.onMotion();
        }
        // 进状态机：说话中或冷却期内会被它拦下（返回 false 就什么都不发生）
        wake.onWake(keyword, source);
    }

    /**
     * 每个模型默认的摆台（x, y, scale），没存过值时用。
     *
     * <p>三个模型的画布留白习惯不一样：大肥鱼的顶点本来就铺满整块画布，miku 两个是
     * 标准全身立绘 —— 画布里头顶和脚下都留了边，直接按「铺满高度」打上去会显得偏小
     * 又偏上。这里给的是初值，改完位置会写进 SharedPreferences 覆盖它。
     */
    private static float[] defaultFit(String directory) {
        // 实测（2026-11-24）：两个标准模型在同一块屏上都是「1.08 时上下都切、水平偏右 77px」，
        // 三个数一起改成 -0.12 / +0.04 / 0.91 后头顶留 24~30px、脚下留 25px、水平中心 512。
        if ("model_miku/".equals(directory)) return new float[]{-0.12f, 0.04f, 0.91f};
        if ("model_sakura/".equals(directory)) return new float[]{-0.12f, 0.04f, 0.91f};
        return new float[]{0f, 0f, 1f};
    }

    /** SharedPreferences 里「某个模型的摆放」用哪个键。目录名去掉末尾斜杠。 */
    private static String fitKey(String directory) {
        String name = directory == null ? "" : directory;
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        return "fit_" + name;
    }

    /**
     * 把某个模型的摆台送到渲染层。
     *
     * <p>存在 SharedPreferences 里的格式是 {@code "x,y,scale"}（三个十进制小数）。
     * 存过就用存的，没存过用 {@link #defaultFit}。换模型、开机都要走一次：
     * 原生对象是新建的，不推就是 0,0,1，位置会跳回中间。
     */
    private void applyFit(String directory) {
        if (gl == null) return;
        final float[] fallback = defaultFit(directory);
        float x = fallback[0], y = fallback[1], scale = fallback[2];
        final String saved = getSharedPreferences("amber", MODE_PRIVATE)
                .getString(fitKey(directory), null);
        if (saved != null) {
            final String[] parts = saved.split(",");
            if (parts.length == 3) {
                try {
                    x = Float.parseFloat(parts[0].trim());
                    y = Float.parseFloat(parts[1].trim());
                    scale = Float.parseFloat(parts[2].trim());
                } catch (NumberFormatException e) {
                    Log.w(TAG, "摆放值解析失败，用默认：" + saved);
                }
            }
        }
        gl.setFit(x, y, scale);
        currentFit = new float[]{x, y, scale};
    }

    // ---------------------------------------------------------------- 副屏（投屏）

    /**
     * 切到副屏：同一块画布改画视频，模型留着不销毁。
     *
     * <p>为什么整套语音链路要让路：喇叭和麦克风紧挨着，而且这台机器的采集流在播放开始时
     * 会被 HAL 拆掉（§4.2）。视频一响，开麦只会把她自己的声音录进去，还会和视频抢音频通路。
     */
    private void enterVideoMode(final String source, final boolean loop) {
        if (source == null || source.trim().length() == 0) {
            view.show("没有视频地址");
            return;
        }
        // 休眠区间里被叫去当副屏：先把屏点亮，否则就是「有声音没画面」
        if (screenOff) {
            applyScreen(false);
        }
        if (skit != null) {
            skit.stop();
        }
        inbox.clear();
        pending = null;
        current = null;
        if (voice != null) {
            voice.stop();
        }
        videoMode = true;
        view.setVisibility(ViewGroup.GONE);   // 字幕层会压在视频上，而且左右镜像，看着像鬼画符
        video.setGain(volumeGain);
        if (!video.play(source, loop)) {
            exitVideoMode("打不开", "视频打不开：" + source);
            return;
        }
        beep(ToneGenerator.TONE_PROP_ACK);
        Log.i(TAG, "进入副屏：" + source + (loop ? "（循环）" : ""));
    }

    /** 收工回大肥鱼。幂等：播完 / 出错 / 按键 / HTTP 都会走这里。 */
    private void exitVideoMode(String why, String err) {
        final boolean was = videoMode;
        videoMode = false;
        if (video != null) {
            video.stop(why);
        }
        view.setVisibility(ViewGroup.VISIBLE);
        view.clear();
        if (err != null) {
            view.show(err);
        }
        if (was) {
            beep(err == null ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_NACK);
            Log.i(TAG, "退出副屏（" + why + "）" + (err == null ? "" : "：" + err));
            // 副屏期间是「强制亮屏、跳过 sleep.tick」的，收工后必须立刻重判一次屏幕：
            // 凌晨投完视频要马上黑回去，不能等下一次 20 秒心跳（待机时帧循环还是停的，
            // doFrame 里那次 tick 也等不到）。进副屏时 applyScreen 已经把状态机同步成
            // 「亮着」，所以这里 tick 一算「该熄」就会真的熄。
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!videoMode && sleep != null && !manualDisplayOverride) {
                        sleep.tick();
                    }
                }
            });
        }
    }

    /**
     * 模型短名 / 目录名 -> assets 目录（末尾带斜杠）。认不出来、或目录在 APK 里不存在，返回 null。
     *
     * <p>「目录真的存在」这一步不能省：SharedPreferences 里存的是目录名，换了 APK
     * 删掉某个模型之后，下次开机照着旧名字解析就是加载失败卡在「加载模型…」。
     */
    private String resolveModelDir(String value) {
        if (value == null) return null;
        final String name = value.trim();
        if (name.length() == 0) return null;
        for (String[] pair : MODEL_ALIASES) {
            if (pair[0].equalsIgnoreCase(name)) return pair[1];
        }
        // 也接受直接写目录名（model_miku / model_miku/），这样往里加模型不必改这张表。
        // 注意比较时要去掉末尾斜杠：SharedPreferences 里存的是带斜杠的（"model_miku/"），
        // 而 assets.list() 返回的是不带斜杠的，直接比会永远不相等 ——
        // 表现就是「换了模型，重启又变回大肥鱼」，而且看不出哪里错了。
        final String bare = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        try {
            final String[] tops = getAssets().list("");
            for (String top : tops) {
                if (top.equals(bare)) return top + "/";
            }
        } catch (IOException e) {
            Log.w(TAG, "列 assets 失败：" + e.getMessage());
        }
        return null;
    }

    @Override
    public void onControl(final String action, final String value) {
        main.post(new Runnable() {
            @Override
            public void run() {
                // 换模型：{@code POST /control} {"action":"model","value":"miku"}。
                // 设备上现在有三套：大肥鱼（默认）、miku、樱花 miku。
                // 换完写进 SharedPreferences，下次开机还是这个 —— 用户换完不会想再来一次。
                if ("model".equals(action)) {
                    final String dir = resolveModelDir(value);
                    if (dir == null) {
                        Log.w(TAG, "没有这个模型：" + value + "（可用：dafeiyu / miku / sakura）");
                        view.show("没有这个模型：" + value);
                        return;
                    }
                    // 小剧场正在演就先停：它排进队列的动作名是给上一个模型写的
                    if (skit != null) {
                        skit.stop();
                    }
                    getSharedPreferences("amber", MODE_PRIVATE).edit()
                            .putString("model_dir", dir).apply();
                    // 摆台是按模型存的：先把新模型的值塞进去，等它建完纹理就会自动下发。
                    // 不这么做的话，换过来会沿用上一个模型的位置（miku 和大肥鱼的画布留白差很多，
                    // 看上去就是「换完模她就吊在半空 / 被切了半只脚」）。
                    applyFit(dir);
                    view.show("换模型中…");
                    final boolean accepted = gl.switchModel(dir, new GlSurface.Ready() {
                        @Override
                        public void onReady(final boolean ok) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (ok) {
                                        view.clear();
                                        Log.i(TAG, "模型切换完成：" + dir);
                                    } else {
                                        view.show("模型加载失败，看 logcat 里的 AmberGL");
                                        Log.e(TAG, "模型切换失败：" + dir);
                                    }
                                }
                            });
                        }
                    });
                    if (!accepted) {
                        view.show("正在切换中，稍后再试");
                    }
                    Log.i(TAG, "切换模型 -> " + dir);
                    return;
                }
                // 摆台：{"action":"fit","value":"x,y,scale"}。
                // 传数字就摆过去并**记在当前模型名下**（每个模型一份，换回来还是这个位置）；
                // value 传 "reset" 就删掉记录、回到 defaultFit。
                if ("fit".equals(action)) {
                    float x = 0f, y = 0f, scale = 1f;
                    final String raw = value == null ? "" : value.trim();
                    // "reset" = 回默认。不能靠「value 为空」表达：/control 的入口校验要求
                    // 除 reboot 外必须有 value，空值在上一层就被 400 挡掉了。
                    final boolean reset = raw.length() == 0 || "reset".equalsIgnoreCase(raw);
                    if (!reset) {
                        final String[] parts = raw.split(",");
                        if (parts.length != 3) {
                            Log.w(TAG, "fit 只认 \"x,y,scale\"，收到：" + raw);
                            return;
                        }
                        try {
                            x = Float.parseFloat(parts[0].trim());
                            y = Float.parseFloat(parts[1].trim());
                            scale = Float.parseFloat(parts[2].trim());
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "fit 数字解析失败：" + raw);
                            return;
                        }
                    }
                    final String dir = gl == null ? null : gl.modelDirectory();
                    if (dir == null) return;
                    if (!reset) {
                        currentFit = new float[]{x, y, scale};
                        gl.setFit(x, y, scale);
                        getSharedPreferences("amber", MODE_PRIVATE).edit()
                                .putString(fitKey(dir), String.format(java.util.Locale.US,
                                        "%.4f,%.4f,%.4f", x, y, scale)).apply();
                    } else {
                        // 复位 = 删掉这个模型的记录，下次按 defaultFit 来
                        getSharedPreferences("amber", MODE_PRIVATE).edit()
                                .remove(fitKey(dir)).apply();
                        final float[] fallback = defaultFit(dir);
                        currentFit = fallback;
                        gl.setFit(fallback[0], fallback[1], fallback[2]);
                    }
                    Log.i(TAG, "摆放 " + dir + " -> x=" + x + " y=" + y + " scale=" + scale);
                    return;
                }
                // 副屏（投屏）：{"action":"video","value":"http://192.168.1.10:8000/a.mp4"}。
                // 传 "stop"/"off" 收工回大肥鱼；不传值也当收工。
                // 视频是画在同一块 GL 画布上的（外部纹理），不是另开 Activity —— 所以：
                // 模型不重载（换回来是一帧的事）、背景透明（灯带的光照样透上来）、
                // 镜像和模型一致（这块屏不补一次左右翻，看实体屏就是反的）。
                if ("video".equals(action)) {
                    final String raw = value == null ? "" : value.trim();
                    if (raw.length() == 0 || "stop".equalsIgnoreCase(raw)
                            || "off".equalsIgnoreCase(raw) || "0".equals(raw)) {
                        exitVideoMode("收到停止", null);
                    } else {
                        enterVideoMode(raw, true);
                    }
                    return;
                }
                // 副屏抠像：{"action":"videokey","value":"green:0.30"}。
                // 跳舞视频渲成纯绿背景，这里按色差抠掉，LED 灯带的光就从人形空隙里透出来。
                // value 取 none / green / blue / black / 0xRRGGBB[:阈值]。
                if ("videokey".equals(action)) {
                    if (!video.setKey(value)) {
                        view.show("抠像参数不认识：" + value);
                    }
                    return;
                }
                // 诊断：整层字幕显不显示。用来分离「这块像素是 GL 画的还是字幕层画的」——
                // 关掉字幕层如果画面上的东西还在，它就只能是 GL 或更下层。
                if ("overlay".equals(action)) {
                    final boolean show = !"off".equals(value);
                    view.setVisibility(show ? ViewGroup.VISIBLE : ViewGroup.GONE);
                    Log.i(TAG, "字幕层 " + (show ? "显示" : "隐藏"));
                    return;
                }
                if ("display".equals(action)) {
                    boolean off = "off".equals(value);
                    boolean on = "on".equals(value);
                    if (!off && !on) {
                        Log.w(TAG, "display 值不识别：" + value + "（只认 on / off）");
                        return;
                    }
                    // 手动覆盖：AstrBot 主动下发，立即生效。
                    //  off → 挂起定时熄屏的自动逻辑（否则下一帧定时判断会把它亮回来）；
                    //  on  → 恢复正常，把自动逻辑交还给定时熄屏状态机。
                    // applyScreen 幂等，重复发同一指令不重复动作。
                    manualDisplayOverride = off;
                    applyScreen(off);
                    Log.i(TAG, "手动 " + (off ? "熄屏" : "亮屏") + "（/control display）");
                    return;
                }
                // 诊断：把模型全部部件藏起来，量 Update 耗时随部件数的变化
                if ("hide".equals(action)) {
                    gl.command("hide".equals(value) ? "hideall" : "showall");
                    Log.i(TAG, "诊断：部件 " + (("hide".equals(value)) ? "全藏" : "全显示"));
                    return;
                }
                // 控制台/诊断：按名字演一段小剧场（不受随机间隔限制），例如 名侦探
                if ("skit".equals(action)) {
                    if (skit != null) {
                        skit.play(value);
                    }
                    return;
                }
                // 诊断直通车：value 原样当成原生层的一条命令执行（params / hideall / set …）。
                // 查参数表这类一次性排查不值得每次都新开一个端点，留这一个口子就够。
                if ("diag".equals(action)) {
                    gl.command(value);
                    Log.i(TAG, "诊断命令：" + value);
                    return;
                }
                // 相机守护进程自愈（qcamerasvr 假死时 open 一律失败）。手动触发一次，
                // 用来确认 su 授权到底有没有给——第一次会在屏幕上弹授权框。
                if ("cameraheal".equals(action)) {
                    Log.i(TAG, "手动触发相机守护进程重启");
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            boolean ok = CameraHealer.reset(MainActivity.this);
                            Log.i(TAG, "相机守护进程重启：" + (ok ? "命令已执行" : "失败（检查 su 授权）"));
                        }
                    }, "amber-camera-heal").start();
                    return;
                }
                // 诊断：手动触发一次摄像头二次确认（FaceConfirm），验证「开摄像头 + 硬件人脸检测」
                // 这条链路。正常路径由人体感应器自动触发；这个口子留给人不在现场时排查摄像头用。
                if ("face".equals(action)) {
                    if (faceConfirm == null) {
                        Log.w(TAG, "faceConfirm 未初始化");
                        return;
                    }
                    faceConfirm.confirm(new FaceConfirm.Listener() {
                        @Override
                        public void onConfirmed(boolean human) {
                            Log.i(TAG, "诊断 face 确认结果：" + (human ? "检测到人脸（真人）" : "未检测到人脸（非人/超时）"));
                        }
                    });
                    Log.i(TAG, "诊断：触发摄像头二次确认");
                    return;
                }
                // 契约 §7 的 reboot：重启 App（不是重启设备）。音箱没有 RTC 电池、断网 NTP 不了，
                // 应用跑久了或被系统杀掉时，AstrBot / 米家可以远程发这条把它重新拉起来。
                // 做法：AlarmManager 延迟一小会儿重新 start MainActivity，再 kill 掉当前进程，
                // 让系统干净地重建一个进程（比 finish() 更彻底——finish 只关 Activity，进程里的
                // 单例、Handler、后台线程都可能残留脏状态）。
                if ("reboot".equals(action)) {
                    Log.i(TAG, "收到 reboot，准备重启 App");
                    Intent intent = new Intent(MainActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    PendingIntent pi = PendingIntent.getActivity(MainActivity.this, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
                    AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    if (am != null) {
                        am.set(AlarmManager.RTC, System.currentTimeMillis() + 500L, pi);
                    }
                    // 稍等让 HTTP 响应有机会发出去（SayServer 在独立线程，respond 已经写好才轮到
                    // 这里执行，但保险起见主线程 post 延迟再杀，避免把还没 flush 的响应一起带走）。
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            Process.killProcess(Process.myPid());
                        }
                    }, 200L);
                    return;
                }
                // 屏幕背光亮度：value 支持 0~1 或 0~100（百分比）。
                // 走 Window.screenBrightness，不需要 root/sysfs，改的是整块 LCD。
                if ("brightness".equals(action)) {
                    try {
                        float v = Float.parseFloat(value);
                        if (v > 1f) v /= 100f;
                        applyScreenBrightness(v);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "brightness 值不是数字：" + value);
                    }
                    return;
                }
                // 舞台灯带整体亮度：value 支持 0~1 或 0~100（65 = 默认的 65%）
                if ("light".equals(action)) {
                    try {
                        float v = Float.parseFloat(value);
                        if (v > 1f) v /= 100f;
                        if (stage != null) {
                            stage.setScale(v);
                        }
                        getSharedPreferences("amber", MODE_PRIVATE).edit()
                                .putFloat("stage_light", stage == null ? v : stage.getScale()).apply();
                        Log.i(TAG, "舞台灯亮度：" + v);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "light 值不是数字：" + value);
                    }
                    return;
                }
                // 契约 §7 的 volume：AstrBot 主动设音量（0~1）。和顶部音量键走同一个增益。
                if ("volume".equals(action)) {
                    try {
                        float v = Float.parseFloat(value);
                        setVolumeGain(v, "/control volume");
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "volume 值不是数字：" + value);
                    }
                    return;
                }
                // 契约 §7 还有 brightness 未实现，暂拒。
                Log.w(TAG, "control 动作暂不支持：" + action);
            }
        });
    }

    /**
     * 时间同步（POST /clock）。
     *
     * <p>音箱没有 RTC 电池、断网也拉不到 NTP，开机时间会停在 1977 年。外部把这个真实
     * 时间推过来之后，要<b>立刻重判一次屏幕</b>——之前很可能就是因为「以为现在是凌晨」
     * 被熄了屏、断了灯带，光同步时间不重判的话，她要等到下一次心跳（20 秒）才亮。
     */
    @Override
    public boolean onClock(final long epochMs) {
        final boolean ok = Clock.sync(this, epochMs);
        if (!ok) {
            return false;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!videoMode && sleep != null && !manualDisplayOverride) {
                    sleep.tick();
                }
                Log.i(TAG, "时间同步后重判：屏幕 " + (screenOff ? "熄屏" : "亮屏")
                        + "，现在 " + Clock.minuteOfDay() + " 分钟");
            }
        });
        return true;
    }

    /**
     * 配网（POST /wifi）。
     *
     * <p>为什么不引导用户去系统设置：这台机器的系统 UI 是竖屏，物理屏是横的（本 app
     * 自己强制 landscape 才是正的），在设置页输密码得歪着头。走自己的端点，密码用 JSON
     * body 传，中文 SSID 也不会有 URL 编码问题。Android 5.1（API 22）还允许普通应用
     * {@code addNetwork}，再晚几个版本这条路就被堵了。
     */
    @Override
    public boolean onWifi(final String ssid, final String pass) {
        final android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
        if (wm == null) {
            Log.e(TAG, "配网：拿不到 WifiManager");
            return false;
        }
        try {
            if (!wm.isWifiEnabled()) {
                wm.setWifiEnabled(true);
            }
            final String quotedSsid = quote(ssid);
            android.net.wifi.WifiConfiguration target = null;
            final java.util.List<android.net.wifi.WifiConfiguration> configured = wm.getConfiguredNetworks();
            if (configured != null) {
                for (android.net.wifi.WifiConfiguration c : configured) {
                    if (quotedSsid.equals(c.SSID)) {
                        target = c;   // 见过的网络就改密码，别堆一堆同名配置
                        break;
                    }
                }
            }
            final boolean isNew = target == null;
            if (isNew) {
                target = new android.net.wifi.WifiConfiguration();
                target.SSID = quotedSsid;
            }
            target.allowedKeyManagement.clear();
            if (pass.length() == 0) {
                target.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE);
                target.preSharedKey = null;
            } else {
                target.allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK);
                target.preSharedKey = quote(pass);
            }

            int netId;
            if (isNew) {
                netId = wm.addNetwork(target);
            } else {
                netId = wm.updateNetwork(target) >= 0 ? target.networkId : -1;
            }
            if (netId < 0) {
                Log.e(TAG, "配网失败：addNetwork/updateNetwork 返回 " + netId + "（权限或系统限制）");
                return false;
            }
            wm.saveConfiguration();
            wm.disconnect();               // 先断开，否则 enableNetwork 可能不重新 DHCP
            final boolean enabled = wm.enableNetwork(netId, true);
            wm.reconnect();
            Log.i(TAG, "配网：" + (isNew ? "新增" : "更新") + " " + ssid + " netId=" + netId
                    + " enabled=" + enabled);

            // DHCP 要几秒，8 秒后把拿到的 IP 打进日志，省得猜「到底连上没有」
            heartbeat.postDelayed(new Runnable() {
                @Override
                public void run() {
                    final android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                    if (info == null || info.getIpAddress() == 0) {
                        Log.w(TAG, "配网：8 秒后仍没拿到 IP（密码错？信号弱？）");
                        return;
                    }
                    final int ip = info.getIpAddress();
                    Log.i(TAG, "配网成功：IP " + (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                            + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF)
                            + "，SSID " + info.getSSID());
                }
            }, 8000);
            return enabled;
        } catch (SecurityException e) {
            // 5.1 上一般不会走到这里；留着是为了让失败原因出现在日志里，而不是静默 false
            Log.e(TAG, "配网被系统拒绝：" + e.getMessage(), e);
            return false;
        }
    }

    /** Wi-Fi 配置里的字符串要带双引号，内部的双引号和反斜杠要转义。 */
    private static String quote(String raw) {
        return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    // ---------------------------------------------------------------- 眼睛工具（§7.1）

    /** 两次取帧的最小间隔（契约 §7.1：≥ 10 分钟）。 */
    private static final long SNAPSHOT_COOLDOWN_MS = 10 * 60 * 1000L;
    /** 每天取帧上限（契约 §7.1：≤ 10 次）。 */
    private static final int SNAPSHOT_DAY_LIMIT = 10;

    /**
     * 「眼睛」工具（POST /snapshot）。同步阻塞（最多 5 秒）取一帧 JPEG，转 base64 交出去。
     *
     * <p>额度闸门（§7.1，防她变成「监控」）：两次间隔 ≥ 10 分钟 + 每天 ≤ 10 次。
     * 超额度返回结构化回执「额度已用完」，不带画面——她拿不到画面、只能收到回执，
     * 不能编造画面内容（§16.1）。取帧单次 ≤ 4 秒（{@link Snapshot} 内部窗口），
     * 只取一帧、不落盘、不识别身份，base64 交 AstrBot 侧视觉模型分析。
     */
    @Override
    public String onSnapshot() {
        final long nowMs = Clock.now();
        // 时间不可信就拒绝：额度闸门依赖墙钟（跨天归零），墙钟不可信时宁可拒给画面。
        if (!Clock.trusted()) {
            Log.w(TAG, "眼睛工具：时间不可信，拒给画面（先 /clock 对时）");
            return "{\"ok\":false,\"error\":\"clock_untrusted\"}";
        }
        // 跨天归零每日计数
        final String dayKey = dayKey(nowMs);
        if (!dayKey.equals(snapshotDayKey)) {
            snapshotDayKey = dayKey;
            snapshotDayCount = 0;
        }
        // 每日上限
        if (snapshotDayCount >= SNAPSHOT_DAY_LIMIT) {
            Log.w(TAG, "眼睛工具：今日已达上限 " + SNAPSHOT_DAY_LIMIT + " 次");
            return "{\"ok\":false,\"error\":\"daily_limit\"}";
        }
        // 冷却间隔
        if (lastSnapshotAt != 0 && nowMs - lastSnapshotAt < SNAPSHOT_COOLDOWN_MS) {
            long waitSec = (SNAPSHOT_COOLDOWN_MS - (nowMs - lastSnapshotAt)) / 1000;
            Log.w(TAG, "眼睛工具：冷却中，还需 " + waitSec + " 秒");
            return "{\"ok\":false,\"error\":\"cooldown\",\"retry_after_sec\":" + waitSec + "}";
        }
        if (snapshot == null) {
            return "{\"ok\":false,\"error\":\"not_ready\"}";
        }

        // 摄像头是单实例资源：交互期间 GazeTracker 正开着它做视线追踪，不让路就会撞成
        // 「Fail to connect to camera service」——主人问「你看看这个」听到的却是「摄像头打不开」。
        final boolean gazeYielded = gaze != null && gaze.isTracking();
        if (gazeYielded) {
            Log.i(TAG, "眼睛工具：先让视线追踪让出摄像头");
            gaze.stop();
            try {
                // 本方法跑在 HTTP 线程，睡一下没事；给相机服务回收句柄的时间。
                Thread.sleep(180);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
        // 同步等待取帧结果（最多 5 秒）。用 CountDownLatch 桥接相机的异步回调。
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final byte[][] holder = new byte[1][];
        final int[] dims = new int[2];
        final String[] err = new String[1];
        snapshot.capture(new Snapshot.Listener() {
            @Override
            public void onFrame(byte[] jpeg, int width, int height) {
                holder[0] = jpeg;
                dims[0] = width;
                dims[1] = height;
                latch.countDown();
            }

            @Override
            public void onError(String reason) {
                err[0] = reason;
                latch.countDown();
            }
        });
        try {
            if (!latch.await(12, java.util.concurrent.TimeUnit.SECONDS)) {
                return "{\"ok\":false,\"error\":\"timeout\"}";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"interrupted\"}";
        }
        if (holder[0] == null) {
            return "{\"ok\":false,\"error\":\"" + (err[0] == null ? "failed" : err[0]) + "\"}";
        }

        // 成功：记额度，base64 编码交出去（只喂视觉模型，不落盘）
        lastSnapshotAt = nowMs;
        snapshotDayCount++;
        String b64 = android.util.Base64.encodeToString(holder[0], android.util.Base64.NO_WRAP);
        Log.i(TAG, "眼睛工具：取到一帧 " + holder[0].length + " 字节"
                + (dims[0] > 0 ? " " + dims[0] + "x" + dims[1] : "")
                + "，今日第 " + snapshotDayCount + " 次");
        return "{\"ok\":true,\"format\":\"jpeg\",\"base64\":\"" + b64 + "\""
                + (dims[0] > 0 ? ",\"width\":" + dims[0] + ",\"height\":" + dims[1] : "")
                + "}";
        } finally {
            ensureGazeRunning();
        }
    }

    /**
     * 取帧结束后确认视线追踪在跑。
     *
     * <p>两种情况要补：① 我们为了取帧主动 {@code stop} 过它（{@code doFrame} 只在相位边沿起停，
     * 不会自己补）；② 它本来就没开成功——相机在此之前是坏的，而刚才的自愈把它修好了。
     * 不补这一下，这一整轮交互她都不看人。
     */
    private void ensureGazeRunning() {
        if (gaze == null || !Settings.gazeEnabled() || gaze.isTracking()) {
            return;
        }
        int phase = wake == null ? -1 : wake.phase();
        if (phase == WakeState.PHASE_LISTENING || phase == WakeState.PHASE_SPEAKING) {
            gaze.start();
            Log.i(TAG, "眼睛工具：取帧结束，视线追踪已接回");
        }
    }

    /** 当天日期键（yyyy-MM-dd），用校正后的墙钟。 */
    private static String dayKey(long epochMs) {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(epochMs);
        return c.get(java.util.Calendar.YEAR) + "-" + (c.get(java.util.Calendar.MONTH) + 1)
                + "-" + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    @Override
    public String onSettings() {
        return "{\"ok\":true,\"settings\":" + Settings.toJson() + "}";
    }

    /**
     * 改一项行为设置（POST /settings）。
     *
     * <p>两点值得说明：
     * <ul>
     *   <li>写盘用 {@link SettingsStore}，改完立刻落 SharedPreferences —— 拔电重启还在。</li>
     *   <li>改完必须补一次 {@code sleep.tick()}：睡眠状态机的判据是「目标态 ≠ 记录」，
     *       如果用户正好把「现在」划进了新的休眠区间，不主动 tick 就得等下次心跳才熄屏。</li>
     * </ul>
     */
    @Override
    public String onSetting(String key, String value) {
        if (key == null) {
            return "缺 key";
        }
        if ("reset".equalsIgnoreCase(key.trim())) {
            SettingsStore.reset(this);
        } else {
            final String err = SettingsStore.apply(this, key, value);
            if (err != null) {
                return err;
            }
        }
        final String k = key.trim();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (gaze != null) {
                    boolean interacting = wake != null
                            && (wake.phase() == WakeState.PHASE_LISTENING
                            || wake.phase() == WakeState.PHASE_SPEAKING);
                    if (!Settings.gazeEnabled()) {
                        gaze.stop();
                    } else if (interacting) {
                        gaze.start();
                    }
                }
                if (sleep != null && !manualDisplayOverride && !videoMode) {
                    sleep.tick();
                }
                Log.i(TAG, "设置变更：" + k + " → " + (value == null ? "" : value));
            }
        });
        return null;
    }

    @Override
    public String onState() {
        boolean speaking = current != null && !current.done();
        long now = SystemClock.elapsedRealtime();
        long tail = lastAudioEndAt == 0 ? -1 : now - lastAudioEndAt;
        boolean busy = busyForListen();
        // 对时年龄（秒，-1 = 本进程还没对过）。调参台/看门狗靠它判断「时间是不是在漂」。
        final long clockAge = Clock.syncAgeMs();
        return "{\"ok\":true,\"contract\":\"1.49\",\"screen\":\"" + (screenOff ? "off" : "on") + "\",\"speaking\":"
                + (speaking ? "true" : "false")
                + ",\"audio\":" + (voiced ? "true" : "false")
                + ",\"queued\":" + inbox.size()
                + ",\"busy\":" + (busy ? "true" : "false")
                + ",\"tail_ms\":" + tail
                + ",\"mic_muted\":" + (micMuted ? "true" : "false")
                + ",\"received\":" + received
                + ",\"phase\":\"" + (wake == null ? "idle" : wake.phaseName()) + "\""
                + ",\"present\":" + (wake != null && wake.presentNow())
                + ",\"presence_s\":" + (wake == null ? -1 : wake.presentForSec())
                + ",\"clock_ok\":" + Clock.trusted()
                + ",\"minute\":" + Clock.minuteOfDay()
                + ",\"clock_age\":" + (clockAge < 0 ? -1 : clockAge / 1000)
                // 看门狗的自检口子：帧数在涨说明 GL 线程活着；content_age 是「上一次在画面上
                // 真读到非空像素」距今几秒（-1 = 还没采到过）。全黑时它会一直涨。
                + ",\"frames\":" + (gl == null ? -1 : gl.frames())
                + ",\"content_age\":" + (gl == null ? -1 : gl.contentAgeSec())
                + ",\"posing\":" + (body.posing() ? "true" : "false")
                // 调参台要回读这两项才能把滑块摆到真实位置（stage 在 onCreate 里才建，
                // 早期 /state 可能先到，所以留个 0.65 兜底）。
                + ",\"light\":" + (stage == null ? 0.65f : stage.getScale())
                + ",\"light_mode\":\"" + StageLight.modeName(stage == null ? 0 : stage.getMode()) + "\""
                + ",\"backlight\":" + screenBrightness
                // 音量增益（0~1）：调参台的音量滑块据此回填。顶部音量键和 /control volume
                // 改的是同一个值，所以两边永远一致。
                + ",\"volume\":" + volumeGain
                // 行为类设置的快照：调参台拿它把滑块/开关摆到真实位置。
                // 和 GET /settings 的内容一致，放这里是为了少一次往返。
                + ",\"settings\":" + Settings.toJson()
                // 调参台的「模型」面板要这两项：当前是哪个模型、摆在什么位置。
                // model 给的是 assets 目录名（含末尾斜杠），前端自己映射成中文短名。
                + ",\"model\":\"" + escape(gl == null ? "" : gl.modelDirectory()) + "\""
                + ",\"fit\":[" + currentFit[0] + "," + currentFit[1] + "," + currentFit[2] + "]"
                // 副屏：调参台要据此显示「正在播 / 播到哪」，也据此判断「回大肥鱼」按钮该不该亮
                + ",\"mode\":\"" + (videoMode ? "video" : "pet") + "\""
                + ",\"video_state\":\"" + (video == null ? "idle" : video.state()) + "\""
                + ",\"video_src\":\"" + escape(video == null ? "" : video.source()) + "\""
                + ",\"video_pos\":" + (video == null ? 0 : video.position())
                + ",\"video_dur\":" + (video == null ? 0 : video.duration())
                + ",\"video_size\":[" + (video == null ? 0 : video.videoWidth())
                + "," + (video == null ? 0 : video.videoHeight()) + "]"
                + ",\"video_key\":\"" + (video == null ? "none" : video.keyName()) + "\""
                + "}";
    }

    /** /state 里的字符串字段用（模型目录名只有字母数字和斜杠，但仍然过一遍，免得将来加中文名）。 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 现在能不能开麦克风（GET /listen 的闸门）。
     *
     * <p>「忙」包括四类：正在说、有一句在等配音、队列里还有话、以及刚播完的尾音窗口。
     * 只看 {@code speaking} 会漏掉后三类——真机上「她说完我说话她听不到」就是漏在
     * 「第一段播完、第二段还在合成」的空档里：那一瞬间 current 已经是 null、队列也空，
     * 但设备其实马上要继续出声。
     */
    /** /video：副屏开播。HTTP 线程调进来，切到主线程（MediaPlayer 要用有 Looper 的线程）。 */
    @Override
    public boolean onVideo(final String source, final boolean loop) {
        if (source == null || source.trim().length() == 0) {
            return false;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                enterVideoMode(source, loop);
            }
        });
        return true;
    }

    /** /video?stop=1：收工回大肥鱼。 */
    @Override
    public void stopVideo() {
        main.post(new Runnable() {
            @Override
            public void run() {
                exitVideoMode("收到停止", null);
            }
        });
    }

    /** /video?key=…：抠像。 */
    @Override
    public boolean onVideoKey(final String spec) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!video.setKey(spec)) {
                    view.show("抠像参数不认识：" + spec);
                }
            }
        });
        return true;
    }

    /**
     * 当前副屏在播的源（绝对路径或 URL）；没在播返回 null。
     *
     * <p>清理上传目录时用：要删的正好是正在放的这一条，得先停下来再删，
     * 不然播放器攥着已 unlink 的 inode，画面会僵在最后一帧。
     */
    @Override
    public String currentVideoSource() {
        final VideoStage stage = video;
        if (stage == null || !stage.active()) {
            return null;
        }
        return stage.source();
    }

    @Override
    public boolean busyForListen() {
        if (videoMode) {
            return true;   // 副屏在响：喇叭挨着麦克风，而且播放会拆掉采集流（§4.2）
        }
        if (micMuted) {
            return true;   // 静音闭麦：不收音，插件会一直看到 busy
        }
        long now = SystemClock.elapsedRealtime();
        if (lastSpeechCommandAt != 0
                && now - lastSpeechCommandAt < SPEECH_COMMAND_GUARD_MS) {
            return true;   // 刚收到 /say 或 /wake，第一句可能还没进队列
        }
        if (current != null || pending != null || inbox.size() > 0) {
            return true;
        }
        if (voiced) {
            return true;
        }
        return lastAudioEndAt != 0
                && now - lastAudioEndAt < LISTEN_TAIL_GUARD_MS;
    }

    /**
     * 执行熄屏 / 亮屏动作。
     *
     * <p>软件层能做的：隐藏/显示根容器（角色 + 字幕一起消失）、切掉常亮 flag 让系统
     * 有机会熄背光。实体 LED 灯带和 LCD 背光的真正开关是硬件适配的事，音响到货后
     * 在这里补上（关灯带 = 全黑；开灯带 = 恢复发光舞台）。
     */
    /**
     * 屏幕亮度 0~1（1 = 系统满亮）。走 Window 的 screenBrightness，
     * 不需要 sysfs 权限，也不需要 WRITE_SETTINGS；只覆盖本 App 窗口，
     * 但我们的 Activity 是全屏的，实际就是整块 LCD 的亮度。
     */
    private void applyScreenBrightness(float value) {
        final float v = Math.max(0.05f, Math.min(1f, value));
        screenBrightness = v;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = v;
        getWindow().setAttributes(lp);
        getSharedPreferences("amber", MODE_PRIVATE).edit()
                .putFloat("screen_brightness", v).apply();
        Log.i(TAG, "屏幕亮度（窗口）：" + v);
    }

    private void applyScreen(boolean off) {
        if (off == screenOff) {
            return;
        }
        screenOff = off;
        // 同步状态机的记录：这里是**唯一**真正改屏幕的地方，但调用方可能不是 SleepState
        // （副屏强制亮屏、/control display 手动覆盖）。不同步的话 tick 会以为「已经熄着了」
        // 而永远不再动作 —— 半夜投完视频切回大肥鱼一直亮屏就是这么来的。
        if (sleep != null) {
            sleep.sync(off);
        }
        // 灯带和屏幕一起黑：契约 §4.4 的「全黑」是 LCD + 灯带一起关，灯带由 StageLight 断电
        ledAsleep = off;
        if (off) {
            // 熄屏：角色和字幕一起消失，画面全透明，灯带同步断电。
            //
            // 只靠 root.setVisibility 不够：GLSurfaceView 是 SurfaceView，它的 surface
            // 走 SurfaceFlinger 独立合成，不响应父容器的 visibility，光隐藏 root 会让
            // 「字幕没了、模型画面还在」。所以这里对 gl 单独隐藏 + 置休眠标志
            // （onDrawFrame 只清屏不画模型，surface 不销毁、模型不重载）。
            root.setVisibility(android.view.View.INVISIBLE);
            gl.setVisibility(android.view.View.INVISIBLE);
            gl.setHidden(true);
            // 这里千万不要 clearFlags(FLAG_KEEP_SCREEN_ON)（2026-11-24 踩过）：
            // 清掉之后系统按正常超时锁屏，Activity 被迫退到后台，8788 服务跟着停——
            // 表现就是「关了机就再也开不了机，米家点了没反应」。
            // 背光由下面 Backlight 写 0，物理上已经是全黑了，不需要靠系统熄屏。
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // 真熄屏：LCD 背光写 0（不是只清画面）。灯带由 StageLight 断电，这里关背光。
            Backlight.get().set(true);
            Log.i(TAG, "熄屏休眠（画面 + 灯带 + 背光一起关）");
        } else {
            root.setVisibility(android.view.View.VISIBLE);
            gl.setVisibility(android.view.View.VISIBLE);
            gl.setHidden(false);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Backlight.get().set(false);
            Log.i(TAG, "亮屏");
        }
    }

    // ---------------------------------------------------------------- 时间轴

    @Override
    public void doFrame(long frameTimeNanos) {
        // 每帧推一下状态机：唤醒后没人说话、或大模型一直不回复，超时会自己回 idle
        if (wake != null) {
            wake.tick(SystemClock.elapsedRealtime());
        }

        // 唤醒态视线追踪（契约 §4.5）：只在 listening / speaking 期间开摄像头追踪眼神，
        // 回 idle 立即关。用相位边沿判断，避免每帧重复开关摄像头。
        if (gaze != null && wake != null) {
            int phase = wake.phase();
            boolean inInteraction = phase == WakeState.PHASE_LISTENING
                    || phase == WakeState.PHASE_SPEAKING;
            boolean wasInInteraction = lastPhase == WakeState.PHASE_LISTENING
                    || lastPhase == WakeState.PHASE_SPEAKING;
            if (inInteraction && !wasInInteraction && Settings.gazeEnabled()) {
                gaze.start();
            } else if (!inInteraction && wasInInteraction) {
                gaze.stop();
            }
            lastPhase = phase;
        }
        // 熄屏休眠：每帧判断该亮该黑（幂等，变了才动作）。
        // AstrBot 用 /control display 手动覆盖时挂起自动逻辑，等它发相反指令再恢复。
        // 副屏期间也不许黑：人在看画面，半夜投个屏两分钟就黑掉会很莫名其妙。
        if (sleep != null && !manualDisplayOverride && !videoMode) {
            sleep.tick();
        }

        if (current != null && current.done()) {
            voice.stop();
            voiced = false;
            current = null;
            lastAudioEndAt = SystemClock.elapsedRealtime();
            if (wake != null) {
                wake.onSpeakingEnd();
            }
        }

        if (current == null && !startLine(frameTimeNanos)) {
            // 要么说完了，要么还在等配音。两种都不用往下走。
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        double elapsed;
        if (voiced) {
            long position = voice.positionMs();
            // 播放其实没起来（文件坏了、被别的应用抢了音频通道）：退回用墙上的钟，
            // 否则这一句会永远停在第一个字上。
            if (position == 0) {
                if (voiceZeroAt == 0) {
                    voiceZeroAt = frameTimeNanos;
                } else if ((frameTimeNanos - voiceZeroAt) / 1e6 > 600) {
                    Log.w(TAG, "配音没播起来，改成按字速走");
                    voiced = false;
                    lineStartNanos = frameTimeNanos;
                }
            } else {
                voiceZeroAt = 0;
            }
        }

        if (voiced) {
            // 时间轴跟着音频走：把音频进度按「这句念完要多久」缩放，最后一个字正好落在音频结尾。
            long position = voice.positionMs();
            double scale = current.spoken() / Math.max(0.05, voice.durationMs() / 1000.0);
            elapsed = position / 1000.0 * scale;
            // 播放结束了但时间轴还没走完：MediaPlayer 的 getCurrentPosition 会停在略小于
            // 真实时长的位置（比如 3790 而不是 3800），按比例缩放后 elapsed 差一点到不了
            // spoken，done() 里 clock >= at 永远不成立，这一句会卡死在最后一个字上。
            // 所以播完（且确实播过，position > 0）就直接把时间轴推到结尾。
            // 用 spoken 而不是 until：until 是「已经演到哪」，播完前它还不到最终值，
            // 封顶到它等于没封；而配音模式里 cue 的等待都被 muteWaits 清零，最终 at 正好
            // 等于 spoken，封顶到 spoken 就一定能 done。
            if (!voice.playing() && position > 0 && elapsed < current.spoken()) {
                elapsed = current.spoken();
            }
        } else {
            elapsed = (frameTimeNanos - lineStartNanos) / 1e9;
        }
        current.advance(elapsed);

        String plain = current.plain();
        int shown = Math.min(current.shown(), plain.length());
        // 传整句而不是前缀：气泡按整句排好版，只把前 shown 个字露出来。
        // 按前缀排的话气泡会一边说话一边变宽，字还会往回跳。
        view.say(plain, shown);

        // 嘴也跟着音频走：有配音就用电平，没有才退回正弦。
        if (voiced) {
            setMouth(voice.level());
        } else {
            setMouth(current.talking());
        }

        Choreographer.getInstance().postFrameCallback(this);
    }

    /**
     * 挑下一句开口。
     *
     * @return {@code true} 已经把 {@link #current} 建好可以往下演；{@code false} 表示
     *         说完了（会顺手收起气泡），或者配音还没备好，这一帧先等
     */
    private boolean startLine(long frameTimeNanos) {
        if (pending == null) {
            pending = inbox.take(SystemClock.elapsedRealtime());
            pendingSince = frameTimeNanos;
            if (pending == null) {
                // 说完了：把嘴闭上再收工。这一句不能省——直接 return 的话她说完最后一帧
                // 张着的嘴会一直僵在那里，直到下一句话才动。
                setMouth(false);
                // 气泡不是立刻消失：它停一会儿再淡出去，让人来得及看完最后一句。
                view.dismiss();
                // 说完了：把姿势留给待机，表情不重置（契约 §15）
                frameScheduled = false;
                return false;
            }
        }

        voiced = false;
        if (pending.audio != null) {
            voice.prepare(pending.audio);
            int state = voice.state(pending.audio);
            if (state == Voice.LOADING && (frameTimeNanos - pendingSince) / 1e6 < 2500) {
                // 下载 + 解码通常两三百毫秒。等它是值得的：不等的话第一句永远是哑的。
                return false;
            }
            if (state == Voice.READY) {
                voice.play(pending.audio);
                voiced = true;
                voiceZeroAt = 0;
            }
        }

        current = new Line(pending.text, new Line.Act() {
            @Override
            public double act(String cue, double spoken, char tone) {
                return body.apply(cue, spoken, tone);
            }
        });
        // 配音不会为〔〕停下来，所以这种模式下动作也不许占时间
        current.muteWaits(voiced);
        pending = null;
        lineStartNanos = frameTimeNanos;
        if (wake != null) {
            wake.onSpeakingStart();
        }
        return true;
    }

    /**
     * 推一格嘴型。
     *
     * <p>不写 logcat：说话时这条命令一秒六十条左右，会把 AmberCue 里真正该看的词表命令冲掉。
     * 要核对算法就走 {@code test/MouthDump}，那边有完整的逐帧对照。
     */
    private void setMouth(boolean talking) {
        push(mouth.update(talking, SystemClock.elapsedRealtime()), talking);
        // 没有配音时嘴型是正弦假动，给灯带一个固定的中等电平，别让它跟着正弦闪
        ledMouth = talking ? 0.6f : 0f;
    }

    /** 有配音时：用电平推嘴型。 */
    private void setMouth(float level) {
        push(mouth.update(true, SystemClock.elapsedRealtime(), level), true);
        // 有配音时灯带直接用真实电平——这是「舞台跟着她说话呼吸」的来源
        ledMouth = level;
    }

    /**
     * 把一条嘴型命令交出去。
     *
     * <p>不写 logcat：说话时这条命令一秒六十条左右，会把 AmberCue 里真正该看的词表命令冲掉。
     * 只在「开口/闭嘴」这两个转折上各打一行。要核对算法就走 {@code test/MouthDump}。
     */
    private void push(String command, boolean talking) {
        if (command == null) {
            return;
        }
        gl.command(command);
        if (talking != mouthTalking) {
            mouthTalking = talking;
            Log.i(TAG, "口型 " + (talking ? "开口，首条 " : "闭嘴，末条 ") + command);
        }
    }
}

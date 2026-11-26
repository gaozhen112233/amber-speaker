package com.amber.speaker.led;

import android.os.SystemClock;
import android.util.Log;

/**
 * 舞台灯带：四种情绪 + 18 路分路驱动。
 *
 * <p>这是契约 §4.4 里「实体 LED 灯带由设备端控制」的那一半。驱动是 SN3218（18 路 PWM），
 * 一次灌 18 路 ≈1~2ms，25Hz 跑得动。
 *
 * <p>为什么要分路：一开始 18 路全写同一个值，看起来就是一条「会变亮的灯管」——亮度和情绪
 * 完全绑死，说不说话只有「整条更亮」一个区别。人眼看一条灯带的情绪，靠的是<b>明暗沿灯带
 * 移动</b>：从中间荡开是「在听」，随声音抖是「在说」，缓慢起伏是「在呼吸」。
 * 所以现在每个 tick 算一条 18 点的亮度曲线，再逐点缓动追上去。
 *
 * <table>
 *   <tr><th>模式</th><th>形状</th><th>为什么</th></tr>
 *   <tr><td>待机</td><td>整条同步慢呼吸，4.2 秒一个周期</td><td>像睡着的呼吸，静止但不死</td></tr>
 *   <tr><td>在听</td><td>一圈波从中间向两端荡开，2.2 秒一次</td><td>「我在听你说」——方向感比亮度更明确</td></tr>
 *   <tr><td>说话</td><td>整条跟着电平跳 + 沿灯带的细波纹</td><td>波纹让「亮」看起来像声带在震</td></tr>
 *   <tr><td>副屏</td><td>全条压到低亮平光</td><td>人在看画面，灯不许抢</td></tr>
 *   <tr><td>休眠</td><td>0 + 断电</td><td>契约 §4.4 要求全黑，断电比写 0 更省</td></tr>
 * </table>
 *
 * <p>为什么轮询而不是让 {@code MainActivity} 每帧推：主帧循环在待机时会停
 * （{@code frameScheduled = false}），而灯带在待机时也得继续呼吸，所以这里自带节奏。
 */
public final class StageLight {

    private static final String TAG = "AmberLed";

    /** 灯带情绪。{@code MainActivity} 按唤醒相位与播放状态给出。 */
    public static final int MODE_IDLE = 0;
    public static final int MODE_LISTEN = 1;
    public static final int MODE_SPEAK = 2;
    public static final int MODE_VIDEO = 3;

    /** 灯带要跟随的量，由 {@code MainActivity} 提供；都是 volatile 读，不加锁。 */
    public interface Source {
        /** 熄屏休眠中（00:00–08:00 或 /control display:off）。 */
        boolean asleep();

        /** 唤醒态（waking / speaking / listening）。 */
        boolean awake();

        /** 说话电平 0~1，没在说话就是 0。 */
        float mouth();

        /** 情绪模式，取值见 {@code MODE_*}。 */
        int mode();
    }

    private static final int LEDS = 18;

    /** 待机底光。60 才是「暗但明显」，50 是调过一轮后的值。 */
    private static final int LEVEL_IDLE = 50;
    /** 唤醒态的基底亮度，说话电平再往上叠。 */
    private static final int LEVEL_AWAKE = 95;
    private static final int LEVEL_TALK_GAIN = 110;
    /** 副屏：低亮平光，别和屏幕抢注意力。 */
    private static final int LEVEL_VIDEO = 42;
    /** 在听：基线 + 波纹峰值。 */
    private static final int LEVEL_LISTEN_BASE = 70;
    private static final int LEVEL_LISTEN_PEAK = 85;

    /** 待机呼吸周期。4.2 秒接近平静呼吸（12~14 次/分）。 */
    private static final int BREATH_MS = 4200;
    /** 在听的声呐周期。太快像扫描仪，太慢像卡住。 */
    private static final int LISTEN_MS = 2200;

    /**
     * 整体亮度缩放。默认 0.65：用户觉得舞台灯太亮，压到 65% 后待机 32、唤醒 62、
     * 说话峰值约 133（原来 205）。可用 {@code /control light 0~1} 或 0~100 再调。
     */
    private volatile float scale = 0.65f;

    /** 25Hz。再快没有意义：眼睛分辨不出灯带的 40ms 以内差别，只会白烧 i2c。 */
    private static final long TICK_MS = 40;
    /** 每帧向目标靠近的比例。0.28 大概 150ms 走完 90%，接近人眼的「呼吸」。 */
    private static final float EASE = 0.28f;
    /** 任意一路与上次写入差小于这个值就不写：避免稳态时每帧都去戳驱动。 */
    private static final int DEAD_BAND = 2;

    private final Source source;
    /** 144 字节复用，别每帧 new：25Hz × 2 分钟就是三千个短命数组。 */
    private final byte[] frame = new byte[LEDS * 8];
    /** 当前实际亮度（逐路缓动后的值）。 */
    private final float[] current = new float[LEDS];
    /** 每帧的目标亮度。 */
    private final int[] target = new int[LEDS];
    /** 上一次真写进驱动的值，用来做 DEAD_BAND 判断。 */
    private final byte[] shown = new byte[LEDS];

    private volatile boolean running;
    /** 最近一次生效的模式，给 {@code /state} 回读用（一眼看出灯带以为自己在干嘛）。 */
    private volatile int lastMode = MODE_IDLE;
    private Thread thread;
    private int fd = -1;
    private boolean firstWrite = true;
    private boolean powered;

    public StageLight(Source source) {
        this.source = source;
        // 18 条记录：{u32 寄存器号; u8 亮度; 3 字节填充}，寄存器号固定，亮度每帧改
        for (int i = 0; i < LEDS; i++) {
            final int reg = i + 1;
            frame[i * 8] = (byte) reg;
            frame[i * 8 + 1] = 0;
            frame[i * 8 + 2] = 0;
            frame[i * 8 + 3] = 0;
        }
        java.util.Arrays.fill(shown, (byte) -1);
    }

    public void start() {
        if (running) return;
        fd = NativeLed.open();
        if (fd < 0) {
            // 手机上没有这颗芯片，不是错误，就是没有灯带
            Log.i(TAG, "没有 /dev/sn3218，灯带不参与（非音箱机型）");
            return;
        }
        NativeLed.powerOn(fd);
        powered = true;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "amber-led");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
        Log.i(TAG, "灯带就绪，缩放 " + scale + "，待机 " + Math.round(LEVEL_IDLE * scale)
                + "，唤醒 " + Math.round(LEVEL_AWAKE * scale));
    }

    /** 整体亮度 0~1；0.65 是默认的「比原来暗一点」。 */
    public void setScale(float s) {
        scale = Math.max(0f, Math.min(1f, s));
        Log.i(TAG, "舞台灯亮度缩放 = " + scale);
    }

    /** 当前整体亮度（0~1）。给 {@code /state} 回读用，调参台要靠它显示滑块初值。 */
    public float getScale() {
        return scale;
    }

    /** 当前情绪模式（{@code MODE_*}）。 */
    public int getMode() {
        return lastMode;
    }

    public void stop() {
        running = false;
        final Thread t = thread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        if (fd >= 0) {
            NativeLed.close(fd);
            fd = -1;
        }
    }

    private void loop() {
        final long start = SystemClock.elapsedRealtime();
        while (running) {
            if (source.asleep()) {
                // 断电：比「18 路全写 0」省一路 i2c，也真的全黑
                if (powered) {
                    NativeLed.powerOff(fd);
                    powered = false;
                    java.util.Arrays.fill(shown, (byte) -1);
                    java.util.Arrays.fill(current, 0f);
                    lastMode = MODE_IDLE;
                }
                sleep(TICK_MS);
                continue;
            }
            if (!powered) {
                NativeLed.powerOn(fd);
                powered = true;
                java.util.Arrays.fill(shown, (byte) -1);
            }
            final int mode = source.mode();
            lastMode = mode;
            final long t = SystemClock.elapsedRealtime() - start;
            compute(mode, t, Math.max(0f, Math.min(1f, source.mouth())));
            for (int i = 0; i < LEDS; i++) {
                final float next = current[i] + (target[i] - current[i]) * EASE;
                // 靠近到 0.5 以内直接吸附，免得永远在「差一点点」地抖
                current[i] = Math.abs(target[i] - next) < 0.5f ? target[i] : next;
            }
            push();
            sleep(TICK_MS);
        }
    }

    /**
     * 算这一帧的 18 路目标亮度。
     *
     * @param t 从灯带线程启动算起的毫秒数（用它做相位，不读墙钟——墙钟可能不可信）
     */
    private void compute(int mode, long t, float mouth) {
        final float dim = scale;
        switch (mode) {
            case MODE_LISTEN: {
                // 从中间向两端荡开的一圈光。半径 9.5 刚好覆盖 18 颗（中心 8.5）。
                final float p = (t % LISTEN_MS) / (float) LISTEN_MS;
                final float radius = p * 9.5f;
                // 越到后段越淡，像一声回响散去
                final float fade = 1f - p * 0.55f;
                for (int i = 0; i < LEDS; i++) {
                    final float d = Math.abs(Math.abs(i - 8.5f) - radius);
                    final float ring = (float) Math.exp(-(d * d) / 5.0f) * fade;
                    target[i] = Math.round((LEVEL_LISTEN_BASE + ring * LEVEL_LISTEN_PEAK) * dim);
                }
                break;
            }
            case MODE_SPEAK: {
                final float wave = t / 260f;
                for (int i = 0; i < LEDS; i++) {
                    final float ripple = (float) Math.sin(i * 0.72f + wave) * 9f;
                    target[i] = Math.round((LEVEL_AWAKE + mouth * LEVEL_TALK_GAIN + ripple) * dim);
                }
                break;
            }
            case MODE_VIDEO: {
                final int v = Math.round(LEVEL_VIDEO * dim);
                for (int i = 0; i < LEDS; i++) {
                    target[i] = v;
                }
                break;
            }
            default: {
                // 待机呼吸：整条同步，70%~100% 之间起伏
                final float p = (t % BREATH_MS) / (float) BREATH_MS;
                final float breath = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * p);
                final int base = Math.round(LEVEL_IDLE * (0.70f + 0.30f * breath) * dim);
                for (int i = 0; i < LEDS; i++) {
                    target[i] = base;
                }
                break;
            }
        }
        for (int i = 0; i < LEDS; i++) {
            if (target[i] < 0) target[i] = 0;
            if (target[i] > 255) target[i] = 255;
        }
    }

    /**
     * 把当前 18 路写进驱动。任意一路变化超过 {@link #DEAD_BAND} 才真写。
     *
     * <p>驱动返回 0 是正常的（厂商的 led_write 就这么返回），不能当失败。
     */
    private void push() {
        boolean dirty = false;
        for (int i = 0; i < LEDS; i++) {
            final byte v = (byte) Math.max(0, Math.min(255, Math.round(current[i])));
            if (Math.abs(v - shown[i]) >= DEAD_BAND) {
                dirty = true;
            }
        }
        if (!dirty) {
            return;
        }
        int peak = 0;
        for (int i = 0; i < LEDS; i++) {
            final byte v = (byte) Math.max(0, Math.min(255, Math.round(current[i])));
            frame[i * 8 + 4] = v;
            shown[i] = v;
            if ((v & 0xFF) > peak) peak = v & 0xFF;
        }
        final int written = NativeLed.write(fd, frame, 36);
        if (written < 0) {
            Log.w(TAG, "写入失败 ret=" + written + " 峰值 " + peak + " 模式 " + lastMode);
        } else if (firstWrite || lastModeLog != lastMode) {
            firstWrite = false;
            lastModeLog = lastMode;
            Log.i(TAG, "模式 " + modeName(lastMode) + "，峰值 " + peak + "（ret=" + written + "）");
        }
    }

    /** 模式变化时打一行日志，稳态不刷屏（说话时 25Hz 全打会淹掉日志）。 */
    private int lastModeLog = -1;

    public static String modeName(int mode) {
        switch (mode) {
            case MODE_LISTEN: return "在听";
            case MODE_SPEAK: return "说话";
            case MODE_VIDEO: return "副屏";
            default: return "待机";
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            running = false;
            Thread.currentThread().interrupt();
        }
    }
}

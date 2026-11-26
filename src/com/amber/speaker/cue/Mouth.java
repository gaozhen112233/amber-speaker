package com.amber.speaker.cue;

import java.util.Locale;

/**
 * 她说话时嘴的开合度。
 *
 * <p>这套算法不是我们想的，是<b>照抄桌宠</b>的 {@code PetWindow.UpdateMouth}：
 *
 * <pre>
 *   level = 正在说话 ? 0.2 + 0.35 * |sin(TickCount64 / 70.0)| : 0
 * </pre>
 *
 * <p>为什么值得照抄而不是自己设计一套音频包络：桌宠没有录音，它注释里写得很清楚
 * 「她的嘴跟着字动（没有录音了，只看在不在说话）」。也就是说这套正弦<b>就是</b>主人认可的效果，
 * 换个更好看的算法反而是偏离。而且正弦的周期 2π×70ms ≈ 440ms，约 2.3Hz，正好是一句话里
 * 音节的自然节拍，比包络更容易做对。
 *
 * <p>两个刻意的取舍：
 * <ol>
 *   <li><b>时间源必须传进来</b>（{@code millis}），不能读 {@code SystemClock}——
 *       否则这个类在桌面上没法测，也就没法跟桌宠逐值对照。</li>
 *   <li><b>按两位小数去重后再发</b>，桌宠是每个渲染帧都发。实测同一份 1834 帧的输入：
 *       桌宠会发 1805 条，去重后 972 条——**少了 46%**，但这是用「反正显示不出差别」换来的，
 *       不是算法变了。不发的那一刻，屏幕上的嘴已经是这个两位小数的值了。</li>
 * </ol>
 */
public final class Mouth {

    /** 张嘴的基准：不说话时是 0，说话时从 0.2 起跳，所以她说话不是从「无声」慢慢张开。 */
    private static final double FLOOR = .2;
    /** 上下摆动的幅度。0.2 + 0.35 = 0.55，正好是「中等张嘴」，不会像喊。 */
    private static final double SWING = .35;
    /** 正弦的毫秒除数。周期 = 2π × 70 ≈ 440ms。 */
    private static final double PERIOD_MS = 70;

    /** 上一次真正发出去的值（两位小数的字符串），用来判重。null = 什么都还没发过。 */
    private String sent;

    /** 有配音时用的电平：目标值经过起落平滑后的结果。 */
    private double smoothed;
    private long lastAt;

    /** 张得快、合得慢。人说话就是这个节奏，反过来会像在打嗝。 */
    private static final double ATTACK_MS = 45;
    private static final double RELEASE_MS = 110;
    /** 有配音时的张嘴区间。比正弦的 0.2~0.55 略宽一点——真包络本来就有轻有重。 */
    private static final double VOICE_FLOOR = .12;
    private static final double VOICE_SWING = .55;

    /**
     * 算这一刻该张多大。
     *
     * @param talking 现在是不是正在出字（{@link Line#talking()}）
     * @param millis  开机以来的毫秒数，调用方给 {@code SystemClock.elapsedRealtime()}
     */
    public static double level(boolean talking, long millis) {
        if (!talking) {
            return 0;
        }
        // 注意这里是 millis / 70.0 而不是 millis / 70：整数除法会把相位压成阶梯。
        return FLOOR + SWING * Math.abs(Math.sin(millis / PERIOD_MS));
    }

    /**
     * 推到这一刻，把该发的命令交出去。
     *
     * @return 要发的一行（{@code mouth 0.34}），没有变化时返回 {@code null}
     */
    public String update(boolean talking, long millis) {
        String text = format(level(talking, millis));
        if (text.equals(sent)) {
            return null;
        }
        sent = text;
        return "mouth " + text;
    }

    /**
     * 有配音时走这条：用音频包络（0~1）而不是正弦。
     *
     * <p>为什么还要平滑：20ms 一格的 RMS 是跳的，直接送进参数，她的嘴会像在抖。
     * 平滑之后仍然比正弦准——轻的地方真的只张一点，重的地方真的张开。
     *
     * @param talking 现在是不是该出声
     * @param millis  现在（用来量这一帧走了多久）
     * @param level   这一刻的响度 0~1
     */
    public String update(boolean talking, long millis, float level) {
        long delta = lastAt == 0 ? 16 : Math.max(0, Math.min(80, millis - lastAt));
        lastAt = millis;

        double target = talking ? Math.max(0, Math.min(1, level)) : 0;
        double step = delta / (target > smoothed ? ATTACK_MS : RELEASE_MS);
        smoothed += Math.max(-step, Math.min(step, target - smoothed));
        if (smoothed < 0.002) {
            smoothed = 0;
        }

        String text = format(smoothed == 0 ? 0 : VOICE_FLOOR + VOICE_SWING * smoothed);
        if (text.equals(sent)) {
            return null;
        }
        sent = text;
        return "mouth " + text;
    }

    /** 两位小数，且必须和 C# 的 {@code $"{v:0.00}"} 逐字符一致，所以锁死 ROOT 区域。 */
    public static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * 忘掉「上次发了什么」，让下一次 update 无论如何都发一条。
     *
     * <p>这里**不能**写成 {@code sent = "0.00"}：那样下一次算出来的 0.00 会被判成「没变化」而不发，
     * 正是这个函数想避免的情况。所以是置空而不是置零——第一版就写错在这里，被对照脚本抓出来了。
     */
    public void reset() {
        sent = null;
    }
}

package com.amber.speaker.cue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 〔〕动作词表，一对一移植自桌宠 {@code src/FishPet/Body.cs}。
 *
 * <p><b>这里的每个数字都是从真模型上肉眼调出来的，不要「顺手优化」。</b> 比如「叹气」那条
 * 3.5 秒的曲线里有 26 个关键帧，删掉一半看起来就像卡顿；「缩一下」的 ViewSquash 从 .05 到 .01
 * 再到 0，差 0.01 就会从「一缩」变成「一抖」。要改先看画面。
 *
 * <p>参数名（FaceAngleZ、ParamEyeLOpen…）是模型自己的 id，从 {@code 大肥鱼.cdi3.json} 对出来的，
 * 换模型要重新对表。
 */
public final class WordTable {

    private WordTable() {
    }

    /** 造一个词。where 是「左/右/上/下」这类方向，size 是幅度，count 是 ×N 的次数。 */
    public interface Make {
        Word make(String where, double size, Integer count);
    }

    // ---------------------------------------------------------------- 手部动作名

    public static final String[] HAND_TO_CHEST = {"默认手切举手", "举手切默认手"};
    public static final String[] FINGER_UP = {"竖手指切出来动画", "竖手指切回默认"};
    public static final String[] GRASS = {"狗尾草切换出来", "狗尾草切回默认手"};

    // ---------------------------------------------------------------- 词表

    private static final Map<String, Make> WORDS = new LinkedHashMap<String, Make>();

    private static void put(String name, Make make) {
        WORDS.put(name, make);
    }

    static {
        // —— 头 ——
        put("歪头", (a, s, n) -> Word.w().hold("tilt", Word.p("FaceAngleZ", 16 * s * side(a))));
        put("转头", (a, s, n) -> Word.w().hold("turn", Word.p("FaceAngleX", 20 * s * side(a))));
        put("低头", (a, s, n) -> Word.w().hold("pitch", Word.p("FaceAngleY", -14 * s)));
        put("抬头", (a, s, n) -> Word.w().hold("pitch", Word.p("FaceAngleY", 12 * s)));
        put("正视", (a, s, n) -> Word.w().clear("tilt", "turn", "pitch", "gaze"));

        // —— 身子 ——
        put("前倾", (a, s, n) -> Word.w().hold("lean", Word.p("LeanInput", -14 * s)));
        put("后仰", (a, s, n) -> Word.w().hold("lean", Word.p("LeanInput", 9 * s)));
        put("站直", (a, s, n) -> Word.w().clear("lean"));

        // —— 视线：眼睛先动、头稍微跟一点，跟真看过去一样 ——
        put("看过来", (a, s, n) -> Word.w().clear("gaze"));
        put("看你", (a, s, n) -> Word.w().clear("gaze"));
        put("看左边", (a, s, n) -> look("左", s));
        put("看右边", (a, s, n) -> look("右", s));
        put("看上面", (a, s, n) -> look("上", s));
        put("看下面", (a, s, n) -> look("下", s));

        // —— 眼睛 ——
        put("睁大", (a, s, n) -> Word.w().hold("eyes", Word.p("EyeOpen", 1 + .35 * s)));
        put("半睁", (a, s, n) -> Word.w().hold("eyes", Word.p("EyeOpen", 1 - .55 * s)));
        put("闭眼", (a, s, n) -> Word.w().hold("eyes", Word.p("EyeOpen", 0)));
        // EyeSmile 只在眼皮合上时才看得出来
        put("眯眼笑", (a, s, n) -> Word.w().hold("eyes", Word.p("EyeOpen", 0), Word.p("EyeSmile", 1)));
        put("笑眼", (a, s, n) -> Word.w().hold("eyes", Word.p("EyeOpen", .45), Word.p("EyeSmile", 1)));
        put("斜眼", (a, s, n) -> Word.w().hold("eyes",
                Word.p("REyeSquint", .9 * s), Word.p("LEyeSquint", .9 * s), Word.p("EyeOpen", .7)));
        put("眨单眼", (a, s, n) -> Word.w().hold("eyes", Word.p("ParamEyeLOpen", 0), Word.p("EyeSmile", 1)));
        put("单眨眼", (a, s, n) -> Word.w().move(Word.t("ParamEyeLOpen", "0:0 .2:-1 2.2:-1 2.35:0")));
        // 笑眼状态下单眨一只眼，同时向左歪一下头
        put("快眨单眼", (a, s, n) -> Word.w()
                .hold("eyes", Word.p("EyeOpen", .45), Word.p("EyeSmile", 1))
                .move(Word.t("ParamEyeLOpen", "0:0 .14:-1 .6:-1 .8:0"),
                        Word.t("FaceAngleZ", "0:0 .14:12 .6:12 .8:0")));
        // 比「快眨单眼」慢、比「单眨眼」短，欢迎收尾用
        put("单左眨眼", (a, s, n) -> Word.w().move(Word.t("ParamEyeLOpen", "0:0 .12:-1 .72:-1 .85:0")));
        // 只把眼睛收回默认，嘴和身子都不动
        put("默认眼", (a, s, n) -> Word.w().hold("eyes", Word.p("EyeOpen", 1), Word.p("EyeSmile", 0)));

        // —— 眉毛 ——
        put("挑眉", (a, s, n) -> Word.w().hold("brows",
                Word.p("ParamBrowLY", .6 * s), Word.p("ParamBrowRY", .6 * s), Word.p("BrowInnerUp", .4 * s)));
        put("皱眉", (a, s, n) -> Word.w().hold("brows",
                Word.p("ParamBrowLY", -.8 * s), Word.p("ParamBrowRY", -.8 * s),
                Word.p("ParamBrowLAngle", -s), Word.p("ParamBrowRAngle", -s)));
        put("八字眉", (a, s, n) -> Word.w().hold("brows",
                Word.p("BrowInnerUp", s), Word.p("ParamBrowLAngle", .6 * s), Word.p("ParamBrowRAngle", .6 * s)));

        // —— 嘴 ——
        put("微笑", (a, s, n) -> Word.w().hold("mouth", Word.p("ParamMouthForm", s)));
        put("大笑", (a, s, n) -> Word.w().hold("mouth",
                Word.p("ParamMouthForm", 1), Word.p("ParamMouthOpenY", .6 * s), Word.p("JawOpen", .3 * s)));
        put("坏笑", (a, s, n) -> Word.w().hold("mouth",
                Word.p("ParamMouthForm", 1), Word.p("MouthX", .6 * s * side(a))));
        put("瘪嘴", (a, s, n) -> Word.w().hold("mouth", Word.p("ParamMouthForm", -s)));
        put("张嘴", (a, s, n) -> Word.w().hold("mouth",
                Word.p("ParamMouthOpenY", .7 * s), Word.p("JawOpen", .4 * s), Word.p("MouthFunnel", .3)));
        put("嘟嘴", (a, s, n) -> Word.w().hold("mouth",
                Word.p("MouthFunnel", .7), Word.p("Mouthpucker", 1), Word.p("ParamMouthForm", -.4)));
        put("鼓腮", (a, s, n) -> Word.w().hold("mouth", Word.p("CheeckPuff", 1)));
        put("抿嘴", (a, s, n) -> Word.w().hold("mouth",
                Word.p("MouthPressLipOpen", -1), Word.p("ParamMouthForm", -.5)));
        put("吐舌", (a, s, n) -> Word.w().hold("mouth",
                Word.p("TongueOut", 1), Word.p("ParamMouthOpenY", .35)));

        // —— 脸上叠加的表情（开着，要显式关）——
        put("脸红", (a, s, n) -> Word.w().overlay("脸红"));
        put("黑脸", (a, s, n) -> Word.w().overlay("黑脸"));
        put("冷汗", (a, s, n) -> Word.w().overlay("流冷汗"));
        put("眼泪", (a, s, n) -> Word.w().overlay("流泪"));
        put("爱心眼", (a, s, n) -> Word.w().overlay("爱心眼"));
        put("圈圈眼", (a, s, n) -> Word.w().overlay("圈圈眼"));
        put("加载", (a, s, n) -> Word.w().overlay("加载"));
        put("＞＜", (a, s, n) -> Word.w().overlay("＞＜"));
        put("><", (a, s, n) -> Word.w().overlay("＞＜"));
        put("屑屑眼", (a, s, n) -> Word.w().overlay("屑屑眼"));

        // —— 手 ——
        put("抚胸", (a, s, n) -> Word.w().hand(HAND_TO_CHEST[0], HAND_TO_CHEST[1]));
        put("竖手指", (a, s, n) -> Word.w().hand(FINGER_UP[0], FINGER_UP[1]));
        put("狗尾草", (a, s, n) -> Word.w().hand(GRASS[0], GRASS[1]));
        put("放下手", (a, s, n) -> Word.w().handDown());

        // —— 小动作：做几下、多快，基本就是它们表达的全部 ——
        // 点头/摇头默认两下：只做一下会在看清之前就结束（原作者 2026-11-02 定的）
        put("点头", (a, s, n) -> Word.w().move(Word.t("FaceAngleY",
                scale(repeat(n == null ? 2 : n, .46, .17, .4, -10, 1, .14), s))));
        put("摇头", (a, s, n) -> Word.w().move(Word.t("FaceAngleX",
                scale(repeat(n == null ? 2 : n, .56, .17, .45, -11, 11, .22), s))));
        put("眨眼", (a, s, n) -> Word.w().move(Word.t("EyeOpen",
                repeat(n == null ? 1 : n, .22, .07, .16, -1, 0, 0))));
        put("瞟一眼", (a, s, n) -> {
            if ("上".equals(a) || "下".equals(a)) {
                return Word.w().move(Word.t("ParamEyeBallY",
                        scale("0:0 .12:.7 .55:.7 .8:0", s * ("上".equals(a) ? 1 : -1))));
            }
            return Word.w().move(Word.t("ParamEyeBallX", scale("0:0 .12:.85 .55:.85 .8:0", s * side(a))));
        });
        put("挑一下眉", (a, s, n) -> Word.w().move(
                Word.t("ParamBrowLY", scale("0:0 .1:.7 .45:.7 .7:0", s)),
                Word.t("ParamBrowRY", scale("0:0 .1:.7 .45:.7 .7:0", s)),
                Word.t("BrowInnerUp", scale("0:0 .1:.5 .45:.5 .7:0", s))));
        put("歪一下头", (a, s, n) -> Word.w().move(Word.t("FaceAngleZ",
                scale("0:0 .2:12 .6:12 .9:0", s * side(a)))));
        put("晃头", (a, s, n) -> Word.w().move(Word.t("FaceAngleZ",
                scale("0:0 .3:-10 .7:10 1.1:-8 1.5:6 2:0", s))));
        // 左→右→左→回正，最后慢慢停下，摸头彩蛋第 1 句用
        put("慢歪头", (a, s, n) -> Word.w().move(Word.t("FaceAngleZ",
                scale("0:0 1:8 2.2:-8 3.4:6 4.6:0", s))));
        put("缩一下", (a, s, n) -> Word.w().move(
                Word.t("ViewSquash", scale("0:0 .08:.05 .3:.01 .6:0", s)),
                Word.t("FaceAngleY", scale("0:0 .08:-8 .6:0", s))));
        put("叹气", (a, s, n) -> Word.w().move(
                Word.t("LeanInput", "0:0 .8:5 1.8:-7 2.8:-6 3.5:0"),
                Word.t("FaceAngleY", "0:0 .8:7 1.8:-9 2.8:-8 3.5:0"),
                Word.t("ViewSquash", "0:0 .8:-.02 1.2:-.02 1.9:.03 2.8:.025 3.5:0"),
                Word.t("EyeOpen", "0:0 .8:-.2 1.8:-.6 2.8:-.55 3.5:0"),
                Word.t("MouthFunnel", "0:0 1.1:0 1.4:.9 2.2:.6 2.6:0"),
                Word.t("ParamMouthOpenY", "0:0 1.1:0 1.4:.3 2.2:.12 2.6:0")));
        // 一惊只用在真被吓到的时候（原作者 2026-11-02 定的）：和尾巴被碰到时同一个动作
        put("吓一跳", (a, s, n) -> Word.w().move(
                Word.t("ViewY", scale("0:0 .06:0 .2:.06 .4:0", s)),
                Word.t("ViewSquash", scale("0:0 .06:.06 .18:-.05 .4:.05 .6:0", s)),
                Word.t("EyeOpen", scale("0:0 .08:.35 .8:.3 1.2:0", s)),
                Word.t("BrowInnerUp", scale("0:0 .08:.9 1:.6 1.4:0", s))));
        put("恢复", (a, s, n) -> Word.w().reset());

        // —— 舞步（只在 skits.txt 的「跳舞」和音乐律动里用，不进身体说明）——
        put("摇摆一", (a, s, n) -> dance(s, filled(12, .45),
                new double[]{1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1}, false, 1));
        put("摇摆二", (a, s, n) -> groove(s, filled(6, .4 * 8 / 6),
                new double[]{1, -1, 1, -1, 1, -1}, 1));
        // 小剧场「跳舞」专用：幅度 -30%、速度放慢 30%（每拍拉长到 1/0.7 倍）
        put("剧场摇摆一", (a, s, n) -> dance(s * .7, filled(12, .45),
                new double[]{1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1}, false, 1 / .7));
        put("剧场摇摆二", (a, s, n) -> groove(s * .7, filled(6, .4 * 8 / 6),
                new double[]{1, -1, 1, -1, 1, -1}, 1 / .7));
        // 越摇越慢越小，从右往左接着来，3.86 秒
        put("慢摇", (a, s, n) -> groove(s, new double[]{.7, .9, 1.1}, new double[]{1, -.7, .4}, 1));

        put("左右张望", (a, s, n) -> Word.w().move(
                Word.t("ParamEyeBallX", scale("0:0 .15:.8 .75:.8 1:-.8 1.6:-.8 1.9:0", s)),
                Word.t("FaceAngleX", scale("0:0 .15:8 .75:8 1:-8 1.6:-8 1.9:0", s))));
    }

    // ---------------------------------------------------------------- 组合词

    /** 一个词展开成几个基础词一起做。 */
    private static final Map<String, String> COMBOS = new HashMap<String, String>();

    static {
        COMBOS.put("高兴", "张嘴 眯眼笑 晃头");
        COMBOS.put("偷笑", "坏笑 瞟一眼:左 眯眼笑");
        COMBOS.put("傻眼", "睁大 张嘴 冷汗 快眨眼×2");
        COMBOS.put("无语", "斜眼 抿嘴 微摇头");
        COMBOS.put("得瑟", "屑屑眼 晃头 后仰");
        COMBOS.put("抓狂", "皱眉 ＞＜ 张嘴 快摇头×3");
        COMBOS.put("犯困", "半睁 低头 张嘴 慢眨眼×2");
        COMBOS.put("感动", "八字眉 眼泪 抚胸 微笑");
        COMBOS.put("害怕", "缩一下 冷汗 八字眉 后仰");
        COMBOS.put("鬼点子", "挑眉 坏笑 竖手指");
        COMBOS.put("撒娇", "嘟嘴 歪头 八字眉 慢眨眼");
        COMBOS.put("得救", "睁大 抬头 微笑 叹气");
        COMBOS.put("警觉", "正视 站直 皱眉 看左边");
    }

    /** 反应型动作：不管她在说什么，都按自己的速度走，不跟着语气变快变慢。 */
    public static final Set<String> REFLEXES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("吓一跳", "眨眼", "缩一下", "快眨单眼")));

    /** 让模型现编嘀咕时可以选的动作词（不含吓一跳、爱心眼这类有特定用法的）。 */
    public static final String MUTTER_VOCABULARY =
            "歪头 低头 抬头 前倾 后仰 看左边 看右边 看上面 看下面 睁大 半睁 眯眼笑 笑眼 斜眼 眨单眼 挑眉 皱眉 八字眉 微笑 大笑 坏笑 瘪嘴 张嘴 嘟嘴 鼓腮 抿嘴 吐舌 "
                    + "脸红 黑脸 冷汗 屑屑眼 竖手指 抚胸 点头 摇头 眨眼 瞟一眼 挑一下眉 晃头 缩一下 叹气 "
                    + "偷笑 傻眼 无语 得瑟 抓狂 犯困 感动 害怕 鬼点子 撒娇 得救 警觉 高兴";

    // ---------------------------------------------------------------- 查询

    public static boolean knows(String name) {
        return WORDS.containsKey(name) || COMBOS.containsKey(name);
    }

    public static Make get(String name) {
        return WORDS.get(name);
    }

    public static String combo(String name) {
        return COMBOS.get(name);
    }

    public static Set<String> wordNames() {
        return Collections.unmodifiableSet(WORDS.keySet());
    }

    public static Set<String> comboNames() {
        return Collections.unmodifiableSet(COMBOS.keySet());
    }

    /** 按名字造词，不认识的返回 null。 */
    public static Word find(String name) {
        Make make = WORDS.get(name);
        return make == null ? null : make.make(null, 1, null);
    }

    // ---------------------------------------------------------------- 展开工具

    static int side(String where) {
        return "右".equals(where) ? -1 : 1;
    }

    /**
     * 对应 C# 的 "0.###"：最多三位小数，不留尾零。
     *
     * <p>注意负零：{@code 0 * -1} 在 IEEE 里是 {@code -0.0}，.NET 的 "0.###" 会输出 {@code "-0"}。
     * 这里刻意保持一样——渲染上 -0 和 0 完全等价，但两边输出能逐字对上，
     * 以后再有差异就能一眼看出来是真问题。已用 98 行对照用例验证过。
     */
    public static String num(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        boolean negativeZero = value == 0 && 1.0 / value < 0;
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return negativeZero ? "-0" : Long.toString((long) value);
        }
        String text = String.format(Locale.US, "%.3f", value);
        if (text.indexOf('.') >= 0) {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && text.charAt(end - 1) == '.') {
                end--;
            }
            text = text.substring(0, end);
        }
        return text.length() == 0 ? "0" : text;
    }

    /** 把整条轨道的值乘一个系数。 */
    static String scale(String keys, double by) {
        StringBuilder out = new StringBuilder();
        for (String key : keys.split(" ")) {
            if (key.length() == 0) {
                continue;
            }
            int at = key.indexOf(':');
            double time = Double.parseDouble(key.substring(0, at));
            double value = Double.parseDouble(key.substring(at + 1)) * by;
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(num(time)).append(':').append(num(value));
        }
        return out.toString();
    }

    /** 把整条轨道的时间乘一个系数（>1 更慢）。 */
    public static String pace(String keys, double by) {
        StringBuilder out = new StringBuilder();
        for (String key : keys.split(" ")) {
            if (key.length() == 0) {
                continue;
            }
            int at = key.indexOf(':');
            double time = Double.parseDouble(key.substring(0, at)) * by;
            double value = Double.parseDouble(key.substring(at + 1));
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(num(time)).append(':').append(num(value));
        }
        return out.toString();
    }

    /** 这条轨道最后回到零点是什么时候，也就是它有多长（秒）。 */
    public static double end(String keys) {
        String last = keys.substring(keys.lastIndexOf(' ') + 1);
        return Double.parseDouble(last.substring(0, last.indexOf(':')));
    }

    /** 同一个小动作连做几下：每下下去再回来，做完停一会儿。 */
    static String repeat(int times, double each, double down, double back,
                         double downValue, double backValue, double rest) {
        StringBuilder out = new StringBuilder("0:0");
        double at = 0;
        for (int i = 0; i < times; i++) {
            out.append(' ').append(num(at + down)).append(':').append(num(downValue));
            out.append(' ').append(num(at + back)).append(':').append(num(backValue));
            at += each;
        }
        out.append(' ').append(num(at + rest)).append(":0");
        return out.toString();
    }

    static double[] filled(int count, double value) {
        double[] out = new double[count];
        Arrays.fill(out, value);
        return out;
    }

    private static Word look(String where, double s) {
        if ("上".equals(where)) {
            return Word.w().hold("gaze", Word.p("ParamEyeBallY", .6 * s), Word.p("FaceAngleY", 4 * s));
        }
        if ("下".equals(where)) {
            return Word.w().hold("gaze", Word.p("ParamEyeBallY", -.6 * s), Word.p("FaceAngleY", -5 * s));
        }
        return Word.w().hold("gaze",
                Word.p("ParamEyeBallX", .75 * s * side(where)),
                Word.p("FaceAngleX", 7 * s * side(where)));
    }

    // ---------------------------------------------------------------- 舞步

    /**
     * 一段舞：每一拍身体转到一边（正数转向屏幕左边），头往反方向转、反方向歪，
     * 头比身子晚 0.14 秒到，两头不是同一刻一起掉头，看着连贯。
     *
     * @param dip 同一边连着两拍时，两拍之间身体回弹一半（「顿」一下）
     */
    static Word dance(double size, double[] beats, double[] hits, boolean dip, double stretch) {
        double[] gaps = new double[beats.length];
        for (int i = 0; i < beats.length; i++) {
            gaps[i] = beats[i] / .7 * stretch;
        }
        final double headLag = .14;
        double[] times = timing(gaps, hits.length);
        double endAt = 0;
        for (double gap : gaps) {
            endAt += gap;
        }
        return Word.w().move(
                Word.t("ParamBodyAngleX", track(13, .55, hits, times, dip, size, endAt, 0)),
                Word.t("ParamBodyAngleZ", track(6.5, .55, hits, times, dip, size, endAt, 0)),
                Word.t("ViewRoll", track(2.5, .55, hits, times, dip, size, endAt, 0)),
                Word.t("ViewX", track(.02, .55, hits, times, dip, size, endAt, 0)),
                Word.t("FaceAngleX", track(-19, .55, hits, times, dip, size, endAt, headLag)),
                Word.t("FaceAngleZ", track(-8, .55, hits, times, dip, size, endAt, headLag)),
                Word.t("FaceAngleY", arch(9.5, -4.5, hits, times, size, endAt, headLag)));
    }

    /**
     * 第二段舞：每一拍头歪向一边、脸跟着转过去一点，同一边的肩膀耸起来靠向头，身子轻轻往那边扭；
     * 每拍落下时整个人往下颠一下、头点一下，拍子就出来了。
     */
    static Word groove(double size, double[] beats, double[] hits, double stretch) {
        double[] gaps = new double[beats.length];
        for (int i = 0; i < beats.length; i++) {
            gaps[i] = beats[i] / .7 * stretch;
        }
        double[] times = timing(gaps, hits.length);
        double endAt = 0;
        for (double gap : gaps) {
            endAt += gap;
        }
        return Word.w().move(
                Word.t("FaceAngleZ", grooveSide(21, .06, .45, hits, times, size, endAt)),
                Word.t("FaceAngleX", grooveSide(9, .1, .45, hits, times, size, endAt)),
                Word.t("FaceAngleY", grooveBounce(-4, 3, .05, hits, times, size, endAt)),
                Word.t("ShoulderYy", grooveSide(14, 0, .45, hits, times, size, endAt)),
                Word.t("ShoulderX", grooveSide(10, 0, .45, hits, times, size, endAt)),
                Word.t("ParamBodyAngleZ", grooveSide(5, 0, .45, hits, times, size, endAt)),
                Word.t("ParamBodyAngleX", grooveSide(4, 0, .45, hits, times, size, endAt)),
                Word.t("ViewRoll", grooveSide(1.2, 0, .45, hits, times, size, endAt)),
                Word.t("ViewY", grooveBounce(-.008, .004, 0, hits, times, size, endAt)));
    }

    /** 每一拍落在哪一刻。第一拍在第一个间隔的一半处，之后取前后两个间隔的中点。 */
    private static double[] timing(double[] gaps, int count) {
        double[] times = new double[count];
        times[0] = gaps[0] / 2;
        for (int i = 1; i < count; i++) {
            times[i] = times[i - 1] + (gaps[i - 1] + gaps[i]) / 2;
        }
        return times;
    }

    private static String track(double amount, double extra, double[] hits, double[] times,
                                boolean dip, double size, double endAt, double lag) {
        StringBuilder out = new StringBuilder("0:0");
        for (int i = 0; i < hits.length; i++) {
            out.append(' ').append(num(times[i] + lag)).append(':').append(num(hits[i] * amount * size));
            if (i + 1 < hits.length && dip && Math.signum(hits[i]) == Math.signum(hits[i + 1])) {
                double middle = (times[i] + times[i + 1]) / 2 + lag;
                out.append(' ').append(num(middle)).append(':').append(num(hits[i] * amount * extra * size));
            }
        }
        out.append(' ').append(num(endAt + lag)).append(":0");
        return out.toString();
    }

    private static String arch(double high, double low, double[] hits, double[] times,
                               double size, double endAt, double lag) {
        StringBuilder out = new StringBuilder("0:0");
        for (int i = 0; i < hits.length; i++) {
            out.append(' ').append(num(times[i] + lag)).append(':').append(num(low * size));
            if (i + 1 < hits.length) {
                double middle = (times[i] + times[i + 1]) / 2 + lag;
                double value = Math.signum(hits[i]) == Math.signum(hits[i + 1]) ? high * .25 : high;
                out.append(' ').append(num(middle)).append(':').append(num(value * size));
            }
        }
        out.append(' ').append(num(endAt + lag)).append(":0");
        return out.toString();
    }

    private static String grooveSide(double amount, double lag, double back, double[] hits,
                                     double[] times, double size, double endAt) {
        StringBuilder out = new StringBuilder("0:0");
        for (int i = 0; i < hits.length; i++) {
            boolean again = i > 0 && Math.signum(hits[i]) == Math.signum(hits[i - 1]);
            out.append(' ').append(num(times[i] + lag)).append(':')
                    .append(num(hits[i] * amount * (again ? 1.15 : 1) * size));
            if (i + 1 < hits.length && Math.signum(hits[i]) == Math.signum(hits[i + 1])) {
                double middle = (times[i] + times[i + 1]) / 2 + lag;
                out.append(' ').append(num(middle)).append(':').append(num(hits[i] * amount * back * size));
            }
        }
        out.append(' ').append(num(endAt + lag)).append(":0");
        return out.toString();
    }

    private static String grooveBounce(double down, double up, double lag, double[] hits,
                                       double[] times, double size, double endAt) {
        StringBuilder out = new StringBuilder("0:0");
        for (int i = 0; i < hits.length; i++) {
            out.append(' ').append(num(times[i] + lag)).append(':')
                    .append(num(down * Math.abs(hits[i]) * size));
            if (i + 1 < hits.length) {
                double middle = (times[i] + times[i + 1]) / 2 + lag;
                double value = up * (Math.abs(hits[i]) + Math.abs(hits[i + 1])) / 2;
                out.append(' ').append(num(middle)).append(':').append(num(value * size));
            }
        }
        out.append(' ').append(num(endAt + lag)).append(":0");
        return out.toString();
    }
}

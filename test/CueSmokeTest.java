import com.amber.speaker.cue.Body;
import com.amber.speaker.cue.Cues;
import com.amber.speaker.cue.Line;
import com.amber.speaker.cue.Sink;
import com.amber.speaker.cue.WordTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯逻辑层自测。不依赖 JUnit，直接 java 跑：编好了当场就知道移植对不对。
 *
 * <p>重点不在于「能跑」，而在于盯住几处最容易悄悄出错的地方：
 * 全角空格、编辑距离猜测、方向符号、微/大 前缀、通道叠加、时间轴的首帧。
 */
public final class CueSmokeTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        testStrip();
        testParts();
        testUnread();
        testModifiers();
        testActsWithin();
        testSplit();
        testBodyCommands();
        testOverlayAndCancel();
        testChannelStacking();
        testTimeline();

        System.out.println();
        System.out.println("通过 " + passed + "  失败 " + failed);
        if (failed > 0) {
            System.out.println();
            for (String f : failures) {
                System.out.println("  ✗ " + f);
            }
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    private static void testStrip() {
        eq("剥离〔〕", "你终于来了，我等好久了",
                Cues.strip("〔歪头 微笑〕你终于来了，〔眨眼〕我等好久了"));
        eq("剥离后剩空", "", Cues.strip("〔歪头〕").trim());
        ok("有 cue", Cues.hasCues("〔歪头〕嗨"));
        ok("没 cue", !Cues.hasCues("嗨"));
        eq("cue 列表", "[歪头 微笑, 眨眼]",
                Cues.cues("〔歪头 微笑〕你终于来了，〔眨眼〕我等好久了").toString());
    }

    private static void testParts() {
        // cue / 话 / cue / 话，四段，不是五段
        List<Cues.Part> parts = Cues.parts("〔歪头〕你好〔眨眼〕呀");
        eq("段数", 4, parts.size());
        eq("第0段是cue", true, parts.get(0).cue);
        eq("第1段是话", "你好", parts.get(1).text);
        eq("第3段是话", "呀", parts.get(3).text);
    }

    private static void testUnread() {
        eq("全部认识", 0, Cues.unread("〔歪头 微笑 眨眼〕").size());
        eq("不认识一个", 1, Cues.unread("〔瞒一眼〕").size());
        eq("猜出近义词", "瞟一眼", Cues.unread("〔瞒一眼〕").get(0).like);
        // 「大哭」拿不到「大笑」这个建议：大 被当成幅度修饰符剥掉了，剩一个「哭」，
        // 没有等长的词可比。这是桌宠原版的行为，我们照抄——因为不剥就没法支持「大歪头」。
        // 代价是这类错字只会被指出「不认识」，不会顺带猜。已记进 README 的差异表。
        eq("大哭拿不到建议（原版行为）", null, Cues.unread("〔大哭〕").get(0).like);
        eq("大哭仍被指出", "大哭", Cues.unread("〔大哭〕").get(0).word);
        eq("差两个字不猜", null, Cues.unread("〔哈哈哈〕").get(0).like);
        eq("组合词算认识", 0, Cues.unread("〔高兴〕").size());
        eq("×N 不干扰", 0, Cues.unread("〔快眨眼×2〕").size());
        eq("方向不干扰", 0, Cues.unread("〔瞟一眼:左〕").size());
        eq("可读性：5个词以内", true, Cues.cueReadable("歪头 微笑 眨眼 点头 皱眉"));
        eq("可读性：6个词", false, Cues.cueReadable("歪头 微笑 眨眼 点头 皱眉 叹气"));
    }

    private static void testModifiers() {
        // 微/大 调幅度，快/慢 调速度，都要能剥出来
        Recorder r = new Recorder();
        Body body = new Body(r);
        body.apply("歪头", 0, '\0');
        contains("歪头 16", r.last("set FaceAngleZ"), "16");

        r.clear();
        body.forget();
        body.apply("大歪头", 0, '\0');
        contains("大歪头 22.4", r.last("set FaceAngleZ"), "22.4");

        r.clear();
        body.forget();
        body.apply("微歪头", 0, '\0');
        contains("微歪头 8", r.last("set FaceAngleZ"), "8");

        // 「大笑」以 大 开头，但它是词本身，不能剥成「笑」
        r.clear();
        body.forget();
        body.apply("大笑", 0, '\0');
        contains("大笑不被剥", r.last("set ParamMouthForm"), "1");
        contains("大笑张嘴", r.last("set ParamMouthOpenY"), "0.6");
    }

    private static void testActsWithin() {
        eq("先摆姿势再说", false, Cues.actsWithin("〔歪头〕你好呀"));
        eq("边说边动", true, Cues.actsWithin("你好呀〔歪头〕"));
        eq("中间动", true, Cues.actsWithin("今天〔歪头〕不错"));
        eq("多个前置 cue", false, Cues.actsWithin("〔歪头〕〔微笑〕你好"));
        eq("提醒：行太少", true, Cues.needsActing(Arrays.asList(true)));
        eq("提醒：都不在句中", true, Cues.needsActing(Arrays.asList(false, false, false, false, false)));
        eq("不用提醒", false, Cues.needsActing(Arrays.asList(true, true, false, false, false)));
    }

    private static void testSplit() {
        eq("普通分行", 2, Line.split("第一行\n第二行").size());
        // 一条只有〔〕的行不能变成空气泡
        List<String> lines = Line.split("〔歪头〕\n你好呀");
        eq("纯动作行并到下一行", 1, lines.size());
        eq("并进下一行内容", "〔歪头〕你好呀", lines.get(0));
        // 全角空格不是换行
        eq("全角空格不切行", 1, Line.split("你好　再见").size());
        // 全角斜杠是换行
        eq("全角斜杠切行", 2, Line.split("第一句／第二句").size());
        // 半角斜杠带空格也切，但不带空格的不切（A/B 那种）
        eq("空格斜杠切行", 2, Line.split("第一句 / 第二句").size());
        eq("无空格斜杠不切", 1, Line.split("选项A/B").size());
    }

    private static void testBodyCommands() {
        Recorder r = new Recorder();
        Body body = new Body(r);
        body.apply("歪头", 0, '\0');
        eq("歪头只发一条 set", 1, r.count("set "));
        contains("歪头参数名", r.last("set "), "FaceAngleZ");
        contains("歪头是正的（转向屏幕左）", r.last("set "), "16");

        r.clear();
        body.forget();
        body.apply("歪头:右", 0, '\0');
        contains("右是负的", r.last("set "), "-16");

        r.clear();
        body.forget();
        body.apply("点头", 0, '\0');
        ok("点头是 anim 轨道", r.count("anim FaceAngleY") == 1);
        ok("点头默认两下", r.last("anim ").split(" ").length >= 4);

        r.clear();
        body.forget();
        body.apply("点头×4", 0, '\0');
        ok("×4 是四拍", r.last("anim ").contains("2.31") || r.last("anim ").split(" ").length > 10);

        r.clear();
        body.forget();
        body.apply("抚胸", 0, '\0');
        ok("抚胸举手", r.count("motion 默认手切举手") == 1);
        body.apply("放下手", 0, '\0');
        ok("放下手", r.count("motion 举手切默认手") >= 1);
    }

    private static void testOverlayAndCancel() {
        Recorder r = new Recorder();
        Body body = new Body(r);
        body.apply("脸红", 0, '\0');
        ok("开表情", r.count("exp 脸红 1") == 1);
        ok("表情不占通道", !body.shown().containsKey("ParamMouthForm"));

        body.apply("-脸红", 0, '\0');
        ok("关表情", r.count("exp 脸红 0") == 1);

        r.clear();
        body.forget();
        body.apply("歪头 微笑", 0, '\0');
        body.apply("-歪头", 0, '\0');
        ok("取消姿势会 clear", r.count("clear FaceAngleZ") == 1);
        ok("微笑还在", body.shown().containsKey("ParamMouthForm"));

        r.clear();
        body.forget();
        body.apply("恢复", 0, '\0');
    }

    private static void testChannelStacking() {
        Recorder r = new Recorder();
        Body body = new Body(r);
        // 「看左边」走 gaze 通道、「转头」走 turn 通道，两个都动 FaceAngleX，要相加
        // 注意词之间必须有空格：「看左边转头」连写会被当成一个词，整体不认识（原版就是这样）
        body.apply("看左边 转头", 0, '\0');
        Double angleX = body.shown().get("FaceAngleX");
        // gaze 的 7 + turn 的 20 = 27
        ok("跨通道相加 = 27，实际 " + angleX, angleX != null && Math.abs(angleX - 27) < 1e-9);

        // 连写确实认不出来，要能报出去，不能静默
        body.forget();
        body.apply("看左边转头", 0, '\0');
        ok("连写被报为不认识", body.unknown.size() > 0);

        // 皱眉之后挑眉，同通道顶替，不能相加成怪表情
        r.clear();
        body.forget();
        body.apply("皱眉", 0, '\0');
        body.apply("挑眉", 0, '\0');
        Double browY = body.shown().get("ParamBrowLY");
        ok("同通道顶替", browY != null && browY.doubleValue() > 0);
        ok("顶替后只剩一个值", r.count("set ParamBrowLY") == 2);
    }

    private static void testTimeline() {
        Recorder r = new Recorder();
        Body body = new Body(r);
        Line line = new Line("〔歪头〕你好呀", new Line.Act() {
            @Override
            public double act(String cue, double spoken, char tone) {
                return body.apply(cue, spoken, tone);
            }
        });
        eq("plain 去掉 cue", "你好呀", line.plain());
        eq("spoken 是 3 字", round(3.0 / 9, 6), round(line.spoken(), 6));

        line.advance(0);
        ok("首帧就做动作了", r.count("set FaceAngleZ") == 1);
        eq("首帧还没出字", 0, line.shown());
        eq("首帧不在说话", false, line.talking());

        // 歪头 wait 是 max(.8, ...) 之类，推过 1 秒应该开始出字
        line.advance(1.0);
        ok("过了动作等待开始出字", line.shown() > 0);

        line.advance(100);
        ok("最后说完", line.done());
        eq("全部文字出来", 3, line.shown());

        // 语气只作用于一次性动作（点头这类），不作用于保持型姿势（歪头）。
        // 这是原版的设计：姿势是「她现在是什么样」，不该跟着一句话的语气抖。
        Recorder r2 = new Recorder();
        Body body2 = new Body(r2);
        Line shout = new Line("〔点头〕你好！", new Line.Act() {
            @Override
            public double act(String cue, double spoken, char tone) {
                return body2.apply(cue, spoken, tone);
            }
        });
        shout.advance(0);
        // 感叹号把点头幅度从 -10 放大到 -12
        contains("感叹号放大动作", r2.last("anim FaceAngleY"), "-12");

        Recorder r3 = new Recorder();
        Body body3 = new Body(r3);
        Line calm = new Line("〔点头〕你好。", new Line.Act() {
            @Override
            public double act(String cue, double spoken, char tone) {
                return body3.apply(cue, spoken, tone);
            }
        });
        calm.advance(0);
        contains("平铺直叙不放大", r3.last("anim FaceAngleY"), "-10");

        // 保持型姿势不受语气影响：歪头永远是 16
        Recorder r4 = new Recorder();
        Body body4 = new Body(r4);
        Line held = new Line("〔歪头〕你好！", new Line.Act() {
            @Override
            public double act(String cue, double spoken, char tone) {
                return body4.apply(cue, spoken, tone);
            }
        });
        held.advance(0);
        contains("姿势不吃语气", r4.last("set FaceAngleZ"), "16");
    }

    // ---------------------------------------------------------------- 断言

    private static final class Recorder implements Sink {
        final List<String> all = new ArrayList<String>();

        @Override
        public void send(String command) {
            all.add(command);
        }

        void clear() {
            all.clear();
        }

        int count(String prefix) {
            int n = 0;
            for (String c : all) {
                if (c.startsWith(prefix)) {
                    n++;
                }
            }
            return n;
        }

        String last(String prefix) {
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i).startsWith(prefix)) {
                    return all.get(i);
                }
            }
            return "";
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            passed++;
        } else {
            failed++;
            failures.add(name + "：期望 [" + expected + "] 实际 [" + actual + "]");
        }
    }

    private static void eq(String name, double expected, double actual) {
        if (Math.abs(expected - actual) < 1e-9) {
            passed++;
        } else {
            failed++;
            failures.add(name + "：期望 " + expected + " 实际 " + actual);
        }
    }

    private static void ok(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name);
        }
    }

    private static void contains(String name, String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) {
            passed++;
        } else {
            failed++;
            failures.add(name + "：[" + haystack + "] 里没有 [" + needle + "]");
        }
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }
}

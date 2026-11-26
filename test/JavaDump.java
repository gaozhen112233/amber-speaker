import com.amber.speaker.cue.Body;
import com.amber.speaker.cue.Cues;
import com.amber.speaker.cue.Line;
import com.amber.speaker.cue.Sink;
import com.amber.speaker.cue.WordTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 和 C# 侧 Program.cs 一一对应的输出程序。两边跑同一批用例，输出逐字对比。
 *
 * <p>这是移植正确性的唯一硬证据：词表的 60 个词、通道叠加、语气缩放、时间轴首帧，
 * 任何一处抄错都会在某个字段上差一个数。
 */
public final class JavaDump {

    public static void main(String[] args) {
        List<String> lines = new ArrayList<String>();

        String[] texts = {
                "〔歪头 微笑〕你终于来了，〔眨眼〕我等好久了",
                "〔点头〕你好！",
                "〔点头〕你好。",
                "〔歪头〕你好〔眨眼〕呀",
                "〔瞒一眼〕",
                "〔大哭〕",
                "〔高兴〕",
                "〔快眨眼×2〕",
                "〔瞟一眼:左〕",
                "〔哈哈哈〕",
                "〔高兴 脸红〕今天真好",
        };

        for (String text : texts) {
            lines.add("strip\t" + text + "\t" + Cues.strip(text));
            lines.add("has\t" + text + "\t" + (Cues.hasCues(text) ? "1" : "0"));
            StringBuilder parts = new StringBuilder();
            for (Cues.Part part : Cues.parts(text)) {
                if (parts.length() > 0) {
                    parts.append('|');
                }
                parts.append(part.cue ? "C:" : "T:").append(part.text);
            }
            lines.add("parts\t" + text + "\t" + parts);
            StringBuilder unread = new StringBuilder();
            for (Cues.Unread one : Cues.unread(text)) {
                if (unread.length() > 0) {
                    unread.append('|');
                }
                unread.append(one.word).append('=').append(one.like == null ? "-" : one.like);
            }
            lines.add("unread\t" + text + "\t" + unread);
        }

        Object[][] cues = {
                {"歪头 微笑", 0.0, '\0'},
                {"歪头 微笑", 1.5, '！'},
                {"歪头 微笑", 2.0, '…'},
                {"歪头:右", 0.0, '\0'},
                {"大歪头", 0.0, '\0'},
                {"微歪头", 0.0, '\0'},
                {"点头", 0.0, '\0'},
                {"点头", 1.5, '！'},
                {"点头", 0.3, '\0'},
                {"点头×4", 0.0, '\0'},
                {"摇头×3", 0.0, '\0'},
                {"眨眼×2", 0.0, '\0'},
                {"瞟一眼:上", 0.0, '\0'},
                {"瞟一眼:右", 0.0, '\0'},
                {"叹气", 0.0, '\0'},
                {"吓一跳", 0.0, '\0'},
                {"抚胸", 0.0, '\0'},
                {"竖手指", 0.0, '\0'},
                {"狗尾草", 0.0, '\0'},
                {"放下手", 0.0, '\0'},
                {"脸红 黑脸", 0.0, '\0'},
                {"高兴", 0.0, '\0'},
                {"屑屑眼 大笑", 0.0, '\0'},
                {"鼓腮 屑屑眼", 0.0, '\0'},
                {"恢复", 0.0, '\0'},
                {"摇摆一", 0.0, '\0'},
                {"摇摆二", 0.0, '\0'},
                {"慢摇", 0.0, '\0'},
                {"剧场摇摆一", 0.0, '\0'},
                {"左右张望", 0.0, '\0'},
                {"看左边 转头", 0.0, '\0'},
                {"斜眼", 0.0, '\0'},
                {"嘟嘴 脸红 歪头", 0.0, '\0'},
                {"快眨眼×2", 0.0, '\0'},
                {"单左眨眼", 0.0, '\0'},
                {"慢歪头", 0.0, '\0'},
                {"缩一下", 0.0, '\0'},
                {"挑眉", 0.0, '\0'},
                {"皱眉 眼泪", 0.0, '\0'},
                {"半睁 抿嘴", 0.0, '\0'},
        };

        for (Object[] one : cues) {
            String cue = (String) one[0];
            double spoken = ((Double) one[1]).doubleValue();
            char tone = ((Character) one[2]).charValue();
            final StringBuilder sent = new StringBuilder();
            Body body = new Body(new Sink() {
                @Override
                public void send(String command) {
                    sent.append(command).append(';');
                }
            });
            double wait = body.apply(cue, spoken, tone);
            String commands = sent.length() > 0 ? sent.substring(0, sent.length() - 1) : "";
            lines.add("body\t" + cue + "\t" + WordTable.num(spoken) + "\t" + ((int) tone) + "\t"
                    + WordTable.num(wait) + "\t" + commands + "\t" + join(body.unknown));
        }

        Object[][][] sequences = {
                {{"歪头 微笑", 0.0, '\0'}, {"-歪头", 0.0, '\0'}},
                {{"皱眉", 0.0, '\0'}, {"挑眉", 0.0, '\0'}},
                {{"脸红", 0.0, '\0'}, {"-脸红", 0.0, '\0'}},
                {{"抚胸", 0.0, '\0'}, {"放下手", 0.0, '\0'}},
                {{"歪头 微笑", 0.0, '\0'}, {"恢复", 0.0, '\0'}},
        };

        for (int s = 0; s < sequences.length; s++) {
            final StringBuilder sent = new StringBuilder();
            Body body = new Body(new Sink() {
                @Override
                public void send(String command) {
                    sent.append(command).append(';');
                }
            });
            for (Object[] step : sequences[s]) {
                body.apply((String) step[0], ((Double) step[1]).doubleValue(), ((Character) step[2]).charValue());
                sent.append('|');
            }
            lines.add("seq" + s + "\t" + (sent.length() > 0 ? sent.substring(0, sent.length() - 1) : ""));
        }

        for (final String text : new String[]{"〔歪头〕你好呀", "〔点头〕你好！", "今天天气不错〔歪头〕你说呢"}) {
            final Body body = new Body(Sink.NONE);
            Line line = new Line(text, new Line.Act() {
                @Override
                public double act(String cue, double spoken, char tone) {
                    return body.apply(cue, spoken, tone);
                }
            });
            StringBuilder stamps = new StringBuilder();
            for (double t : new double[]{0, .3, .8, 1.0, 2.0, 5.0}) {
                line.advance(t);
                if (stamps.length() > 0) {
                    stamps.append('|');
                }
                stamps.append(WordTable.num(t)).append(':').append(line.shown()).append(':')
                        .append(line.talking() ? 1 : 0);
            }
            lines.add("line\t" + text + "\t" + line.plain() + "\t" + WordTable.num(line.spoken()) + "\t"
                    + WordTable.num(line.until()) + "\t" + stamps);
        }

        for (String text : new String[]{
                "第一行\n第二行",
                "〔歪头〕\n你好呀",
                "你好　再见",
                "第一句／第二句",
                "第一句 / 第二句",
                "选项A/B",
        }) {
            lines.add("split\t" + text.replace("\n", "\\n") + "\t" + join(Line.split(text)));
        }

        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        System.out.print(out);
    }

    private static String join(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) {
                out.append('|');
            }
            out.append(item);
        }
        return out.toString();
    }
}

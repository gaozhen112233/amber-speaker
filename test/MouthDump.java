import com.amber.speaker.cue.Mouth;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 把 {@link Mouth} 在给定 tick 序列上的输出摊开，给逐字对照用。
 *
 * <p>输出三列：毫秒 、未取整的原始值（17 位有效数字，能看出 ulp 级差异）、两位小数字符串。
 * 同时统计两件跟「和桌宠一致」直接相关的事：
 * <ol>
 *   <li><b>取整边界的最近距离</b>。如果每个值离 .xx5 这个分界都远，那么 C# 的 Math.Sin
 *       和 Java 的 Math.sin 即使差几个 ulp，取两位小数也不可能不同——这才是不依赖
 *       「我也跑一遍 C#」的证明方式。</li>
 *   <li><b>去重前后的命令条数</b>。桌宠每个渲染帧都发，我们只在两位小数真的变了才发。</li>
 * </ol>
 */
public final class MouthDump {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "out/ticks.txt";
        PrintStream out = new PrintStream(System.out, true, "UTF-8");

        Mouth mouth = new Mouth();
        int frames = 0;
        int sent = 0;
        int rawChanges = 0;
        double lastRaw = 0;
        double nearest = 1;
        long nearestAt = -1;

        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] cols = line.split("\t");
            if (cols.length < 2) continue;
            long ms = Long.parseLong(cols[0].trim());
            boolean talking = "1".equals(cols[1].trim());

            double level = Mouth.level(talking, ms);
            String text = Mouth.format(level);
            String command = mouth.update(talking, ms);
            if (command != null) sent++;
            if (level != lastRaw) rawChanges++;
            lastRaw = level;
            frames++;

            // 离 .xx5 这条分界线还有多远（以两位小数为单位）
            double scaled = level * 100;
            double gap = Math.abs(scaled - Math.floor(scaled) - .5);
            if (gap < nearest) {
                nearest = gap;
                nearestAt = ms;
            }

            out.println(ms + "\t" + String.format(Locale.ROOT, "%.17g", level) + "\t" + text);
        }
        reader.close();

        System.err.println("frames=" + frames);
        System.err.println("raw_dedup=" + rawChanges);
        System.err.println("text_dedup=" + sent);
        System.err.println(String.format(Locale.ROOT,
                "取整边界最近距离=%.3e（在 %d ms）", nearest, nearestAt));
        mouth.reset();
        if (mouth.update(false, 0) != null) {
            System.err.println("reset 后应发出一条 mouth 0.00 —— 正常");
        } else {
            System.err.println("!! reset 后没发 0.00，说明残留值没清干净");
        }
    }
}

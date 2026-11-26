import com.amber.speaker.cue.Body;
import com.amber.speaker.cue.Sink;
import com.amber.speaker.cue.WordTable;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 把词表里每个词<b>真正发出去的参数名</b>都记下来，给 {@code tools/param_audit.py} 对模型核对。
 *
 * <p>走 {@link Body} 而不是直接读 {@code Word}，因为 Body 会做组合词展开、幅度/速度修饰、
 * 通道覆盖——这些都会改变最终发给模型的名字。测就得测出口那一层。
 *
 * <p>为什么要查：词表是从桌宠抄的，桌宠那套参数名是它自己模型的。这个模型的参数名是自定义的
 * （已经撞见过 {@code AngleX} 而不是 {@code ParamAngleX}）。名字对不上时 {@code SetParameterValue}
 * 会**静默无效**：不报错、不打日志，她只是不做那个动作。这种缺陷在真机上看不出来，
 * 因为她本来也在动；只能靠对名字抓。
 */
public final class ParamAudit {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");

        for (String name : new TreeSet<String>(WordTable.wordNames())) {
            final List<String> commands = new ArrayList<String>();
            Body body = new Body(new Sink() {
                @Override
                public void send(String command) {
                    commands.add(command);
                }
            });
            try {
                body.apply(name);
            } catch (RuntimeException e) {
                out.println(name + "\t!!" + e.getClass().getSimpleName());
                continue;
            }
            TreeSet<String> ids = new TreeSet<String>();
            for (String command : commands) {
                String[] word = command.split(" ");
                if (word.length < 2) continue;
                if ("set".equals(word[0]) || "clear".equals(word[0]) || "anim".equals(word[0])
                        || "animloop".equals(word[0])) {
                    ids.add(word[1]);
                } else if ("exp".equals(word[0])) {
                    ids.add("@" + word[1]);
                } else if ("motion".equals(word[0])) {
                    ids.add("#" + word[1]);
                }
            }
            out.println(name + "\t" + String.join(",", ids));
        }
    }
}

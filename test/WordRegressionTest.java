import com.amber.speaker.cue.Body;
import com.amber.speaker.cue.Sink;
import com.amber.speaker.cue.WordTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 全量动作词回归：把词表里每一个基础词 + 组合词都过一遍，确认她「身体做得出」。
 *
 * <p>为什么值得有：CueSmokeTest 只抽了十几个代表性词，而真正的坑（参数名对不上、某词
 * 展开成死参数、组合词引用了不存在的词）是<b>某几个词悄悄坏</b>，抽样永远测不到。这里遍历
 * {@code WordTable.wordNames()} 和 {@code comboNames()}，一个词一个词地：
 * <ol>
 *   <li>断言 {@code knows()} 认识它</li>
 *   <li>单独 {@code body.apply()} 它，断言<b>不产生 unknown</b>（身体能做出）</li>
 *   <li>断言<b>至少产出命令</b>（set / anim / motion / exp / clear 之一，不是空转）</li>
 * </ol>
 *
 * <p>几个要单独伺候的词：{@code 恢复}（reset，要先摆姿势才有东西可清）、{@code 放下手}
 * （handDown，要先举手才有 motion 可发）、{@code 正视 / 站直 / 看过来 / 看你}（clear 型，
 * 要先摆对应通道才有 clear 可发）。它们「空转」是语义正确的——对它们改判为「在摆好姿势的
 * body 上确实发得出命令」，而不是和真 bug 混为一谈。
 */
public final class WordRegressionTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<String>();

    /** 靠「清掉某个东西」起作用的词：单独跑是空转，得先摆好对应姿势。 */
    private static final String[] NEEDS_CONTEXT = {"恢复", "放下手", "正视", "站直", "看过来", "看你"};

    public static void main(String[] args) {
        int words = 0;
        int combos = 0;

        // 每个基础词：单独一个 body，apply 之后不许有 unknown、不许空转
        for (String name : sorted(WordTable.wordNames())) {
            words++;
            checkWord(name);
        }

        // 每个组合词：展开后也是几个基础词，同样不许 unknown、不许空转
        for (String name : sorted(WordTable.comboNames())) {
            combos++;
            checkWord(name);
        }

        System.out.println();
        System.out.println("WordRegressionTest: " + passed + " 通过, " + failed + " 失败"
                + "（基础词 " + words + " + 组合词 " + combos + "）");
        if (!failures.isEmpty()) {
            System.out.println("失败项：");
            for (String f : failures) {
                System.out.println("  ✗ " + f);
            }
            System.exit(1);
        }
    }

    /** 单个词（基础或组合）的完整检查。 */
    private static void checkWord(String name) {
        Recorder r = new Recorder();
        Body body = new Body(r);
        body.apply(name);
        ok("「" + name + "」不产生 unknown", body.unknown.isEmpty());

        if (needsContext(name)) {
            // 这类词靠「清掉已摆的姿势」起作用，单独跑空转是对的。
            // 给它一个摆满姿势、举着手的 body，确认它确实发得出命令。
            Recorder r2 = new Recorder();
            Body body2 = new Body(r2);
            body2.apply("前倾");       // 摆身子（lean 通道，给「站直」清）
            body2.apply("歪头");       // 摆头（tilt 通道，给「正视」清）
            body2.apply("看左边");     // 摆视线（gaze 通道，给「看你/看过来」清）
            body2.apply("抚胸");       // 举只手（给「放下手」清）
            r2.clear();
            body2.apply(name);          // 现在清，应该发得出命令
            ok("「" + name + "」在有姿势时产出命令", r2.count() > 0);
        } else {
            ok("「" + name + "」产出命令", r.count() > 0);
        }
    }

    private static boolean needsContext(String name) {
        for (String n : NEEDS_CONTEXT) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> sorted(Set<String> set) {
        return new ArrayList<String>(new TreeSet<String>(set));
    }

    private static final class Recorder implements Sink {
        final List<String> all = new ArrayList<String>();

        @Override
        public void send(String command) {
            all.add(command);
        }

        void clear() {
            all.clear();
        }

        int count() {
            return all.size();
        }
    }

    private static void ok(String name, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            failures.add(name);
            System.out.println("FAIL " + name);
        }
    }
}

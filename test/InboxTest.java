import com.amber.speaker.cue.Inbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 排队的语义测试。时间全部手给，所以「第 3 句过期了没有」这类问题不用真等 30 秒。
 *
 * <p>重点不是覆盖率，是把三条容易被实现写歪的规则钉死：
 * ① interrupt 要连正在说的那句一起作废（光清队列不算打断）；
 * ② ttl 从<b>入队</b>算，不是从<b>开始说</b>算；
 * ③ ttl=0 是「永不过期」，不是「立刻过期」——这两个差一个符号，写反了她就一句话都不说。
 */
public final class InboxTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    private static void ok(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(name);
        }
    }

    private static void eq(String name, Object want, Object got) {
        boolean same = want == null ? got == null : want.equals(got);
        if (!same) {
            failures.add(name + "（期望 " + want + "，实际 " + got + "）");
        } else {
            passed++;
        }
    }

    private static List<String> lines(String... texts) {
        return new ArrayList<String>(Arrays.asList(texts));
    }

    public static void main(String[] args) {
        // ---- 基本排队 ----
        Inbox inbox = new Inbox();
        Inbox.Offer offer = inbox.offer(lines("第一句", "第二句"), 0, false, 1000);
        eq("入队两条", 2, offer.queued);
        eq("入队不丢弃", 0, offer.dropped);
        eq("取出第一条", "第一句", inbox.next(1000));
        eq("取出第二条", "第二句", inbox.next(1000));
        eq("取空了返回 null", null, inbox.next(1000));

        // ---- 空白行要被吃掉，不能变成空气泡 ----
        inbox = new Inbox();
        inbox.offer(lines("真话", "   ", "\u3000", "", null), 0, false, 0);
        eq("空白行不入队", 1, inbox.size());

        // ---- interrupt ----
        inbox = new Inbox();
        inbox.offer(lines("老的1", "老的2", "老的3"), 0, false, 0);
        eq("打断前 3 条", 3, inbox.size());
        offer = inbox.offer(lines("新的"), 0, true, 10);
        eq("打断丢弃 3 条", 3, offer.dropped);
        eq("打断后只剩新的", 1, offer.queued);
        eq("打断后第一条是新的", "新的", inbox.next(10));

        // ---- ttl：从入队算 ----
        inbox = new Inbox();
        inbox.offer(lines("早些的"), 0, false, 0);
        inbox.offer(lines("晚些的"), 1000, false, 500);   // 500ms 入队，寿命 1s
        eq("还没过期，先拿到早的", "早些的", inbox.next(1400));
        // 晚的入队于 500，此刻 1400，才过 900ms —— 还没到 1000，应该还在
        eq("900ms 时还没到期", "晚些的", inbox.next(1400));
        // 换成 1600 就过了 1100ms
        inbox = new Inbox();
        inbox.offer(lines("晚些的"), 1000, false, 500);
        eq("1100ms 时已过期", null, inbox.next(1600));

        // ---- ttl 边界：正好等于不算过期 ----
        inbox = new Inbox();
        inbox.offer(lines("正好卡点"), 1000, false, 0);
        eq("刚好 1000ms 还在", "正好卡点", inbox.next(1000));
        inbox = new Inbox();
        inbox.offer(lines("超 1ms"), 1000, false, 0);
        eq("超 1ms 就丢", null, inbox.next(1001));

        // ---- ttl=0 是永不过期（写反的话她一句话都不说，而且完全没报错）----
        inbox = new Inbox();
        inbox.offer(lines("永久有效"), 0, false, 0);
        eq("ttl=0 一小时也还在", "永久有效", inbox.next(3600_000L));

        // ---- 过期的会被跳过，后面的还能出来 ----
        inbox = new Inbox();
        inbox.offer(lines("会过期"), 100, false, 0);
        inbox.offer(lines("不过期"), 0, false, 0);
        eq("跳过期、取到下一条", "不过期", inbox.next(5000));

        // ---- 负 ttl 当 0 处理（上游传 -1 表示「不限」的写法很常见）----
        inbox = new Inbox();
        inbox.offer(lines("负数当永久"), -1, false, 0);
        eq("负 ttl 不过期", "负数当永久", inbox.next(100000));

        // ---- 打断一个空队列，不算丢弃 ----
        inbox = new Inbox();
        offer = inbox.offer(lines("孤零零"), 0, true, 0);
        eq("空队列打断丢弃 0", 0, offer.dropped);
        eq("但东西要收下", 1, inbox.size());

        // ---- clear ----
        inbox = new Inbox();
        inbox.offer(lines("a", "b"), 0, false, 0);
        inbox.clear();
        eq("clear 之后是空的", 0, inbox.size());
        eq("clear 之后取不到", null, inbox.next(0));

        // ---- peek 只看不取 ----
        inbox = new Inbox();
        inbox.offer(lines("一", "二"), 0, false, 0);
        eq("peek 列出两条", 2, inbox.peek().size());
        eq("peek 不改动队列", 2, inbox.size());

        System.out.println();
        System.out.println("通过 " + passed + "  失败 " + failures.size());
        for (String f : failures) {
            System.out.println("  ✗ " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }
}

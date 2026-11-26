import com.amber.speaker.sound.WakeReply;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 预录库 + 唤醒开场白候选的自测。纯 Java，PC 上跑。
 *
 * <p>覆盖：
 * <ol>
 *   <li>语音唤醒开场白随机落在 2 条候选内（小鱼在的 / 小鱼收到）</li>
 *   <li>走近唤醒开场白随机落在 10 条候选内</li>
 *   <li>button / 未知来源回落到语音那组</li>
 *   <li>随机性：大量采样能覆盖候选全集，且不同来源不互相串</li>
 *   <li>每条候选都能被 match() 命中预录库（返回 asset:// 地址）</li>
 *   <li>match 对空白 / 未命中返回 null</li>
 * </ol>
 */
public final class WakeReplyTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        // 1. 语音唤醒候选集
        Set<String> voice = new HashSet<String>();
        voice.add("小鱼在的");
        voice.add("小鱼收到");

        // 2. 走近唤醒候选集
        Set<String> proximity = new HashSet<String>();
        proximity.add("你来啦");
        proximity.add("怎么了");
        proximity.add("你回来了？主人");
        proximity.add("我在呢");
        proximity.add("怎么了？主人");
        proximity.add("你终于回来啦？我好想你呀");
        proximity.add("你终于回来啦");
        proximity.add("你终于回来啦，我等你好久了");
        proximity.add("下次你早点回来呀");
        proximity.add("你今天累不累啊");

        // 2.5 在场停留搭话候选集
        Set<String> presence = new HashSet<String>();
        presence.add("你在干嘛");
        presence.add("要不要我陪陪你");
        presence.add("陪陪小鱼嘛");
        presence.add("和我说说话嘛");
        presence.add("坐太久起来运动一下嘛");
        presence.add("要不要我帮你捶捶背");
        presence.add("记得喝点水呀");
        presence.add("你在旁边我很安心哦");
        presence.add("看我一下嘛");
        presence.add("今天记得早点睡哦");

        // 3. 语音来源随机落在 voice 候选内，且不会串到 proximity
        {
            Set<String> seen = new HashSet<String>();
            boolean leaked = false;
            for (int i = 0; i < 200; i++) {
                String s = WakeReply.openingFor("voice");
                if (!voice.contains(s)) {
                    leaked = true;
                }
                seen.add(s);
            }
            ok("voice 开场白不串到 proximity 组", !leaked);
            ok("voice 采样覆盖全部 2 条候选", seen.containsAll(voice));
        }

        // 4. proximity 来源随机落在 proximity 候选内
        {
            Set<String> seen = new HashSet<String>();
            boolean leaked = false;
            for (int i = 0; i < 400; i++) {
                String s = WakeReply.openingFor("proximity");
                if (!proximity.contains(s)) {
                    leaked = true;
                }
                seen.add(s);
            }
            ok("proximity 开场白不串到 voice 组", !leaked);
            ok("proximity 采样覆盖全部 10 条候选", seen.containsAll(proximity));
        }

        // 5. button / 未知来源回落到语音那组
        {
            ok("button 回落到 voice 组", voice.contains(WakeReply.openingFor("button")));
            ok("未知来源回落到 voice 组", voice.contains(WakeReply.openingFor(null)));
            ok("空串回落到 voice 组", voice.contains(WakeReply.openingFor("")));
        }

        // 5.5 presence 来源随机落在 presence 候选内，且不串组
        {
            Set<String> seen = new HashSet<String>();
            boolean leaked = false;
            for (int i = 0; i < 300; i++) {
                String s = WakeReply.openingFor("presence");
                if (!presence.contains(s)) {
                    leaked = true;
                }
                seen.add(s);
            }
            ok("presence 开场白不串组", !leaked);
            ok("presence 采样覆盖全部 10 条候选", seen.containsAll(presence));
        }

        // 6. 每条候选都能命中预录库（返回 asset:// 地址）
        {
            for (String s : voice) {
                String a = WakeReply.match(s);
                ok("预录命中 voice 候选「" + s + "」", a != null && a.startsWith("asset://"));
            }
            for (String s : proximity) {
                String a = WakeReply.match(s);
                ok("预录命中 proximity 候选「" + s + "」", a != null && a.startsWith("asset://"));
            }
            for (String s : presence) {
                String a = WakeReply.match(s);
                ok("预录命中 presence 候选「" + s + "」", a != null && a.startsWith("asset://"));
            }
            // 定时播报预录句（固定台词，不参与随机开场白）
            ok("预录命中定时播报「早上好呀主人」", WakeReply.match("早上好呀主人") != null);
            ok("预录命中定时播报「好困啊~我去睡了~晚安主人」", WakeReply.match("好困啊~我去睡了~晚安主人") != null);
        }

        // 7. match 边界：空白 / 未命中返回 null
        {
            ok("match(null) 返回 null", WakeReply.match(null) == null);
            ok("match(\"  \") 返回 null", WakeReply.match("   ") == null);
            ok("match(未预录句) 返回 null", WakeReply.match("随便说点啥") == null);
        }

        System.out.println();
        System.out.println("WakeReplyTest: " + passed + " 通过, " + failed + " 失败");
        if (!failures.isEmpty()) {
            System.out.println("失败项：");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
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

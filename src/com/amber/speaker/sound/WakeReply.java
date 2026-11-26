package com.amber.speaker.sound;

import com.amber.speaker.Text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * 预录库：延迟敏感的高频短句，提前用她的音色录好打进 APK，命中就直接播，一毫秒都不等 TTS。
 *
 * <p>最典型的场景是唤醒应答——「大肥鱼」喊醒后她要立刻应一声「小鱼在的」。
 * 如果这句还要等 1.5~3 秒 TTS，那个「应一声」就没有意义了：人已经说完第二句。
 * 契约 §14.7 把这一级叫「预录命中」，排在「顺手合成」之前。
 *
 * <p>匹配规则是<b>台词原文精确命中</b>（规整掉首尾空白后全等）。不做模糊匹配：
 * 预录句就那么几个字，差一个字就是另一句话，硬凑反而答非所问。
 *
 * <p>音频放在 {@code assets/voice/} 下，用 {@code asset://} 前缀交给 {@link Voice} 读。
 */
public final class WakeReply {

    /** 台词原文（规整后）→ assets/voice/ 下的文件名。按需往里加。 */
    private static final Map<String, String> PRERECORDED = new LinkedHashMap<String, String>();

    static {
        // 语音唤醒应答（喊「大肥鱼」/「小鱼小鱼」）——多条候选，随机挑一条，别每次都同一句。
        PRERECORDED.put("小鱼在的", "xiaoyuzaide.wav");
        PRERECORDED.put("小鱼收到", "xiaoyushoudao.wav");
        // 走近唤醒应答（人体感应 + 摄像头确认）——多条候选，随机挑一条。
        PRERECORDED.put("你来啦", "nilaile.wav");
        PRERECORDED.put("怎么了", "zenmele.wav");
        PRERECORDED.put("你回来了？主人", "zhuren_nilaile.wav");
        PRERECORDED.put("我在呢", "wozaine.wav");
        PRERECORDED.put("怎么了？主人", "en.wav");
        PRERECORDED.put("你终于回来啦？我好想你呀", "zhongyu_xiangni.wav");
        PRERECORDED.put("你终于回来啦", "zhongyu.wav");
        PRERECORDED.put("你终于回来啦，我等你好久了", "zhongyu_dengni.wav");
        PRERECORDED.put("下次你早点回来呀", "xiaqizaodian.wav");
        PRERECORDED.put("你今天累不累啊", "leibulei.wav");
        // 在场停留搭话（人一直待旁边，持续在场超时后她主动搭话）——多条候选，随机挑一条。
        PRERECORDED.put("你在干嘛", "nizaiganma.wav");
        PRERECORDED.put("要不要我陪陪你", "yaowopeinima.wav");
        PRERECORDED.put("陪陪小鱼嘛", "wozaiting.wav");
        PRERECORDED.put("和我说说话嘛", "heshuoshuo.wav");
        PRERECORDED.put("坐太久起来运动一下嘛", "zuotaiyundong.wav");
        PRERECORDED.put("要不要我帮你捶捶背", "chuichuibei.wav");
        PRERECORDED.put("记得喝点水呀", "jideheshui.wav");
        PRERECORDED.put("你在旁边我很安心哦", "nihenanxin.wav");
        PRERECORDED.put("看我一下嘛", "kanwoyixia.wav");
        PRERECORDED.put("今天记得早点睡哦", "zaodian_shui.wav");
        // 定时播报（v1.17）——到点主动说，固定台词不随机。
        PRERECORDED.put("早上好呀主人", "zaoshanghao.wav");
        PRERECORDED.put("好困啊~我去睡了~晚安主人", "shuile_wanan.wav");
    }

    /**
     * 语音唤醒（喊「大肥鱼」/「小鱼小鱼」）的应答，<b>几条候选随机挑一条</b>。
     *
     * <p>这里反复过两轮，结论记下来免得再来一次：
     * <ul>
     *   <li>用户说「说唤醒词没有固定回答」——那次的问题其实是<b>时有时无</b>（识别把
     *       「大肥鱼」听成「大培鱼」，唤醒词根本没命中），不是「应该固定成一句」。
     *       我误读成后者，把它收敛成了一条。</li>
     *   <li>收敛之后用户又说「每次都只有这一个回答词」——<b>单条候选听起来像复读机</b>，
     *       而且三个字就没了，不像有人在应你。</li>
     * </ul>
     * 所以真正的需求是两件事同时成立：<b>每次都必须有应答</b>（这是确认信号，不能漏），
     * 但<b>话可以换着说</b>（这是人味）。三条都短、都是「我在」的意思，
     * 换着说不影响它作为信号的可预期性。
     */
    private static final String[] VOICE_OPENERS = {
            "小鱼在的", "小鱼收到", "我在呢",
    };

    /**
     * 走近唤醒（人体感应 + 摄像头确认）的应答候选，随机挑一条。
     */
    private static final String[] PROXIMITY_OPENERS = {
            "你来啦", "怎么了", "你回来了？主人", "我在呢", "怎么了？主人",
            "你终于回来啦？我好想你呀", "你终于回来啦", "你终于回来啦，我等你好久了",
            "下次你早点回来呀", "你今天累不累啊",
    };

    /**
     * 在场停留搭话（持续在场超时）的候选，随机挑一条。语气更「主动关心」，
     * 和走近唤醒的「打招呼」区分开。
     */
    private static final String[] PRESENCE_OPENERS = {
            "你在干嘛", "要不要我陪陪你", "陪陪小鱼嘛",
            "和我说说话嘛", "坐太久起来运动一下嘛", "要不要我帮你捶捶背",
            "记得喝点水呀", "你在旁边我很安心哦", "看我一下嘛", "今天记得早点睡哦",
    };

    /** 随机源：只用来挑开场白，不要求加密级均匀。 */
    private static final Random RANDOM = new Random();

    private WakeReply() {
    }

    /**
     * 台词原文命中预录句时返回对应的 {@code asset://} 地址，否则返回 {@code null}。
     * 调用方拿非空结果替换 audio_url，就能让这一句走本地预录音，不等网络。
     */
    public static String match(String text) {
        if (Text.isBlank(text)) {
            return null;
        }
        String key = Text.trim(text);
        String file = PRERECORDED.get(key);
        return file == null ? null : "asset://" + file;
    }

    /**
     * 按唤醒来源选开场白台词（随机挑一条候选）。不同唤醒方式对应不同的应答集：
     * <ul>
     *   <li>{@code voice} → 「小鱼在的 / 小鱼收到」里随机一条（被喊名字，应一声）</li>
     *   <li>{@code proximity} → 「你来啦 / 怎么了 / 你回来了？主人 / 我在呢 / 怎么了？主人 /
     *       你终于回来啦？我好想你呀 / 你终于回来啦 / 你终于回来啦，我等你好久了 /
     *       下次你早点回来呀 / 你今天累不累啊」里随机一条（有人走近，主动招呼）</li>
     *   <li>{@code presence} → 「你在干嘛 / 要不要我陪陪你 / 陪陪小鱼嘛 / 和我说说话嘛 /
     *       坐太久起来运动一下嘛 / 要不要我帮你捶捶背 / 记得喝点水呀 / 你在旁边我很安心哦 / 看我一下嘛 /
     *       今天记得早点睡哦」里随机一条（人一直待旁边，主动搭话）</li>
     *   <li>{@code button} 或未知 → 回落到语音唤醒那组</li>
     * </ul>
     *
     * <p>为什么随机：固定一句每次唤醒都一样，听多了像复读机。多候选随机，应答更自然。
     * 候选都已在 {@link #match} 的预录库里，保证能命中 {@code asset://} 内置音频、零延迟。
     *
     * @param source 唤醒来源（WakeState.SOURCE_VOICE 等）
     * @return 开场白台词，永不返回 null
     */
    public static String openingFor(String source) {
        String[] pool;
        if ("proximity".equals(source)) {
            pool = PROXIMITY_OPENERS;
        } else if ("presence".equals(source)) {
            pool = PRESENCE_OPENERS;
        } else {
            pool = VOICE_OPENERS;
        }
        return pool[RANDOM.nextInt(pool.length)];
    }
}

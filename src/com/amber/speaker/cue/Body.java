package com.amber.speaker.cue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 她的身体状态：现在保持着什么姿势、脸上开着什么表情、手举着没有。对应桌宠 Body.cs 的执行部分。
 *
 * <p>核心是一条叠加规则：<b>通道之间相加，通道之内覆盖</b>。
 * <ul>
 *   <li>「斜眼」进 eyes 通道、「看左边」进 gaze 通道，两边的 ParamEyeBallX 相加，所以侧着看会更偏。</li>
 *   <li>「皱眉」之后再「挑眉」，同在 brows 通道，后者直接顶替前者，不会叠加成怪表情。</li>
 * </ul>
 *
 * <p>表情（overlay）是独立的开关，不占通道，所以「脸红」能和任何姿势同时存在，要显式用 {@code -脸红} 或
 * 「恢复」关掉。
 */
public final class Body {

    /** 一句之后等她做多久，最长等这么久（再久她的话就断了）。 */
    private static final double LONGEST_WAIT = 2.4;
    private static final double BEAT = .35;
    private static final double HAND_BEAT = .7;
    private static final double STARTLE_BEAT = .45;

    private static final class Hand {
        final String on;
        final String off;

        Hand(String on, String off) {
            this.on = on;
            this.off = off;
        }
    }

    private final Sink sink;

    /** 通道 → 参数。同 key 后写覆盖前写。 */
    private final Map<String, Word.Param[]> channels = new LinkedHashMap<String, Word.Param[]>();
    /** 通道 → 是哪个词摆的，托盘开关按钮跟着它亮灭。 */
    private final Map<String, String> wordOf = new HashMap<String, String>();
    private final Set<String> overlays = new HashSet<String>();
    /** 现在实际发出去的值，用来避免重复发同一个 set。 */
    private Map<String, Double> shown = new HashMap<String, Double>();
    private Hand hand;
    /** 跳舞时的表情单独放：不算「摆着姿势」，否则一换脸就判定成被摆姿势，舞会自己把自己停下。 */
    private Word.Param[] danceFace;

    /** 她写了、身体做不出来的词，留着生成 note 告诉她。 */
    public final List<String> unknown = new ArrayList<String>();

    public Body(Sink sink) {
        this.sink = sink;
    }

    // ---------------------------------------------------------------- 执行一段 cue

    /**
     * 做一段 cue（不含括号），返回她的话要等多久再往下说。
     *
     * @param spoken 这段 cue 后面那句话要念多久（0 表示后面没话了）
     * @param tone   后面那句话的语气：{@code '！'} 快而大、{@code '…'} 慢而小、其它不变
     */
    public double apply(String cue, double spoken, char tone) {
        // 先展开组合词：「高兴」→「张嘴 眯眼笑 晃头」
        StringBuilder expanded = new StringBuilder();
        for (String token : Cues.tokens(cue)) {
            String parts = WordTable.combo(token);
            if (expanded.length() > 0) {
                expanded.append(' ');
            }
            expanded.append(parts != null ? parts : token);
        }

        double wait = 0;
        double toneSize = tone == '！' ? 1.2 : tone == '…' ? .85 : 1;
        double toneSpeed = tone == '！' ? .85 : tone == '…' ? 1.25 : 1;

        for (String token : Cues.tokens(expanded.toString())) {
            if (token.length() == 0) {
                continue;
            }
            // 取消：-脸红、-歪头
            if (token.charAt(0) == '-' || token.charAt(0) == '－') {
                Word gone = WordTable.find(token.substring(1));
                if (gone == null) {
                    unknown.add(token);
                    continue;
                }
                if (gone.overlay != null && overlays.remove(gone.overlay)) {
                    sink.send("exp " + gone.overlay + " 0");
                }
                if (gone.channel != null) {
                    channels.remove(gone.channel);
                }
                if (gone.handOff != null && hand != null && sameHand(gone)) {
                    putHandDown();
                }
                continue;
            }

            String name = Cues.nameOf(token);
            Double size = null;
            Double speed = null;
            // 微/大 调幅度，快/慢 调速度。但如果这个字本身是词的一部分（大笑），就不能剥
            while (!WordTable.knows(name) && name.length() > 1 && Cues.isModifier(name.charAt(0))) {
                char first = name.charAt(0);
                if (first == '微') {
                    size = .5;
                } else if (first == '大') {
                    size = 1.4;
                } else if (first == '快') {
                    speed = .65;
                } else {
                    speed = 1.7;
                }
                name = name.substring(1);
            }

            WordTable.Make make = WordTable.get(name);
            if (make == null) {
                unknown.add(token);
                continue;
            }

            Integer count = Cues.countOf(token);
            String where = Cues.whereOf(token);
            boolean reflex = WordTable.REFLEXES.contains(name);

            double baseSize = size == null ? 1 : size.doubleValue();
            Word word = make.make(where, baseSize, count);
            // 没有明说幅度时，让她的话决定动作多大：感叹号更大，省略号更小
            if (word.move != null && size == null && !reflex) {
                word = make.make(where, toneSize, count);
            }

            Word.Track[] move = word.move;
            if (move != null && !reflex) {
                double length = longest(move);
                double pace = speed != null ? speed.doubleValue()
                        : toneSpeed * (spoken > 0 && length > 0 ? clamp(spoken / length, .7, 1.6) : 1);
                pace = clamp(pace, .6, 1.8);
                if (pace != 1) {
                    move = repace(move, pace);
                }
            } else if (move != null && speed != null) {
                // 反应型动作保持自己的速度，除非她自己说了「快/慢」
                move = repace(move, speed.doubleValue());
            }

            double moving = move == null ? 0 : longest(move);
            double need;
            if (spoken <= 0) {
                // 后面没话了，就等它演完
                need = Math.max(.8, Math.max(moving, word.handOn != null ? 1.1 : 0));
            } else if ("吓一跳".equals(name)) {
                need = STARTLE_BEAT;
            } else if (word.handOn != null) {
                need = HAND_BEAT;
            } else {
                need = BEAT;
            }
            wait = Math.max(wait, need);

            if (word.reset) {
                relax();
            }
            if (word.clear != null) {
                for (String channel : word.clear) {
                    channels.remove(channel);
                }
            }
            if (word.channel != null) {
                channels.put(word.channel, word.hold);
                wordOf.put(word.channel, name);
            }
            if (word.overlay != null && overlays.add(word.overlay)) {
                sink.send("exp " + word.overlay + " 1");
            }
            if (word.handDown) {
                putHandDown();
            }
            if (word.handOn != null && (hand == null || !hand.on.equals(word.handOn))) {
                putHandDown();
                hand = new Hand(word.handOn, word.handOff);
                sink.send("motion " + word.handOn);
            }
            if (move != null) {
                for (Word.Track track : move) {
                    sink.send("anim " + track.id + " " + track.keys);
                }
            }
        }

        // 屑屑眼自带一张小嘴，别的东西压上去会飘在脸外。但鼓腮是腮帮子，不冲突，留着
        if (overlays.contains("屑屑眼") && channels.containsKey("mouth")) {
            Word.Param[] held = channels.get("mouth");
            List<Word.Param> cheeks = new ArrayList<Word.Param>();
            for (Word.Param param : held) {
                if ("CheeckPuff".equals(param.id)) {
                    cheeks.add(param);
                }
            }
            if (cheeks.isEmpty()) {
                channels.remove("mouth");
            } else {
                channels.put("mouth", cheeks.toArray(new Word.Param[cheeks.size()]));
                wordOf.put("mouth", "鼓腮");
            }
        }

        show();
        return Math.min(wait, LONGEST_WAIT);
    }

    /** 没有语气、后面也没话时最常用的重载。 */
    public double apply(String cue) {
        return apply(cue, 0, '\0');
    }

    // ---------------------------------------------------------------- 表情与整体

    /**
     * 跳舞专用的脸：
     * 0 = 眯眼笑＋微笑、1 = 眯眼笑＋大笑、2 = 闭眼＋挑眉、-1 = 收掉回到默认。
     */
    public void danceFace(int mood) {
        if (mood < 0) {
            danceFace = null;
        } else if (mood == 1) {
            danceFace = new Word.Param[]{
                    Word.p("EyeOpen", 0), Word.p("EyeSmile", 1), Word.p("ParamMouthForm", 1),
                    Word.p("ParamMouthOpenY", .5), Word.p("JawOpen", .25)};
        } else if (mood == 2) {
            danceFace = new Word.Param[]{
                    Word.p("EyeOpen", 0), Word.p("ParamBrowLY", .6), Word.p("ParamBrowRY", .6),
                    Word.p("BrowInnerUp", .4)};
        } else {
            danceFace = new Word.Param[]{
                    Word.p("EyeOpen", 0), Word.p("EyeSmile", 1), Word.p("ParamMouthForm", .9)};
        }
        show();
    }

    /**
     * 跟着拍子把现成的舞步演一遍：BPM 高就放快、低就放慢。
     * 只发 anim 轨道，不碰通道和表情——这些舞步本身就是一串纯轨迹，演完自己回原样。
     *
     * @param seconds 这一句该演多久
     * @return 实际演了多久（变速有 .45~2.4 的限制，所以可能对不上）
     */
    public double danceToBeat(String word, double seconds, double size) {
        WordTable.Make make = WordTable.get(word);
        if (make == null) {
            return 0;
        }
        Word made = make.make(null, size, null);
        if (made.move == null) {
            return 0;
        }
        double natural = longest(made.move);
        if (natural <= 0 || seconds <= 0) {
            return 0;
        }
        double pace = clamp(seconds / natural, .45, 2.4);
        for (Word.Track track : repace(made.move, pace)) {
            sink.send("anim " + track.id + " " + track.keys);
        }
        return natural * pace;
    }

    /**
     * 回到她什么都不写时的样子。只收她自己的：身体类型和主人给她穿的衣服不动。
     */
    public void relax() {
        channels.clear();
        for (String overlay : overlays) {
            sink.send("exp " + overlay + " 0");
        }
        overlays.clear();
        Hand wasHand = hand;
        putHandDown();
        if (wasHand != null) {
            // 再补发一次放下：渲染器的手部动作是队列式的，偶尔没及时让位，手会一直举着
            sink.send("motion " + wasHand.off);
        }
        show();
    }

    /** 只收会保持的姿势和手，脸上表情不动。控制台临时摆一下时用。 */
    public void relaxChannels() {
        channels.clear();
        putHandDown();
        show();
    }

    /** 一个反应型动作结束后，把原来保持的姿势重新发一遍（它动过同一批参数又松手了）。 */
    public void restore() {
        for (Map.Entry<String, Double> entry : shown.entrySet()) {
            sink.send("set " + entry.getKey() + " " + WordTable.num(entry.getValue()));
        }
    }

    /** 换了个渲染器，新来的什么都不知道。 */
    public void forget() {
        channels.clear();
        overlays.clear();
        shown = new HashMap<String, Double>();
        hand = null;
        danceFace = null;
    }

    // ---------------------------------------------------------------- 查询

    public boolean posing() {
        return !channels.isEmpty() || !overlays.isEmpty() || hand != null;
    }

    public boolean handUp() {
        return hand != null;
    }

    public Set<String> heldOverlays() {
        return overlays;
    }

    /** 现在保持着的词（眼睛/眉毛/嘴/头/身子各一个，加手）。 */
    public List<String> heldWords() {
        List<String> words = new ArrayList<String>();
        for (Map.Entry<String, String> entry : wordOf.entrySet()) {
            if (channels.containsKey(entry.getKey())) {
                words.add(entry.getValue());
            }
        }
        if (hand != null) {
            words.add(hand.on.equals(WordTable.HAND_TO_CHEST[0]) ? "抚胸"
                    : hand.on.equals(WordTable.FINGER_UP[0]) ? "竖手指" : "狗尾草");
        }
        return words;
    }

    public Map<String, Double> shown() {
        return shown;
    }

    // ---------------------------------------------------------------- 内部

    private void putHandDown() {
        if (hand != null) {
            sink.send("motion " + hand.off);
        }
        hand = null;
    }

    private boolean sameHand(Word gone) {
        return gone.handOn != null && hand.on.equals(gone.handOn);
    }

    /** 通道之间相加，然后只为变化了的部分发命令。 */
    private void show() {
        Map<String, Double> total = new LinkedHashMap<String, Double>();
        for (Word.Param[] hold : channels.values()) {
            for (Word.Param param : hold) {
                Double before = total.get(param.id);
                total.put(param.id, (before == null ? 0 : before.doubleValue()) + param.value);
            }
        }
        if (danceFace != null) {
            for (Word.Param param : danceFace) {
                Double before = total.get(param.id);
                total.put(param.id, (before == null ? 0 : before.doubleValue()) + param.value);
            }
        }
        for (String id : shown.keySet()) {
            if (!total.containsKey(id)) {
                sink.send("clear " + id);
            }
        }
        for (Map.Entry<String, Double> entry : total.entrySet()) {
            Double before = shown.get(entry.getKey());
            if (before == null || before.doubleValue() != entry.getValue().doubleValue()) {
                sink.send("set " + entry.getKey() + " " + WordTable.num(entry.getValue()));
            }
        }
        shown = total;
    }

    private static double longest(Word.Track[] tracks) {
        double longest = 0;
        for (Word.Track track : tracks) {
            longest = Math.max(longest, WordTable.end(track.keys));
        }
        return longest;
    }

    private static Word.Track[] repace(Word.Track[] tracks, double pace) {
        Word.Track[] out = new Word.Track[tracks.length];
        for (int i = 0; i < tracks.length; i++) {
            out[i] = new Word.Track(tracks[i].id, WordTable.pace(tracks[i].keys, pace));
        }
        return out;
    }

    private static double clamp(double value, double low, double high) {
        return value < low ? low : value > high ? high : value;
    }
}

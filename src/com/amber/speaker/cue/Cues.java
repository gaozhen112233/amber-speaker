package com.amber.speaker.cue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 〔〕的切分与校对，对应桌宠 Body.cs 里那几个静态方法。
 *
 * <p>这一层是纯字符串处理，不碰渲染，所以能在 PC 上跑单元测试，不用等真机。
 * 界面那边只要拿到 {@link Part} 列表就能排时间轴，拿到 {@link Unread} 列表就能生成 note
 * 回给 AstrBot。
 */
public final class Cues {

    private Cues() {
    }

    /** 一个〔〕片段，不含两侧的括号。 */
    private static final Pattern CUE = Pattern.compile("〔[^〔〕]*〕");

    /** 一个词里带方向、次数、参数：{@code 瞟一眼:左}、{@code 快眨眼×2}。 */
    private static final Pattern TOKEN = Pattern.compile("^(.+?)(?:[×xX*](\\d))?(?:[:：](.+))?$");

    /** 〔〕之后还有话：这一行是边做边说，不是先说再做。 */
    private static final Pattern ACTS_WITHIN = Pattern.compile("[^〕\\s]\\s*〔");

    private static final Pattern LEADING_CUES = Pattern.compile("^(\\s*〔[^〔〕]*〕)+");

    private static final Pattern GUIDE_VERSION = Pattern.compile("【你的身体·完】（身体说明 (\\S+) 版）");

    /** 词与词之间的分隔：半角空格、全角空格、顿号、逗号。 */
    private static final Pattern SPLIT = Pattern.compile("[ 　、，,]");

    // ---------------------------------------------------------------- 基本操作

    /** 去掉所有〔〕，只留话。 */
    public static String strip(String text) {
        return text == null ? "" : CUE.matcher(text).replaceAll("");
    }

    public static boolean hasCues(String text) {
        return text != null && CUE.matcher(text).find();
    }

    /** 一行里出现的所有〔〕内容（不含括号），按出现顺序。 */
    public static List<String> cues(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        Matcher m = CUE.matcher(text);
        while (m.find()) {
            out.add(m.group().substring(1, m.group().length() - 1));
        }
        return out;
    }

    /** 按词切开一个 cue 的内容。空串返回空表，不会返回只含空字符串的表。 */
    public static List<String> tokens(String cue) {
        List<String> out = new ArrayList<String>();
        if (cue == null || cue.trim().length() == 0) {
            return out;
        }
        for (String token : SPLIT.split(cue)) {
            if (token.length() > 0) {
                out.add(token);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 切段

    /** 一段：要么是话，要么是一个 cue。 */
    public static final class Part {
        public final boolean cue;
        public final String text;

        Part(boolean cue, String text) {
            this.cue = cue;
            this.text = text;
        }

        @Override
        public String toString() {
            return cue ? "〔" + text + "〕" : text;
        }
    }

    /** 把她这一句按顺序切成「话 / cue / 话 / cue …」。 */
    public static List<Part> parts(String text) {
        List<Part> out = new ArrayList<Part>();
        if (text == null) {
            return out;
        }
        int at = 0;
        Matcher m = CUE.matcher(text);
        while (m.find()) {
            if (m.start() > at) {
                out.add(new Part(false, text.substring(at, m.start())));
            }
            out.add(new Part(true, m.group().substring(1, m.group().length() - 1)));
            at = m.end();
        }
        if (at < text.length()) {
            out.add(new Part(false, text.substring(at)));
        }
        return out;
    }

    // ---------------------------------------------------------------- 校对

    /** 她写了、但身体做不出来的词。{@code like} 是差一个字那个候选，可能为 null。 */
    public static final class Unread {
        public final String word;
        public final String like;

        Unread(String word, String like) {
            this.word = word;
            this.like = like;
        }

        @Override
        public String toString() {
            return like == null ? word : word + "（是不是「" + like + "」）";
        }
    }

    /**
     * 找出所有看不懂的词，每个附上「是不是那个」的猜测。
     *
     * <p>只在差一个字时给建议，而且**只用来告诉她，绝不替她改**——「大哭」和「大笑」是两件事。
     */
    public static List<Unread> unread(String text) {
        List<Unread> out = new ArrayList<Unread>();
        if (text == null) {
            return out;
        }
        Matcher cueMatcher = CUE.matcher(text);
        while (cueMatcher.find()) {
            String inner = cueMatcher.group().substring(1, cueMatcher.group().length() - 1);
            for (String token : tokens(inner)) {
                String name = nameOf(token);
                while (!WordTable.knows(name) && name.length() > 1 && isModifier(name.charAt(0))) {
                    name = name.substring(1);
                }
                if (WordTable.knows(name)) {
                    continue;
                }
                String near = null;
                int nearCount = 0;
                for (String known : WordTable.wordNames()) {
                    if (known.length() == name.length() && oneApart(known, name)) {
                        near = known;
                        nearCount++;
                    }
                }
                out.add(new Unread(token, nearCount == 1 ? near : null));
            }
        }
        return out;
    }

    /** 一个 token 里真正的词名：去掉 ×N 和 :参数，再去掉前导的 -（那是「取消」）。 */
    static String nameOf(String token) {
        String trimmed = token;
        int at = 0;
        while (at < trimmed.length() && (trimmed.charAt(at) == '-' || trimmed.charAt(at) == '－')) {
            at++;
        }
        trimmed = trimmed.substring(at);
        Matcher m = TOKEN.matcher(trimmed);
        return m.matches() ? m.group(1) : trimmed;
    }

    /** ×N 里的 N，没有就是 null。 */
    static Integer countOf(String token) {
        Matcher m = TOKEN.matcher(stripCancel(token));
        return m.matches() && m.group(2) != null ? Integer.valueOf(Integer.parseInt(m.group(2))) : null;
    }

    /** :后面的方向，没有就是 null。 */
    static String whereOf(String token) {
        Matcher m = TOKEN.matcher(stripCancel(token));
        return m.matches() && m.group(3) != null ? m.group(3) : null;
    }

    static String stripCancel(String token) {
        int at = 0;
        while (at < token.length() && (token.charAt(at) == '-' || token.charAt(at) == '－')) {
            at++;
        }
        return token.substring(at);
    }

    static boolean isModifier(char c) {
        return c == '微' || c == '大' || c == '快' || c == '慢';
    }

    private static boolean oneApart(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i) && ++diff > 1) {
                return false;
            }
        }
        return diff == 1;
    }

    /**
     * 这段 cue 读得懂吗，词数合不合理。
     *
     * @param most 最多几个词，默认 5。多了说明她在堆动作，堆出来是抽搐不是表演。
     */
    public static boolean cueReadable(String cue, int most) {
        if (cue == null || cue.trim().length() == 0) {
            return false;
        }
        if (tokens(cue).size() > most) {
            return false;
        }
        return unread("〔" + cue + "〕").isEmpty();
    }

    public static boolean cueReadable(String cue) {
        return cueReadable(cue, 5);
    }

    // ---------------------------------------------------------------- 表演质量

    /**
     * 这一行是不是边说边动：开头之后的某处还有〔〕。
     *
     * <p>「〔歪头〕你好呀」是先摆好姿势再说，说完姿势还在；「你好呀〔歪头〕」才是话到一半动了一下。
     * 后者才是活人的样子，前者看多了像提线木偶。
     */
    public static boolean actsWithin(String line) {
        if (line == null) {
            return false;
        }
        String rest = LEADING_CUES.matcher(line).replaceFirst("");
        return ACTS_WITHIN.matcher(rest).find();
    }

    /**
     * 该不该提醒她「说的时候想着身体」。
     *
     * <p>规则来自原作者的实测：最近的行里少于两条是边说边动、或总行数不到五条，就提醒一次。
     * 在推广视频上重放过一遍，一个好演员 53 句里被提醒 5 次——提醒多了会把表演压平。
     */
    public static boolean needsActing(List<Boolean> recentLines) {
        if (recentLines == null || recentLines.size() < 5) {
            return true;
        }
        int within = 0;
        for (Boolean line : recentLines) {
            if (Boolean.TRUE.equals(line)) {
                within++;
            }
        }
        return within < 2;
    }

    /** 身体说明是哪个版本（从结束行 【你的身体·完】（身体说明 X 版）读），没有就是 null。 */
    public static String guideVersion(String guide) {
        if (guide == null) {
            return null;
        }
        Matcher m = GUIDE_VERSION.matcher(guide);
        return m.find() ? m.group(1) : null;
    }
}

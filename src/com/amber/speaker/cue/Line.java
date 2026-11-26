package com.amber.speaker.cue;

import com.amber.speaker.Text;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 她说的一句话，配着身体演出来。对应桌宠 Body.cs 里的 Line。
 *
 * <p>字幕和动作的同步靠的是同一个时钟：这句话拆成一串小步，字按 9 字/秒出，
 * 碰到〔〕就停下来等身体做完（{@link Body#apply} 返回等多久），等完再往下走。
 * 所以「说完才做」和「边做边说」是由〔〕放在句子的哪个位置自然决定的，不需要额外参数。
 *
 * <p>时间轴是<b>推</b>的：外部按帧调 {@link #advance(double)} 传「这句话开始到现在过了几秒」，
 * 它内部把该走完的步走完。这样音画不同步的时候只要改一个传入值，不用改内部状态机。
 */
public final class Line {

    /** 说话速度。实测 3.5 字/秒在 CosyVoice 上偏慢，9 字/秒对应正常语速。 */
    public static final double CHARACTERS_PER_SECOND = 9;

    private static final double DOTS = .25;
    private static final double PAUSE = .12;
    private static final String STOPS = "，。！？、,!?;；：:~～—";

    /** 全角「／」和有空格包围的「/」当换行。{@code A/B} 这种没空格的不动。 */
    private static final Pattern SLASH_BREAK = Pattern.compile("\\s*／\\s*|\\s+/\\s+");

    private static final class Step {
        final String cue;
        final int end;
        final double seconds;
        final boolean talks;

        Step(String cue, int end, double seconds, boolean talks) {
            this.cue = cue;
            this.end = end;
            this.seconds = seconds;
            this.talks = talks;
        }
    }

    /** 做一段 cue：返回她的话要等多久。 */
    public interface Act {
        double act(String cue, double spoken, char tone);
    }

    /**
     * 有配音时打开：〔〕照做，但时间轴不为它停。
     *
     * <p>配音是连着放的，中途不会因为「她要抬个手」而暂停；要是字幕还按老规矩等，
     * 字就会越落越后，一句话说到最后能差出半秒。所以这种模式下动作立即生效、不占时间。
     */
    public void muteWaits(boolean free) {
        this.freeWaits = free;
    }

    private final List<Step> steps = new ArrayList<Step>();
    private final Act act;
    private final String plain;
    private final double spoken;
    private int index;
    private double at;
    private double clock;
    private int shown;
    private boolean talking;
    private boolean freeWaits;

    public Line(String text, Act act) {
        this(text, act, CHARACTERS_PER_SECOND);
    }

    public Line(String text, Act act, double charactersPerSecond) {
        this.act = act;
        StringBuilder plainBuilder = new StringBuilder();
        for (Cues.Part part : Cues.parts(text)) {
            if (part.cue) {
                steps.add(new Step(part.text, plainBuilder.length(), 0, false));
                continue;
            }
            BreakIterator letters = BreakIterator.getCharacterInstance(Locale.ROOT);
            letters.setText(part.text);
            int start = letters.first();
            for (int end = letters.next(); end != BreakIterator.DONE; start = end, end = letters.next()) {
                String letter = part.text.substring(start, end);
                plainBuilder.append(letter);
                boolean dots = "…".equals(letter) || "⋯".equals(letter) || ".".equals(letter);
                boolean stop = letter.length() == 1 && STOPS.indexOf(letter.charAt(0)) >= 0;
                boolean blank = Text.isBlank(letter);
                double seconds = blank ? 0 : dots ? DOTS : stop ? PAUSE : 1.0 / charactersPerSecond;
                steps.add(new Step(null, plainBuilder.length(), seconds, !blank && !dots && !stop));
            }
        }
        this.plain = plainBuilder.toString();
        double total = 0;
        for (Step step : steps) {
            total += step.seconds;
        }
        this.spoken = total;
    }

    /**
     * 把她一整段回复切成一行行。一行一个气泡，一行一行说。
     *
     * <p>只有〔〕的行不单独成泡——那是身体的事，没有话。它跟着相邻的话走：
     * 在最后就并进上一行，在最前就并进下一行。
     */
    public static List<String> split(String text) {
        List<String> lines = new ArrayList<String>();
        if (text == null) {
            return lines;
        }
        String broken = SLASH_BREAK.matcher(text).replaceAll("\n").replace("\r\n", "\n");
        String pending = "";
        for (String raw : broken.split("\n", -1)) {
            String line = Text.trim(raw);
            if (line.length() == 0) {
                continue;
            }
            if (Text.trim(Cues.strip(line)).length() == 0) {
                pending = pending + line;
                continue;
            }
            lines.add(pending + line);
            pending = "";
        }
        if (pending.length() > 0) {
            if (lines.isEmpty()) {
                lines.add(pending);
            } else {
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + pending);
            }
        }
        return lines;
    }

    public static boolean hasSlashBreak(String text) {
        return text != null && SLASH_BREAK.matcher(text).find();
    }

    // ---------------------------------------------------------------- 推进

    /**
     * 推到一个新的时刻。
     *
     * @param seconds 这句话从开始到现在过了多少秒
     */
    public void advance(double seconds) {
        clock = seconds;
        talking = false;
        while (index < steps.size() && at <= seconds) {
            Step step = steps.get(index);
            if (step.cue != null) {
                Tone tone = after(index);
                double wait = act.act(step.cue, tone.spoken, tone.mark);
                if (freeWaits) {
                    wait = 0;
                }
                at += wait;
                index++;
                continue;
            }
            shown = step.end;
            if (seconds < at + step.seconds) {
                talking = step.talks;
                return;
            }
            at += step.seconds;
            index++;
        }
    }

    private static final class Tone {
        final double spoken;
        final char mark;

        Tone(double spoken, char mark) {
            this.spoken = spoken;
            this.mark = mark;
        }
    }

    /**
     * 这段 cue 到下一个 cue 之间的那句话：多久能念完，是什么语气。
     *
     * <p>语气决定动作幅度和速度：感叹号更大更快，省略号更小更慢。这是让它「说话有起伏」的关键，
     * 比任何随机化都有效。
     */
    private Tone after(int cue) {
        double spokenSeconds = 0;
        int from = steps.get(cue).end;
        int to = from;
        for (int i = cue + 1; i < steps.size() && steps.get(i).cue == null; i++) {
            spokenSeconds += steps.get(i).seconds;
            to = steps.get(i).end;
        }
        String words = plain.substring(from, Math.max(from, to));
        char mark;
        if (words.indexOf('！') >= 0 || words.indexOf('!') >= 0) {
            mark = '！';
        } else {
            String tail = Text.trimEnd(words);
            mark = tail.endsWith("…") || tail.endsWith("⋯") ? '…' : '\0';
        }
        return new Tone(spokenSeconds, mark);
    }

    // ---------------------------------------------------------------- 查询

    /** 去掉〔〕之后的话。 */
    public String plain() {
        return plain;
    }

    /** 已经显示到 Plain 的第几个字符（UTF-16 位置，直接当 substring 终点用）。 */
    public int shown() {
        return shown;
    }

    public double clock() {
        return clock;
    }

    /** 现在是不是正在出字（说话中）。嘴型和这个对齐。 */
    public boolean talking() {
        return talking;
    }

    /** 连最后那个等待都演完了，下一句可以接上。 */
    public boolean done() {
        return index >= steps.size() && clock >= at;
    }

    /** 全部演完是什么时候（从这句开始算的秒数）。 */
    public double until() {
        return at;
    }

    /** 字念完要多久，不含等身体的那些停顿。有配音时应该用配音时长替掉它。 */
    public double spoken() {
        return spoken;
    }
}

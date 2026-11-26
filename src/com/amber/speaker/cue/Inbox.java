package com.amber.speaker.cue;

import com.amber.speaker.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 等着她说的那些话。
 *
 * <p>以前这儿就是一个 {@code ArrayDeque<String>}，一次一句往下说。这个类把另外三件事也说清楚了：
 *
 * <h3>一、打断（interrupt）</h3>
 * AstrBot 那边经常会「一句还没说完就改口」。人的做法是立刻停下改说新话，不是把两句都说完。
 * {@code interrupt=true} 就是这个：<b>清空待说的，并让正在说的那句当场作废</b>。
 * 不打断的时候是默认排队——她一句一句说，不抢话。
 *
 * <h3>二、过期（ttl）</h3>
 * 排队有个真实风险：网络抖一下、上游连发五句，等她说完已经是半分钟后，
 * 前面那些「现在几点了」「快看窗外」早就没意义了，说出去反而奇怪。
 * {@code ttl} 是<b>从入队那一刻算起</b>的寿命：轮到她开口时如果已经超了，就当场丢掉，
 * 不播、不占字幕、继续看下一条。{@code ttl=0} 表示永不过期（默认，和以前行为一样）。
 *
 * <p>注意 ttl 只管「还没开口」的那些。已经开口的那句就算超时也让它说完——
 * 话说一半掐掉比说过期的话更难受。
 *
 * <h3>三、为什么单独一个类</h3>
 * 因为它必须能在没有 Android、没有时钟的情况下被反复测。时间全部由调用方传进来，
 * 这样「第 3 句过期了没有」这种问题不用等 30 秒就能验证。
 */
public final class Inbox {

    /** 一条待说的话。一次请求拆出来的多行共用一个入队时刻和寿命，否则同一句会被切成两半。 */
    private static final class Entry {
        final String text;
        final String audio;
        final long at;
        final long ttl;

        Entry(String text, String audio, long at, long ttl) {
            this.text = text;
            this.audio = audio;
            this.at = at;
            this.ttl = ttl;
        }

        boolean expired(long now) {
            return ttl > 0 && now - at > ttl;
        }
    }

    /** 轮到她说的一句：正文和它的配音。 */
    public static final class Speech {
        public final String text;
        /** 配音地址；没有配音就是 {@code null}。 */
        public final String audio;

        Speech(String text, String audio) {
            this.text = text;
            this.audio = audio;
        }
    }

    /** 收下之后的结果，用来回给上游。 */
    public static final class Offer {
        /** 因为这次打断而被扔掉的条数。 */
        public final int dropped;
        /** 排队里现在有多少条（不含正在说的那句）。 */
        public final int queued;

        Offer(int dropped, int queued) {
            this.dropped = dropped;
            this.queued = queued;
        }
    }

    private final ArrayDeque<Entry> queue = new ArrayDeque<Entry>();

    /**
     * 收下一批要说的行。
     *
     * @param lines     已经切好的一行一个气泡
     * @param ttlMs     寿命毫秒，0 表示不过期
     * @param interrupt 是否打断：清空待说 + 让调用方作废正在说的那句
     * @param nowMs     现在
     */
    public Offer offer(List<String> lines, long ttlMs, boolean interrupt, long nowMs) {
        return offer(lines, null, ttlMs, interrupt, nowMs);
    }

    /**
     * 同上，另外带一段配音。
     *
     * <p>配音只会挂在这批的<b>第一行</b>上：一段 TTS 对应的是整次请求，不是其中某一行。
     * 真要用上配音时调用方会先把整段当成一句（不切行），所以这里不需要更复杂的分配。
     *
     * @param audioUrl 配音地址，{@code null} 表示这句没有配音
     */
    public Offer offer(List<String> lines, String audioUrl, long ttlMs, boolean interrupt, long nowMs) {
        int dropped = 0;
        if (interrupt) {
            dropped = queue.size();
            queue.clear();
        }
        boolean first = true;
        for (String line : lines) {
            if (line == null || Text.isBlank(line)) {
                continue;
            }
            queue.addLast(new Entry(Text.trim(line), first ? audioUrl : null, nowMs, ttlMs < 0 ? 0 : ttlMs));
            first = false;
        }
        return new Offer(dropped, queue.size());
    }

    /**
     * 取下一条该说的，顺手把已经过期的跳过。
     *
     * @return 下一条的正文和配音；队列空了（或者剩下的全过期了）返回 {@code null}
     */
    public Speech take(long nowMs) {
        while (!queue.isEmpty()) {
            Entry entry = queue.pollFirst();
            if (entry.expired(nowMs)) {
                continue;
            }
            return new Speech(entry.text, entry.audio);
        }
        return null;
    }

    /**
     * 同 {@link #take}，但只取正文。
     *
     * <p>它存在的理由是测试：那些断言关心的是顺序和过期，不需要配音。
     * 拿配音一律走 {@code take}。
     */
    public String next(long nowMs) {
        Speech speech = take(nowMs);
        return speech == null ? null : speech.text;
    }

    /** 还剩几条待说（不含已过期的，但不会为了数它们而丢掉）。 */
    public int size() {
        return queue.size();
    }

    /** 全部倒掉。用在「她已经不在了」这类场合。 */
    public void clear() {
        queue.clear();
    }

    /** 只为了测试和日志：把现在排队的正文列出来。 */
    public List<String> peek() {
        List<String> out = new ArrayList<String>(queue.size());
        for (Entry entry : queue) {
            out.add(entry.text);
        }
        return out;
    }
}

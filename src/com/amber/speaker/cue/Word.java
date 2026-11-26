package com.amber.speaker.cue;

/**
 * 一个动作词展开后的结果，对应桌宠 Body.cs 里的 Word record。
 *
 * <p>三种作用方式，一个词可以同时用其中几种：
 * <ul>
 *   <li><b>保持（hold）</b>：往某个通道上设参数，同通道后一个词顶替前一个。眼睛、眉毛、嘴、
 *       头、身子各有自己的通道，所以「皱眉」之后再「微笑」是同时成立的两件事。</li>
 *   <li><b>一次性动画（move）</b>：一条或多条关键帧轨道，播完自己回原样，不占通道。
 *       「点头」「歪一下头」都是这种。</li>
 *   <li><b>开关（overlay / hand / handDown）</b>：表情是开着的，手是举着的，都要显式关。</li>
 * </ul>
 *
 * <p>字段一律照抄 C# 原版语义，不做「优化」。动作幅度这些数字是作者在真模型上一个个调出来的，
 * 改一个数就会看起来不对劲，而这正是最难靠肉眼发现的那类错误。
 *
 * <p>这个类是可变 + 链式的：词表在静态初始化时构造一次，之后每次 {@code make()} 都新建实例，
 * 所以链式修改不会串味。用 Java 8 语法写（Android 5.1 只认 v1 签名，dex 也按 Java 8 编）。
 */
public final class Word {

    /** 保持型参数：设在通道上，一直生效到同通道下一个词。 */
    public static final class Param {
        public final String id;
        public final double value;

        public Param(String id, double value) {
            this.id = id;
            this.value = value;
        }
    }

    /** 一次性动画轨道。keys 是 "时间:值 时间:值 …" 的关键帧串，时间单位秒。 */
    public static final class Track {
        public final String id;
        public final String keys;

        public Track(String id, String keys) {
            this.id = id;
            this.keys = keys;
        }
    }

    String channel;
    Param[] hold;
    String overlay;
    String handOn;
    String handOff;
    boolean handDown;
    Track[] move;
    String[] clear;
    boolean reset;

    Word() {
    }

    public static Word w() {
        return new Word();
    }

    public static Param p(String id, double value) {
        return new Param(id, value);
    }

    public static Track t(String id, String keys) {
        return new Track(id, keys);
    }

    /** 往通道上摆一组参数。{@code channel} 是谁的通道：eyes / brows / mouth / tilt / turn / pitch / gaze / lean。 */
    public Word hold(String channel, Param... params) {
        this.channel = channel;
        this.hold = params;
        return this;
    }

    /** 打开/关闭一个表情。表情是叠加层，不占通道，所以能和任何姿势同时存在。 */
    public Word overlay(String name) {
        this.overlay = name;
        return this;
    }

    /** 举手的动作。on 是举起来的 motion 名，off 是放下的 motion 名。 */
    public Word hand(String on, String off) {
        this.handOn = on;
        this.handOff = off;
        return this;
    }

    /** 明确放下手。和「恢复」不同，它只动手，不碰别的。 */
    public Word handDown() {
        this.handDown = true;
        return this;
    }

    public Word move(Track... tracks) {
        this.move = tracks;
        return this;
    }

    /** 只清掉这些通道，别的不动。「正视」清头，「站直」清身子。 */
    public Word clear(String... channels) {
        this.clear = channels;
        return this;
    }

    /** 全部回到默认（「恢复」）。姿势、表情、手一起收。 */
    public Word reset() {
        this.reset = true;
        return this;
    }
}

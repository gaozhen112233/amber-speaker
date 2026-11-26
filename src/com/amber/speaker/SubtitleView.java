package com.amber.speaker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 台词气泡：奶油白底、发色描边、尾巴指向她，话音刚落就淡出。
 *
 * <p>样式照搬桌宠（大肥鱼）的 {@code Bubble.cs}，尺寸全部按字号的倍率来，
 * 这样音响的小屏和手机的大屏看起来是同一个东西：
 * <ul>
 *   <li>底 {@code #FFFBF4}（暖白，不是纯白——纯白压在黑背景上会发飘）</li>
 *   <li>边 {@code #506098}，就是她头发的蓝灰，粗 0.1 倍字号</li>
 *   <li>圆角 1.6 倍字号，内边距左右 1.2 / 0.93 倍，行高 1.47 倍</li>
 *   <li>字 {@code #323854}，深靛蓝</li>
 * </ul>
 *
 * <p>唯一和桌宠不一样的是位置：桌宠的气泡浮在她头顶，而我们是全屏角色，她头顶离屏幕上沿
 * 只有 10%，放不下。所以改到右侧，尾巴从气泡左下角伸出去指她——朝向和视频里一致。
 *
 * <p>排版有两件事必须做对：
 * <ul>
 *   <li><b>按整句排版、只逐字显示</b>。如果按已显示的前缀排版，气泡会随着出字一点点变宽、
 *       字还会往回跳——看起来像坏了。</li>
 *   <li><b>标点不能掉到行首</b>。中文排版里「，」打头是硬伤，断行时要把它拽回上一行。</li>
 * </ul>
 */
final class SubtitleView extends View {

    /** 桌宠的气泡配色。 */
    private static final int PAPER = 0xFFFFFBF4;
    private static final int EDGE = 0xFF506098;
    private static final int INK = 0xFF323854;

    /** 说完停多久再开始淡出。太短会让人觉得「我还没看完」，太长会挡住下一句。 */
    private static final float HOLD_MS = 1100f;
    private static final float FADE_IN_MS = 160f;
    private static final float FADE_OUT_MS = 260f;
    private static final int MAX_LINES = 6;

    /**
     * 左上角调试信息（「已收 N 〔〕全部认识」）的开关。默认关——正式画面不需要它。
     * 真机排障时临时设成 {@code true}，看是「没收到」还是「收到了没显示」。
     */
    private static final boolean DEBUG_HUD = false;

    /** 这些字符不能出现在行首，断行时让它们挂在上一行末尾。 */
    private static final String NO_LINE_START = "，。！？、；：）」』】…—～,.;:!?)]}";

    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meta = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path body = new Path();
    private final Path pointer = new Path();
    private final Path outline = new Path();
    private final RectF box = new RectF();
    private final List<int[]> spans = new ArrayList<int[]>();

    /** 整句（去掉〔〕的原文）。排版用它，显示只用前 {@link #shown} 个字。 */
    private String full = "";
    private int shown;
    private String cueLine = "";
    private int received;

    /** 0~1，淡入淡出用。 */
    private float alpha;
    private boolean live;
    private long dismissAt;

    /**
     * 左右镜像。琥珀音箱那块屏要开（和角色的镜像同步，见 {@link #onDraw}）；
     * 普通手机、投屏调试时关掉，否则读到的字是反的。
     */
    private boolean mirror = true;
    private long lastStep;

    /** 文字区的左上角与行高，排版时算好，画字时直接用。 */
    private float textLeft;
    /**
     * 气泡**右**内边（未翻转坐标）。镜像绘制时文字要从这里起排。
     *
     * <p>为什么不能用 textLeft：气泡被外层镜像翻到屏幕左边之后，未翻转坐标里的
     * {@code textLeft} 对应的屏幕位置是**气泡右侧**，文字会从右边开始往外溢出。
     * 只有 {@code width - textRight} 才落在气泡左内边上。
     */
    private float textRight;
    private float textTop;
    private float lineHeight;

    private final Runnable kick = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    SubtitleView(Context context) {
        super(context);
        // 底色交给底下那层 GL 和窗口背景，这里必须透明——否则一整块黑会把她的脸盖掉
        setBackgroundColor(0x00000000);

        Typeface face = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
        fill.setTypeface(face);
        fill.setColor(INK);
        fill.setTextAlign(Paint.Align.LEFT);

        bubble.setColor(PAPER);
        bubble.setStyle(Paint.Style.FILL);

        edge.setColor(EDGE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeJoin(Paint.Join.ROUND);

        hint.setTypeface(face);
        hint.setColor(0xFF7A7A7A);
        hint.setTextAlign(Paint.Align.CENTER);

        meta.setTypeface(Typeface.MONOSPACE);
        meta.setColor(0xFF336633);
        meta.setTextSize(16f);
    }

    // ---------------------------------------------------------------- 对外

    /**
     * 说一句。
     *
     * @param text  整句原文（不含〔〕），气泡按它排版
     * @param chars 已经显示到第几个字，逐字出现用
     */
    void say(String text, int chars) {
        boolean changed = false;
        if (!text.equals(full)) {
            full = text;
            changed = true;
        }
        int clamped = Math.max(0, Math.min(chars, text.length()));
        if (clamped != shown) {
            shown = clamped;
            changed = true;
        }
        if (!live) {
            live = true;
            changed = true;
        }
        // 只要还在往下说，就把淡出倒计时撤掉：下一句可能紧接着来
        dismissAt = 0;
        if (changed) {
            invalidate();
        }
    }

    /** 立刻显示整句（加载提示、错误信息这种不需要逐字出现）。 */
    void show(String text) {
        say(text, text.length());
    }

    /** 这一句说完了。停 {@link #HOLD_MS} 之后淡出。重复调用不会把倒计时往后推。 */
    void dismiss() {
        if (!live || dismissAt != 0) {
            return;
        }
        dismissAt = SystemClock.uptimeMillis() + (long) HOLD_MS;
        // 到点必须有人叫醒自己：这期间画面是静止的，不会有任何东西触发重绘
        postDelayed(kick, (long) HOLD_MS);
    }

    /** 立刻收起（被打断时用）。 */
    void clear() {
        full = "";
        shown = 0;
        live = false;
        dismissAt = 0;
        if (alpha > 0f) {
            invalidate();
            postDelayed(kick, (long) FADE_OUT_MS);
        }
    }

    void setCue(String cue) {
        if (!cue.equals(cueLine)) {
            cueLine = cue;
            invalidate();
        }
    }

    void setReceived(int count) {
        received = count;
        invalidate();
    }

    /** 左右镜像开关。改完立刻重画一帧，免得要等下一句话才生效。 */
    void setMirror(boolean on) {
        if (mirror == on) return;
        mirror = on;
        invalidate();
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        step();

        final int width = getWidth();
        final int height = getHeight();
        if (!mirror || width <= 0 || height <= 0) {
            paint(canvas, width, height);
            return;
        }
        // 字幕也跟着翻，且**只翻这一次**。屏上的链路是「内容 → 系统转 180° → 光路上下翻」，
        // 合成后对人眼等于一次左右镜像，角色那半边由 GL 的投影矩阵镜像（Native.setFlipX），
        // 字幕这半边只能自己翻：整块画布绕中心左右翻一次，位置和字形一起翻，
        // 翻完正好被后面的链路翻回来。
        //
        // 2026-11-22 踩过的坑：这里翻一次之后，paint() 里又逐字翻了一次，
        // 两次相乘等于没翻——画到 surface 上的字是正的，人眼再过一次光路就成了镜子里的字。
        // 当时用「截图旋转 180° 看图」验证，看到字正就以为对了，其实那个参照系里
        // 少算了一次光路翻转，所以越修越反。判据只能是**实体屏上的样子**。
        // 结论：位置和字形都由这一次翻转负责，paint() 里不许再翻任何东西。
        final int save = canvas.save();
        canvas.scale(-1f, 1f, width * 0.5f, height * 0.5f);
        paint(canvas, width, height);
        canvas.restoreToCount(save);
    }

    /** 真正的绘制。坐标系永远是「屏幕原样」，镜像在 {@link #onDraw} 外面套。 */
    private void paint(Canvas canvas, int width, int height) {

        final boolean showText = full.length() > 0 && alpha > 0.01f;

        // 气泡（含尾巴）先画：它的位置要跟着外层那次镜像走。不跟的话气泡会翻到
        // 人物背后去——真机上气泡本来就该在人物对面那半边。
        //
        // 一次画一条连续轮廓：底面和尾巴先并成一个形状，再统一描边。
        // 分开画会在尾巴根部留一道横线——桌宠那边也是这么合的（CombinedGeometry.Union）。
        if (showText) {
            layout(width, height);
            final int a = (int) (alpha * 255f);
            bubble.setAlpha(a);
            canvas.drawPath(outline, bubble);
            edge.setAlpha(a);
            canvas.drawPath(outline, edge);
        }

        // ★ 文字为什么不能跟着外层一起翻
        //
        // onDraw 里那次 canvas.scale(-1,1) 是为琥珀屏的装配方向服务的（和 GL 的
        // scaleX 取负同开同关）。整屏翻转对气泡是「挪位置」，对文字却是「照镜子」：
        // 气泡翻过去还在人物对面，字翻过来就全反了。
        //
        // 2026-11-22 真机第一次收到 /say 才暴露出来——在此之前字幕从没显示过，
        // 所以这个 bug 一直躺着。截图上是「气泡位置正确、字整个左右反」。
        //
        // 试过「整行以屏幕中心再翻一次」：字正了，但整行跑到屏幕另一边去了——
        // 以中心缩放会把 x 映射成 w-x，位置跟着变。所以只能逐字以自己中心翻，
        // 见 drawWords()。这里不再做整体翻转。

        // 左上角：调试信息。真机调的时候靠它判断「是收到没收到」还是「收到了没显示」
        if (DEBUG_HUD && (cueLine.length() > 0 || received > 0)) {
            canvas.drawText("已收 " + received + "  " + cueLine, 16, 24, meta);
        }

        if (full.length() == 0) {
            if (received == 0 && alpha <= 0.01f) {
                hint.setTextSize(Math.max(16f, Math.min(width, height) * 0.035f));
                hint.setAlpha(140);
                drawMirrored(canvas, "等她说话…", width * 0.82f, height * 0.3f, hint);
            }
        } else if (alpha > 0.01f) {
            fill.setAlpha((int) (alpha * 255f));
            drawWords(canvas, width);
        }
    }

    /**
     * 画一段文字。外层 {@link #onDraw} 已经整体翻过一次，所以这里**什么都不翻**：
     * 屏幕上（以及人眼）看到的字形正不正，由那一次整体翻转 + 光路共同决定，
     * 这里再翻一次就会变成镜子里的字。
     */
    private void drawMirrored(Canvas canvas, String text, float x, float baseline, Paint paint) {
        canvas.drawText(text, x, baseline, paint);
    }

    /** 把整句排好，算出气泡的矩形和尾巴。每帧都算一次——句子短、行数少，代价可以忽略。 */
    private void layout(int width, int height) {
        float shortSide = Math.min(width, height);
        float margin = shortSide * 0.035f;

        // 字号跟着屏幕走，不写死像素：音响是 480x800 左右的屏，手机是 1920x1080，
        // 写死的字号在其中一个上必然错。
        float size = Math.max(17f, Math.min(width * 0.055f, height * 0.045f));
        // 竖屏时她会占满宽度，气泡只能窄一点；横屏右侧那块空地宽得多
        float maxText = width * (width >= height ? 0.28f : 0.40f);

        fill.setTextSize(size);
        wrap(size, maxText);
        while (spans.size() > MAX_LINES && size > 17f) {
            size -= 2f;
            fill.setTextSize(size);
            wrap(size, maxText);
        }

        // 桌宠的比例：左右 1.2 / 0.93，上下 0.93，行高 1.47，圆角 1.6
        float padLeft = size * 1.2f;
        float padTop = size * 0.93f;
        float padOther = size * 0.93f;
        lineHeight = size * 1.47f;
        float radius = size * 1.6f;

        float widest = 0;
        for (int[] span : spans) {
            widest = Math.max(widest, fill.measureText(full, span[0], span[1]));
        }
        // 高度只按「已经露出来的行」算：整句预留的话，打字过程中下半截是空的，很难看。
        // 宽度仍然按整句算，这样气泡不会一边说一边横向抽动。
        int visible = 0;
        for (int[] span : spans) {
            if (span[0] < shown) {
                visible++;
            }
        }
        if (visible == 0) {
            visible = 1;
        }
        float boxWidth = Math.min(maxText + padLeft + padOther, widest + padLeft + padOther);
        float boxHeight = visible * lineHeight + padTop + padOther;

        float right = width - margin;
        float left = right - boxWidth;
        float top = height * 0.13f;
        if (top + boxHeight > height - margin) {
            top = Math.max(margin, height - margin - boxHeight);
        }
        float bottom = top + boxHeight;

        // 尾巴长在左边、朝左指她。垂直位置钉在第一行的中线：句子变长时它不会乱跑，
        // 而且永远对着她的脸那一侧。
        float tailBase = size * 1.0f;
        float tailOut = size * 0.7f;
        float tailY = top + padTop + lineHeight * 0.5f;

        box.set(left, top, right, bottom);
        body.reset();
        body.addRoundRect(box, radius, radius, Path.Direction.CW);

        // 底边压进气泡 2 像素，并集之后才不会有缝
        pointer.reset();
        pointer.moveTo(left + 2f, tailY - tailBase * 0.5f);
        pointer.lineTo(left + 2f, tailY + tailBase * 0.5f);
        pointer.lineTo(left - tailOut, tailY + tailBase * 0.15f);
        pointer.close();

        outline.reset();
        outline.op(body, pointer, Path.Op.UNION);

        edge.setStrokeWidth(Math.max(2f, size * 0.075f));

        textLeft = left + padLeft;
        textRight = right - padOther;
        textTop = top + padTop;
    }

    private void drawWords(Canvas canvas, int width) {
        float size = fill.getTextSize();
        // 整行一次画完，起点 textLeft，正常从左往右排。
        //
        // ★ 这里**绝不做任何位置修正或逐字翻转**。
        // onDraw 那次整屏 scale(-1,1) 是「整层统一翻一次」：位置、字形、字序一起翻。
        // 翻完之后由屏幕链路（系统 180° + 光路）再翻一次，正好回到正确。
        //
        // 2026-11-22 折腾一整轮的根因：这里曾经为了「截图上看着顺眼」做了两件事——
        // 从右内边 textRight 往回排、再逐字 scale(-1) 把字形掰正。
        // 那等于在整体翻转之外又加了一次「局部反向修正」，跟 onDraw 那次相抵消，
        // 屏幕上就成了「字形镜像 + 字序反」。截图转 180° 看是正的，实体屏却是反的。
        //
        // 结论：字幕层只允许有 onDraw 那一处翻转，paint/drawWords 里一律按未翻转的
        // 正常坐标系写——整行 drawText，从 textLeft 起，就这一句。
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            if (start >= shown) {
                break;
            }
            int end = Math.min(spans.get(i)[1], shown);
            if (end <= start) {
                break;
            }
            float baseline = textTop + size * 0.95f + lineHeight * i;
            canvas.drawText(full, start, end, textLeft, baseline, fill);
        }
    }

    /**
     * 按整句断行，记下每行的起止下标（相对于 {@link #full}）。
     *
     * <p>断行规则很朴素：宽度超了就断，但如果下一个字是标点，就把它一起带过来。
     */
    private void wrap(float size, float maxWidth) {
        spans.clear();
        int length = full.length();
        int start = 0;
        for (int i = 0; i < length; i++) {
            if (full.charAt(i) == '\n') {
                spans.add(new int[] { start, i });
                start = i + 1;
                continue;
            }
            if (i == start) {
                continue;
            }
            if (fill.measureText(full, start, i + 1) <= maxWidth) {
                continue;
            }
            // full[i] 放不下了：断在 i，但如果它是标点，就容它超出一点，别让它去行首
            int cut = i;
            if (NO_LINE_START.indexOf(full.charAt(i)) >= 0) {
                cut = Math.min(length, i + 1);
            }
            spans.add(new int[] { start, cut });
            start = cut;
            i = cut - 1;
        }
        if (start < length) {
            spans.add(new int[] { start, length });
        }
        if (spans.isEmpty()) {
            spans.add(new int[] { 0, 0 });
        }
    }

    /** 推一帧淡入淡出。 */
    private void step() {
        long now = SystemClock.uptimeMillis();
        float dt = lastStep == 0 ? 16f : Math.min(64f, now - lastStep);
        lastStep = now;

        boolean gone = !live || (dismissAt != 0 && now >= dismissAt);
        float target = gone ? 0f : 1f;
        if (alpha == target) {
            lastStep = 0;
            return;
        }
        float span = target > alpha ? FADE_IN_MS : FADE_OUT_MS;
        float delta = dt / span;
        alpha = target > alpha ? Math.min(target, alpha + delta) : Math.max(target, alpha - delta);
        postInvalidateOnAnimation();
    }
}

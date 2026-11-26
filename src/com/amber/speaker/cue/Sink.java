package com.amber.speaker.cue;

/**
 * 身体参数的出口。桌宠那边是一个 {@code Action<string>}，发的是一行行命令文本；
 * 这里保留同样的形状，原因有两个：
 *
 * <ol>
 *   <li>两边输出能直接 diff。移植对不对，跑同一句话比字符串就行，不用看图猜。</li>
 *   <li>GL 那边要做的只是把命令翻成 uniform / motion 调用，翻译层可以单独测。</li>
 * </ol>
 *
 * <p>命令格式（和桌宠一致，不要改）：
 * <pre>
 *   set   &lt;参数名&gt; &lt;值&gt;        设一个参数，值为 0 时也照发
 *   clear &lt;参数名&gt;              把这个参数交回默认
 *   exp   &lt;表情名&gt; 1|0          开/关一个表情
 *   anim  &lt;参数名&gt; &lt;关键帧串&gt;   放一条一次性动画轨道
 *   motion &lt;动作名&gt;             放一段模型自带的动作（举手、竖手指）
 * </pre>
 */
public interface Sink {

    void send(String command);

    /** 什么都不做的出口：单元测试和「只解析不显示」的时候用。 */
    Sink NONE = new Sink() {
        @Override
        public void send(String command) {
            // 故意为空
        }
    };
}

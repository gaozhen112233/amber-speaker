package com.amber.speaker.gl;

import android.content.res.AssetManager;

/**
 * 和原生渲染器之间唯一的一道门。
 *
 * <p>所有方法都必须在 GL 线程调用（也就是 {@link GlSurface} 的 onSurfaceCreated /
 * onSurfaceChanged / onDrawFrame 里），只有 {@link #command} 可以从别的线程进——
 * 它内部只是加锁入队，不碰 GL。
 *
 * <p>句柄为 0 表示模型没加载起来（读不到 moc3、贴图超过显卡上限之类），
 * 这时候调用别的入口全是空操作，不会崩。
 */
public final class Native {

    static {
        System.loadLibrary("ambergl");
    }

    private Native() {
    }

    /**
     * 只做 CPU 解析（moc3、物理、表情、动作），不碰 GL，可以放在后台线程跑。
     * 返回 0 表示读不到资源。
     */
    static native long prepare(AssetManager assets, String directory);

    /** 建纹理和渲染器。必须在 GL 线程、上下文 current 时调。 */
    static native boolean createGl(long handle, int width, int height);

    static native void resize(long handle, int width, int height);

    /**
     * 画面左右镜像。琥珀音箱的屏是「系统转 180° + 光路上下翻」，应用侧补一次左右翻正好抵消；
     * 普通手机上应当传 false。必须在 GL 线程调（和绘制同一个线程，不加锁）。
     */
    static native void setFlipX(long handle, boolean flip);

    static native void drawFrame(long handle);

    static native void command(long handle, String command);

    static native void destroy(long handle);
}

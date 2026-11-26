package com.amber.speaker.led;

/**
 * 舞台灯带（SN3218，18 路 PWM）的原生入口。
 *
 * <p>为什么一定要走 native：厂商驱动的 {@code write()} 把 count 当成 32 位**字数**用，
 * 18 条记录（144 字节）得传 {@code count=36}。Java 的 {@code FileOutputStream.write(b, off, len)}
 * 传的就是 len，没法让「传 36、但底层有 144 字节」——只有自己拿字节数组指针调 write 才行。
 *
 * <p>编解码细节写在 {@code jni/src/jni.cpp} 的「舞台灯带」那段，包括为什么传错 count 会让
 * 整机重启（踩过一次）。这里只保证：传进来的 {@code data} 必须真的有 144 字节。
 */
final class NativeLed {

    static {
        System.loadLibrary("ambergl");
    }

    private NativeLed() {
    }

    /** 打开 /dev/sn3218。返回 -1 表示设备节点不在（非音箱机型）。 */
    static native int open();

    /** 给灯带上电并使能 18 路。{@link #powerOff} 之后要重新亮起来时调它。 */
    static native void powerOn(int fd);

    /**
     * 一次灌 18 路亮度。
     *
     * @param fd    {@link #open()} 返回的句柄
     * @param data  必须是 144 字节：18 条 {u32 寄存器号; u8 亮度; 3 字节填充}
     * @param words {@code data} 的字数，也就是 36。别填 144。
     */
    static native int write(int fd, byte[] data, int words);

    /** 断电（芯片 shutdown + 关电源脚）。休眠熄屏时用，比全写 0 更省。 */
    static native void powerOff(int fd);

    static native void close(int fd);
}

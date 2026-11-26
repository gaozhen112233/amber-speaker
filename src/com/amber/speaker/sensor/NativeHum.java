package com.amber.speaker.sensor;

/**
 * 人体感应器（hum_sensor）独占读取的原生入口。
 *
 * <p>为什么走 native：要独占 {@code /dev/input/event5} 得调 {@code ioctl(fd, EVIOCGRAB, 1)}，
 * Android 没有公开的 ioctl API（{@code android.system.Os} 没暴露，{@code libcore.io.Posix}
 * 是隐藏 API 反射也不稳）。这里照 {@code NativeLed} 的模式，直接 native {@code ::ioctl}。
 *
 * <p>背景（2026-11-22 实测）：system_server 的 InputManager 会把 hum_sensor 注册成输入设备，
 * 它的 InputReader 持续读 event5，把真实硬件上报的 KEY_F6 事件抢走，导致应用侧 {@code read}
 * 永远等不到「有人靠近」——表象就是「走近唤醒没反应」。EVIOCGRAB 让本进程独占，别的 reader
 * 拿不到事件。
 */
final class NativeHum {

    static {
        System.loadLibrary("ambergl");
    }

    private NativeHum() {
    }

    /**
     * 独占一个已打开的 input 设备 fd。
     *
     * @param fd 由 {@code FileInputStream} 反射拿到的文件描述符整数
     * @return 0 成功，负数为 errno（独占失败时退回普通读，至少不崩）
     */
    static native int grab(int fd);
}

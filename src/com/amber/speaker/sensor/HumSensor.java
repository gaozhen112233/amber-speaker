package com.amber.speaker.sensor;

import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.FileDescriptor;
import java.lang.reflect.Field;

/**
 * 人体感应器（hum_sensor）读取。
 *
 * <p>这台机器的 PIR 人体感应器走 Linux input 子系统，设备节点 {@code /dev/input/event5}，
 * 名字 {@code hum_sensor}，上报一个按键（code 64，见下）：
 *
 * <pre>
 *   EV_KEY, KEY_F6, value=1   →  有人靠近（按下）
 *   EV_KEY, KEY_F6, value=0   →  没人（抬起）
 * </pre>
 *
 * <p>它是<b>边沿触发</b>：只在「有人/没人」切换那一刻上报，没人时不发事件。所以这里
 * 起一个后台线程阻塞读，读到一次状态翻转就回调一次 {@link #onPresence(boolean)}。
 *
 * <p>为什么直接读 {@code /dev/input/event5} 而不是走 {@code SensorManager}：
 * 这颗感应器不是标准 Android 传感器（没有进 HAL 的 sensors 列表，{@code /sys/class/sensors}
 * 是空的），拿不到 {@code Sensor.TYPE_PROXIMITY}。而它是 misc 类 input 设备，节点对
 * 应用可读（和灯带 {@code /dev/sn3218} 一样是 {@code 0666}），直接读最省事、也最稳。
 *
 * <p>只做「读事件」，不在这里做任何唤醒判断——判断（走近唤醒 / 在场搭话）是
 * {@code WakeState} 的事。这个类把硬件翻成「现在有没有人」一个布尔，回调出去。
 */
public final class HumSensor {

    private static final String TAG = "HumSensor";

    /** 设备节点。真机固定是 event5（看 {@code getevent -lp}，name 是 hum_sensor）。 */
    private static final String DEVICE = "/dev/input/event5";

    /**
     * 人体感应上报的键码。getevent -lp 显示的名字是 KEY_F6，但实抓到的 code 是
     * {@code 0x0040}=64（KEY_F6 标准值是 61，这颗驱动报的名字和 code 对不上）。
     * 以实测 code 为准 —— 硬编码 61 会一个事件都匹配不上，走近唤醒/在场搭话全程没反应。
     */
    private static final int KEY_HUM = 64;

    /** struct input_event 的字节数：timeval(8) + type(2) + code(2) + value(4) = 16。 */
    private static final int EVENT_SIZE = 16;

    /** 状态变化回调。near=true 有人，false 没人。 */
    public interface Listener {
        void onPresence(boolean near);
    }

    private final Listener listener;
    private Thread thread;
    private volatile boolean running;

    public HumSensor(Listener listener) {
        this.listener = listener;
    }

    /** 起后台线程开始监听。幂等：已启动就忽略。 */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "hum-sensor");
        thread.start();
    }

    /** 停掉后台线程。不可恢复（要再开就 new 一个）。 */
    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop() {
        FileInputStream in = null;
        try {
            in = new FileInputStream(DEVICE);
            // 2026-11-23：不再 EVIOCGRAB。Linux evdev 是「每个 fd 各自一份事件队列」，
            // system_server 的 InputReader 读 event5 **不会**把事件从我们的 fd 里抢走；
            // 之前把「没反应」归因于被抢，是误判（真因是键码 61→64 和 PIR 运动特性）。
            // 而 EVIOCGRAB 一旦成功，反而会让别的 reader（包括 getevent 诊断）全都收不到，
            // 真机排查时连「硬件到底有没有产生事件」都看不到。所以退回普通读。
            // 如果将来发现真有 reader 抢事件，再打开 grab(in)。
            byte[] buf = new byte[EVENT_SIZE];
            while (running) {
                int n = 0;
                while (n < EVENT_SIZE) {
                    int got = in.read(buf, n, EVENT_SIZE - n);
                    if (got < 0) {
                        return;  // 设备关了 / EOF
                    }
                    n += got;
                }
                // 小端：type 在 offset 8，code 在 offset 10，value 在 offset 12
                int type = buf[8] & 0xFF | (buf[9] & 0xFF) << 8;
                int code = buf[10] & 0xFF | (buf[11] & 0xFF) << 8;
                int value = buf[12] & 0xFF | (buf[13] & 0xFF) << 8
                        | (buf[14] & 0xFF) << 16 | (buf[15] & 0xFF) << 24;
                // 只关心人体感应键的按下/抬起；EV_SYN 和别的键忽略
                if (type == 1 && code == KEY_HUM) {
                    boolean near = value != 0;
                    Log.i(TAG, near ? "有人靠近" : "人离开");
                    if (listener != null) {
                        listener.onPresence(near);
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "人体感应器读不了（" + DEVICE + "）：" + e.getMessage()
                    + " —— 走近唤醒/在场搭话不可用，语音唤醒不受影响");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 关不掉就算了
                }
            }
        }
    }

    /**
     * 用 EVIOCGRAB ioctl 独占设备节点，防止 system_server 的 InputReader 抢事件。
     *
     * <p>根因（2026-11-22 实测）：system_server 的 InputManager 会把 hum_sensor 注册成输入
     * 设备（{@code dumpsys input} 里能看到 {@code Device 2: hum_sensor}），它的 InputReader
     * 持续读 event5，把真实硬件上报的 KEY_F6 事件抢走。结果是：应用侧 HumSensor 的
     * {@code read} 永远等不到真实事件（「走近唤醒没反应」），但 {@code sendevent} 注入却能
     * 收到——因为注入走内核 soft path 会广播给所有 reader，真实硬件事件却先被 InputReader
     * 消费。判据：/proc 下同时有 system_server 和本应用的 fd 指向 event5。
     *
     * <p>修法：EVIOCGRAB 让本进程独占读取，别的 reader（system_server）拿不到事件。
     * 反射拿 FileInputStream 的 fd 整数，交给 {@link NativeHum#grab} 走 native {@code ioctl}。
     * EVIOCGRAB = _IOW('E', 0x90, int) = 0x40044590，参数传 1 表示独占。
     */
    private void grab(FileInputStream in) {
        try {
            Field fdField = FileInputStream.class.getDeclaredField("fd");
            fdField.setAccessible(true);
            FileDescriptor fd = (FileDescriptor) fdField.get(in);
            // FileDescriptor 的 fd 整数存在私有字段 descriptor 里（getInt$ 是隐藏方法，编译期不可见，
            // 反射拿字段最稳）。拿到 int 后交给 native ioctl(EVIOCGRAB)。
            Field descField = FileDescriptor.class.getDeclaredField("descriptor");
            descField.setAccessible(true);
            int fdInt = descField.getInt(fd);
            int r = NativeHum.grab(fdInt);
            if (r == 0) {
                Log.i(TAG, "已独占 " + DEVICE + "（EVIOCGRAB）");
            } else {
                Log.w(TAG, "独占 " + DEVICE + " 返回 " + r + " —— 退回普通读");
            }
        } catch (Throwable t) {
            // 独占失败不致命：退回普通读（可能被 system_server 抢事件，但至少不崩）。
            Log.w(TAG, "独占 " + DEVICE + " 失败：" + t + " —— 退回普通读");
        }
    }
}

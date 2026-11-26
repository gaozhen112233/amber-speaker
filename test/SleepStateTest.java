import com.amber.speaker.SleepState;

import java.util.ArrayList;
import java.util.List;
import com.amber.speaker.fn.Suppliers.BooleanSupplier;
import com.amber.speaker.fn.Suppliers.IntSupplier;

/**
 * 定时熄屏休眠状态机的自测。纯 Java，PC 可跑。
 *
 * <p>验证「00:00–08:00 熄屏、其余亮屏、交互中强制亮屏」这条逻辑。
 */
public final class SleepStateTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        // 1. 不在休眠区间（白天）恒亮，不熄屏
        {
            final int[] mod = { 720 };  // 12:00
            final boolean[] busy = { false };
            final List<SleepState.Screen> seen = new ArrayList<SleepState.Screen>();
            SleepState s = new SleepState(
                    new IntSupplier() { @Override public int getAsInt() { return mod[0]; } },
                    new BooleanSupplier() { @Override public boolean getAsBoolean() { return busy[0]; } },
                    new SleepState.Listener() { @Override public void onScreen(SleepState.Screen x) { seen.add(x); } },
                    null);

            s.tick();
            eq("白天恒亮", SleepState.Screen.ON, s.screen());
            eq("白天无熄屏回调", 0, seen.size());
        }

        // 2. 进入休眠区间（凌晨）→ 熄屏
        {
            final int[] mod = { 60 };  // 01:00
            final boolean[] busy = { false };
            final List<SleepState.Screen> seen = new ArrayList<SleepState.Screen>();
            SleepState s = new SleepState(
                    new IntSupplier() { @Override public int getAsInt() { return mod[0]; } },
                    new BooleanSupplier() { @Override public boolean getAsBoolean() { return busy[0]; } },
                    new SleepState.Listener() { @Override public void onScreen(SleepState.Screen x) { seen.add(x); } },
                    null);

            s.tick();
            eq("凌晨熄屏", SleepState.Screen.OFF, s.screen());
            eq("熄屏回调一次", 1, seen.size());
            eq("回调是 OFF", SleepState.Screen.OFF, seen.get(0));
        }

        // 3. 熄屏中，语音唤醒词叫醒她（busy=true）→ 亮屏
        {
            final int[] mod = { 60 };  // 01:00
            final boolean[] busy = { false };
            final List<SleepState.Screen> seen = new ArrayList<SleepState.Screen>();
            SleepState s = new SleepState(
                    new IntSupplier() { @Override public int getAsInt() { return mod[0]; } },
                    new BooleanSupplier() { @Override public boolean getAsBoolean() { return busy[0]; } },
                    new SleepState.Listener() { @Override public void onScreen(SleepState.Screen x) { seen.add(x); } },
                    null);

            s.tick();                    // 熄屏
            busy[0] = true;              // 语音唤醒词叫醒，进入交互
            s.tick();
            eq("交互中亮屏", SleepState.Screen.ON, s.screen());
            eq("亮屏回调", SleepState.Screen.ON, seen.get(1));
        }

        // 4. 交互结束回待机，仍在休眠区间 → 重新熄屏
        {
            final int[] mod = { 60 };  // 01:00
            final boolean[] busy = { false };
            final List<SleepState.Screen> seen = new ArrayList<SleepState.Screen>();
            SleepState s = new SleepState(
                    new IntSupplier() { @Override public int getAsInt() { return mod[0]; } },
                    new BooleanSupplier() { @Override public boolean getAsBoolean() { return busy[0]; } },
                    new SleepState.Listener() { @Override public void onScreen(SleepState.Screen x) { seen.add(x); } },
                    null);

            s.tick();
            busy[0] = true;  s.tick();   // 亮屏
            busy[0] = false; s.tick();   // 回待机，重新熄屏
            eq("交互结束重新熄屏", SleepState.Screen.OFF, s.screen());
            eq("回调序列 OFF,ON,OFF", 3, seen.size());
            eq("最后是 OFF", SleepState.Screen.OFF, seen.get(2));
        }

        // 5. 到 08:00 边界（不含 08:00）→ 亮屏；07:59 仍熄屏
        {
            final int[] mod = { 479 };  // 07:59，仍在区间内
            final boolean[] busy = { false };
            SleepState s = new SleepState(
                    new IntSupplier() { @Override public int getAsInt() { return mod[0]; } },
                    new BooleanSupplier() { @Override public boolean getAsBoolean() { return busy[0]; } },
                    null, null);
            s.tick();
            eq("07:59 仍熄屏", SleepState.Screen.OFF, s.screen());

            mod[0] = 480;               // 08:00，区间外
            s.tick();
            eq("08:00 亮屏", SleepState.Screen.ON, s.screen());
        }

        // 6. 没接墙钟（minuteOfDay=null）恒亮
        {
            SleepState s = new SleepState(null,
                    new BooleanSupplier() { @Override public boolean getAsBoolean() { return false; } },
                    null, null);
            s.tick();
            eq("无墙钟恒亮", SleepState.Screen.ON, s.screen());
        }

        // 6b. 墙钟未知（-1）：RTC 无电池，开机停在 1977 年 —— 宁可常亮也不能误熄屏。
        //     这是真机事故的回归用例：用着用着灯灭了，屏幕也黑了。
        {
            final int[] mod = { -1 };
            final boolean[] busy = { false };
            final List<SleepState.Screen> seen = new ArrayList<SleepState.Screen>();
            SleepState s = new SleepState(
                    new IntSupplier() { @Override public int getAsInt() { return mod[0]; } },
                    new BooleanSupplier() { @Override public boolean getAsBoolean() { return busy[0]; } },
                    new SleepState.Listener() { @Override public void onScreen(SleepState.Screen x) { seen.add(x); } },
                    null);
            s.tick();
            s.tick();
            eq("墙钟未知：不熄屏", SleepState.Screen.ON, s.screen());
            eq("墙钟未知：不打回调", 0, seen.size());

            // 时间校正过来了（比如 /clock 推了真实时间），凌晨 3 点该熄屏就熄屏
            mod[0] = 180;
            s.tick();
            eq("时间校正后回到自动逻辑", SleepState.Screen.OFF, s.screen());
        }

        // 7. 幂等：状态不变时不重复回调
        {
            final int[] mod = { 60 };
            final boolean[] busy = { false };
            final List<SleepState.Screen> seen = new ArrayList<SleepState.Screen>();
            SleepState s = new SleepState(
                    new IntSupplier() { @Override public int getAsInt() { return mod[0]; } },
                    new BooleanSupplier() { @Override public boolean getAsBoolean() { return busy[0]; } },
                    new SleepState.Listener() { @Override public void onScreen(SleepState.Screen x) { seen.add(x); } },
                    null);
            s.tick();
            s.tick();
            s.tick();
            eq("熄屏状态稳定，只回调一次", 1, seen.size());
        }

        System.out.println();
        System.out.println("通过 " + passed + "  失败 " + failed);
        for (String f : failures) {
            System.out.println("  ✗ " + f);
        }
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            failures.add(name + "：期望 " + expected + "，实际 " + actual);
        }
    }
}

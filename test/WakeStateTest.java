import com.amber.speaker.WakeState;

import java.util.ArrayList;
import java.util.List;
import com.amber.speaker.fn.Suppliers.BooleanSupplier;
import com.amber.speaker.fn.Suppliers.IntSupplier;
import com.amber.speaker.fn.Suppliers.LongSupplier;

/**
 * 唤醒应答状态机的自测。纯 Java（WakeState 不依赖 android.*），所以能在 PC 上跑。
 *
 * <p>用注入的假时钟精确控制时间流逝，验证几条硬约束：
 * <ol>
 *   <li>说话中拒唤醒（防自激）</li>
 *   <li>同一个唤醒词 3 秒冷却</li>
 *   <li>连续对话：一次唤醒、多轮，大模型回复完回 LISTENING 而不是 IDLE</li>
 *   <li>15 秒静音超时结束本轮</li>
 *   <li>说「再见」结束本轮</li>
 *   <li>各相位超时兜底（唤醒后没人应 / 收音没人说话）</li>
 * </ol>
 */
public final class WakeStateTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<String>();

    /** 假时钟：测试里手动拨。 */
    private static final class FakeClock implements LongSupplier {
        long now;
        @Override public long getAsLong() { return now; }
    }

    public static void main(String[] args) {
        // 1. 连续对话完整流转：一次唤醒、两轮问答、告别结束
        {
            FakeClock clock = new FakeClock();
            final List<String> woke = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    woke.add(keyword);
                }
            }, clock, null);

            eq("初始 idle", "idle", w.phaseName());

            // 唤醒
            ok("onWake 返回 true", w.onWake("大肥鱼"));
            eq("唤醒后 waking", "waking", w.phaseName());
            eq("回调收到关键词", 1, woke.size());
            eq("关键词正确", "大肥鱼", woke.get(0));

            // 应一声（唤醒应答）→ listening
            w.onSpeakingStart();
            eq("应答 speaking", "speaking", w.phaseName());
            w.onSpeakingEnd();
            eq("应答说完 listening（等用户）", "listening", w.phaseName());

            // 第 1 轮：用户说话 → 大模型回复 → 回 listening（连续对话，不回 idle）
            w.onSpeakingStart();
            eq("第 1 轮回复 speaking", "speaking", w.phaseName());
            w.onSpeakingEnd();
            eq("第 1 轮回复完回 listening（连续对话）", "listening", w.phaseName());

            // 第 2 轮：继续说话 → 回复 → 仍回 listening
            w.onSpeakingStart();
            eq("第 2 轮回复 speaking", "speaking", w.phaseName());
            w.onSpeakingEnd();
            eq("第 2 轮回复完仍 listening", "listening", w.phaseName());

            // 说再见 → 结束
            w.onFarewell();
            eq("告别后 idle", "idle", w.phaseName());
        }

        // 2. 说话中拒唤醒（防自激）
        {
            FakeClock clock = new FakeClock();
            final List<String> woke = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) { woke.add(keyword); }
            }, clock, null);

            w.onWake("大肥鱼");
            w.onSpeakingStart();
            clock.now = 5000; // 冷却期早就过了，但正在说话
            ok("说话中 onWake 返回 false", !w.onWake("大肥鱼"));
            eq("说话中不触发回调", 1, woke.size());
            eq("相位仍 speaking", "speaking", w.phaseName());
        }

        // 3. 冷却：3 秒内同一唤醒词不重复触发
        {
            FakeClock clock = new FakeClock();
            final List<String> woke = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) { woke.add(keyword); }
            }, clock, null);

            w.onWake("大肥鱼");
            w.onSpeakingStart();
            w.onSpeakingEnd();
            w.onFarewell(); // 结束本轮，回 idle

            clock.now = 1000; // 距上次唤醒才 1 秒
            ok("冷却期内 onWake 返回 false", !w.onWake("大肥鱼"));
            eq("冷却期不触发回调", 1, woke.size());

            clock.now = 3500; // 超过 3 秒
            ok("冷却过后 onWake 返回 true", w.onWake("大肥鱼"));
            eq("冷却过后触发回调", 2, woke.size());
        }

        // 4. 超时兜底：WAKING 一直没人应
        {
            FakeClock clock = new FakeClock();
            WakeState w = new WakeState(null, clock, null);
            w.onWake("大肥鱼");
            eq("唤醒后 waking", "waking", w.phaseName());
            clock.now = 4999;
            w.tick();
            eq("waking 4.9s 还没超时", "waking", w.phaseName());
            clock.now = 5000;
            w.tick();
            eq("waking 5s 超时回 idle", "idle", w.phaseName());
        }

        // 5. 连续对话超时：LISTENING 15 秒没新对话就结束
        {
            FakeClock clock = new FakeClock();
            WakeState w = new WakeState(null, clock, null);
            w.onWake("大肥鱼");
            w.onSpeakingStart();
            w.onSpeakingEnd(); // 进 listening
            eq("进入 listening", "listening", w.phaseName());
            clock.now = 14999;
            w.tick();
            eq("listening 14.99s 还没超时", "listening", w.phaseName());
            clock.now = 15000;
            w.tick();
            eq("listening 15s 超时回 idle", "idle", w.phaseName());
        }

        // 6. SPEAKING 超时兜底（说话卡住）
        {
            FakeClock clock = new FakeClock();
            WakeState w = new WakeState(null, clock, null);
            w.onWake("大肥鱼");
            w.onSpeakingStart();
            clock.now = 30000;
            w.tick();
            eq("speaking 30s 超时回 idle", "idle", w.phaseName());
        }

        // 7. reset 从任意相位直接回 idle
        {
            FakeClock clock = new FakeClock();
            WakeState w = new WakeState(null, clock, null);
            w.onWake("大肥鱼");
            w.onSpeakingStart();
            eq("reset 前 speaking", "speaking", w.phaseName());
            w.reset();
            eq("reset 后 idle", "idle", w.phaseName());
        }

        // 8. 大模型回复完回 listening，不是 idle（连续对话核心语义）
        {
            FakeClock clock = new FakeClock();
            WakeState w = new WakeState(null, clock, null);
            w.onWake("大肥鱼");
            w.onSpeakingStart(); // 唤醒应答
            w.onSpeakingEnd();   // → listening
            w.onSpeakingStart(); // 大模型回复
            w.onSpeakingEnd();   // → 仍 listening（连续对话）
            eq("大模型回复后是 listening 不是 idle", "listening", w.phaseName());
        }

        // 9. idle 下 tick 无害
        {
            FakeClock clock = new FakeClock();
            WakeState w = new WakeState(null, clock, null);
            clock.now = 999999;
            w.tick();
            eq("idle 下 tick 保持 idle", "idle", w.phaseName());
        }

        // 10. 无 listener 也能跑（null 安全）
        {
            FakeClock clock = new FakeClock();
            WakeState w = new WakeState(null, clock, null);
            ok("无 listener onWake 正常", w.onWake("大肥鱼"));
            eq("无 listener 相位正常", "waking", w.phaseName());
        }

        // 11. 告别词判断
        ok("「再见」是告别", WakeState.isFarewell("再见"));
        ok("「拜拜」是告别", WakeState.isFarewell("拜拜"));
        ok("「晚安」是告别", WakeState.isFarewell("晚安"));
        ok("「再见！」带标点也是告别", WakeState.isFarewell("再见！"));
        ok("「再见啦今天过得不错」不是告别", !WakeState.isFarewell("再见啦今天过得不错"));
        ok("「今天好累啊」不是告别", !WakeState.isFarewell("今天好累啊"));
        ok("空串不是告别", !WakeState.isFarewell(""));
        ok("null 不是告别", !WakeState.isFarewell(null));

        // 12. 告别词在 listening 阶段生效
        {
            FakeClock clock = new FakeClock();
            WakeState w = new WakeState(null, clock, null);
            w.onWake("大肥鱼");
            w.onSpeakingStart();
            w.onSpeakingEnd(); // → listening
            w.onFarewell();
            eq("listening 阶段告别回 idle", "idle", w.phaseName());
        }

        // 13. 唤醒来源区分：proximity 也会触发唤醒，且回调带上 source
        {
            FakeClock clock = new FakeClock();
            final List<String> sources = new ArrayList<String>();
            final List<String> keywords = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    sources.add(source);
                    keywords.add(keyword);
                }
            }, clock, null);

            // 语音唤醒（默认）
            w.onWake("大肥鱼");
            eq("默认 source 是 voice", "voice", sources.get(0));
            eq("voice 关键词正确", "大肥鱼", keywords.get(0));

            // 走近唤醒（人体感应）
            w.onSpeakingStart();
            w.onSpeakingEnd();
            w.onFarewell(); // 结束，避免冷却干扰
            clock.now = 4000; // 过冷却期
            w.onWake("", "proximity");
            eq("proximity source 传对", "proximity", sources.get(1));

            // button 唤醒
            w.onSpeakingStart();
            w.onSpeakingEnd();
            w.onFarewell();
            clock.now = 8000;
            w.onWake("", "button");
            eq("button source 传对", "button", sources.get(2));
        }

        // 14. 走近唤醒也能走完整连续对话链路（source 不影响相位流转）
        {
            FakeClock clock = new FakeClock();
            WakeState w = new WakeState(null, clock, null);
            ok("proximity 唤醒成功", w.onWake("", "proximity"));
            eq("proximity 唤醒后 waking", "waking", w.phaseName());
            w.onSpeakingStart();
            w.onSpeakingEnd();
            eq("proximity 应答完 listening", "listening", w.phaseName());
            w.onFarewell();
            eq("proximity 告别后 idle", "idle", w.phaseName());
        }

        // 15. 在场停留搭话：持续 near 超过阈值，idle 下触发 presence 唤醒
        {
            FakeClock clock = new FakeClock();
            final List<String> sources = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    sources.add(source);
                }
            }, clock, null);

            clock.now = 0;
            w.onPresence(true);          // 有人来了，开始计时
            eq("在场未超时前 idle", "idle", w.phaseName());

            clock.now = 200000;
            w.tick(200000);              // 200 秒，还没到 300 秒阈值
            eq("200 秒未触发搭话", 0, sources.size());

            clock.now = 300000;
            w.tick(300000);              // 到 300 秒阈值
            eq("300 秒触发 presence 搭话", 1, sources.size());
            eq("搭话 source 是 presence", "presence", sources.get(0));
            eq("搭话后进 waking", "waking", w.phaseName());

            // 搭话结束回 listening，然后 idle（这里直接告别收尾）
            w.onSpeakingStart();
            w.onSpeakingEnd();
            w.onFarewell();
            eq("搭话结束回 idle", "idle", w.phaseName());
        }

        // 16. 在场搭话的长冷却：触发一次后 50 分钟内不再搭话
        {
            FakeClock clock = new FakeClock();
            final List<String> sources = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    sources.add(source);
                }
            }, clock, null);

            clock.now = 0;
            w.onPresence(true);
            clock.now = 300000;
            w.tick(300000);              // 第一次触发
            eq("第一次搭话", 1, sources.size());
            w.onSpeakingStart();
            w.onSpeakingEnd();
            w.onFarewell();

            // 继续在场，再过 300 秒（累计 600 秒），但还在 50 分钟冷却内
            clock.now = 600000;
            w.tick(600000);
            eq("冷却期内不重复搭话", 1, sources.size());

            // 过了 50 分钟冷却（3000000ms），再停留 300 秒可再次搭话
            clock.now = 3300000;
            w.tick(3300000);
            eq("冷却过后可再次搭话", 2, sources.size());
        }

        // 17. 离场重置：人走了（稳定信号 near=false），计时立即清零，回来重新算停留时长
        {
            FakeClock clock = new FakeClock();
            final List<String> sources = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    sources.add(source);
                }
            }, clock, null);

            clock.now = 0;
            w.onPresence(true);
            clock.now = 299000;
            w.tick(299000);              // 差 1 秒到 300 秒阈值
            w.onPresence(false);          // 人走了（稳定信号），立即清计时
            clock.now = 299001;
            w.onPresence(true);           // 又回来，重新计时
            eq("离场后回来重新计时，未触发", 0, sources.size());

            clock.now = 599001;           // 回来后满 300 秒
            w.tick(599001);
            eq("回来停留满 300 秒才搭话", 1, sources.size());
        }

        // 17b. onPresence(true) 只在 absent→near 上升沿返回 true，持续在场重复喂不重复触发
        {
            FakeClock clock = new FakeClock();
            final List<String> sources = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    sources.add(source);
                }
            }, clock, null);

            clock.now = 0;
            boolean first = w.onPresence(true);       // 第一次出现：上升沿
            eq("第一次在场是上升沿", true, first);

            // 持续在场期间重复喂 true（PresenceWatch 周期确认到人），应都返回 false（非边沿）
            for (int t = 3000; t <= 300000; t += 3000) {
                clock.now = t;
                boolean edge = w.onPresence(true);
                eq("持续在场重复喂不是上升沿", false, edge);
                w.tick(t);
            }
            // 300 秒到，持续在场，presence 搭话应触发一次
            eq("持续在场 300 秒触发一次 presence 搭话", 1, sources.size());
            eq("搭话来源是 presence", "presence", sources.get(0));
        }

        // 18. 在场搭话只发生在 idle：她正在说话时，不插嘴
        {
            FakeClock clock = new FakeClock();
            final List<String> presenceSources = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    if ("presence".equals(source)) {
                        presenceSources.add(source);
                    }
                }
            }, clock, null);

            clock.now = 0;
            w.onPresence(true);
            // 先进入 speaking（比如一次语音唤醒在说话）
            w.onWake("大肥鱼");
            w.onSpeakingStart();
            eq("正在 speaking", "speaking", w.phaseName());

            clock.now = 300000;
            w.tick(300000);              // 停留超时了，但她在说话
            eq("speaking 时不插嘴搭话", 0, presenceSources.size());
        }

        // 19. 时段限制：深夜（不在 10:00–23:00）不搭话，到了白天才搭话
        {
            FakeClock clock = new FakeClock();
            final int[] mod = { 720 };   // 可变的「当天第几分钟」；720 = 12:00（窗口内）
            final List<String> sources = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    sources.add(source);
                }
            }, clock, null, new IntSupplier() {
                @Override public int getAsInt() { return mod[0]; }
            });

            clock.now = 0;
            w.onPresence(true);
            clock.now = 300000;
            w.tick(300000);              // 12:00，在窗口内（10–23），应触发
            eq("12 点在窗口内，触发搭话", 1, sources.size());
            w.onSpeakingStart();
            w.onSpeakingEnd();
            w.onFarewell();

            // 冷却跨到深夜：把分钟拨到凌晨 3 点（180），且已经过了冷却
            mod[0] = 180;
            clock.now = 4000000;
            w.tick(4000000);             // 停留超阈值、已过冷却，但凌晨 3 点不在窗口
            eq("凌晨 3 点不搭话", 1, sources.size());

            // 墙钟未知（-1，RTC 无电池开机停在 1977 年）：判断不了几点，一律不主动搭话
            mod[0] = -1;
            clock.now = 8000000;
            w.tick(8000000);
            eq("墙钟未知：不搭话", 1, sources.size());
        }

        // 19b. 墙钟未知时，定时播报整个停掉 —— 否则「早上好呀主人」可能在半夜开口
        {
            FakeClock clock = new FakeClock();
            final int[] mod = { -1 };
            final List<String> said = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    said.add(keyword);
                }
            }, clock, null, new IntSupplier() {
                @Override public int getAsInt() { return mod[0]; }
            });
            clock.now = 0;
            w.tick(0);
            clock.now = 1000;
            w.tick(1000);
            eq("墙钟未知：不播报（哪怕分钟值看起来像 08:40）", 0, said.size());

            // 时间同步过来后，该播就播（说明 lastScheduleMinute 没被 -1 污染）
            mod[0] = 8 * 60 + 40;
            clock.now = 2000;
            w.tick(2000);
            eq("时间校正后正常播报", 1, said.size());
        }

        // 20. 时段限制：不在窗口时重计计时，一到窗口不会立刻爆发
        {
            FakeClock clock = new FakeClock();
            final int[] mod = { 120 };   // 120 = 凌晨 2:00（窗口外）
            final List<String> sources = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    sources.add(source);
                }
            }, clock, null, new IntSupplier() {
                @Override public int getAsInt() { return mod[0]; }
            });

            clock.now = 0;
            w.onPresence(true);
            clock.now = 300000;
            w.tick(300000);              // 凌晨 2 点，不在窗口，不触发且重计
            eq("凌晨 2 点不搭话", 0, sources.size());

            mod[0] = 600;                 // 到 10:00 进入窗口
            clock.now = 310000;           // 重计后只过了 10 秒，还没满 300 秒
            w.tick(310000);
            eq("进窗口后仍需重新停留满 300 秒", 0, sources.size());

            clock.now = 610000;           // 从 10 点起又停留满 300 秒
            w.tick(610000);
            eq("进窗口后停留满 300 秒才搭话", 1, sources.size());
        }

        // 21. 定时播报：08:40 播「早上好呀主人」，每天该时刻只播一次
        {
            FakeClock clock = new FakeClock();
            final int[] mod = { 520 };   // 520 = 08:40
            final List<String> words = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    if ("schedule".equals(source)) {
                        words.add(keyword);
                    }
                }
            }, clock, null, new IntSupplier() {
                @Override public int getAsInt() { return mod[0]; }
            });

            clock.now = 0;
            w.tick(0);
            eq("08:40 播早上好", 1, words.size());
            eq("播报台词是早上好呀主人", "早上好呀主人", words.get(0));

            // 同一分钟里多次 tick，不重复播
            w.onSpeakingStart();
            w.onSpeakingEnd();
            w.onFarewell();
            w.tick(1);
            w.tick(2);
            eq("同一时刻只播一次", 1, words.size());
        }

        // 22b. 定时播报：23:59 播「好困啊~我去睡了~晚安主人」，每天该时刻只播一次
        {
            FakeClock clock = new FakeClock();
            final int[] mod = { 1439 };  // 1439 = 23:59
            final List<String> words = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    if ("schedule".equals(source)) {
                        words.add(keyword);
                    }
                }
            }, clock, null, new IntSupplier() {
                @Override public int getAsInt() { return mod[0]; }
            });

            clock.now = 0;
            w.tick(0);
            eq("23:59 播晚安", 1, words.size());
            eq("播报台词是好困啊~我去睡了~晚安主人", "好困啊~我去睡了~晚安主人", words.get(0));

            // 同一分钟里多次 tick，不重复播
            w.onSpeakingStart();
            w.onSpeakingEnd();
            w.onFarewell();
            w.tick(1);
            w.tick(2);
            eq("23:59 同一时刻只播一次", 1, words.size());
        }

        // 23. 熄屏休眠时人体感应关闭：proximity 唤醒被拦，语音唤醒词仍有效
        {
            FakeClock clock = new FakeClock();
            final boolean[] sensorOn = { false };   // 熄屏中，人体感应关闭
            final List<String> woke = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    woke.add(source);
                }
            }, clock, null, null, new BooleanSupplier() {
                @Override public boolean getAsBoolean() { return sensorOn[0]; }
            });

            // 熄屏时走近唤醒（proximity）→ 拦
            boolean p = w.onWake("", WakeState.SOURCE_PROXIMITY);
            eq("熄屏时 proximity 唤醒被拦", false, p);
            eq("proximity 未触发", 0, woke.size());

            // 熄屏时语音唤醒词 → 仍有效
            boolean v = w.onWake("大肥鱼", WakeState.SOURCE_VOICE);
            eq("熄屏时语音唤醒词有效", true, v);
            eq("voice 触发", 1, woke.size());
            eq("触发来源是 voice", "voice", woke.get(0));
        }

        // 24. 熄屏时在场搭话（presence）也不触发；亮屏后恢复
        {
            FakeClock clock = new FakeClock();
            final boolean[] sensorOn = { false };
            final List<String> woke = new ArrayList<String>();
            WakeState w = new WakeState(new WakeState.Listener() {
                @Override public void onAwake(String keyword, String source, long nowMs) {
                    woke.add(source);
                }
            }, clock, null, null, new BooleanSupplier() {
                @Override public boolean getAsBoolean() { return sensorOn[0]; }
            });

            clock.now = 0;
            w.onPresence(true);
            clock.now = 300000;
            w.tick(300000);              // 停留满 300 秒，但熄屏中人体感应关
            eq("熄屏时 presence 不搭话", 0, woke.size());

            sensorOn[0] = true;           // 亮屏，人体感应恢复
            clock.now = 600000;
            w.tick(600000);              // 再停留满 300 秒
            eq("亮屏后 presence 恢复搭话", 1, woke.size());
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
            failures.add(name + "：期望 [" + expected + "] 实际 [" + actual + "]");
        }
    }

    private static void ok(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name);
        }
    }
}

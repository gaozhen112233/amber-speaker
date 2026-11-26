package com.amber.speaker;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.amber.speaker.cue.Body;
import com.amber.speaker.cue.Sink;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 待机小剧场：把桌宠 {@code skits.txt} 那套「名侦探 / 认真加班 / 偷吃零食 /
 * 突然入戏 / 魔法少女 / 学习打瞌睡」搬到音箱上，<b>只做动作、不出声</b>。
 *
 * <p>为什么只做动作：半夜对着空气说话很吓人（契约 §8.6），但白天有人在场时，
 * 戴单片眼镜、皱眉、看左边、竖手指这些动作是无声的，看起来就是「她在自己忙事情」。
 * 台词先不显示，只用来给日志/以后做字幕留位置。
 *
 * <p>道具走 {@code exp} 命令：模型自带「单片眼镜 / 黑框眼镜 / 钢盆 / 兔牙」等表情，
 * 演的时候开、演完关；不经过 TTS，不占 token，也不依赖 AstrBot。
 *
 * <p>触发条件：idle + 有人在场 + 10:00–23:00 + 不在说话/排队，每 90~180 秒随机演一段。
 * 说话、唤醒、按键会立刻 {@link #stop()}，不会和正式对话抢动作。
 */
public final class SkitPlayer {

    private static final String TAG = "AmberSkit";

    /** 外部闸门：现在能不能自己演。由 MainActivity 根据相位/在场/屏幕/时段判断。 */
    public interface Gate {
        boolean canPlay();
    }

    private static final class Step {
        final String text;
        final String actions;
        final double seconds;

        Step(String text, String actions, double seconds) {
            this.text = text;
            this.actions = actions;
            this.seconds = seconds;
        }
    }

    private static final class Skit {
        final String name;
        final List<String> props = new ArrayList<String>();
        final List<Step> steps = new ArrayList<Step>();

        Skit(String name) {
            this.name = name;
        }
    }

    /** 两段小剧场之间至少隔这么久；太长像随机事件，太短会像卡住循环。 */
    private static final long MIN_GAP_MS = 90000;
    private static final long MAX_GAP_MS = 180000;

    private final Context app;
    private final Body body;
    private final Sink sink;
    private final Gate gate;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<Skit> skits = new ArrayList<Skit>();

    private Skit current;
    private int stepIndex;
    private final List<String> appliedProps = new ArrayList<String>();
    private long nextAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            runNextStep();
        }
    };

    public SkitPlayer(Context context, Body body, Sink sink, Gate gate) {
        this.app = context.getApplicationContext();
        this.body = body;
        this.sink = sink;
        this.gate = gate;
        load();
        nextAt = SystemClock.elapsedRealtime() + MIN_GAP_MS;
    }

    /** 读 assets/skits.txt。格式见桌宠原文件：{@code # 名字}、{@code @道具}、{@code 台词|动作|秒数}。 */
    private void load() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(app.getAssets().open("skits.txt"), "UTF-8"));
            Skit skit = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("//")) {
                    continue;
                }
                if (line.startsWith("#")) {
                    String name = line.substring(1).trim();
                    // 带 @条件 的是彩蛋（生日/跨年/亲密度/深夜催睡），需要外部条件才该演；
                    // 音箱端还没有羁绊/节日系统，先整段跳过，别在普通下午随机演「生日快乐」。
                    if (name.contains("@")) {
                        skit = null;
                        continue;
                    }
                    skit = new Skit(name);
                    skits.add(skit);
                    continue;
                }
                if (skit == null) {
                    continue;
                }
                if (line.startsWith("@道具")) {
                    for (String prop : line.substring(3).trim().split("\\s+")) {
                        if (prop.length() > 0) {
                            skit.props.add(prop);
                        }
                    }
                    continue;
                }
                // 桌宠脚本里有全角分隔符「｜」，统一成半角再切
                line = line.replace('｜', '|');
                String[] parts = line.split("\\|");
                String text = parts.length > 0 ? parts[0] : "";
                String actions = parts.length > 1 ? parts[1] : "";
                double seconds = 3.5;
                if (parts.length > 2) {
                    try {
                        seconds = Double.parseDouble(parts[2].trim());
                    } catch (NumberFormatException ignored) {
                        // 秒数写错就用默认值，不能让一条脚本把整段废掉
                    }
                }
                if (actions.length() > 0) {
                    skit.steps.add(new Step(text, actions, Math.max(0.5, Math.min(10.0, seconds))));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "读 skits.txt 失败：" + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // 关不掉无所谓
                }
            }
        }
        Log.i(TAG, "小剧场脚本：" + skits.size() + " 段");
    }

    /** 心跳里调；满足条件就随机开一段。 */
    public void maybeStart() {
        if (current != null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < nextAt) {
            return;
        }
        if (gate != null && !gate.canPlay()) {
            // 条件不满足就 20 秒后再看，别每 20 秒心跳都白算
            nextAt = now + 20000;
            return;
        }
        if (skits.isEmpty()) {
            return;
        }
        nextAt = now + MIN_GAP_MS + random.nextInt((int) (MAX_GAP_MS - MIN_GAP_MS));
        start(skits.get(random.nextInt(skits.size())));
    }

    /** 诊断/控制台用：按名字直接演一段（不受随机间隔限制）。 */
    public void play(String name) {
        for (Skit skit : skits) {
            if (skit.name.equals(name)) {
                stop();
                start(skit);
                return;
            }
        }
        Log.w(TAG, "没有这个小剧场：" + name);
    }

    public boolean running() {
        return current != null;
    }

    private void start(Skit skit) {
        current = skit;
        stepIndex = 0;
        Log.i(TAG, "小剧场：《" + skit.name + "》，道具 " + skit.props);
        for (String prop : skit.props) {
            sink.send("exp " + prop);
            appliedProps.add(prop);
        }
        runNextStep();
    }

    private void runNextStep() {
        if (current == null) {
            return;
        }
        if (stepIndex >= current.steps.size()) {
            finish();
            return;
        }
        Step step = current.steps.get(stepIndex++);
        Log.i(TAG, "  " + step.actions + "（" + step.seconds + "s）");
        try {
            body.apply(step.actions, step.seconds, '。');
            if (!body.unknown.isEmpty()) {
                Log.w(TAG, "不认识的动作：" + body.unknown);
                body.unknown.clear();
            }
        } catch (Exception e) {
            Log.w(TAG, "动作执行失败：" + e);
        }
        handler.postDelayed(tick, (long) (step.seconds * 1000));
    }

    private void finish() {
        if (current != null) {
            Log.i(TAG, "小剧场结束：《" + current.name + "》");
        }
        removeProps();
        current = null;
        stepIndex = 0;
        // 收回姿势，但表情由下一个说话/待机接管；不在这里动 token/语音
        body.relax();
        nextAt = SystemClock.elapsedRealtime() + MIN_GAP_MS
                + random.nextInt((int) (MAX_GAP_MS - MIN_GAP_MS));
    }

    /** 说话/唤醒/按键时立刻停：摘道具、收姿势、取消下一步。 */
    public void stop() {
        handler.removeCallbacks(tick);
        if (current != null) {
            Log.i(TAG, "小剧场中断：《" + current.name + "》");
        }
        removeProps();
        current = null;
        stepIndex = 0;
        if (body != null) {
            body.relax();
        }
        nextAt = SystemClock.elapsedRealtime() + MIN_GAP_MS;
    }

    private void removeProps() {
        for (String prop : appliedProps) {
            sink.send("exp " + prop + " 0");
        }
        appliedProps.clear();
    }
}

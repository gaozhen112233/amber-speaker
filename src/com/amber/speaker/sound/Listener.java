package com.amber.speaker.sound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 听人说话：一直采，检测到人声就截一段存盘。
 *
 * <p>为什么要它而不是 {@link Recorder}：录音接口是「我喊开始、你数秒」，可人说话
 * 的时机由不得接口——我这边发完请求才看到提示，人早就说完或还没开口，两头对不上。
 * 这个类是「你随便什么时候说，我抓到你说了什么就返回」，时序问题从接口层消掉。
 *
 * <p>判据只有能量：20ms 一帧算 RMS，噪声底取慢速滑动估计，帧能量超过
 * {@code max(底×2, 底+50)} 算有声；累计有声够 {@code needMs} 才算真的说了话，
 * 避免敲桌子、外壳热胀冷缩这种单帧尖峰被当成语音。
 *
 * <p>三个数字是 2026-11-22 晚上按实测调过的，别再凭直觉改小：
 * <ul>
 *   <li><b>收尾静音 650ms</b>（原 350）。原来的 350 会把「大肥……鱼」中间那个
 *       自然停顿当成人说完了，于是截出来只有 400~700ms——「大肥鱼」三个字
 *       根本录不全，识别出来是「飞鱼」「太」「大培鱼」。收尾必须长过一个词的内部停顿。</li>
 *   <li><b>阈值放到 底×2 / 底+50</b>（原 ×2.5 / +80）。用户正常距离说话帧能量
 *       常常只到 1000~4000，旧阈值把轻声的辅音全判成静音，语音被切得支离破碎。</li>
 *   <li><b>收尾静音随已说时长递增</b>（见 {@link #quietNeedMs}）。650ms 只够挡住
 *       词内停顿；长句的「换气停顿」动辄 0.8~1.2 秒，一刀切 650ms 就会把
 *       「我下午看的那个电影讲的是……」截成半句。2026-11-27 真机日志里连续三句
 *       都以「讲的是。」「他是讲那个。」结尾，就是这么切出来的。</li>
 * </ul>
 */
public final class Listener {

    public static final int RATE = 16000;

    private static final int FRAME_SAMPLES = 320;          // 20ms
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    // 前后各留多少上下文。这两个数字不是「留得越多越保险」——它们直接决定
    // 云端识别的耗时：整段 4.3 秒送上去要 3.8~9.7 秒才回，裁到 1.4 秒只要 0.83 秒
    // （2026-11-22 实测，同一个模型、同一段话）。原来 1200/800 是「怕切掉字头」的
    // 保险，可它让每一句都多带 2 秒纯静音，唤醒应答就变成「喊完等五秒」。
    //
    // 2026-11-23 又踩回来一次：350/300 对「小鱼小鱼」够用（4 个音节，切掉一个还剩一个），
    // 但「大肥鱼」只有 3 个音节，字头「大」的爆破音被切掉、字尾「鱼」又被 300ms 收尾切掉，
    // 识别器就只吐出「第」「肥」这种残字。本地 SenseVoice 现在只要 0.3~0.5 秒，
    // 多带 300ms 完全承受得起，所以把两边都放宽。
    private static final int PRE_MS = 500;                 // 抓到人声前多留一段（原 350）
    private static final int TAIL_MS = 450;                // 说完再多录一点（原 300）
    /**
     * 收尾静音：静多久算说完。短句用 700ms（响应快），长句按已说时长递增，
     * 见 {@link #quietNeedMs(int)}。
     */
    private static final int QUIET_END_MS = 700;           // 说不到 2 秒：700ms
    private static final int QUIET_END_MID_MS = 1100;      // 说了 2 秒以上：1.1 秒
    private static final int QUIET_END_LONG_MS = 1500;     // 说了 5 秒以上：1.5 秒

    /**
     * 这一句需要多长的静音才算说完。
     *
     * <p>为什么不能一刀切：短句（「现在几点」）要快，等 1.5 秒才张嘴会让每轮对话都拖沓；
     * 可长句里换气、想词的自然停顿经常 0.8~1.2 秒，用 650~700ms 判就把一句话劈成两半，
     * 后一半没带唤醒词，直接被丢掉——听感就是「她只听了我前半句」。
     *
     * <p>阈值跟着「已经说了多久」走：说得越久，越可能还在继续说，越应该肯等。
     */
    private static int quietNeedMs(int voiceMs) {
        if (voiceMs >= 5000) {
            return QUIET_END_LONG_MS;
        }
        if (voiceMs >= 2000) {
            return QUIET_END_MID_MS;
        }
        return QUIET_END_MS;
    }

    private Listener() {
    }

    /** 一次监听的结论。{@code hit=false} 表示等到超时也没人说话。 */
    public static final class Result {
        public final boolean hit;
        public final String path;
        public final int bytes;
        public final int voiceMs;
        public final int waitMs;
        public final int peak;
        /** 实际写进文件、要送去识别的音频长度（毫秒）。跟 voiceMs 不是一回事：
         *  voiceMs 是「其中多少毫秒有声」，这个是「整段多长」——云端耗时跟后者走。 */
        public final int clipMs;
        /** 是否因为「跨度很长但真正有声的帧太少」被当成稀疏噪声拒绝（不是人说话）。 */
        public final boolean sparse;

        Result(boolean hit, String path, int bytes, int voiceMs, int waitMs, int peak) {
            this(hit, path, bytes, voiceMs, waitMs, peak, 0, false);
        }

        Result(boolean hit, String path, int bytes, int voiceMs, int waitMs, int peak, int clipMs) {
            this(hit, path, bytes, voiceMs, waitMs, peak, clipMs, false);
        }

        Result(boolean hit, String path, int bytes, int voiceMs, int waitMs, int peak,
               int clipMs, boolean sparse) {
            this.hit = hit;
            this.path = path;
            this.bytes = bytes;
            this.voiceMs = voiceMs;
            this.waitMs = waitMs;
            this.peak = peak;
            this.clipMs = clipMs;
            this.sparse = sparse;
        }

        public String toJson() {
            if (!hit) {
                // 没抓到也要把「听到了多少」带回去：voice_ms 有值说明确实有人声、
                // 只是不够长（门槛没到），peak 有值说明麦克风在工作。
                // 这两项为空的话，「她没反应」永远查不出是没听见还是听见了没够数。
                return "{\"ok\":true,\"hit\":false,\"wait_ms\":" + waitMs
                        + ",\"voice_ms\":" + voiceMs + ",\"peak\":" + peak
                        + ",\"sparse\":" + (sparse ? "true" : "false") + "}";
            }
            return "{\"ok\":true,\"hit\":true,\"path\":\"" + path + "\",\"bytes\":" + bytes
                    + ",\"voice_ms\":" + voiceMs
                    + ",\"clip_ms\":" + clipMs
                    + ",\"wait_ms\":" + waitMs
                    + ",\"peak\":" + peak + "}";
        }
    }

    /**
     * 等一段人声。
     *
     * @param maxSec   最多等多少秒（到点没抓到就返回 hit=false）
     * @param needMs   累计多少毫秒的有声帧才算「说了话」，建议 400~800
     * @param path     抓到的片段写到哪里
     */
    public static Result listen(int maxSec, int needMs, String path) throws IOException {
        return listen(maxSec, needMs, path, null, MediaRecorder.AudioSource.MIC);
    }

    /**
     * 同上，多一个「随时叫停」的开关。
     *
     * <p>为什么需要叫停：她要开口的时候不能还在听——喇叭就在麦克风旁边。
     * 更要紧的是这台机器的采集流在播放一响起来时会被 HAL 拆掉，read() 直接失败，
     * 于是只能一边报错一边硬撑。有了这个开关，/say 一到就干净地退出，
     * 调用方看到「没听到」自然会等下一轮。
     */
    public static Result listen(int maxSec, int needMs, String path,
                                java.util.concurrent.atomic.AtomicBoolean abort) throws IOException {
        return listen(maxSec, needMs, path, abort, MediaRecorder.AudioSource.MIC);
    }

    /**
     * 完整版本：可以指定 {@link MediaRecorder.AudioSource}。
     *
     * <p>{@code source} 是 2026-11-23 加的 AEC 对照实验口子：
     * <ul>
     *   <li>{@code MIC}：默认，最干净，但播放一开始 HAL 会拆掉采集流；</li>
     *   <li>{@code VOICE_COMMUNICATION}：Android 通常会给它挂 AEC/NS，
     *       如果这颗 HAL 支持，播放期间采集流不会断，回声也会被消掉——这正是商用
     *       音箱「边说边听」的做法；</li>
     *   <li>{@code VOICE_RECOGNITION}：部分 HAL 会给降噪/AGC，但不保证 AEC。</li>
     * </ul>
     * 正常链路不传，仍是 {@code MIC}；只有诊断/实验才从 {@code /listen?source=} 指定。
     */
    public static Result listen(int maxSec, int needMs, String path,
                                java.util.concurrent.atomic.AtomicBoolean abort,
                                int audioSource) throws IOException {
        return listen(maxSec, needMs, path, abort, audioSource, false);
    }

    /**
     * 完整版本 + 可选 AEC。
     *
     * <p>{@code aecOn=true} 时用 Android 的 {@link AcousticEchoCanceler}（如果 HAL 支持）
     * 把扬声器回声从麦克风里消掉。这是「边说边听」的关键：没有 AEC 时，她自己的声音
     * 会以 peak 26000+ 被录进来，一开麦就自激；有了 AEC，播放期间也能保持监听，
     * 人可以在她说话时插话、她说完也不用等尾音。
     */
    public static Result listen(int maxSec, int needMs, String path,
                                java.util.concurrent.atomic.AtomicBoolean abort,
                                int audioSource, boolean aecOn) throws IOException {
        final int channel = AudioFormat.CHANNEL_IN_MONO;
        final int encoding = AudioFormat.ENCODING_PCM_16BIT;

        int minBuf = AudioRecord.getMinBufferSize(RATE, channel, encoding);
        if (minBuf <= 0) {
            throw new IOException("getMinBufferSize 返回 " + minBuf + "（没有录音能力）");
        }
        final int bufSize = Math.max(minBuf * 2, RATE);

        AudioRecord record = new AudioRecord(
                audioSource, RATE, channel, encoding, bufSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IOException("AudioRecord 初始化失败，state=" + record.getState());
        }

        // AEC：只有显式要求才开（诊断口子）。创建失败不致命，退回普通采集。
        AcousticEchoCanceler aec = null;
        if (aecOn) {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                    if (aec != null) {
                        aec.setEnabled(true);
                        Log.i("Listener", "AEC 已请求，enabled=" + aec.getEnabled());
                    } else {
                        Log.i("Listener", "AEC create 返回 null（HAL 没给）");
                    }
                } else {
                    Log.i("Listener", "AEC 不可用（isAvailable=false）");
                }
            } catch (Throwable t) {
                Log.w("Listener", "AEC 创建失败：" + t);
                aec = null;
            }
        }

        final int maxFrames = Math.max(50, maxSec * 50);        // 50 帧/秒
        final byte[] pcm = new byte[maxFrames * FRAME_BYTES];
        final short[] level = new short[maxFrames];             // 每帧 RMS，用于判定与裁剪
        int frames = 0;
        int voiceFrames = 0;
        int firstVoice = -1;
        int lastVoice = -1;
        int peak = 0;
        double floor = -1;
        // 前 10 帧（200ms）用来估底噪，不参与人声判定。见下面的注释。
        final double[] warm = new double[10];
        int warmCount = 0;
        int quietRun = 0;
        // 连续有声帧数。单个 20ms 尖峰（咔哒、外壳热胀冷缩、电视遥控器）不算人声：
        // 以前一帧过阈值就 voiceFrames++ 并清掉 quietRun，客厅底噪里零星几下咔哒
        // 就能让「一句话」永远结束不了，最后截出整整 8 秒的低能量噪声。
        // 真机 18 段调试录音里有 7 段就是这种 8 秒空包，识别结果全是「。」。
        int run = 0;
        int maxRun = 0;
        final int ONSET_FRAMES = 2;   // 连续 40ms 才算人声起点
        // 诊断计数：AEC 对照实验要看「播放期间 read 到底断没断」。
        int readFails = 0;
        boolean feedBroken = false;

        final byte[] one = new byte[FRAME_BYTES];
        final long t0 = System.currentTimeMillis();
        final long deadline = t0 + Math.max(1, maxSec) * 1000L;

        try {
            record.startRecording();
            while (frames < maxFrames && System.currentTimeMillis() < deadline) {
                if (abort != null && abort.get()) {
                    break;
                }
                int got = 0;
                int spin = 0;
                while (got < FRAME_BYTES) {
                    int n = record.read(one, got, FRAME_BYTES - got);
                    if (n <= 0) {
                        readFails++;
                        // 播放一响，HAL 可能把采集流拆掉，read 会连续失败。
                        // 不要立刻放弃——等 3 秒，如果她自己说完了还能接上。
                        if (abort != null && abort.get()) {
                            break;
                        }
                        if (++spin > 30) {
                            break;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    got += n;
                }
                if (got < FRAME_BYTES) {
                    if (abort != null && abort.get()) {
                        break;
                    }
                    // 采集彻底断了，交给调用方重开一次
                    feedBroken = true;
                    break;
                }

                double sum = 0;
                for (int i = 0; i + 1 < FRAME_BYTES; i += 2) {
                    short s = (short) ((one[i] & 0xFF) | (one[i + 1] << 8));
                    sum += (double) s * s;
                    int a = s < 0 ? -s : s;
                    if (a > peak) {
                        peak = a;
                    }
                }
                final double rms = Math.sqrt(sum / FRAME_SAMPLES);

                System.arraycopy(one, 0, pcm, frames * FRAME_BYTES, FRAME_BYTES);
                level[frames] = (short) Math.min(32767, Math.round(rms));
                frames++;

                // 底噪初始化：不能拿「第一帧」当底噪。
                //
                // 真机踩过的坑：她刚说完、`/listen` 立刻开麦时，第一帧常常是她的尾音
                // （RMS 800~1500）。旧代码 `floor = 第一帧` → 阈值被顶到 1600~3000，
                // 人紧接着说的第一句（RMS 400~1200）全部被判成静音 → 第一次接话永远丢，
                // 第二遍说才被听到。这正是「第二步/第三步要问第二遍」的根因。
                //
                // 改成先收 10 帧（200ms），取中位数并把底噪夹在 40~300：
                // 安静房间≈40~80，有电视≈150~250；即使这 10 帧全是人声，
                // 中位数被夹到 300，阈值 600，正常说话仍然过线，字头也只是「没标记」，
                // 音频还在 pcm 里，后面 firstVoice - PRE_MS 会把它们一起截进去。
                if (floor < 0) {
                    warm[warmCount++] = rms;
                    if (warmCount < warm.length) {
                        continue;
                    }
                    double[] sorted = warm.clone();
                    java.util.Arrays.sort(sorted);
                    // 取最小值而不是中位数：她的尾音/人声会让前几帧偏高，
                    // 取最小能把底噪压回真实水平；再夹在 40~150，避免把阈值顶到
                    // 人轻声说话都过不去。实测客厅底噪 40~80，有电视 150~250。
                    floor = Math.max(40.0, Math.min(150.0, sorted[0]));
                }
                // 绝对值下限 250：比客厅底噪高、比轻声说话低。没有它，极安静的房间
                // 会把呼吸声都算成人声；有了它，再叠加自适应底噪仍然能适应有电视的环境。
                final double threshold = Math.max(250.0, Math.max(floor * 2.0, floor + 50));

                if (rms > threshold) {
                    run++;
                    if (run >= ONSET_FRAMES) {
                        if (run > maxRun) {
                            maxRun = run;
                        }
                        if (firstVoice < 0) {
                            // 起点回退到这一串连续有声帧的第一帧，别把字头丢掉
                            firstVoice = frames - run;
                            voiceFrames = run;
                        } else {
                            // run==2 时这一串的前一帧还没计过，一次补 2
                            voiceFrames += (run == ONSET_FRAMES) ? ONSET_FRAMES : 1;
                        }
                        lastVoice = frames - 1;
                        quietRun = 0;
                    }
                    // run==1：孤立单帧，不算人声，也不清零 quietRun——
                    // 否则每秒一个咔哒就能把「说完」永远往后推。
                } else {
                    run = 0;
                    // 非人声帧才更新底噪，否则人一开口底噪就被自己顶上去了
                    floor = floor * 0.98 + rms * 0.02;
                    if (firstVoice >= 0) {
                        quietRun++;
                    }
                }

                // 条件：累计有声够长，并且已经连续静了「这一句该等的静音」（一句话说完了）
                if (voiceFrames * 20 >= needMs
                        && quietRun * 20 >= quietNeedMs(voiceFrames * 20)) {
                    break;
                }
            }
            record.stop();
        } finally {
            if (aec != null) {
                try {
                    aec.setEnabled(false);
                    aec.release();
                } catch (Throwable ignored) {
                    // AEC 释放失败不影响已经采到的数据
                }
            }
            try {
                record.release();
            } catch (Exception ignored) {
                // release 失败不影响已经采到的数据
            }
        }

        final int waitMs = (int) (System.currentTimeMillis() - t0);
        final int voiceMs = voiceFrames * 20;
        Log.i("Listener", "采集结束：source=" + audioSource + " 帧=" + frames
                + " read失败=" + readFails + " 断流=" + feedBroken
                + " 有声=" + voiceMs + "ms 峰值=" + peak + " 用时=" + waitMs + "ms");
        if (firstVoice < 0 || voiceMs < needMs) {
            return new Result(false, "", 0, voiceMs, waitMs, peak);
        }

        // 稀疏噪声闸门：跨度超过 3 秒、真正有声的帧不到 20%，而且**从来没有连续
        // 160ms 以上的人声**——这不是一句话，是客厅底噪里零星几下咔哒。
        //
        // 为什么加 maxRun：真机 18:51 有一句真实的「大肥鱼」（900ms 有声、峰值 5109）
        // 被只按密度的闸门误杀了：它跨度长，但人声那一段是连续的 20~30 帧。
        // 噪声咔哒是 2~4 帧一簇，永远连不成 8 帧。用「最长连续人声」判，两者分得开。
        final int spanMs = (lastVoice - firstVoice + 1) * 20;
        if (spanMs >= 3000 && voiceMs < spanMs * 0.2 && maxRun < 8) {
            return new Result(false, "", 0, voiceMs, waitMs, peak, 0, true);
        }

        int from = Math.max(0, firstVoice - PRE_MS / 20);
        int to = Math.min(frames, lastVoice + TAIL_MS / 20 + 1);
        // 上限 30 秒：SenseVoice 单次能吃的就是 30 秒，再长它自己也会切。
        // 原来写 8 秒，等于「说到第 9 秒的话全部丢掉」——长句被砍成前 8 秒，
        // 后半句既没进识别、也不会重来（人已经说完了），听感就是「听不全」。
        final int maxClipFrames = 30000 / 20;
        if (to - from > maxClipFrames) {
            to = from + maxClipFrames;
            Log.w("Listener", "这段话超过 30 秒（有声 " + voiceMs + "ms），只送前 30 秒");
        }
        int len = (to - from) * FRAME_BYTES;
        byte[] clip = new byte[len];
        System.arraycopy(pcm, from * FRAME_BYTES, clip, 0, len);

        writeWav(new File(path), clip, len);
        return new Result(true, path, len, voiceFrames * 20, waitMs, peak,
                (to - from) * 20);
    }

    /** 写标准 44 字节 WAV 头。 */
    private static void writeWav(File file, byte[] data, int len) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(new byte[]{
                    'R', 'I', 'F', 'F',
                    (byte) ((len + 36) & 0xFF), (byte) (((len + 36) >> 8) & 0xFF),
                    (byte) (((len + 36) >> 16) & 0xFF), (byte) (((len + 36) >> 24) & 0xFF),
                    'W', 'A', 'V', 'E',
                    'f', 'm', 't', ' ',
                    16, 0, 0, 0,
                    1, 0,                                      // PCM
                    1, 0,                                      // 单声道
                    (byte) (RATE & 0xFF), (byte) ((RATE >> 8) & 0xFF),
                    (byte) ((RATE >> 16) & 0xFF), (byte) ((RATE >> 24) & 0xFF),
                    (byte) ((RATE * 2) & 0xFF), (byte) (((RATE * 2) >> 8) & 0xFF),
                    (byte) (((RATE * 2) >> 16) & 0xFF), (byte) (((RATE * 2) >> 24) & 0xFF),
                    2, 0,                                      // 块对齐
                    16, 0,                                     // 位深
                    'd', 'a', 't', 'a',
                    (byte) (len & 0xFF), (byte) ((len >> 8) & 0xFF),
                    (byte) ((len >> 16) & 0xFF), (byte) ((len >> 24) & 0xFF),
            });
            out.write(data, 0, len);
        } finally {
            out.close();
        }
    }
}

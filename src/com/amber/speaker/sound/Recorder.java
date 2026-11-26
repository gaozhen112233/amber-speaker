package com.amber.speaker.sound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 麦克风录音（16kHz / 单声道 / 16bit）。
 *
 * <p>唤醒词的前置就是这一段：先把「能不能采到有用的声音」验证掉，再谈识别。
 * 这台机器是 Android 5.1（API 22），危险权限还是安装即授予，只要清单里声明
 * RECORD_AUDIO 就能直接用，不需要运行时申请。
 *
 * <p>为什么是 16k 单声道：所有离线 KWS / ASR 模型（sherpa-onnx、Porcupine、
 * snowboy）都吃这个格式，48k 只是白占带宽再重采样。
 */
public final class Recorder {

    public static final int RATE = 16000;

    private Recorder() {
    }

    /** 录音结果：字节数、均方根、峰值、有声时长，用来一眼判断「有没有人说话」。 */
    public static final class Result {
        public final String path;
        public final int bytes;
        public final double rms;
        public final int peak;
        public final int readMs;
        /** 判定为「有声音」的累计毫秒。静音环境这个值是 0。 */
        public final int voiceMs;
        /** 噪声底（第 20 百分位帧能量），判阈值就是它乘倍数。 */
        public final double floor;

        Result(String path, int bytes, double rms, int peak, int readMs, int voiceMs, double floor) {
            this.path = path;
            this.bytes = bytes;
            this.rms = rms;
            this.peak = peak;
            this.readMs = readMs;
            this.voiceMs = voiceMs;
            this.floor = floor;
        }

        public String toJson() {
            return "{\"ok\":true,\"path\":\"" + path + "\",\"bytes\":" + bytes
                    + ",\"rms\":" + Math.round(rms * 10) / 10.0
                    + ",\"peak\":" + peak
                    + ",\"read_ms\":" + readMs
                    + ",\"voice_ms\":" + voiceMs
                    + ",\"floor\":" + Math.round(floor * 10) / 10.0
                    + "}";
        }
    }

    /**
     * 录 {@code seconds} 秒，写到 {@code path}。返回统计量。
     *
     * <p>失败一律抛异常，由调用方决定怎么回给 HTTP——不要在这里吞掉，
     * 否则「麦克风不存在」和「麦克风静音」在日志里长得一模一样。
     */
    public static Result record(int seconds, String path) throws IOException {
        final int channel = AudioFormat.CHANNEL_IN_MONO;
        final int encoding = AudioFormat.ENCODING_PCM_16BIT;

        int minBuf = AudioRecord.getMinBufferSize(RATE, channel, encoding);
        if (minBuf <= 0) {
            throw new IOException("getMinBufferSize 返回 " + minBuf + "（没有录音能力）");
        }
        int bufSize = Math.max(minBuf * 2, RATE);

        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, channel, encoding, bufSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IOException("AudioRecord 初始化失败，state=" + record.getState());
        }

        final int total = RATE * Math.max(1, seconds) * 2;     // 16bit = 2 字节
        byte[] data = new byte[total];
        int read = 0;
        long peak = 0;
        double sq = 0;

        try {
            record.startRecording();
            long t0 = System.currentTimeMillis();
            final long deadline = t0 + Math.max(1, seconds) * 1000L;
            int fails = 0;
            // 按**时间**收，不按字节数收。read() 偶尔返回 0 是正常的（缓冲区没攒够），
            // 早先按 n<=0 就 break，结果请求 30 秒只拿到 15 秒；现在只有连续失败才放弃。
            while (read + 320 <= total && System.currentTimeMillis() < deadline) {
                int n = record.read(data, read, Math.min(bufSize, total - read));
                if (n <= 0) {
                    if (++fails >= 20) {
                        break;
                    }
                    continue;
                }
                fails = 0;
                read += n;
            }
            int ms = (int) (System.currentTimeMillis() - t0);
            record.stop();

            for (int i = 0; i + 1 < read; i += 2) {
                short s = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
                int a = s < 0 ? -s : s;
                if (a > peak) {
                    peak = a;
                }
                sq += (double) s * s;
            }
            int samples = Math.max(1, read / 2);
            double rms = Math.sqrt(sq / samples);

            final int[] vad = voiceStats(data, read);
            writeWav(new File(path), data, read);
            return new Result(path, read, rms, (int) peak, ms, vad[0], vad[1]);
        } finally {
            try {
                record.release();
            } catch (Exception ignored) {
                // release 失败不影响已经录到的数据
            }
        }
    }

    /**
     * 粗粒度语音活动统计：{@code int[]{有声毫秒, 噪声底}}。
     *
     * <p>只做「这 20ms 比本底响得多吗」这一件事，不做频谱、不做模型。
     * 目的是把「麦克风通但没人说话」和「有人说话」在 HTTP 返回里分开——
     * 靠人耳听录音来判断太慢，而且我这边听不到。
     *
     * <p>噪声底取全部帧能量的第 20 百分位：比最小值稳（不会被单帧异常拉低），
     * 又不会被零星人声带高。阈值 = max(底 × 2.5, 底 + 80)。
     */
    private static int[] voiceStats(byte[] data, int len) {
        final int frameBytes = 640;                 // 320 样本 = 20ms
        final int frames = len / frameBytes;
        if (frames <= 0) {
            return new int[]{0, 0};
        }
        final double[] energy = new double[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            final int base = f * frameBytes;
            for (int i = base; i + 1 < base + frameBytes; i += 2) {
                short s = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
                sum += (double) s * s;
            }
            energy[f] = Math.sqrt(sum / 320.0);
        }
        final double[] sorted = energy.clone();
        java.util.Arrays.sort(sorted);
        final double floor = sorted[(int) (frames * 0.2)];
        final double threshold = Math.max(floor * 2.5, floor + 80);

        // 单帧抖一下不算说话，要求连续 3 帧（60ms）以上才计入——
        // 键盘声、塑料壳热胀冷缩都是单帧尖峰。
        int run = 0;
        int voiced = 0;
        int voicedSamples = 0;
        for (int f = 0; f < frames; f++) {
            if (energy[f] > threshold) {
                run++;
                if (run >= 3) {
                    voicedSamples++;
                }
            } else {
                // 已计入的那 3 帧在起头时漏算了，这里补回来一次
                if (run >= 3) {
                    voicedSamples += Math.min(run, 3);
                }
                run = 0;
            }
        }
        if (run >= 3) {
            voicedSamples += Math.min(run, 3);
        }
        voiced = voicedSamples * 20;
        return new int[]{voiced, (int) Math.round(floor)};
    }

    /** 写标准 44 字节 WAV 头。MediaPlayer / PC 都能直接放。 */
    private static void writeWav(File file, byte[] data, int len) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            writeAscii(out, "RIFF");
            writeLe32(out, 36 + len);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeLe32(out, 16);
            writeLe16(out, 1);              // PCM
            writeLe16(out, 1);              // 单声道
            writeLe32(out, RATE);
            writeLe32(out, RATE * 2);       // 字节率
            writeLe16(out, 2);              // 块对齐
            writeLe16(out, 16);             // 位深
            writeAscii(out, "data");
            writeLe32(out, len);
            out.write(data, 0, len);
        } finally {
            out.close();
        }
    }

    private static void writeAscii(FileOutputStream out, String s) throws IOException {
        out.write(s.getBytes("US-ASCII"));
    }

    private static void writeLe32(FileOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeLe16(FileOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    /** 给调试用：文件长度够不够。 */
    public static long sizeOf(String path) {
        try {
            RandomAccessFile f = new RandomAccessFile(path, "r");
            try {
                return f.length();
            } finally {
                f.close();
            }
        } catch (IOException e) {
            return -1;
        }
    }
}

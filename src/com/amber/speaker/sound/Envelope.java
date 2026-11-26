package com.amber.speaker.sound;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 把一段音频解成「每 20 毫秒有多响」，喂给嘴型。
 *
 * <p>为什么要先解一遍再播，而不是边播边测：Android 上想实时拿到播放输出的波形，
 * 标准做法是 {@code Visualizer}，而它<b>要求 RECORD_AUDIO 权限</b>——一个只会说话的玩偶
 * 不该申请录音权限。所以改成离线量：播放前把整段解成 PCM 算包络，播的时候按播放位置查表。
 * 代价是准备时间（十几毫秒到几百毫秒），换来的是一不越权、二比 Visualizer 准
 * （Visualizer 的采样率被系统压得很低，还带延迟）。
 *
 * <p>解码失败一律返回 {@code null}，由调用方退回「正弦嘴型」。音频格式千奇百怪，
 * 这里不追求全覆盖——TTS 出来的是 mp3/wav，够用。
 */
final class Envelope {

    /** 包络的时间分辨率。20ms 对应 50Hz，比嘴能跟上的速度还快一档。 */
    static final int FRAME_MS = 20;

    /** 超过这个长度就不解了。一句话不该有这么长，多半是给错了文件。 */
    private static final int MAX_MS = 120_000;

    static final class Clip {
        final float[] levels;
        final int durationMs;

        Clip(float[] levels, int durationMs) {
            this.levels = levels;
            this.durationMs = durationMs;
        }

        /** 取某个播放位置上的响度 0~1。 */
        float at(long positionMs) {
            if (levels.length == 0 || positionMs < 0) {
                return 0f;
            }
            int index = (int) (positionMs / FRAME_MS);
            return index >= levels.length ? 0f : levels[index];
        }

        /**
         * 一整段包络压成一串 0~9，每格 {@link #FRAME_MS} 毫秒。
         *
         * <p>只为打日志：嘴「该动而没动」的时候，有这一串就能一眼看出是包络算错了
         * 还是渲染没接上。也可以拿它跟 PC 上独立算的结果逐字符比。
         */
        String bar() {
            StringBuilder text = new StringBuilder(levels.length);
            for (float level : levels) {
                int step = (int) (level * 9f + 0.5f);
                text.append((char) ('0' + Math.max(0, Math.min(9, step))));
            }
            return text.toString();
        }
    }

    static Clip decode(File file) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getPath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0 || format == null) {
                return null;
            }
            extractor.selectTrack(track);

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream(1 << 16);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            boolean floats = false;

            // 解码是本地文件、纯 CPU，正常几十毫秒就完了。给 8 秒上限，
            // 是防「文件其实是个在线流」这种把主线程外的线程挂死的情况。
            long deadline = System.currentTimeMillis() + 8000;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        int size = in == null ? -1 : extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if (out != null && info.size > 0) {
                        byte[] chunk = new byte[info.size];
                        out.position(info.offset);
                        out.get(chunk);
                        pcm.write(chunk, 0, chunk.length);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 真正的采样率/声道数以解码器说了算（AAC 的容器声明经常不准）
                    MediaFormat out = codec.getOutputFormat();
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floats = out.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4; // ENCODING_PCM_FLOAT
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    return null;
                }
            }

            if (sampleRate <= 0 || channels <= 0) {
                return null;
            }
            byte[] raw = pcm.toByteArray();
            if (raw.length < 2) {
                return null;
            }
            return build(raw, sampleRate, channels, floats);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static Clip build(byte[] raw, int sampleRate, int channels, boolean floats) {
        int frames = raw.length / (2 * channels);
        if (floats) {
            frames = raw.length / (4 * channels);
        }
        int durationMs = (int) (frames * 1000L / sampleRate);
        if (durationMs <= 0 || durationMs > MAX_MS) {
            return null;
        }

        int perFrame = Math.max(1, sampleRate * FRAME_MS / 1000);
        int count = (frames + perFrame - 1) / perFrame;
        float[] levels = new float[count];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        double peak = 0;
        for (int f = 0; f < count; f++) {
            int from = f * perFrame;
            int to = Math.min(frames, from + perFrame);
            double sum = 0;
            int n = 0;
            for (int i = from; i < to; i++) {
                for (int c = 0; c < channels; c++) {
                    float value;
                    int at = (i * channels + c) * (floats ? 4 : 2);
                    if (floats) {
                        value = at + 4 <= raw.length ? buffer.getFloat(at) : 0f;
                    } else {
                        value = (at + 2 <= raw.length ? buffer.getShort(at) : 0) / 32768f;
                    }
                    sum += value * value;
                    n++;
                }
            }
            double rms = n == 0 ? 0 : Math.sqrt(sum / n);
            levels[f] = (float) rms;
            if (rms > peak) {
                peak = rms;
            }
        }
        if (peak <= 0.0005) {
            // 一整段几乎没声音（或者全是数字静音）：没必要驱动嘴型
            return new Clip(new float[0], durationMs);
        }

        // 归一化：以峰值的 70% 当「满」，再开一次方把轻声抬起来。
        // 不开方的话，正常语速下嘴只会在一两个帧里张开一点，看起来像没动。
        float reference = (float) (peak * 0.7);
        for (int i = 0; i < levels.length; i++) {
            float v = levels[i] / reference;
            if (v > 1f) {
                v = 1f;
            }
            levels[i] = (float) Math.sqrt(v);
        }
        return new Clip(levels, durationMs);
    }
}

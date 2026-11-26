package com.amber.speaker.sound;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 她的嗓子：下载一段配音、量出包络、播出去。
 *
 * <p>按地址缓存，不是一个「当前这句」。原因是排队：A 句还在播，B 句的配音就已经开始下了。
 * 如果只有一个槽位，B 一来就会把 A 的包络清掉，A 的嘴会当场闭上——试过，很怪。
 *
 * <p>为什么不边下边播：嘴型要靠整段的包络才对得上，而包络必须等整段解完。
 * 一段 3 秒的 TTS 通常几百毫秒就准备好，等得起；等不起的时候宁可没声音，也不让口型乱动。
 */
public final class Voice {

    public static final int IDLE = 0;
    public static final int LOADING = 1;
    public static final int READY = 2;
    public static final int FAILED = 3;

    private static final String TAG = "AmberCue";
    /** 一段配音的上限。TTS 一句话不该有这么大，超过多半是地址给错了。 */
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    /** 最多同时记住几段配音。够覆盖「连发几句」的排队深度，再多就淘汰最旧的。 */
    private static final int KEEP = 6;

    private static final class Prepared {
        final File file;
        final Envelope.Clip clip;
        final int state;

        Prepared(File file, Envelope.Clip clip, int state) {
            this.file = file;
            this.clip = clip;
            this.state = state;
        }
    }

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Prepared> cache = new LinkedHashMap<String, Prepared>(8, 0.75f, true);

    private MediaPlayer player;
    /** 正在播的那段包络。单独存一份，和缓存解耦——它不能被后面的 prepare 清掉。 */
    private Envelope.Clip playing;
    private String playingUrl;
    private boolean released;
    /**
     * 播放增益 0~1。顶部音量键改的是这个，不是系统音量。
     *
     * <p>这台机器的 framework 不处理音量键（{@code dumpsys audio} 的 STREAM_MUSIC 一直是 15/15），
     * 所以只能自己调 MediaPlayer 的左右声道音量。新起的每个 MediaPlayer 都要套上，
     * 不然「调完音量，下一句又变回最大」。
     */
    private float gain = 1.0f;

    public Voice(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 设置播放增益（0~1），对正在播的这一句立即生效，对之后每一句也生效。 */
    public void setGain(float value) {
        gain = Math.max(0f, Math.min(1f, value));
        MediaPlayer p = player;
        if (p != null) {
            try {
                p.setVolume(gain, gain);
            } catch (IllegalStateException ignored) {
                // 正在切歌 / 已释放，下一次 play 会套上新的 gain
            }
        }
    }

    public float gain() {
        return gain;
    }

    /** 某个地址准备好了没有。没准备过返回 {@link #IDLE}。 */
    public int state(String url) {
        if (url == null) {
            return IDLE;
        }
        Prepared entry = cache.get(url);
        return entry == null ? IDLE : entry.state;
    }

    public int durationMs() {
        Prepared entry = playingUrl == null ? null : cache.get(playingUrl);
        if (entry != null && entry.clip != null) {
            return entry.clip.durationMs;
        }
        return playing == null ? 0 : playing.durationMs;
    }

    /** 让后台去准备。重复调用同一个地址不会重复下载。 */
    public void prepare(String url) {
        if (url == null || released) {
            return;
        }
        Prepared known = cache.get(url);
        if (known != null && known.state != FAILED) {
            return;
        }
        cache.put(url, new Prepared(null, null, LOADING));
        worker.execute(new Runnable() {
            @Override
            public void run() {
                load(url);
            }
        });
    }

    private void load(String url) {
        try {
            File local = fetch(url);
            Envelope.Clip clip = Envelope.decode(local);
            if (released) {
                return;
            }
            cache.put(url, new Prepared(local, clip, READY));
            trim();
            if (clip == null) {
                Log.i(TAG, "配音就绪（解不出包络，只播不驱动嘴）");
            } else {
                Log.i(TAG, "配音就绪 " + clip.durationMs + "ms 包络 " + clip.bar());
            }
        } catch (Exception e) {
            if (released) {
                return;
            }
            cache.put(url, new Prepared(null, null, FAILED));
            Log.w(TAG, "配音拿不到：" + e.getMessage());
        }
    }

    /** 淘汰旧的，但绝不淘汰正在播的那段。 */
    private void trim() {
        if (cache.size() <= KEEP) {
            return;
        }
        Iterator<Map.Entry<String, Prepared>> it = cache.entrySet().iterator();
        while (it.hasNext() && cache.size() > KEEP) {
            String key = it.next().getKey();
            if (key.equals(playingUrl)) {
                continue;
            }
            it.remove();
        }
    }

    /** 播。没准备好就什么都不做（调用方已经判断过状态）。 */
    public void play(String url) {
        stop();
        Prepared entry = url == null ? null : cache.get(url);
        if (entry == null || entry.state != READY || entry.file == null) {
            return;
        }
        try {
            MediaPlayer made = new MediaPlayer();
            made.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            made.setDataSource(entry.file.getPath());
            made.prepare();
            made.setVolume(gain, gain);   // 上一句调过的音量要带过来
            made.start();
            player = made;
            playing = entry.clip;
            playingUrl = url;
        } catch (Exception e) {
            Log.w(TAG, "播放失败：" + e.getMessage());
        }
    }

    public boolean playing() {
        try {
            return player != null && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public long positionMs() {
        try {
            return player == null ? 0 : player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /** 当前这一刻的响度 0~1，没在播就是 0。 */
    public float level() {
        if (playing == null || !playing()) {
            return 0f;
        }
        return playing.at(positionMs());
    }

    public void stop() {
        playing = null;
        playingUrl = null;
        if (player == null) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.stop();
            }
        } catch (IllegalStateException ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }

    public void release() {
        released = true;
        stop();
        worker.shutdownNow();
        cache.clear();
    }

    /** 下载到缓存；已经下过就直接用。 */
    private File fetch(String address) throws IOException {
        if (address.startsWith("asset://")) {
            // APK 内置的预录音（唤醒应答这类延迟敏感的高频短句）。
            // 从 assets 解到缓存文件，复用下面的解码/播放链路，不走网络。
            String name = address.substring("asset://".length());
            File target = new File(context.getCacheDir(), "voice-asset-" + name);
            if (target.length() > 0) {
                return target;
            }
            InputStream in = context.getAssets().open("voice/" + name);
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
                in.close();
            }
            return target;
        }
        if (address.startsWith("http://") || address.startsWith("https://")) {
            File target = new File(context.getCacheDir(),
                    "voice-" + Integer.toHexString(address.hashCode()) + ".audio");
            if (target.length() > 0) {
                return target;
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            try {
                int code = connection.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }
                InputStream in = connection.getInputStream();
                FileOutputStream out = new FileOutputStream(target);
                try {
                    byte[] buffer = new byte[16384];
                    long total = 0;
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        total += read;
                        if (total > MAX_BYTES) {
                            throw new IOException("音频过大");
                        }
                        out.write(buffer, 0, read);
                    }
                } finally {
                    out.close();
                    in.close();
                }
            } finally {
                connection.disconnect();
            }
            return target;
        }
        // 本地路径：真机上用不着，但调试时比架服务器快得多
        String path = address.startsWith("file://") ? address.substring(7) : address;
        File local = new File(path);
        if (!local.isFile() || local.length() == 0) {
            throw new IOException("文件不存在 " + path);
        }
        return local;
    }
}

package com.amber.speaker.net;

import android.media.MediaRecorder;
import android.util.Log;

import com.amber.speaker.Text;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简 HTTP 服务，只做契约里要的那几个端点。
 *
 * <p>手写而不是用框架，理由：Android 上没有 {@code com.sun.net.httpserver}，引一个
 * NanoHTTPD 又要多一层依赖和混淆风险；而我们要处理的东西极少——一个 POST、两个 GET、
 * 请求体最大 300 字。这种情况下手写比引库更容易看清边界。
 *
 * <p>不实现的部分也明确：不支持 keep-alive（每个连接处理完就关）、不支持 chunked、
 * 不支持 HTTPS。音箱在局域网里被 AstrBot 单向下发，这些都不需要。
 */
public final class SayServer {

    /** 收到请求时的回调。实现方在主线程之外被调用，要自己切线程。 */
    public interface Listener {
        /**
         * 一条 /say。
         *
         * @param text      正文（已经去掉 HTTP 层，但〔〕还在，由设备端解析）
         * @param id        幂等键，可能是空字符串
         * @param audioUrl  这句的配音地址，没有就是 {@code null}
         * @param interrupt 是否打断正在说的那句并清空排队
         * @param ttlMs     排队寿命毫秒，0 表示不过期
         */
        void onSay(String text, String id, String audioUrl, boolean interrupt, long ttlMs);

        /**
         * 一次唤醒事件（POST /wake）。设备端真实唤醒走 KWS 回调 / 人体感应回调，
         * 不经过 HTTP；这个端点是给到货前联调用——用 curl 模拟「有人喊了大肥鱼」
         * 或「有人走近」（{@code source=proximity}）。
         *
         * @param keyword 命中词，默认「大肥鱼」
         * @param source  唤醒来源：voice / proximity / button（对齐契约 §3）
         */
        void onWake(String keyword, String source);

        /**
         * 一次设备控制（POST /control）。对齐契约 §7。当前只实现 {@code display} 动作，
         * 其余（brightness / volume / reboot）暂不落地，调用方收到时会原样拒绝。
         *
         * @param action 动作名（display / brightness / volume / reboot）
         * @param value  值（display 是 on / off；brightness / volume 是 0–1；reboot 无）
         */
        void onControl(String action, String value);

        /** /state 的响应体，由调用方决定内容。 */
        String onState();

        /**
         * 现在能不能开麦克风听人说话。
         *
         * <p>不只是「有没有在说话」：她可能刚播完最后一句、或者还有一句在等配音、
         * 或者声音刚停但喇叭尾音还在。这些时候开麦克风都会把她自己的声音录进去，
         * 真机上的表现是「她说完之后我说什么她都不听」——因为录到的是她自己的尾音，
         * 识别成「查了一下」这种，然后被当成「没叫她」丢掉，白白占掉一轮。
         *
         * <p>由 {@code MainActivity} 实现：说话/排队/配音中 + 播完后的
         * {@code LISTEN_TAIL_GUARD_MS} 都算忙。
         */
        boolean busyForListen();

        /**
         * 时间同步（POST /clock）。这台机器没有 RTC 电池，开机时间停在 1977 年，
         * 断网时也没法自己 NTP 对时，所以由外部（AstrBot / PC 探针）把真实时间推进来。
         * 设备端只记偏移量，不改系统时间。
         *
         * @param epochMs 真实时间的 epoch 毫秒
         * @return 是否接受（外部给的时间太旧会拒收）
         */
        boolean onClock(long epochMs);

        /**
         * 读当前的行为类设置（GET /settings）。
         *
         * @return 一整段 JSON，形如 {@code {"ok":true,"settings":{...}}}
         */
        String onSettings();

        /**
         * 改一项行为类设置（POST /settings）。{@code key="reset"} 表示全部恢复默认。
         *
         * <p>这些是「不用重新编译就该能改」的东西：作息区间、定时播报、在场搭话的
         * 阈值与时段、接话窗口、视线跟随。默认值与改造前写死的常量完全一致。
         *
         * @return null 表示成功；非 null 是给人看的错误说明（会原样回 400）
         */
        String onSetting(String key, String value);

        /**
         * 配网（POST /wifi）。设备端用自己的 WifiManager 添加并连接。
         *
         * <p>为什么不让用户在系统设置里连：这台机器的系统是竖屏 UI，而物理屏是横的
         * （我们的 app 自己强制 landscape 才是正的），在设置页里输入密码要歪着头，
         * 极难操作。走自己的端点，密码用 JSON body 传（不受 URL 编码影响，中文 SSID 也安全）。
         *
         * @param ssid 网络名（原样传，不带引号）
         * @param pass 密码；空串表示开放网络
         * @return 是否已提交连接
         */
        boolean onWifi(String ssid, String pass);

        /**
         * 「眼睛」工具（POST /snapshot）。主动取一帧画面交视觉模型分析。
         *
         * <p>同步阻塞（内部最多等 5 秒），返回拼好的 JSON：成功带 base64 图片，
         * 失败或超额度带 {@code error} 字段。额度闸门在设备端（§7.1），超额度返回
         * 结构化回执「额度已用完」，不带画面。
         */
        String onSnapshot();

        /**
         * 副屏：播一段视频（HTTP/HTTPS URL，或设备上的文件路径）。返回是否受理。
         *
         * <p>视频是解到外部纹理上、由我们自己的 GL 路径画的（见
         * {@code VideoStage}）：所以背景能透明、镜像和模型一致、切回来不用重载模型。
         * 播放期间 {@link #busyForListen()} 恒为 true —— 喇叭在响，开麦只会把她自己录进去。
         */
        boolean onVideo(String source, boolean loop);

        /** 副屏收工，切回大肥鱼。 */
        void stopVideo();

        /** 副屏抠像参数（none / green / blue / black / 0xRRGGBB[:阈值]）。 */
        boolean onVideoKey(String spec);

        /**
         * 当前副屏正在播的源（设备文件绝对路径或 URL）；没在播返回 {@code null}。
         *
         * <p>清视频前要用它判断「要删的这段是不是正在放」——正在放就先停下再删，
         * 否则播放器手里那个 fd 还指着已经 unlink 的 inode，画面会停在最后一帧不动。
         */
        String currentVideoSource();
    }

    private static final int PORT = 8788;
    private static final int MAX_BODY = 64 * 1024;
    /** 副屏视频落地目录。放 /sdcard 而不是应用私有目录：adbx 能直接 ls/pull，排查方便。 */
    private static final String VIDEO_DIR = "/sdcard/amber/video";

    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * 叫停正在跑的 /listen。
     *
     * <p>她一张嘴就不能再听：喇叭紧挨着麦克风，而且这台机器的采集流在播放开始时
     * 会被 HAL 拆掉。收到 /say 或 /wake 就把这个开关拨起来，监听线程下一帧就退出，
     * 省掉「一边报错一边硬撑」的那几秒。
     */
    private final AtomicBoolean listenAbort = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread acceptThread;
    private ExecutorService workers;

    public SayServer(Listener listener) {
        this.listener = listener;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(PORT));
        workers = Executors.newFixedThreadPool(4);
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "say-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running.set(false);
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // 关服务时出错没什么可做的
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    public int port() {
        return PORT;
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket = null;
            try {
                socket = server.accept();
                final Socket accepted = socket;
                workers.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(accepted);
                    }
                });
            } catch (IOException e) {
                // server 被 close 时会走到这里，正常退出
                if (running.get()) {
                    closeQuietly(socket);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = readHead(in);
            if (head == null) {
                return;
            }
            String text = new String(head.toByteArray(), "UTF-8");
            String[] lines = text.split("\r\n");
            if (lines.length == 0) {
                return;
            }
            String[] request = lines[0].split(" ");
            if (request.length < 2) {
                return;
            }
            String method = request[0];
            String path = request[1];
            // 查询串单独摘出来：/say?interrupt=1&ttl=30
            String query = "";
            int mark = path.indexOf('?');
            if (mark >= 0) {
                query = path.substring(mark + 1);
                path = path.substring(0, mark);
            }
            int contentLength = 0;
            String authorization = "";
            for (int i = 1; i < lines.length; i++) {
                String lower = lines[i].toLowerCase(java.util.Locale.US);
                if (lower.startsWith("content-length:")) {
                    contentLength = parseInt(lines[i].substring(15).trim());
                } else if (lower.startsWith("authorization:")) {
                    authorization = lines[i].substring(14).trim();
                }
            }

            String body = "";
            // 传视频是几十上百 MB，不能走「读进内存再分发」这条路：边收边落盘。
            if ("POST".equals(method) && path.startsWith("/upload")) {
                handleUpload(socket, in, contentLength, query);
                return;
            }
            if (contentLength > 0 && contentLength <= MAX_BODY) {
                body = readBody(in, contentLength);
            }

            if ("GET".equals(method) && path.startsWith("/video/list")) {
                handleVideoList(socket.getOutputStream());
            } else if ("POST".equals(method) && path.startsWith("/video/delete")) {
                handleVideoDelete(query, socket.getOutputStream());
            } else if ("POST".equals(method) && path.startsWith("/video/clean")) {
                handleVideoClean(socket.getOutputStream());
            } else if ("POST".equals(method) && path.startsWith("/video")) {
                handleVideo(body, query, socket.getOutputStream());
            } else if ("POST".equals(method) && path.startsWith("/say")) {
                handleSay(body, query, socket.getOutputStream());
            } else if ("POST".equals(method) && path.startsWith("/wake")) {
                handleWake(body, query, socket.getOutputStream());
            } else if ("POST".equals(method) && path.startsWith("/control")) {
                handleControl(body, query, socket.getOutputStream());
            } else if ("POST".equals(method) && path.startsWith("/clock")) {
                handleClock(body, query, socket.getOutputStream());
            } else if ("GET".equals(method) && path.startsWith("/settings")) {
                respond(socket.getOutputStream(), 200, listener.onSettings());
            } else if ("POST".equals(method) && path.startsWith("/settings")) {
                handleSettings(body, query, socket.getOutputStream());
            } else if ("POST".equals(method) && path.startsWith("/wifi")) {
                handleWifi(body, query, socket.getOutputStream());
            } else if ("POST".equals(method) && path.startsWith("/snapshot")) {
                handleSnapshot(socket.getOutputStream());
            } else if ("GET".equals(method) && path.startsWith("/state")) {
                respond(socket.getOutputStream(), 200, listener.onState());
            } else if ("GET".equals(method) && path.startsWith("/record")) {
                handleRecord(query, socket.getOutputStream());
            } else if ("GET".equals(method) && path.startsWith("/listen")) {
                handleListen(query, socket.getOutputStream());
            } else if ("GET".equals(method) && path.startsWith("/clip")) {
                handleClip(socket.getOutputStream());
            } else if ("GET".equals(method) && path.startsWith("/health")) {
                respond(socket.getOutputStream(), 200, "{\"ok\":true}");
            } else {
                respond(socket.getOutputStream(), 404, "{\"ok\":false,\"error\":\"no such endpoint\"}");
            }
        } catch (IOException e) {
            // 连接断了就断了，不打扰她
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * POST /video —— 副屏播一段视频 / 收工 / 调抠像。
     *
     * <pre>
     *   POST /video?url=http%3A%2F%2F192.168.31.10%3A8000%2Fa.mp4&loop=1
     *   POST /video   {"url":"http://…/a.mp4","loop":true}      ← 推荐，省掉两次编码
     *   POST /video?stop=1
     *   POST /video?key=green:0.30
     * </pre>
     *
     * <p>为什么不用 DLNA/投屏 App：这块屏要「背景透明 + 和模型同一个镜像」，
     * 第三方播放器两样都做不到（详见 {@code VideoStage} 的注释）。
     */
    private void handleVideo(String body, String query, OutputStream out) throws IOException {
        final String key = queryValue(query, "key");
        if (key != null && key.length() > 0) {
            final boolean ok = listener.onVideoKey(urlDecode(key));
            respond(out, ok ? 200 : 400, ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"bad key\"}");
            return;
        }
        if (truthy(queryValue(query, "stop"))) {
            listener.stopVideo();
            respond(out, 200, "{\"ok\":true,\"video\":\"stopped\"}");
            return;
        }
        String url = queryValue(query, "url");
        if (url == null || url.length() == 0) {
            url = jsonString(body, "url");
        }
        boolean loop = true;
        final String qLoop = queryValue(query, "loop");
        if (qLoop != null) {
            loop = truthy(qLoop);
        } else {
            final String bLoop = jsonRaw(body, "loop");
            if (bLoop != null) {
                loop = truthy(bLoop);
            }
        }
        if (url == null || url.length() == 0) {
            respond(out, 400, "{\"ok\":false,\"error\":\"need url\"}");
            return;
        }
        final String src = urlDecode(url.trim());
        final boolean ok = listener.onVideo(src, loop);
        respond(out, ok ? 200 : 500, ok
                ? "{\"ok\":true,\"video\":\"playing\",\"src\":\"" + escape(src) + "\"}"
                : "{\"ok\":false,\"error\":\"open failed\"}");
    }

    /**
     * POST /upload?name=xxx.mp4，请求体就是文件本体（{@code Content-Type} 随便填）。
     *
     * <p>PC 那边不一定有能对外提供 HTTP 的空间存储（还有防火墙），所以让她自己收下文件
     * 最省事：收完落在 {@code /sdcard/amber/video/} 再播，播的是本地文件，不卡顿。
     * 边收边写，不经过内存 —— 一段 1080p 的跳舞视频几十上百 MB。
     */
    private void handleUpload(Socket socket, InputStream in, int contentLength, String query)
            throws IOException {
        final OutputStream out = socket.getOutputStream();
        if (contentLength <= 0) {
            respond(out, 411, "{\"ok\":false,\"error\":\"need content-length\"}");
            return;
        }
        String name = queryValue(query, "name");
        name = safeName(name == null ? "" : urlDecode(name));
        final File dir = new File(VIDEO_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            respond(out, 500, "{\"ok\":false,\"error\":\"mkdir failed\"}");
            return;
        }
        final File target = new File(dir, name);
        // 默认 5 秒的 SO_TIMEOUT 会在传大文件时把连接掐断
        socket.setSoTimeout(180000);
        long total = 0;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(target);
            byte[] buf = new byte[64 * 1024];
            int left = contentLength;
            while (left > 0) {
                final int n = in.read(buf, 0, Math.min(buf.length, left));
                if (n < 0) {
                    break;
                }
                fos.write(buf, 0, n);
                left -= n;
                total += n;
            }
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                    // 关不上也不影响已经写进去的部分
                }
            }
        }
        Log.i("AmberVideo", "收到视频 " + name + " " + total + " 字节");
        // 视频一律循环：副屏的意思是「一块一直在放的屏」，播完停在最后一帧更奇怪
        final boolean ok = total > 0 && listener.onVideo(target.getAbsolutePath(), true);
        respond(out, ok ? 200 : 500, "{\"ok\":" + ok + ",\"file\":\"" + escape(name)
                + "\",\"bytes\":" + total + "}");
    }

    /**
     * GET /video/list —— 已上传的视频清单（按修改时间倒序）+ 总占用。
     *
     * <p>给调参台的「清理上传视频」用：列出来、能删单个、能一键清空。
     */
    private void handleVideoList(OutputStream out) throws IOException {
        final File dir = new File(VIDEO_DIR);
        File[] files = dir.listFiles();
        if (files == null) {
            files = new File[0];
        }
        // 按修改时间倒序（选排，文件个数是几十的量级，不值得为它引 Arrays/Comparator）
        for (int i = 0; i < files.length; i++) {
            for (int j = i + 1; j < files.length; j++) {
                if (files[j].lastModified() > files[i].lastModified()) {
                    final File t = files[i];
                    files[i] = files[j];
                    files[j] = t;
                }
            }
        }
        final StringBuilder items = new StringBuilder();
        long total = 0;
        int count = 0;
        for (int i = 0; i < files.length; i++) {
            final File f = files[i];
            if (!f.isFile() || f.getName().startsWith(".")) {
                continue;
            }
            final long size = f.length();
            total += size;
            if (count > 0) {
                items.append(',');
            }
            items.append("{\"name\":\"").append(escape(f.getName()))
                    .append("\",\"bytes\":").append(size)
                    .append(",\"mtime\":").append(f.lastModified()).append('}');
            count++;
        }
        respond(out, 200, "{\"ok\":true,\"dir\":\"" + escape(VIDEO_DIR)
                + "\",\"files\":[" + items + "],\"count\":" + count + ",\"bytes\":" + total + "}");
    }

    /** POST /video/delete?name=xxx.mp4 —— 删单个文件。正在播的那段会先停下。 */
    private void handleVideoDelete(String query, OutputStream out) throws IOException {
        final String raw = queryValue(query, "name");
        if (raw == null || raw.trim().length() == 0) {
            respond(out, 400, "{\"ok\":false,\"error\":\"need name\"}");
            return;
        }
        final String name = safeName(urlDecode(raw).trim());
        final File target = new File(new File(VIDEO_DIR), name);
        if (!target.isFile()) {
            respond(out, 404, "{\"ok\":false,\"error\":\"no such file\",\"name\":\"" + escape(name) + "\"}");
            return;
        }
        stopIfPlaying(target);
        final long size = target.length();
        final boolean ok = target.delete();
        Log.i("AmberVideo", "删除视频 " + name + " -> " + ok);
        respond(out, ok ? 200 : 500, "{\"ok\":" + ok + ",\"name\":\"" + escape(name)
                + "\",\"freed\":" + (ok ? size : 0) + "}");
    }

    /** POST /video/clean —— 清空上传目录（只删文件，目录本身留着）。 */
    private void handleVideoClean(OutputStream out) throws IOException {
        final File[] files = new File(VIDEO_DIR).listFiles();
        long freed = 0;
        int removed = 0;
        int failed = 0;
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                final File f = files[i];
                if (!f.isFile() || f.getName().startsWith(".")) {
                    continue;
                }
                stopIfPlaying(f);
                final long size = f.length();
                if (f.delete()) {
                    freed += size;
                    removed++;
                } else {
                    failed++;
                }
            }
        }
        Log.i("AmberVideo", "清空视频：删 " + removed + " 个、失败 " + failed + "、释放 " + freed + " 字节");
        respond(out, 200, "{\"ok\":" + (failed == 0) + ",\"removed\":" + removed
                + ",\"failed\":" + failed + ",\"freed\":" + freed + "}");
    }

    /**
     * 要删的这段正在播就先停掉。
     *
     * <p>不停的话播放器还攥着已经 unlink 的 inode：删是删掉了，但画面会僵在最后一帧，
     * 看着像「清理没生效」。停播是切主线程异步做的，这里稍等一下再删。
     */
    private void stopIfPlaying(File f) {
        final String playing = listener.currentVideoSource();
        if (playing == null || !playing.equals(f.getAbsolutePath())) {
            return;
        }
        listener.stopVideo();
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 上传文件名：只留字母数字、点、横线、下划线和中文，防止 {@code ../} 之类跑出目录。 */
    private static String safeName(String raw) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 96; i++) {
            final char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || (c >= 0x4e00 && c <= 0x9fff)) {
                sb.append(c);
            }
        }
        String name = sb.toString();
        if (name.length() == 0 || name.startsWith(".")) {
            name = "upload" + System.currentTimeMillis() + ".mp4";
        }
        return name;
    }

    private static String urlDecode(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * GET /record?sec=3 —— 录一小段存 /sdcard/amber_mic.wav，返回 RMS / 峰值 / 字节数。
     *
     * <p>这是唤醒词的前置探针：先确认麦克风真的采得到声音，再决定要不要上离线 KWS。
     * 返回里带 {@code rms} 是为了把「静音（rms≈0）」和「根本没设备/没权限（error）」
     * 一眼分开——这两种情况在设备上看起来都是「没反应」，但修法完全不同。
     */
    private void handleRecord(String query, OutputStream out) throws IOException {
        int sec = 3;
        String qSec = queryValue(query, "sec");
        if (qSec != null) {
            sec = (int) parseLong(qSec, 3);
        }
        if (sec < 1) sec = 1;
        if (sec > 120) sec = 120;    // 唤醒词要长窗口；再长就没必要了（一次请求占线程）
        try {
            com.amber.speaker.sound.Recorder.Result r =
                    com.amber.speaker.sound.Recorder.record(sec, "/sdcard/amber_mic.wav");
            respond(out, 200, r.toJson());
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).replace("\"", "'");
            respond(out, 500, "{\"ok\":false,\"error\":\"" + msg + "\"}");
        }
    }

    /**
     * GET /listen?max=60&need=500 —— 一直听，抓到一段人声就返回。
     *
     * <p>和 {@code /record} 的区别是「谁定时间」：{@code /record} 是调用方定秒数，
     * 人什么时候开口全看运气；这个是人定时间，抓到就回。唤醒词/对话都靠它。
     *
     * <p>返回 {@code hit=false} 表示等到 {@code max} 秒也没人说话（不是错误）。
     */
    private void handleListen(String query, OutputStream out) throws IOException {
        int max = 60;
        int need = 500;
        String qMax = queryValue(query, "max");
        if (qMax != null) {
            max = (int) parseLong(qMax, 60);
        }
        if (max < 3) max = 3;
        if (max > 300) max = 300;
        String qNeed = queryValue(query, "need");
        if (qNeed != null) {
            need = (int) parseLong(qNeed, 350);
        }
        if (need < 80) need = 80;
        if (need > max * 1000) need = max * 1000;
        // 诊断用：指定录音源做 AEC 对照实验。正常调用不传 = MIC。
        //   mic = 默认；vc = VOICE_COMMUNICATION（HAL 可能带 AEC）；vr = VOICE_RECOGNITION
        int audioSource = MediaRecorder.AudioSource.MIC;
        String qSource = queryValue(query, "source");
        if ("vc".equalsIgnoreCase(qSource)) {
            audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        } else if ("vr".equalsIgnoreCase(qSource)) {
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
        // 诊断：?aec=1 尝试开 Android 的 AcousticEchoCanceler（HAL 支持才有效）。
        boolean aecOn = truthy(queryValue(query, "aec"));
        final long listenT0 = System.currentTimeMillis();
        Log.i("AmberListen", "/listen 开始 source=" + audioSource
                + " max=" + max + " need=" + need + " aec=" + aecOn);
        // 自己正在说话 / 刚说完 / 还有话排队的时候不要听：麦克风就在喇叭旁边，
        // 会把她的声音当成「有人在说话」。调用方看到 busy 就等一会儿再来。
        // 判据交给 MainActivity：只查 speaking 不够，配音准备和尾音都会漏。
        if (listener.busyForListen()) {
            Log.i("AmberListen", "/listen busy（设备忙）：" + (System.currentTimeMillis() - listenT0) + "ms");
            respond(out, 200, "{\"ok\":true,\"hit\":false,\"busy\":true}");
            return;
        }
        listenAbort.set(false);
        try {
            com.amber.speaker.sound.Listener.Result r =
                    com.amber.speaker.sound.Listener.listen(
                            max, need, "/sdcard/amber_heard.wav", listenAbort, audioSource, aecOn);
            Log.i("AmberListen", "/listen 返回 hit=" + r.hit + " voice=" + r.voiceMs
                    + " 用时=" + (System.currentTimeMillis() - listenT0) + "ms");
            respond(out, 200, r.toJson());
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).replace("\"", "'");
            respond(out, 500, "{\"ok\":false,\"error\":\"" + msg + "\"}");
        }
    }

    /**
     * GET /clip —— 把上一次 {@code /listen} 抓到的那段 WAV 原样吐出来。
     *
     * <p>设备没有文件服务，NAS 那边要拿音频做识别只能走这个。返回的是裸 WAV，
     * 不做 base64、不包 JSON——识别服务要的就是一段字节流。
     */
    private void handleClip(OutputStream out) throws IOException {
        File f = new File("/sdcard/amber_heard.wav");
        if (!f.exists()) {
            respond(out, 404, "{\"ok\":false,\"error\":\"还没有任何片段\"}");
            return;
        }
        byte[] buf = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            respondBytes(out, 200, "audio/wav", buf);
        } finally {
            in.close();
        }
    }

    private void handleSay(String body, String query, OutputStream out) throws IOException {
        // 调试：?noabort=1 时故意不掐断正在跑的 /listen，用来做 AEC 对照实验
        // （让播放和录音同时发生，看 HAL 是拆流还是有回声消除）。正常调用不带这个参数。
        if (!truthy(queryValue(query, "noabort"))) {
            listenAbort.set(true);      // 要开口了，别再听着自己的声音
        }
        String text;
        String id = "";
        String audioUrl = null;
        boolean interrupt = false;
        long ttlMs = 0;

        // 查询串和 JSON 两种写法都收。查询串是给人手测用的（curl 一行就能试），
        // JSON 是给 AstrBot 那种结构化调用用的。
        String qInterrupt = queryValue(query, "interrupt");
        if (qInterrupt != null) {
            interrupt = truthy(qInterrupt);
        }
        String qTtl = queryValue(query, "ttl");
        if (qTtl != null) {
            ttlMs = secondsToMillis(qTtl);
        }
        String qTtlMs = queryValue(query, "ttl_ms");
        if (qTtlMs != null) {
            ttlMs = parseLong(qTtlMs, ttlMs);
        }
        String qAudio = queryValue(query, "audio_url");
        if (qAudio != null && qAudio.trim().length() > 0) {
            audioUrl = qAudio.trim();
        }

        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            // 契约允许 JSON，但纯文本才是主路径（AstrBot 那边一句 speak() 直接丢过来）
            text = jsonString(trimmed, "text");
            String got = jsonString(trimmed, "id");
            if (got != null) {
                id = got;
            }
            String jAudio = jsonString(trimmed, "audio_url");
            if (jAudio != null && jAudio.trim().length() > 0) {
                audioUrl = jAudio.trim();
            }
            String jInterrupt = jsonRaw(trimmed, "interrupt");
            if (jInterrupt != null) {
                interrupt = truthy(jInterrupt);
            }
            String jTtl = jsonRaw(trimmed, "ttl");
            if (jTtl != null) {
                ttlMs = secondsToMillis(jTtl);
            }
            String jTtlMs = jsonRaw(trimmed, "ttl_ms");
            if (jTtlMs != null) {
                ttlMs = parseLong(jTtlMs, ttlMs);
            }
        } else {
            text = body;
        }
        if (Text.isBlank(text)) {
            // 空 body 要拒绝。注意不能用 length()==0 判断：一个全角空格长度是 1，
            // 放过去她就得说一句根本不存在的空气话，字幕还会被清空。
            respond(out, 400, "{\"ok\":false,\"error\":\"empty text\"}");
            return;
        }
        listener.onSay(text, id, audioUrl, interrupt, ttlMs);

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true");
        if (id.length() > 0) {
            json.append(",\"id\":\"").append(escape(id)).append('"');
        }
        json.append(",\"bubbles\":").append(countLines(text));
        json.append(",\"interrupt\":").append(interrupt);
        json.append(",\"ttl_ms\":").append(ttlMs);
        json.append(",\"audio\":").append(audioUrl != null);
        // 排了多深不在这里回：队列在主线程上，这个响应比主线程先返回，如实答只能是猜。
        // 要深度就查 /state 的 queued，那是准的。
        json.append('}');
        respond(out, 200, json.toString());
    }

    private void handleWake(String body, String query, OutputStream out) throws IOException {
        listenAbort.set(true);      // 应答「小鱼在」之前先停掉监听
        // 关键词从查询串或 JSON 里取，默认「大肥鱼」（另一唤醒词「小鱼小鱼」用 ?keyword= 传入）。
        // 到货前用这个端点模拟喊醒。
        String keyword = "大肥鱼";
        String q = queryValue(query, "keyword");
        if (q != null && q.trim().length() > 0) {
            keyword = q.trim();
        }
        // 唤醒来源：默认 voice；?source=proximity 模拟「有人走近」的唤醒
        String source = "voice";
        String qs = queryValue(query, "source");
        if (qs != null && qs.trim().length() > 0) {
            source = qs.trim();
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            String j = jsonString(trimmed, "keyword");
            if (j != null && j.trim().length() > 0) {
                keyword = j.trim();
            }
            String js = jsonString(trimmed, "source");
            if (js != null && js.trim().length() > 0) {
                source = js.trim();
            }
        }
        listener.onWake(keyword, source);
        respond(out, 200, "{\"ok\":true,\"keyword\":\"" + escape(keyword)
                + "\",\"source\":\"" + escape(source) + "\"}");
    }

    private void handleControl(String body, String query, OutputStream out) throws IOException {
        // 契约 §7：{ "action": "display", "value": "off" }。也收查询串 ?action=display&value=off，
        // 给 curl 手测用。value 用 jsonRaw 取（display 的 on/off 是字符串，brightness/volume 是数字）。
        String action = queryValue(query, "action");
        String value = queryValue(query, "value");
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            String ja = jsonString(trimmed, "action");
            if (ja != null && ja.trim().length() > 0) {
                action = ja.trim();
            }
            // value 可能是字符串（"off"）也可能是数字（0.4），用 jsonRaw 原文取，
            // 再按动作语义解释。display 只认 on/off 字符串。
            String jv = jsonRaw(trimmed, "value");
            if (jv != null && jv.trim().length() > 0) {
                value = jv;
            }
        }
        if (Text.isBlank(action)) {
            respond(out, 400, "{\"ok\":false,\"error\":\"missing action\"}");
            return;
        }
        // reboot 无 value（契约 §7：value 一栏是「—」），不要求 value 非空。
        if (Text.isBlank(value) && !"reboot".equals(action)) {
            respond(out, 400, "{\"ok\":false,\"error\":\"missing value\"}");
            return;
        }
        listener.onControl(action.trim(), value == null ? "" : value.trim());
        respond(out, 200, "{\"ok\":true,\"action\":\"" + escape(action.trim())
                + "\",\"value\":\"" + escape(value.trim()) + "\"}");
    }

    /**
     * POST /clock —— 把真实时间推给她。
     *
     * <p>两种写法都收，手测和 AstrBot 各用各的：
     * <pre>
     *   curl -X POST "http://音箱:8788/clock?epoch=1795000000"
     *   curl -X POST -d '{"epoch":1795000000}' http://音箱:8788/clock
     * </pre>
     * 秒级（10 位）和毫秒级（13 位）都认：小于 {@code 1e11} 的当秒，乘 1000。
     */
    private void handleClock(String body, String query, OutputStream out) throws IOException {
        long epoch = parseEpoch(queryValue(query, "epoch"));
        if (epoch <= 0) {
            epoch = parseEpoch(jsonRaw(body.trim(), "epoch"));
        }
        if (epoch <= 0) {
            respond(out, 400, "{\"ok\":false,\"error\":\"epoch required\"}");
            return;
        }
        if (!listener.onClock(epoch)) {
            respond(out, 400, "{\"ok\":false,\"error\":\"untrusted epoch\"}");
            return;
        }
        respond(out, 200, "{\"ok\":true,\"epoch\":" + epoch + "}");
    }

    /**
     * POST /settings —— 改一项行为设置，改完立刻生效并落盘。
     *
     * <pre>
     *   curl -X POST -d '{"key":"listen_sec","value":"25"}' http://音箱:8788/settings
     *   curl -X POST -d '{"key":"sleep_start","value":"23:30"}' http://音箱:8788/settings
     *   curl -X POST -d '{"key":"presence_on","value":"false"}' http://音箱:8788/settings
     *   curl -X POST -d '{"key":"reset"}' http://音箱:8788/settings
     * </pre>
     *
     * <p>时间收两种写法：{@code "520"}（当天第几分钟）或 {@code "08:40"}。
     * 改完返回整份设置，前端不用再发一次 GET。
     */
    private void handleSettings(String body, String query, OutputStream out) throws IOException {
        String key = queryValue(query, "key");
        String value = queryValue(query, "value");
        final String trimmed = body == null ? "" : body.trim();
        if (key == null && trimmed.startsWith("{")) {
            key = jsonString(trimmed, "key");
            value = jsonString(trimmed, "value");
        }
        if (key == null || key.trim().length() == 0) {
            respond(out, 400, "{\"ok\":false,\"error\":\"need key\"}");
            return;
        }
        final String err = listener.onSetting(key.trim(), value == null ? "" : value.trim());
        if (err != null) {
            respond(out, 400, "{\"ok\":false,\"error\":\"" + escape(err) + "\"}");
            return;
        }
        final String all = listener.onSettings();
        // onSettings 已经带了 {"ok":true,"settings":…}，这里只补一个 key 回显
        respond(out, 200, all.substring(0, all.length() - 1) + ",\"key\":\"" + escape(key.trim()) + "\"}");
    }

    /**
     * POST /wifi —— 配网。
     *
     * <pre>
     *   curl -X POST -d '{"ssid":"主路由","pass":"12345678"}' http://音箱:8788/wifi
     *   curl -X POST "http://音箱:8788/wifi?ssid=MyWiFi&pass=12345678"     # 纯 ASCII 才稳
     * </pre>
     *
     * <p>SSID 用 JSON body 传最稳：查询串里的中文要 URL 编码，而这里的手写解析不解码。
     * 密码为空表示开放网络。
     */
    private void handleWifi(String body, String query, OutputStream out) throws IOException {
        String ssid = queryValue(query, "ssid");
        String pass = queryValue(query, "pass");
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            String js = jsonString(trimmed, "ssid");
            if (js != null && js.trim().length() > 0) {
                ssid = js;
            }
            String jp = jsonString(trimmed, "pass");
            if (jp != null) {
                pass = jp;  // 允许空串＝开放网络
            }
        }
        if (ssid == null || ssid.trim().length() == 0) {
            respond(out, 400, "{\"ok\":false,\"error\":\"missing ssid\"}");
            return;
        }
        final boolean ok = listener.onWifi(ssid.trim(), pass == null ? "" : pass);
        respond(out, ok ? 200 : 500,
                "{\"ok\":" + ok + ",\"ssid\":\"" + escape(ssid.trim()) + "\"}");
    }

    /**
     * POST /snapshot —— 「眼睛」工具（契约 §7.1）：主动取一帧画面交视觉模型分析。
     *
     * <p>设备端只负责取一帧 JPEG、转 base64、原样交出去（不落盘、不识别、不分析），
     * 画面分析在 AstrBot / SiliconFlow 侧。额度闸门在设备端（{@code listener.onSnapshot}），
     * 超额度返回结构化回执「额度已用完」，不带画面。
     */
    private void handleSnapshot(OutputStream out) throws IOException {
        // 同步阻塞取帧（内部最多等 5 秒）。这是「主动调用」而非常开监控，额度约束在设备端。
        String result = listener.onSnapshot();
        respond(out, 200, result);
    }

    /** 把 "1730000000" / "1730000000000" / "1730000000.5" 都解析成毫秒；失败返回 0。 */
    private static long parseEpoch(String raw) {
        if (raw == null) {
            return 0;
        }
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        int dot = s.indexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);  // 小数秒直接截掉
        }
        try {
            long v = Long.parseLong(s);
            if (v <= 0) {
                return 0;
            }
            return v < 100000000000L ? v * 1000L : v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 取查询串里的一个值，没有就返回 null。不处理 URL 编码——我们的参数只有数字和 0/1。 */
    private static String queryValue(String query, String key) {
        if (query == null || query.length() == 0) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                if (key.equals(pair)) {
                    return "";
                }
                continue;
            }
            if (key.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /** 认 1/true/yes/on（大小写不限）为真。空串也算真——{@code ?interrupt} 这种写法意图很明确。 */
    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim().toLowerCase(java.util.Locale.US);
        return text.length() == 0 || "1".equals(text) || "true".equals(text)
                || "yes".equals(text) || "on".equals(text);
    }

    /**
     * {@code ttl} 按<b>秒</b>算，可以带小数（{@code ttl=2.5}）。毫秒请用 {@code ttl_ms}。
     *
     * <p>为什么不统一成一个单位：秒是给人写的，`ttl=30` 一眼知道多久；而毫秒是给程序算的。
     * 真正危险的是「以为是毫秒传了 30000，结果排了 8 小时」，所以两个键分开、各自明确。
     */
    private static long secondsToMillis(String value) {
        try {
            double seconds = Double.parseDouble(value.trim());
            if (seconds <= 0) {
                return 0;
            }
            return (long) (seconds * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int countLines(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- HTTP 细节

    /** 读到 \r\n\r\n 为止，返回 header 原文（含结尾空行）。连接提前断掉返回 null。 */
    private static ByteArrayOutputStream readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        int[] last = new int[4];
        int count = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buffer.write(b);
            last[count & 3] = b;
            count++;
            if (count >= 4
                    && last[(count - 4) & 3] == '\r'
                    && last[(count - 3) & 3] == '\n'
                    && last[(count - 2) & 3] == '\r'
                    && last[(count - 1) & 3] == '\n') {
                return buffer;
            }
            if (count > 32 * 1024) {
                return null;
            }
        }
    }

    private static String readBody(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return new String(body, 0, read, "UTF-8");
    }

    private static void respond(OutputStream out, int code, String body) throws IOException {
        respondBytes(out, code, "application/json; charset=utf-8", body.getBytes("UTF-8"));
    }

    /** 二进制响应：{@code /clip} 要把录到的 WAV 原样吐给 NAS 去做识别。 */
    private static void respondBytes(OutputStream out, int code, String type, byte[] bytes)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " Error").append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(bytes.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已经关了
            }
        }
    }

    /**
     * 从 JSON 里取一个字符串字段。故意写得很笨：只认双引号包裹、支持 \" \\ \n \t 这几种转义。
     *
     * <p>不引 JSON 库是因为只为一个字段引 200KB 的依赖不划算，而契约里的 JSON 就是我们自己人写的。
     * 但**不要**拿它去解析任意来源的 JSON。
     */
    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder out = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == 'n') {
                    out.append('\n');
                } else if (next == 't') {
                    out.append('\t');
                } else if (next == 'r') {
                    out.append('\r');
                } else if (next == 'u' && i + 5 < json.length()) {
                    try {
                        out.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                        i += 6;
                        continue;
                    } catch (NumberFormatException e) {
                        out.append(next);
                    }
                } else {
                    out.append(next);
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                return out.toString();
            }
            out.append(c);
            i++;
        }
        return null;
    }

    /**
     * 取一个**非字符串**字段的原文（数字、true/false），取不到返回 null。
     *
     * <p>和 {@link #jsonString} 一样笨：只认「key 后面紧跟冒号」的简单结构。
     * 够用是因为这些 JSON 都是我们自己人写的，不要拿它去解析任意来源。
     */
    private static String jsonRaw(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        if (json.charAt(i) == '"') {
            return jsonString(json, key);
        }
        int start = i;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ',' || c == '}' || Character.isWhitespace(c)) {
                break;
            }
            i++;
        }
        return i > start ? json.substring(start, i) : null;
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}

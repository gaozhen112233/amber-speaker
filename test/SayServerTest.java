import com.amber.speaker.net.SayServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 网络层自测。{@link SayServer} 只用了 java.net，没有 android.* 依赖，
 * 所以能在 PC 上起真服务、发真请求——这比在模拟器里点按钮可靠得多。
 *
 * <p>HTTP 这一层最容易出的 bug 都在细节：header 边界识别、body 长度、
 * UTF-8 解码、「纯文本 body」这条非常规路径。这些全都能在这里抓住。
 */
public final class SayServerTest {

    private static int passed;
    private static int failed;
    private static final java.util.List<String> failures = new java.util.ArrayList<String>();

    private static String gotText;
    private static String gotId;
    private static String gotAudio;
    private static String gotSource;
    private static boolean gotInterrupt;
    private static long gotTtl;
    private static int sayCount;
    private static String gotAction;
    private static String gotValue;
    private static long gotEpoch;
    private static String gotSsid;
    private static String gotPass;

    public static void main(String[] args) throws Exception {
        SayServer server = new SayServer(new SayServer.Listener() {
            @Override
            public void onSay(String text, String id, String audioUrl, boolean interrupt, long ttlMs) {
                gotText = text;
                gotId = id;
                gotAudio = audioUrl;
                gotInterrupt = interrupt;
                gotTtl = ttlMs;
                sayCount++;
            }

            @Override
            public void onWake(String keyword, String source) {
                gotText = keyword;
                gotSource = source;
            }

            @Override
            public void onControl(String action, String value) {
                gotAction = action;
                gotValue = value;
            }

            @Override
            public String onState() {
                return "{\"ok\":true,\"speaking\":false,\"queued\":0}";
            }

            @Override
            public boolean busyForListen() {
                // 测试里不跑录音，永远允许；/listen 本身不在本测试覆盖范围（会阻塞等真人说话）
                return false;
            }

            @Override
            public boolean onClock(long epochMs) {
                gotEpoch = epochMs;
                // 和真实实现一样：太旧的时间拒收
                return epochMs >= 1577836800000L;
            }

            @Override
            public boolean onWifi(String ssid, String pass) {
                gotSsid = ssid;
                gotPass = pass;
                return !"fail".equals(ssid);
            }
        });
        server.start();
        Thread.sleep(200);

        // 1. 纯文本 body —— 这是 AstrBot 的主路径
        String body = post("/say", "〔歪头 微笑〕你终于来了，〔眨眼〕我等好久了", "text/plain; charset=utf-8");
        eq("纯文本：状态码", 200, statusOf(body));
        eq("纯文本：收到的原文", "〔歪头 微笑〕你终于来了，〔眨眼〕我等好久了", gotText);
        eq("纯文本：没有 id", "", gotId);
        ok("纯文本：响应带 bubbles", body.contains("\"bubbles\":1"));

        // 2. JSON body
        post("/say", "{\"text\":\"〔点头〕好的\",\"id\":\"s-123\"}", "application/json");
        eq("JSON：取到 text", "〔点头〕好的", gotText);
        eq("JSON：取到 id", "s-123", gotId);

        // 3. JSON 里的转义换行
        post("/say", "{\"text\":\"第一行\\n第二行\"}", "application/json");
        eq("JSON：换行转义", "第一行\n第二行", gotText);
        ok("JSON：bubbles 算两行", lastBody.contains("\"bubbles\":2"));

        // 4. 空 body 要拒绝，不能让她说一句空话
        String empty = post("/say", "   ", "text/plain");
        eq("空 body：400", 400, statusOf(empty));

        // 5. /state 和 /health
        String state = get("/state");
        ok("/state 有 speaking", state.contains("\"speaking\":false"));
        String health = get("/health");
        ok("/health", health.contains("\"ok\":true"));

        // 6. 没这个端点
        eq("404", 404, statusOf(get("/nope")));

        // 7. 中文不会被截断（多字节字符跨缓冲区）
        StringBuilder longText = new StringBuilder("〔歪头〕");
        for (int i = 0; i < 90; i++) {
            longText.append("字");
        }
        post("/say", longText.toString(), "text/plain; charset=utf-8");
        eq("长中文完整收到", longText.toString(), gotText);

        // 8. 默认不打断、不过期 —— 和以前的排队行为一模一样
        gotInterrupt = true;
        gotTtl = -1;
        post("/say", "普通一句", "text/plain");
        eq("默认：interrupt=false", false, gotInterrupt);
        eq("默认：ttl=0", 0L, gotTtl);

        // 9. 查询串写法：curl 手测就靠这个
        post("/say?interrupt=1&ttl=30", "打断一下", "text/plain");
        eq("查询串：interrupt", true, gotInterrupt);
        eq("查询串：ttl 30 秒 -> 30000ms", 30000L, gotTtl);
        ok("查询串：响应回显 ttl_ms", lastBody.contains("\"ttl_ms\":30000"));

        // 10. 查询串的 ttl 可以带小数
        post("/say?ttl=2.5", "两秒半", "text/plain");
        eq("查询串：ttl=2.5 -> 2500ms", 2500L, gotTtl);

        // 11. ttl_ms 是毫秒，不会被当成秒
        post("/say?ttl_ms=30000", "三十秒", "text/plain");
        eq("ttl_ms 直给", 30000L, gotTtl);

        // 12. JSON 写法
        post("/say", "{\"text\":\"闹一下\",\"interrupt\":true,\"ttl\":12}", "application/json");
        eq("JSON：interrupt", true, gotInterrupt);
        eq("JSON：ttl 12 秒", 12000L, gotTtl);
        eq("JSON：正文没被 interrupt 带歪", "闹一下", gotText);

        // 13. ?interrupt 不带等号也算真（写法意图很明确）
        post("/say?interrupt", "没等号的写法", "text/plain");
        eq("?interrupt 无值也算真", true, gotInterrupt);

        // 14. interrupt=false 要能明确关掉
        post("/say?interrupt=0", "别打断", "text/plain");
        eq("interrupt=0 是假", false, gotInterrupt);

        // 15. 空 body 依然优先报错，不会被 interrupt 绕过
        eq("空 body 带 interrupt 仍然是 400", 400, statusOf(post("/say?interrupt=1", "  ", "text/plain")));

        // 16. audio_url：JSON 里的配音地址
        gotAudio = "没被清掉";
        post("/say", "{\"text\":\"带配音的一句\",\"audio_url\":\"http://192.168.1.9:9880/a.mp3\"}", "application/json");
        eq("JSON：audio_url 传到监听器", "http://192.168.1.9:9880/a.mp3", gotAudio);
        ok("JSON：响应回显 audio=true", lastBody.contains("\"audio\":true"));

        // 17. audio_url：查询串写法（手测用）
        post("/say?audio_url=file:///data/local/tmp/a.wav", "手测配音", "text/plain");
        eq("查询串：audio_url", "file:///data/local/tmp/a.wav", gotAudio);

        // 18. 没给 audio_url 时必须是 null，不能是空串——
        //     空串会被当成「有配音」，上层就会去等下不出来的音频
        gotAudio = "没被清掉";
        post("/say", "没有配音的一句", "text/plain");
        eq("没配音：audio 是 null", null, gotAudio);
        ok("没配音：响应回显 audio=false", lastBody.contains("\"audio\":false"));

        // 19. 空字符串的 audio_url 与没给等价
        gotAudio = "没被清掉";
        post("/say", "{\"text\":\"空地址\",\"audio_url\":\"\"}", "application/json");
        eq("空 audio_url 当没有", null, gotAudio);

        // 20. /wake 唤醒端点：默认关键词「大肥鱼」、默认来源 voice
        post("/wake", "", "text/plain");
        eq("/wake 默认关键词", "大肥鱼", gotText);
        eq("/wake 默认来源 voice", "voice", gotSource);
        ok("/wake 响应带 keyword", lastBody.contains("\"keyword\":\"大肥鱼\""));

        // 21. /wake 查询串指定关键词（用 ASCII 避开控制台中文编码歧义）
        post("/wake?keyword=wake1", "", "text/plain");
        eq("/wake 查询串关键词", "wake1", gotText);

        // 22. /wake JSON 指定关键词
        post("/wake", "{\"keyword\":\"在吗\"}", "application/json");
        eq("/wake JSON 关键词", "在吗", gotText);

        // 23. /wake 来源：查询串 ?source=proximity 模拟「有人走近」
        post("/wake?source=proximity", "", "text/plain");
        eq("/wake 查询串来源 proximity", "proximity", gotSource);
        ok("/wake 响应带 source", lastBody.contains("\"source\":\"proximity\""));

        // 24. /wake 来源：JSON 指定
        post("/wake", "{\"keyword\":\"在吗\",\"source\":\"button\"}", "application/json");
        eq("/wake JSON 来源 button", "button", gotSource);
        eq("/wake JSON 关键词不丢", "在吗", gotText);

        // 25. /control display:off —— JSON 写法（契约 §7 主路径）
        post("/control", "{\"action\":\"display\",\"value\":\"off\"}", "application/json");
        eq("/control JSON action", "display", gotAction);
        eq("/control JSON value", "off", gotValue);
        ok("/control 响应回显 action", lastBody.contains("\"action\":\"display\""));

        // 26. /control display:on —— 查询串写法（curl 手测）
        post("/control?action=display&value=on", "", "text/plain");
        eq("/control 查询串 action", "display", gotAction);
        eq("/control 查询串 value", "on", gotValue);
        ok("/control 查询串响应回显 value", lastBody.contains("\"value\":\"on\""));

        // 27. /control 缺 action / 缺 value 要拒绝
        eq("/control 缺 action 400", 400, statusOf(post("/control", "{\"value\":\"off\"}", "application/json")));
        eq("/control 缺 value 400", 400, statusOf(post("/control", "{\"action\":\"display\"}", "application/json")));

        // 28. /clock 查询串：秒级（10 位）自动乘 1000
        gotEpoch = 0;
        post("/clock?epoch=1795000000", "", "text/plain");
        eq("/clock 秒级转毫秒", 1795000000000L, gotEpoch);
        ok("/clock 响应回显 epoch", lastBody.contains("\"epoch\":1795000000000"));

        // 29. /clock JSON：毫秒级原样
        gotEpoch = 0;
        post("/clock", "{\"epoch\":1795000000123}", "application/json");
        eq("/clock 毫秒级原样", 1795000000123L, gotEpoch);

        // 30. /clock 缺 epoch 要拒绝
        eq("/clock 缺 epoch 400", 400, statusOf(post("/clock", "", "text/plain")));

        // 31. /clock 给个 1977 年的坏时间要拒绝（不能把错误的钟再设一遍）
        eq("/clock 拒收坏时间 400", 400, statusOf(post("/clock?epoch=241000000", "", "text/plain")));

        // 32. /wifi：JSON body 带中文 SSID（主路径，不受 URL 编码影响）
        gotSsid = null;
        gotPass = null;
        post("/wifi", "{\"ssid\":\"主路由\",\"pass\":\"abc12345\"}", "application/json");
        eq("/wifi 中文 SSID", "主路由", gotSsid);
        eq("/wifi 密码", "abc12345", gotPass);
        ok("/wifi 响应回显 ssid", lastBody.contains("\"ok\":true"));

        // 33. /wifi：查询串写法（纯 ASCII SSID）
        post("/wifi?ssid=MyWiFi&pass=12345678", "", "text/plain");
        eq("/wifi 查询串 SSID", "MyWiFi", gotSsid);
        eq("/wifi 查询串密码", "12345678", gotPass);

        // 34. /wifi：开放网络（不传 pass 时是空串，不能是 null）
        gotPass = "没被清掉";
        post("/wifi", "{\"ssid\":\"OpenNet\",\"pass\":\"\"}", "application/json");
        eq("/wifi 开放网络密码为空", "", gotPass);

        // 35. /wifi 缺 ssid 要拒绝
        eq("/wifi 缺 ssid 400", 400, statusOf(post("/wifi", "{\"pass\":\"x\"}", "application/json")));

        // 36. 底层拒绝时回 500（不是 200），这样调用方能看出没连上
        eq("/wifi 底层失败 500", 500, statusOf(post("/wifi", "{\"ssid\":\"fail\",\"pass\":\"x\"}", "application/json")));

        server.stop();

        System.out.println();
        System.out.println("通过 " + passed + "  失败 " + failed);
        for (String f : failures) {
            System.out.println("  ✗ " + f);
        }
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String lastBody = "";

    private static String post(String path, String body, String contentType) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:8788" + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        byte[] bytes = body.getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(bytes.length);
        OutputStream out = conn.getOutputStream();
        out.write(bytes);
        out.close();
        String response = readAll(conn);
        lastBody = response;
        // 状态码从 header 拿，body 里没有
        lastStatus = conn.getResponseCode();
        return response;
    }

    private static int lastStatus;

    private static String get(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:8788" + path).openConnection();
        conn.setRequestMethod("GET");
        String response = readAll(conn);
        lastStatus = conn.getResponseCode();
        lastBody = response;
        return response;
    }

    private static int statusOf(String body) {
        return lastStatus;
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        in.close();
        return new String(buffer.toByteArray(), "UTF-8");
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

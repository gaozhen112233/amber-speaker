# 可改设置总表

分三层。**先看第一层**——绝大多数「想调一下」的需求到不了改代码那一层。

- 第一层：调参台（网页点几下，立即生效，写进设备）
- 第二层：AstrBot 平台配置（人设、唤醒词、音色、概率）
- 第三层：代码常量（要重新编译）

---

## 第一层 · 调参台

启动：`tools/model_console/start_console.cmd`（Windows 双击）或
`python tools/model_console/server.py <音箱IP>:8788`，浏览器开 `http://127.0.0.1:8790/`。

存储：设备 `SharedPreferences`，**拔电不丢**。设备和接口字段一一对应
（`GET/POST /settings`，见 [API.md](API.md#settings)）。

### 外观

| 界面 | 字段 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| 舞台灯带 | `light` | 65% | 0–100 | 所有灯效的**乘数**。调到 0 = 全灭（不是省电，是关灯） |
| 屏幕背光 | `brightness` | 100% | 5–100 | 低于 5% 基本看不见，别设 0 |
| 说话音量 | `volume` | 100% | 0–100 | TTS 播放增益，和顶部音量键同一个值 |
| 显示/隐藏字幕 | `overlay` | 显示 | on/off | 只影响那行灰字，用来分辨「这块像素是谁画的」 |
| 亮屏/熄屏 | `display` | 自动 | on/off | **手动覆盖**：发了之后自动作息挂起，直到发相反指令 |

### 行为

| 界面 | 字段 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| 自动熄屏 | `sleep_on` | 开 | 布尔 | 关掉 = 恒亮（展厅/调试常用） |
| 从 / 到 | `sleep_start` / `sleep_end` | 00:00 / 08:00 | 0–1439 分钟 | **支持跨午夜**（23:30 → 08:00）。起止相同会自动改成 +60 分钟 |
| 定时播报 | `schedule_on` | 开 | 布尔 | 到点主动说固定台词 |
| 早上 / 晚上 | `schedule_morning` / `schedule_night` | 08:40 / 23:59 | 0–1439 | 只在 idle 播；忙则跳过不补 |
| 在场搭话 | `presence_on` | 开 | 布尔 | **只影响主动搭话**，不影响走近唤醒 |
| 停留 | `presence_after` | 300 秒 | 10–86400 | 连续在场多久才开口 |
| 冷却 | `presence_cool` | 3000 秒 | 60–86400 | 搭过一次后多久不再搭 |
| 时段 | `presence_hour_start` / `_end` | 10 / 23 | 0–23 | 只有这个时段内会主动搭话 |
| 接话窗口 | `listen_sec` | 15 秒 | 3–120 | 唤醒后这么久没说话就结束本轮 |
| 视线跟随 | `gaze_on` | 开 | 布尔 | 说话/听时眼睛跟人转（会占用摄像头） |

界面上的分钟/小时只是显示换算，设备里存的是秒与分钟数。

### 模型与画面

| 界面 | 接口 | 说明 |
|---|---|---|
| 模型 | `POST /control {"action":"model","value":"dafeiyu\|miku\|sakura"}` | 写 `model_dir`，下次开机还是它 |
| 位置与大小 | `POST /control {"action":"fit","value":"x,y,scale"}` | **每个模型存一份**；`reset` 回默认 |
| 副屏视频 | `POST /video` / `POST /upload` / `GET /video/list` / `delete` / `clean` | 视频落在 `/sdcard/amber/video/` |
| 抠像 | `POST /video?key=green:0.30` | `none` / `green` / `blue` / `black` / `0xRRGGBB[:阈值]` |

> 摆台的 x **要反号**、y 不翻，预览图的变换和实体屏差一次「上下翻」。
> 这些在界面上已经处理好了，手写 curl 时要注意。

### 运维

| 界面 | 接口 | 说明 |
|---|---|---|
| 用电脑时间对时 | `POST /clock {"epoch": 毫秒}` | 收毫秒且 ≥ 2020-01-01，传秒静默拒收 |
| 重启应用 | `POST /control {"action":"reboot"}` | 不是重启设备；1.5 秒后 AlarmManager 拉起新进程 |
| 设备地址 | `POST /api/config`（调参台） | 写 `console.json`，不用改源码 |
| 恢复默认设置 | `POST /settings {"key":"reset"}` | 只重置行为类，不动模型/灯带/音量 |

---

## 第二层 · AstrBot 平台配置

| 想改什么 | 键 | 提示 |
|---|---|---|
| 唤醒词 | `wake_words` | 逗号分隔。改完要重启容器 |
| 应一声后多久内不用再喊 | `wake_window` | 默认 20 秒。**别为了「第一次没接上」调大** |
| 最多几轮不喊名字 | `wake_followups` | 默认 4，挡电视 |
| 音色 | `tts_provider` | 按你自己的 TTS 配置填 |
| 语音概率 | —— | 音箱固定 100%，不读全局 `trigger_probability` |
| 人设 | AstrBot 的 persona | 三个平台共用；改一处全平台生效 |
| 记忆 | `conversations` | QQ 与音箱共享同一条会话 |

逐项说明见 [ASTRBOT.md](ASTRBOT.md#配置项)。

---

## 第三层 · 代码常量

改完要 `python build.py`（改了 C++ 还要先 `python jni/build.py`）。

| 想改什么 | 文件 | 位置 |
|---|---|---|
| 默认模型 | `src/.../gl/GlSurface.java` | `DEFAULT_MODEL` |
| 模型别名 | `src/.../MainActivity.java` | `MODEL_ALIASES` |
| 待机风格 / 呼吸节奏 | `jni/src/Model.cpp` | `TickIdle()`、`idleSwitchIn_` |
| 待机幅度 | `jni/src/Model.cpp` | `idleScale_`、`SampleIdle()` 里的系数 |
| 动作幅度 | `tools/gen_big_motions.py` | 改完重新生成 `m30~m36` |
| 口型映射 | `src/.../cue/WordTable.java` | 汉字 → 参数曲线 |
| 灯带曲线 | `src/.../led/StageLight.java` | 每个模式的逐路公式 |
| 唤醒/说话超时 | `src/.../WakeState.java` | `WAKING_TIMEOUT_MS`、`SPEAKING_TIMEOUT_MS` |
| 看门狗阈值 | `src/.../MainActivity.java` | `GL_PING_MS`、画面无像素 90 秒、10 分钟 3 次 |
| 对时探测地址 | `src/.../ClockSync.java` | `PROBES`（第一项是局域网地址） |
| 灯带设备节点 / SN3218 命令 | `src/.../led/NativeLed.java` | `ioctl` 常量 |
| 人体感应键码 | `src/.../sensor/NativeHum.java` | 64（不是 `getevent` 报的 61） |
| 渲染批次键 | `jni/local/CubismRenderer_OpenGLES2.cpp` | 最近一次修的是「批次键必须带乘算/加算色」 |

---

## 不在界面上、也不该随便改的

| 项 | 为什么 |
|---|---|
| SN3218 的 `write` count | 传 144 会让内核 oops 重启，必须 36 |
| 屏幕方向 / 镜像 | 改一次要重新标定实体屏，截图不能当参照 |
| VAD 底噪算法 | 改动会直接影响「第一遍听得见吗」 |
| 遮罩缓冲数量 | 固定 3 张，SDK 按帧累计分配 |
| 唤醒回答的概率 | 音箱侧刻意 100%，和 QQ 的 0.3 是两条独立通道 |

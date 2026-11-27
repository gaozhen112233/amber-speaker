# 接口参考

设备端是一个手写的 HTTP 服务（`SayServer.java`），只跑在局域网里，没有鉴权。
**不要把它暴露到公网。** 默认端口 8788。

约定：

- POST 的 body 可以是**纯文本**（当成消息正文），也可以是 JSON；
- 查询串优先于 body；
- ⚠️ 查询串**不做 URL 解码**（除了 `/video` 的 url/key 显式解码），
  中文和空格请走 JSON body；
- 所有响应都是 UTF-8 JSON，`/clip` 除外（返回 wav）。

---

## GET /state

一切自检的入口。调参台每 2 秒拉一次。

```bash
curl http://<音箱IP>:8788/state
```

| 字段 | 含义 |
|---|---|
| `ok` / `contract` | 服务活着 / 协议版本 |
| `screen` | `on` / `off`（背光实际状态） |
| `speaking` / `audio` | 正在说话 / 喇叭有声 |
| `queued` | 还在队列里的句子数 |
| `busy` | 现在能不能开麦（说话、尾音保护期都算忙） |
| `tail_ms` | 上一句结束到现在多久（-1 = 还没说过） |
| `mic_muted` | 是否被 F3 闭麦 |
| `received` | 累计收到的消息条数 |
| `phase` | `idle` / `waking` / `speaking` / `listening` |
| `present` / `presence_s` | 有人在 / 已经在场多少秒 |
| `clock_ok` / `minute` / `clock_age` | 时间可信 / 当天第几分钟 / 上次对时距今秒数 |
| `frames` | GL 累计帧数（**看它有没有在涨**） |
| `content_age` | 上一次在画面上读到非空像素距今秒数（全黑时一直涨） |
| `posing` | 是否在被手动摆姿势 |
| `light` / `light_mode` / `backlight` | 灯带缩放 / 当前灯效模式 / 背光 |
| `volume` | 音量增益 0~1 |
| `model` / `fit` | 当前模型目录 / `[x,y,scale]` |
| `mode` | `pet` / `video`（副屏） |
| `video_state` / `video_src` / `video_pos` / `video_dur` / `video_size` / `video_key` | 副屏状态 |
| `settings` | 行为设置快照（同 `GET /settings`） |

---

## POST /say

让她说话。这是 AstrBot 的主路径。

```bash
# 纯文本
curl -X POST http://<音箱IP>:8788/say -d '你终于回来啦'

# JSON（推荐：可以带音频和动作）
curl -X POST http://<音箱IP>:8788/say \
  -H 'Content-Type: application/json' \
  -d '{"text":"〔歪头〕你终于回来啦","audio_url":"http://<主机>:8789/audio/a.wav","id":"s-1"}'
```

| 参数 | 说明 |
|---|---|
| `text` | 正文。`〔动作词〕` 不会被念出来，只触发动作 |
| `id` | 幂等/回执用，可省 |
| `audio_url` | 音频地址；`asset://` 表示读 APK 内置预录音。不传就走本地 TTS 或只上字幕 |
| `interrupt` | `true` = 立刻打断当前这句 |
| `ttl` / `ttl_ms` | 过期时间；过期就别说了 |
| `noabort` | `true` = 不要因为这句而中断 `/listen` |

响应里带 `bubbles`（按行拆分的气泡数）。

---

## POST /wake

模拟「有人叫了她一声」。

```bash
curl -X POST 'http://<音箱IP>:8788/wake?source=voice'
curl -X POST 'http://<音箱IP>:8788/wake?source=proximity'
```

| 参数 | 说明 |
|---|---|
| `keyword` | 命中词，默认「大肥鱼」 |
| `source` | `voice` / `proximity` / `presence` / `button` / `schedule` |

不同 `source` 会挑不同的开场白（预录音，零延迟）。

---

## POST /control

统一的控制入口。`value` 除 `reboot` 外不能为空，空值直接 400。

```bash
curl -X POST http://<音箱IP>:8788/control -H 'Content-Type: application/json' \
     -d '{"action":"light","value":"70"}'
```

| action | value | 说明 |
|---|---|---|
| `model` | `dafeiyu` / `miku` / `sakura` / 目录名 | 换模型，写盘，下次开机还是它 |
| `fit` | `x,y,scale` 或 `reset` | 摆台，**按模型各存一份** |
| `video` | URL / 设备路径 / `stop` | 副屏开播 / 收工 |
| `videokey` | `none` / `green:0.30` / `0x00FF00:0.4` | 抠像 |
| `overlay` | `on` / `off` | 字幕层显隐（诊断用） |
| `display` | `on` / `off` | 手动亮/熄屏，会挂起自动作息 |
| `brightness` | `0~1` 或 `0~100` | 背光 |
| `light` | `0~1` 或 `0~100` | 灯带整体缩放 |
| `volume` | `0~1` | 播放增益 |
| `reboot` | 空 | 重启应用（不是设备） |
| `skit` | 小剧场名 | 演一段 `assets/skits.txt` 里的段子 |
| `face` | 空 | 手动触发一次摄像头人脸确认（诊断） |
| `cameraheal` | 任意非空 | 重启相机守护进程 `qcamerasvr`（「摄像头打不开」的急救，需要 su 授权） |
| `hide` | `hide` / `show` | 全部件隐藏/显示（量渲染开销） |
| `diag` | 原生命令 | 直通车，见下 |

### diag 直通车

```bash
curl -X POST http://<音箱IP>:8788/control -H 'Content-Type: application/json' \
     -d '{"action":"diag","value":"params"}'
```

常用：`params`（dump 509 个参数到 logcat）、`set 参数 值`、`clear 参数`、
`anim 动作`、`motion 动作`、`hideall` / `showall`、`solo N`、`batch 0`（关合批）、
`overlay on|off`、`idle 0|1`。

⚠️ 只支持 POST + JSON body。`%20` 不会被解码，会变成字面 `clear%20ParamEyeROpen` 然后静默失效。

---

## GET/POST /settings

行为设置。15 项，默认值与固件原始常量一致（额度和间隔见 `snap_cool` / `snap_day`）。

```bash
curl http://<音箱IP>:8788/settings

curl -X POST http://<音箱IP>:8788/settings -H 'Content-Type: application/json' \
     -d '{"key":"listen_sec","value":"25"}'

curl -X POST http://<音箱IP>:8788/settings -H 'Content-Type: application/json' \
     -d '{"key":"sleep_start","value":"23:30"}'

curl -X POST http://<音箱IP>:8788/settings -d '{"key":"reset"}'
```

字段与范围见 [SETTINGS.md](SETTINGS.md#第一层--调参台)。

时间接受两种写法：`"520"`（当天第几分钟）或 `"08:40"`。
改成功返回整份新设置；非法键/值返回 400 和中文原因。

---

## POST /clock

对时。设备只记偏移，不改系统时间。

```bash
curl -X POST -H 'Content-Type: application/json' \
     -d "{\"epoch\":$(date +%s000)}" http://<音箱IP>:8788/clock
```

**收毫秒**且必须 ≥ `1577836800000`（2020-01-01），传秒会静默拒收。
收到后会立刻重判一次屏幕（可能马上就该亮/该黑）。

---

## 语音相关

```bash
# 抓一段，检测到人声就提前返回（插件的主路径）
curl 'http://<音箱IP>:8788/listen?max=90&need=350&source=mic'

# 取走上一次抓到的 wav
curl -o a.wav http://<音箱IP>:8788/clip

# 定长录一段（调试）
curl 'http://<音箱IP>:8788/record?sec=5'
```

| 参数 | 说明 |
|---|---|
| `max` | 最长多少毫秒（默认 8000） |
| `need` | 判定「有人说话」需要连续多少毫秒（默认 350） |
| `source` | `mic` / `voice_comm` 等 `AudioSource` |
| `aec` | 打开系统 AEC（这台机器上没用，见 HARDWARE） |

播放期间返回 `{"busy":true}`——HAL 拆采集流，开麦必失败。

---

## 副屏

```bash
# 播一个 URL（循环）
curl -X POST http://<音箱IP>:8788/video -H 'Content-Type: application/json' \
     -d '{"url":"http://<电脑IP>:8000/dance.mp4","loop":true}'

# 播设备上的文件
curl -X POST 'http://<音箱IP>:8788/video?url=/sdcard/amber/video/a.mp4'

# 收工回模型
curl -X POST 'http://<音箱IP>:8788/video?stop=1'

# 绿幕抠像
curl -X POST 'http://<音箱IP>:8788/video?key=green:0.30'

# 上传（body 就是文件本体，边收边写盘）
curl -X POST --data-binary @dance.mp4 'http://<音箱IP>:8788/upload?name=dance.mp4'

# 清单 / 删除 / 清空
curl http://<音箱IP>:8788/video/list
curl -X POST 'http://<音箱IP>:8788/video/delete?name=dance.mp4'
curl -X POST http://<音箱IP>:8788/video/clean
```

视频存 `/sdcard/amber/video/`，同名覆盖。删正在播的那一段会先停下再删。
上传绕过了 64KB 的 body 上限（单独分支、边收边写、超时 180 秒）。

---

## 其他

| 端点 | 说明 |
|---|---|
| `GET /health` | 活着就 `{"ok":true}`，给探活用 |
| `POST /wifi` | 配网 `{"ssid":"...","pass":"..."}`。设备的系统设置是竖屏，歪头输密码太痛苦，所以自己做了一个 |
| `POST /snapshot` | 「眼睛」：抓一帧给视觉模型。同步阻塞 ≤12 秒（含失败重试与相机自愈）；间隔 `snap_cool` / 每日 `snap_day` 可调，默认 30 秒 / 100 次 |

---

## 调参台的接口（`127.0.0.1:8790`）

只监听本机，给网页用（设备没有 CORS 头，所以必须同源代理）。

| 端点 | 说明 |
|---|---|
| `GET /api/info` | 设备地址、端口、adb 路径、在线状态、协议版本 |
| `GET /api/model` | 参数表 + 表情 + 动作 |
| `GET /api/models` | 模型目录列表 + 当前 |
| `GET /api/state` | 转发 `/state` |
| `GET/POST /api/settings` | 转发 `/settings` |
| `GET /api/snapshot` | 经 adb 截屏（PNG，这是屏幕不是摄像头） |
| `POST /api/camera-heal` | 经 adb root 重启相机守护进程（不需要设备端 su 授权） |
| `POST /api/control` | 转发 `/control` |
| `POST /api/cmd` | 批量 diag 命令 |
| `POST /api/clock` | 转发 `/clock` |
| `POST /api/config` | 改设备地址（写 `console.json`） |
| `POST /api/video/upload` | 边收边转发到设备 |
| `GET /api/video/list`、`POST /api/video/delete|clean` | 视频管理 |
| `POST /api/shutdown` | 关掉调参台（不动设备） |

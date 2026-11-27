# 排障：按现象查

每条都是「现象 → 判据 → 修法」。判据是可以直接复制执行的命令，不要靠猜。

---

## 8788 完全没响应

**先分清「进程没了」还是「进程活着但服务死了」**——两种修法不一样。

```bash
adb shell su -c '/data/adb/magisk/busybox pidof com.amber.speaker'
```

| pidof | 说明 | 修 |
|---|---|---|
| 空 | 进程没了（最常见：`adb install -r` 之后被 force-stop，没人拉起） | `adb shell am start -n com.amber.speaker/.MainActivity` |
| 有数字 | 进程活着，但服务没起来 / 主线程卡死 | 看 `logcat -d \| tail -50`；不行就 `reboot` 应用 |

长期解法：装 `tools/amber_keepalive.sh`（见 [DEPLOY §3](DEPLOY.md#3-magisk-与权限决定完整体验还是能跑)）。

---

## 喊她完全没反应

按顺序排除，最后一条最容易被忽略：

```bash
curl http://<音箱IP>:8788/state
```

1. `"screen":"off"` —— 她在休眠。喊唤醒词应该能叫醒；叫不醒看第 3 条。
2. `"mic_muted":true` —— 被 **F3 键**闭麦了（或者盖子压住了 F3）。再按一下。
3. **`Standby: yes`** —— 被软关机了（插件 `/power?value=off`）。这是内存态，容器重启才复位：

   ```bash
   curl -X POST 'http://<主机IP>:8789/power?value=on'
   ```

   忘了复位 = 语音循环空转 `sleep(1)`，看起来就是「彻底坏了」。
4. `"phase":"speaking"` 卡住 —— 她以为还在说话。等 30 秒（`SPEAKING_TIMEOUT_MS`）会自动回 idle。
5. `"busy":true` 一直不落 —— 尾音保护或队列没清空，重启应用。

---

## 有字幕，没声音

四种原因，按出现频率：

> **如果是别人的机器 / 全新部署，先看这条**：`assets/voice/` 里那 24 句预录 wav 不在仓库里，
> 唤醒应答会直接抛 `FileNotFoundException: voice/xxx.wav`，表现是「灯带亮了、画面动了，但一声不吭」。
> 补法见 [DEPLOY §6.4](DEPLOY.md)。

**① TTS 的 WAV 头是坏的（最常见）**

流式合成输出的 WAV，头里 size 字段是 `0xFFFFFFxx`。设备侧：

```
MediaPlayer: Prepare failed: status=0x1
```

修法在插件侧：`clean_wav()` 重算头再落盘。判据：把那个 wav 下下来，
用 `ffprobe` 看时长是不是 `N/A` 或异常大。

**② `audio_base` 设备访问不到**

插件给的是 `http://127.0.0.1:8789/...` 或容器内网 IP。设备当然拉不到。
改成宿主机的局域网 IP，并确认 8789 已映射。

判据：`curl -I http://<audio_base>/audio/<某个文件>` 在**另一台机器**上能 200。

**③ 音量是 0**

```bash
curl http://<音箱IP>:8788/state | grep -o '"volume":[0-9.]*'
```

**④ 音频格式设备不支持**

只保证 16k/24k 单声道 PCM WAV（16 bit）。mp3、48k 立体声都可能播不出来。

---

## 第一遍说话她没反应，第二遍才行

经典的 **VAD 底噪取错帧**。

原实现把**第一帧**当底噪。但她刚说完话，第一帧往往还是自己的尾音，
阈值被顶到 1600~3000，人开口的 300 根本过不去 → 整句被判静音。

现在的做法：

- 取**前 10 帧里最小**的那一帧当底噪，再 clamp 到 40~150；
- 阈值下限 250；
- 连续 2 帧算人声；`maxRun < 8` 的稀疏噪声丢掉。

如果你改了 `need` / `max` 又出现这个现象，先把它调回去。

---

## 她说完话，我说什么她都不听

不是 VAD 的问题，是**她自己的尾音被录进去了**。

时序上：喇叭刚停，空气里还有尾音，此时开麦录到的是她自己 → 识别成「查了一下」之类 →
被当成「没叫她」丢掉，白占一轮。

已有的保护：`busyForListen()` 在「说话中 + 排队中 + 配音中 + 播完后的 `LISTEN_TAIL_GUARD_MS`」
都返回 true。如果还是发生，把 `tail_ms` 打出来看：

```bash
watch -n1 "curl -s http://<音箱IP>:8788/state | tr ',' '\n' | grep -E 'tail_ms|busy'"
```

`busy:false` 出现后立刻说话，才是有效窗口。

---

## 有画面但全黑（进程活着、帧率正常）

**着色器编译失败**。这是最阴的一种：进程活着、`/state` 正常、`frames` 在涨，但一个像素都没有。

判据：

```bash
adb logcat -d -s AmberGL | grep -E "program|个|贴图"
```

看到 `program 0 个` + `贴图 0 张` 就是它。

修：`adb shell am force-stop com.amber.speaker` 再启动。**偶发**，重启即好。
如果每次都这样，检查 GPU 驱动是否被改过。

自愈：应用内看门狗会在画面连续 90 秒无像素时自动重启（见 `content_age`）。

---

## 颜色不对：皮肤整块纯白 / 某一片用了别人的颜色

合批（batching）的批次键漏了**乘算色 / 加算色**。

460 个 drawable 被压成约 136 批，每批的 uniform 只取**第一条** drawable 的颜色。
如果批次键里没有颜色，一整批都会用第一条的颜色——表现就是「一大块纯白」。

**唯一可靠的判据**：关掉合批，如果立刻正常，就是它。

```bash
curl -X POST http://<音箱IP>:8788/control -H 'Content-Type: application/json' \
     -d '{"action":"diag","value":"batch 0"}'
```

修法在 `jni/local/CubismRenderer_OpenGLES2.cpp`：批次键要带 `mulR/mulG/mulB/mulA` 与
`scr*`。另外 `multiplyColor` 要读 `GetDrawableMultiplyColor(index)`，
只读 `GetOverrideMultiplyAndScreenColor()` 会丢掉 moc3 自带的颜色（默认全 1/0）。

> 另外：「下巴白块」「侧发像胡子」曾经被当成两个独立 bug 修了两轮，都是假象。
> `ArtMesh245` 是脸的皮肤底色，藏掉会让蓝发从两颊透上来、嘴只剩悬线；
> 挪侧发会把刘海和嘴一起挪走。**根因都是上面的合批颜色。**

---

## 方向反了：尾巴朝左、字幕在左、字是镜像的

先记住：**截图不能当方向参照系**。`screencap` 和实体屏差一次翻转，
照着截图调会越调越反（这个坑踩了三轮）。

正确的组合只有一个：

```bash
adb shell su -c 'settings put system user_rotation 2'
adb shell su -c 'settings put system accelerometer_rotation 0'
```

加上应用侧**整层翻一次**：GL 投影 `scaleX` 取负 + 字幕 `canvas.scale(-1,1)`。
**字幕文字层不能再逐字翻**——翻两次相乘等于没翻，屏幕上看着是正的，人眼过一遍光路就成镜子里的字。

判据：站在实体屏前看，尾巴朝右、字幕在右、字正着读。

---

## 旋转变形器整片塌掉，但弯曲变形器正常

`jni/local` 里用的是 **hard-float** 的 Cubism Core（`armv7_vfp`），
但链进去的 `libm` 是 **softfp** 的。`sincosf` 把指针当弧度算，旋转结果全是垃圾。

判据（每帧刷）：

```
[W]RotationDeformer: Not found transformed Direction.
```

修：

```bash
python tools/patch_core_armv7.py     # 把 4 个 libm 引用改名成 __amber_hf_*
python jni/build.py
```

**不能顶系统符号**——只能改名 + 自带一份 `LibmHardFloatArmv7.cpp`。

---

## 重启后熄屏不黑、走近唤醒失效

不是脚本写错了，是 **Magisk 环境不完整，所有 `service.d` 被静默跳过**。

```bash
adb shell su -c 'cat /cache/magisk.log | tail -30'
```

看到 `environment incomplete, abort` 或 `execve .../busybox failed`：

```bash
adb shell su -c 'cp /system/bin/busybox /data/adb/magisk/busybox'
```

顺带检查脚本本身：必须是 **LF 换行 + 纯 ASCII**（Windows 记事本改过就会坏）。

---

## 她说「摄像头打不开」／看不见东西

让她看东西（「你看看我手里拿的什么」），她回「【眼睛】取帧失败：camera_open_failed」，或者干脆说摄像头打不开。同时「走近唤醒」也不再确认人脸。

**根因是相机守护进程假活，不是被占用。** `mm-qcamera-daemon`（init 服务名 `qcamerasvr`）会出现这种状态：

- 进程活着，`ps | grep camera` 能看到；
- `dumpsys media.camera` 也正常，还写着 `No active camera clients`；
- 但任何 `Camera.open(0)` 都抛 `Fail to connect to camera service`。

判据：

```bash
adb logcat -d -s Snapshot:V GazeTracker:V | tail
# W/Snapshot: 开摄像头失败（第 1 次）：Fail to connect to camera service
```

**三种修法，从省事到彻底：**

1. **等她自愈**（0.2.1 起内置）：连续开三次失败会自动 su 重启 `qcamerasvr`，然后重试，全程约 3 秒——主人只会觉得「她想了下才回答」。前提是 Magisk 给过本应用 su 授权（见 [DEPLOY §3](DEPLOY.md)），没授权时日志是 `su 超时（可能弹了授权框没人点）`。
2. **调参台点「修复摄像头」**：走电脑这边的 adb root，**不需要设备上的 su 授权**，3 秒完成。这是别人部署时最稳的一条。
3. **命令行**：
   ```bash
   adb shell su -c "stop qcamerasvr; start qcamerasvr"
   ```

修完再叫她一次——她那轮交互里视线追踪已经放弃了，要重新进入 listening 才会重新开摄像头。

> 注意：别去 `stop media`（重启 mediaserver）。音频和相机在同一个进程里，重启它会让正在播放的声音断掉，而且实测对这个问题没帮助。

---

## 走近她没反应

```bash
adb shell getevent -lt /dev/input/event5
```

- **一个事件都没有**：节点权限或设备名不对。按名字匹配，不要硬编码 `event5`：

  ```bash
  adb shell su -c 'for i in 0 1 2 3 4 5; do echo -n "$i: "; cat /sys/class/input/event$i/device/name; done'
  ```

- **有 `0040` 但应用没反应**：权限没开（`crw-rw---- root input` → 应用 `EACCES`）。
  跑一次 `hum_sensor_perms.sh`。
- **时有时无**：正常。PIR 是运动检测型，人静止不动就不发事件；它只在红外变化时发一次 DOWN。

> 键码实抓是 **64（`0x0040`）**，而 `getevent -lp` 报的是 `KEY_F6(61)`。
> 按 61 写，一个都匹配不上。

---

## 靠近时摄像头没反应

`Camera` 的启动顺序错了。少任何一步都会「不出帧」，表象就是「靠近没反应」：

```java
setPreviewTexture(new SurfaceTexture(0));  // ① 最容易漏
params.set("face-detection", "on");        // ②
startPreview();                            // ③
setFaceDetectionListener(listener);        // ④
startFaceDetection();                      // ⑤
```

另外**同一时刻只能有一个摄像头使用者**：走近确认、视线追踪、在场周期确认三者互斥。
如果你刚开了视线跟随（`gaze_on`），走近确认会排队。

---

## 凌晨不熄屏 / 早上不播报

先看时间可不可信：

```bash
curl http://<音箱IP>:8788/state | tr ',' '\n' | grep -E 'clock_ok|clock_age|minute'
```

`clock_ok:false` 时，`SleepState` 故意不熄屏、`WakeState` 故意不播报——
宁可白天多亮一会儿，也不要因为时间停在 1977 年就把灯全关了。

修：手动推一次（**毫秒**）：

```bash
curl -X POST -H 'Content-Type: application/json' \
     -d "{\"epoch\":$(date +%s000)}" http://<音箱IP>:8788/clock
```

然后接上 AstrBot，插件每 6 小时自动推。

---

## 调参台里「模型」整张卡片空白

设备的 `/state` 返回了**不完整的 JSON**（通常是加字段时漏了收尾的 `}`）。

```bash
curl -s http://<音箱IP>:8788/state | python -m json.tool | tail -5
```

`json.tool` 报错就是它。这是曾经踩过的真实事故：`device_get` 抛 `JSONDecodeError`，
而分支只 `except (URLError, HTTPError, OSError)`，于是整个接口 500 → 前端空白。

现在 `server.py` 会抛带原文的 `ValueError`，前端能看到是哪一段坏了。

---

## QQ 里每句话都带〔尾巴一晃〕

不是人设的问题，也不是模型乱写。是**音箱和 QQ 共用同一条会话**：
音箱侧为了让动作能被驱动，注入了一段「把动作写成〔词〕」的说明，
这段说明跟着共享历史跑到了 QQ。

修：装 `contrib/astrbot_plugin_qq_plaintext/`（出站清洗，只清 QQ），
**不要改人设，也不要关掉音箱侧的说明书**——关了音箱就不做动作了。

判据：容器日志里 `[QQ纯文字] '旧' -> '新'`。

---

## 换模型之后她吊在半空 / 被切掉半只脚

`fit` 是**按模型分别存**的。新模型第一次用会继承上一个模型的摆台，
而两个模型的画布留白差很多。

```bash
curl -X POST http://<音箱IP>:8788/control -H 'Content-Type: application/json' \
     -d '{"action":"fit","value":"reset"}'
```

然后到调参台的「位置与大小」里拖。手写 curl 注意：**x 要反号**，y 不翻。

---

## 副屏播完停在最后一帧 / 删不掉正在播的视频

- 单次播放结束会自己回模型；如果是循环模式，它不会自己停。
- 删正在播的那一段，必须**先 `stopVideo()` 等 150ms 再删**，否则会僵在最后一帧。
  调参台的删除按钮已经处理了；手写脚本要自己加。

---

## 装完 APK 起不来，`NoClassDefFoundError: MainActivity$1`

Android 5.1 + 裸 `aapt2/d8` **不脱糖** `java.util.function.*`。
项目里用 `com.amber.speaker.fn.Suppliers` 代替，不要图省事引 JDK 的 lambda。

---

## 灯带一写就重启

SN3218 的 `write()` 第三个参数是**字节数按 32 位字算**：

```c
write(fd, buf18, 36);   // ✅
write(fd, buf18, 144);  // ❌ 内核 oops，设备直接重启
```

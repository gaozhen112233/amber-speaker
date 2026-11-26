# 硬件笔记（C2 / la0920）

这台机器和普通 Android 手机差得很远。下面每一条都是实测出来的，写下来是为了让后来的人
不用再花一个周末。**换设备的话，这一页基本要重写。**

| 项 | 值 |
|---|---|
| SoC | 高通 MSM8909（4×A7，32 位） |
| 内存 / 存储 | 1 GB / 8 GB |
| 系统 | Android 5.1（API 22），`armeabi-v7a` |
| 屏 | 1024×600，物理横装、系统竖屏 UI，靠 `user_rotation=2` 转过来 |
| 灯带 | SN3218，18 路，走 `/dev/sn3218` |
| 麦克风 | NAU85L40 四通道 ADC（`zhc_adc_nau85l40`），**不是** WCD 编解码器 |
| 人体感应 | PIR，走 `/dev/input/eventN`，设备名 `hum_sensor` |
| 摄像头 | 后摄（Camera 0），支持硬件人脸检测（`num_fd=5`） |
| 电池 | 自带，拔掉充电器仍在运行 |
| RTC | **没有备用电池**，断电后时间回到 1977-10-30 |

---

## 屏幕方向

三件事叠在一起：

1. 系统被固定成 `user_rotation=2`（180°）+ `accelerometer_rotation=0`；
2. 光路里还有一次反射，等于上下再翻一次；
3. 结果是：**应用必须自己补一次左右镜像**，实体屏上才是正的。

```bash
adb shell su -c 'settings put system user_rotation 2'
adb shell su -c 'settings put system accelerometer_rotation 0'
```

应用侧的两次补正（已固化在代码里）：

- GL 投影矩阵：`scaleX` 取负（`Pet::SetViewFit` 里 `offsetX = flipX_ ? -viewX_ : viewX_`）；
- 字幕层：`onDraw` 里整屏 `canvas.scale(-1, 1)`。

> **铁律：截图不能当方向参照系。**
> `adb screencap` 的结果和实体屏差一次翻转（而且差的是「上下」不是转 180°）。
> 照着截图调方向，会越调越反——这个坑踩了三轮。
> 唯一可靠的判据是站在实体屏前看：**尾巴朝右、字幕在右、字正着读。**

---

## 灯带（SN3218）

```c
int fd = open("/dev/sn3218", O_RDWR);
ioctl(fd, 0x4C01);              // 上电
unsigned char buf[18];          // 18 路亮度
write(fd, buf, 36);             // ⚠️ count 是「32 位字数」= 36，不是 18，也不是 144
ioctl(fd, 0x4C04);              // 断电（休眠时用）
```

`write()` 的 `count` 传 144 会让内核 oops 直接重启——**这是这台机器最容易搞坏自己的地方**。

三档基线（现在由 `StageLight` 按模式动态给）：

| 状态 | 亮度 |
|---|---|
| 休眠 | 0 + 断电 |
| 待机 | 50（慢呼吸，4.2 秒一个周期） |
| 在听 | 从中间向两端荡开，2.2 秒一次 |
| 说话 | 95 + 电平 × 110 + 沿灯带的细波纹 |
| 副屏 | 42 平光 |

整体亮度是用户可调的乘数（调参台「舞台灯带」，默认 65%）。

---

## 麦克风与 AEC

采集通路是 `MultiMedia1 Mixer QUAT_MI2S_TX`（HAL 开录音时自动打开）。
调 `DEC1 MUX`、`ADC1 Volume` 没有任何用——**它不是 WCD，是 NAU85L40 四通道 ADC**。

**播放一开始，HAL 会把采集流拆掉**，`AudioRecord.read()` 连续失败。所以：

- `/say`、`/wake` 期间设备置 `listenAbort`，`/listen` 直接返回 `busy`；
- **做不到「边说边听」**。三条路都试过：

| 尝试 | 结果 |
|---|---|
| `MediaRecorder.AudioSource.MIC` / `VOICE_RECOGNITION` | 不断流，但**没有 AEC**，录到自己 |
| `VOICE_COMMUNICATION` | 有 AEC，但卡死音频 HAL（之后再也开不了麦） |
| `AcousticEchoCanceler` | 报 `enabled=true`，峰值一点没降 |

替代方案：把回复按标点分句，句间 `stop()` 让 HAL 释放、采集流重建，开 350~450ms 听窗。
代价是每句多 150~250ms 空档——听起来就是换气。（尚未实现，见 [DEVELOPMENT](DEVELOPMENT.md#还没做的)）

---

## 人体感应

- 设备名 `hum_sensor`，是**按键事件设备**：有人=1、没人=0；
- **键码实抓是 64（`0x0040`）**，而 `getevent -lp` 会报 `KEY_F6(61)`——按 61 写一个都匹配不上；
- 权限默认 `crw-rw---- root input`，普通应用 `EACCES`，要靠 Magisk 开机 chmod 666；
- **PIR 是运动检测型**：只在红外变化时发一次 DOWN，约 3 秒后自动 UP。
  「时有时无」不是 bug。排查「没反应」先 `getevent -lt` 看有没有 `0040`。

```bash
adb shell getevent -lt /dev/input/event5    # 手在它前面晃，应该看到 0040 的 DOWN/UP
```

> 脚本里**按设备名匹配**，不要硬编码 `event5`——枚举顺序会变。

---

## 摄像头

后摄，硬件人脸检测，最多 5 张脸。启动顺序不能乱：

```java
camera.setPreviewTexture(new SurfaceTexture(0));  // ① 没有它不出帧，表象是「靠近没反应」
params.set("face-detection", "on");               // ②
camera.setParameters(params);
camera.startPreview();                            // ③
camera.setFaceDetectionListener(listener);        // ④
camera.startFaceDetection();                      // ⑤
```

只有一个摄像头实例：走近确认、视线追踪、在场周期确认三者互斥，靠 `FaceConfirm` 排队。

---

## 顶部五个键

| 位置 | 键 | 功能 |
|---|---|---|
| 左 | F1 | 音量 − |
| 上 | F2 | 配网 |
| 中 | F3 | 静音（闭麦） |
| 右 | F4 | 音量 + |
| 下 | F5 | 唤醒 |

- **系统音量键是坏的**：`keyevent 25` 改不了 `STREAM_MUSIC`。音量走应用内的 `Voice` 增益
  （0~1，10 档，`SharedPreferences` 记忆），和 `/control volume` 是同一个值。
- **盖盖子会压到这几个键**，包括 F3（静音）——盖之前先确认没压住。

---

## 电源

- 自带电池，**断电只拔充电器**即可，不需要智能插座；
- 软关机是插件侧的行为（`/power?value=off`）：闭麦 + 熄屏 + `/utter` 返 409。
  **只存在内存里，容器重启才复位**——测完一定调回 `on`，否则现象是「喊她完全没反应」；
- 判据：`/state` 里 `Standby: yes`（正常是 `no`）。

---

## Android 5.1 的两个限制

1. **不能用 `java.util.function.*`**。裸 `aapt2 + d8` 不脱糖，运行时
   `NoClassDefFoundError: MainActivity$1`。项目里用 `com.amber.speaker.fn.Suppliers` 代替。
2. **`setLooping(true)` 在本机是坏的**：MediaPlayer 第一帧就回调 `onCompletion`。
   改成在 completion 里 `seekTo(0) + start()`。

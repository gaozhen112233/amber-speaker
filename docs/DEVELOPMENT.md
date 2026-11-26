# 二次开发笔记

这一页写给想改代码的人。只记「为什么这么做」和「坑在哪」，不解释 Cubism SDK 本身。

---

## 构建体系

**没有 Gradle，也没有 Android Studio 工程。** 构建链是：

```
aapt2 link  →  javac  →  d8  →  zip(塞 dex + .so)  →  zipalign -p  →  apksigner(v1+v2)
```

为什么：这个规模（39 个 Java 文件）全量编译只要几秒，引 Gradle 的收益是负的。
代价是没有增量编译、没有依赖管理、没有资源混淆。

两条命令：

```bash
python jni/build.py     # 改了 C++ 才需要（十几秒）
python build.py         # 出 APK（几秒）
```

注意：

- `.so` 必须以 **STORED（不压缩）** 塞进 APK，且 `zipalign -p` 做页对齐，否则 5.1 装得上但加载失败；
- 签名要 **v1 + v2 都开**：5.1 只看 v1（但必须有），新系统要求 v2；
- `python build.py` 失败时会打 `!! 失败` 且中文在 GBK 终端里是乱码，
  **不要用 `grep error` 过滤**（匹配不上会误以为成功，装到旧包）。看最后 20 行。

---

## 渲染管线

### Cubism Core 的 32 位 ARM 问题

官方只提供 `armeabi-v7a` 的 `armv7_vfp`（hard-float）静态库，但 NDK 默认链的是
softfp 的 `libm`。结果：

- 弯曲变形器（warp deformer）正常；
- **旋转变形器整片塌掉**，日志每帧刷 `Not found transformed Direction.`。

原因是 `sincosf` 这类函数在 softfp ABI 下把指针当弧度用。修法：

1. `LibmHardFloatArmv7.cpp` 自带一份 `pcs("aapcs-vfp")` 的 `sinf/cosf/sincosf/atan2f`；
2. `tools/patch_core_armv7.py` 用 `objcopy --redefine-sym` 把 Core 里那 4 个 libm 引用
   改名成 `__amber_hf_*`。

**不能直接顶掉系统符号**——那会影响整个进程。

### 合批（batching）

460 个 drawable → 约 136~140 批。规则：

- 批次键必须包含：纹理 id、混合模式、遮罩状态、**乘算色、加算色**；
- 每批只取第一条 drawable 的 uniform，所以键里少一个维度，整批就用错色；
- 遮罩缓冲固定 **3 张**（SDK 是按帧累计分配，不是按单部件最大值）。

判据永远是这一条：`diag batch 0` 关掉合批，如果画面立刻正常，就是合批的锅。

### 帧率

| 场景 | 帧率 |
|---|---|
| 空场景（只清屏） | 42.5 fps |
| 副屏视频 | 60 fps |
| 模型待机 | 11~12 fps |
| miku 模型 | 18~20 fps |

瓶颈是 **CPU Update + 460 次 drawable 提交**，不是填充率：
分辨率减半、减遮罩、关合批都试过，没有明显提升。GPU 单帧 19~26 ms。

---

## 动作系统

### motion3.json 必须是 `indent=1`

`CubismJson::ParseNumeric` 只认 `\n` 和 `,` 作为终止符。紧凑 JSON 里 `0.0]`
会被读成 `non-numeric charactor found`，`LoadMotion` 返回 `nullptr`——
**外面只看到「没有这个动作」**，没有任何其他线索。

写完检查：

```bash
grep -c '[0-9][\]\}]' assets/model/motion/*.motion3.json   # 必须是 0
```

Meta 也要对：`TotalPointCount = Σ((len-2)/3+1)`、`TotalSegmentCount = Σ(n-1)`。

### 参数体系

- **509 个参数**。`diag params` 会 dump 全表到 logcat。
- 参数分两类：
  - **物理输入**（`AngleX/Y/Z`、`PositionX`、`LeanInput`）：写了有效；
  - **物理输出**（`ParamAngle*`、`ParamBodyAngle*`、`LArm/RArm`、`Param44~54`…）：写了会被覆盖。
- **`set` 是「按住」，只有 `clear` 才松开**。绕过 `Body` 直发的按住值永远不会回收，
  真机现象是「走近一次，眼睛永远半睁」。持续姿态用 `set`，一次性动作用 `anim` + `clear` 兜底。
- 下半身只有 **`HipAngleXq`** 能驱（±30 约 7% 变化）；`HipAngleX/Y/Z` 是自反馈。
  站姿是绑定的，做不出真坐姿。

### 待机不是播动画

自带的 `m0`（IDLE）21 条曲线全是特效，头、身、尾一条都没动。
所以待机是**持续驱动物理输入**：`AngleX/Y/Z`、`PositionX`、`LeanInput`、`ParamBreath`、
`ParamEyeBallX/Y`，写在参数阶段**最前面**（物理之前）。7 套风格，呼吸 3.2~6.4 秒。

### 动作幅度

原版动作只用了量程的 6~19（±30 的量程），单参数打满屏幕变化只有 1.4~7%，
而待机噪声本身就有 2~3%——所以「看不出来」。

`tools/gen_big_motions.py` 生成 `m30~m36` 七段大幅度动作：多参数叠加、用满量程、
「快起 - 停住 - 收回」。手动下发动作前要 `StopAllMotions()` 抢占，
否则同优先级会被 `MotionManager` 静默丢弃。

---

## 模型适配

### 两种骨架

| | 大肥鱼 | miku / 樱花 |
|---|---|---|
| `ParamAngle*` | **输出** | **输入** |
| 驱动方式 | `FaceAngle` 反号 + 头部物理之后覆盖 | 直接写 `ParamAngle*`，物理之前 |
| 待机 | `AngleX/Y/Z` | `ParamAngle*` + `ParamBodyAngleX*0.35` |

`assets/<模型>/index.txt` 里可以放 `[meta]`、`[hide]`、`[order]`、`[fix]`、`[exp]`、`[motion]`，
格式见 `examples/model-index.txt`。

### 打包

`tools/pack_models.py`：贴图 4096 → 2048。**六张 4096 会让 APK 到 384MB，1GB 内存直接爆。**

---

## 语音链路

三个数字决定体验：

| 参数 | 值 | 为什么 |
|---|---|---|
| 唤醒判定 | 拼音音节级，允许声母同组互换、前后鼻音混淆 | SenseVoice 稳定把「大肥鱼」识别成「大菲鱼」 |
| 接话窗口 | 20 秒 | 每答一轮续期；35 秒会让电视声续聊成自问自答 |
| 尾音保护 | 播完后 ~1.5 秒内 `busy` | 否则录到自己的尾音，识别成「查了一下」，白占一轮 |

VAD 的底噪**取前 10 帧的最小值**，不取第一帧（第一帧常是自己的尾音）。

---

## 测试

`test/` 下是纯 Java 断言，不依赖 Android：

```bash
javac -encoding UTF-8 -d out/test test/SleepStateTest.java src/com/amber/speaker/SleepState.java ...
java -cp out/test SleepStateTest
```

`SayServerTest` 会起一个真的 HTTP 服务发真的请求（需要补齐 `SayServer.Listener` 的全部方法）。
注意 `build.py` **不编译 `test/`**，所以接口变动后测试可能编不过，需要手动补。

---

## 代码地图

```
MainActivity.java     装配一切：屏幕、传感器、状态机、HTTP、按键、看门狗
├── gl/GlSurface       GL 线程、模型生命周期、副屏切换、帧计数、内容探测
│   ├── VideoStage     MediaPlayer → SurfaceTexture → OES 纹理
│   └── Native         JNI 桥（drawFrame / command / setFlipX / SetViewFit）
├── net/SayServer      手写 HTTP（/say /listen /control /settings /video …）
├── WakeState          相位机：idle → waking → speaking → listening（纯 Java）
├── SleepState         作息熄屏（纯 Java）
├── Settings           行为设置的静态缓存（纯 Java）
├── SettingsStore      上面那层的 SharedPreferences 落盘
├── cue/               台词 → 字幕气泡 + 口型 + 动作词
├── led/StageLight     18 路灯效，自带 25Hz 节奏（不占主帧循环）
├── sound/             Recorder（VAD）、Voice（播放）、Listener（唤醒监听）
└── sensor/            HumSensor（PIR）、PresenceWatch、GazeTracker、FaceConfirm、Snapshot
```

---

## 还没做的

按「值不值得做」排序：

1. **句间插话**：她能说话时你不能插嘴，是最不像人的地方。AEC 已证死，替代方案是
   按标点分句 + 句间让 HAL 释放采集流 + 350ms 听窗（见 [HARDWARE](HARDWARE.md#麦克风与-aec)）。
2. **认人**：硬件人脸检测只给矩形，要「谁在」得自己上轻量模型。1GB + Android 5.1 风险大。
3. **实时投屏**：现在是投文件。要实时得做 WebRTC/H.264，工程量大。
4. **智能家居触发**：米家的 `check_new_msg` 只给 bool，只能轮询 `get_devices_prop`，体验不划算。

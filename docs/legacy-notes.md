# 大肥鱼 · Android 端（琥珀音箱）

音箱这一端的实现。目标是让 AstrBot 发来的一句话，在这块 1024×600 的屏上变成「她在说话」。

当前是 **0.2**：逻辑层已验证；Live2D 的 GL 渲染已经接上并出包，**等真机验证**。

---

## 已经能做的事

| 能力 | 状态 |
|---|---|
| 收 `POST /say`（纯文本 body、JSON body 都支持） | ✅ 实测通过 |
| `GET /state`、`GET /health` | ✅ |
| 〔〕解析：60 个动作词 + 13 个组合词 | ✅ 和桌宠逐字一致 |
| 认不出的词跳过继续播，并生成 `note` | ✅ |
| 时间轴：字按 9 字/秒出，碰〔〕等身体做完 | ✅ |
| 逐字字幕（带描边，半透屏上看得清） | ✅ |
| 动作命令打到 logcat（tag `AmberCue`） | ✅ |
| **Live2D 渲染**（Cubism 5-r.5 + OpenGL ES 2） | ✅ **真机通过**（Android 14 / arm32 / Adreno） |
| 物理 / 眨眼 / 呼吸 / 23 表达式 / 8 动作 | ✅ **真机通过**（逐项截图比对） |
| 嘴型跟着字动（`mouth` 正弦） | ✅ **真机通过**（说话中连拍可见开合） |
| `/say` 的 `interrupt` / `ttl` / 排队 | ✅ 单测 24 项 + 真机实测 |
| 着色器按需编译（启动 6.4s → 0.45s） | ✅ **真机通过**（474 个 program → 8 个） |
| 配音播放 + 真包络驱动口型 | ✅ **真机通过**（MediaCodec 解包络，20ms 帧，连发不串） |

## 还没做

| 事项 | 为什么还没做 |
|---|---|
| 唤醒词 | 依赖音频输入链路（音箱的麦克风/拾音） |
| 镜像翻转 | **必须等音响**。四档真值表见契约 §12.1，跑一次探针就知道翻在哪层 |
| 前倾/后仰 → 视图变换映射 | 等音响看实际画面再定（`LeanInput`/`ViewX/Y` 是视图变换不是模型参数） |

---

## 目录

```
amber-android/
├── AndroidManifest.xml        minSdk 21 / targetSdk 30，横屏全屏
├── build.py                   aapt2 → javac → d8 → 塞 dex/.so → zipalign → apksigner
├── deploy.py                  装 + 起 + 盯 AmberGL / AmberCue 日志
├── src/com/amber/speaker/
│   ├── MainActivity.java      收 /say、驱动时间轴、命令分发给 GL 和 logcat
│   ├── SubtitleView.java      逐字字幕（透明背景，压在 GL 之上）
│   ├── Text.java              空白判断（全角空格，见下）
│   ├── gl/
│   │   ├── GlSurface.java     GLSurfaceView：两段式加载、命令缓存、上下文重建
│   │   └── Native.java        五个 JNI 入口
│   ├── cue/
│   │   ├── Word.java          一个动作词展开成什么
│   │   ├── WordTable.java     ★ 60 个词的关键帧，从桌宠逐字抄的
│   │   ├── Cues.java          〔〕切分、校对、「是不是那个」猜测
│   │   ├── Body.java          通道叠加、表情开关、动作调度
│   │   ├── Line.java          一句话的时间轴
│   │   └── Sink.java          参数出口（命令文本）
│   └── net/
│       └── SayServer.java     手写极简 HTTP
├── jni/
│   ├── CMakeLists.txt         链接 Cubism Framework + Core + libambergl.so
│   ├── build.py               原生库构建（工具链路径硬编码，见踩坑记录）
│   ├── include/stb_image.h    借 SDK 自带的那份
│   └── src/
│       ├── Model.hpp/.cpp     ★ Pet：物理/眨眼/呼吸/表情/动作，从桌宠 Pet 移植
│       └── jni.cpp            JNI 壳：AssetManager、字符串、dt
├── tools/make_assets.py       模型进 APK：贴图缩 1/4、中文名转索引
├── assets/model/              19MB：moc3 + 5 张贴图 + 23 表情 + 8 动作 + index.txt
└── test/                      PC 上跑的验证，不进 APK
    ├── CueSmokeTest.java      68 项断言
    ├── JavaDump.java          与 C# 逐字对照
    ├── SayServerTest.java     41 项网络断言
    ├── WakeStateTest.java     84 项：连续对话/相位/冷却/告别词/唤醒来源/在场搭话/定时播报/超时兜底
    └── WakeReplyTest.java     36 项：唤醒开场白多候选随机（语音/走近/在场）+ 预录命中
```

---

## 怎么构建

```bash
cd C:\android\amber

python tools\make_assets.py   # 模型资源（改贴图比例时才需要）
python jni\build.py           # libambergl.so（动了 C++ 才需要，约 20 秒）
python build.py               # APK，约 16MB

python deploy.py              # 装到音箱并盯日志
```

不依赖 Gradle。构建链需要：JDK 17 + Android SDK（build-tools 34.0.0、platform android-33）
+ Android NDK r26d（在 `E:\toolchain\ndkx`，从腾讯镜像下的，dl.google.com 连不通）。

---

## 怎么验证

### 逻辑层（不需要设备）

```bash
cd C:\android\amber
JDK=C:\android\jdk17\bin

# 1. 68 项断言：词表、解析、时间轴、通道叠加
$JDK\javac -source 8 -target 8 -encoding UTF-8 -nowarn -d build/classes src/com/amber/speaker/cue/*.java src/com/amber/speaker/Text.java
$JDK\javac -encoding UTF-8 -cp build/classes -d build/test-classes test/CueSmokeTest.java
$JDK\java -Dfile.encoding=UTF-8 -cp "build/classes;build/test-classes" CueSmokeTest

# 1.5 全量动作词回归：遍历词表 69 基础词 + 13 组合词，每个都「做得出来」
$JDK\javac -encoding UTF-8 -nowarn -d build/word-classes src/com/amber/speaker/cue/*.java src/com/amber/speaker/Text.java
$JDK\javac -encoding UTF-8 -cp build/word-classes -d build/word-classes test/WordRegressionTest.java
$JDK\java -Dfile.encoding=UTF-8 -cp build/word-classes WordRegressionTest

# 2. 和桌宠逐字对照（98 行）
$JDK\java -Dfile.encoding=UTF-8 -cp "build/classes;build/test-classes" JavaDump > java.txt
# 与 E:\toolchain\out\csharp.txt 对比，应该完全一致

# 3. 网络层（起真服务、发真请求）
$JDK\javac -encoding UTF-8 -d build/pc-classes src/com/amber/speaker/Text.java src/com/amber/speaker/net/SayServer.java
$JDK\javac -encoding UTF-8 -cp build/pc-classes -d build/pc-classes test/SayServerTest.java
$JDK\java -Dfile.encoding=UTF-8 -cp build/pc-classes SayServerTest

# 4. 唤醒状态机（纯 Java，无 android 依赖）
$JDK\javac -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -d build/wake-classes src/com/amber/speaker/WakeState.java
$JDK\javac -encoding UTF-8 -cp build/wake-classes -d build/wake-classes test/WakeStateTest.java
$JDK\java -Dfile.encoding=UTF-8 -cp build/wake-classes WakeStateTest
```

### 和桌宠对照是怎么做的

把 `大肥鱼插件\src\FishPet\Body.cs` 单独编成一个控制台程序
（`E:\toolchain\bodytest`），跑同一批 40 个 cue / 11 段文本 / 5 个序列 / 3 条时间轴，
输出命令串和解析结果；Java 侧 `JavaDump` 跑同样的输入。两边 **98 行逐字对比，完全一致**。

这件事值得单独说：**没有这个对照，「移植对了」只是感觉**。60 个词里只要有一个关键帧
抄错三位小数，在真机上就是「看起来有点怪」，永远不会被发现。

第一次对比出了 1 处差异：`0:-0` 对 `0:0`。是 IEEE 负零的字符串表示不同，
渲染上完全等价，但还是让 Java 跟着输出 `-0`，这样以后再出现差异就一定是真问题。

---

## 和桌宠的差异（都是有意为之）

| 项 | 桌宠 | 这里 | 原因 |
|---|---|---|---|
| 空白判断 | .NET `Trim()`，Unicode-aware | `Text.java` 自己实现 | **Java 的 `String.trim()` 不去全角空格 U+3000**，会让纯动作行变成空气泡、让 `"　"` 通过判空 |
| 网络 | .NET HttpListener | 手写 ServerSocket | Android 没有 `com.sun.net.httpserver`，为一个 POST 引库不划算 |
| 渲染 | D3D11 渲染器子进程 | OpenGL ES 2 + GLSurfaceView | 平台不同；但 `Sink` 命令格式和桌宠完全一致，能直接对照 |
| 「身体怎么说」 | 脸追、扫视、走路晃动都在渲染器里 | 只留「怎么动」，驱动全交给 Java 的 `Body` | 音箱没有摄像头，动作只来自〔〕 |
| 签名 | — | v1 + v2 双签 | **5.1 必须有 v1**（它只校验 v1），但没有 v2 会让 targetSdk 30 的包在新系统上装不上。两个都要 |

## 照抄了上游的一个局限

`〔大哭〕` 拿不到「大笑」这个建议。因为 `大` 被当成幅度修饰符剥掉了，剩一个单字 `哭`，
没有等长的词可以比。要修就得放弃「大歪头」这类写法，不划算——而且模型看得到身体说明，
不会用表外的词。**知道它会在什么时候给不出建议就够了**，不要以为是 bug。

---

## 调试

```bash
# 看她身体收到了什么
adb logcat -s AmberCue

# 手动喂一句
curl -X POST http://<音箱IP>:8788/say \
     -H "Content-Type: text/plain; charset=utf-8" \
     --data-binary "〔歪头 微笑〕你终于来了"

# 问一下状态
curl http://<音箱IP>:8788/state
```

`/state` 的 `speaking` 是她现在有没有在说话，`posing` 是身上有没有保持的姿势。
按契约 §6，这里**不放情绪**——情绪只走〔〕，设备不该有自己的心情。

### 打断与过期

默认是**排队**：她一句一句说，不抢话。两个可选的参数改变这个行为，
查询串和 JSON body 两种写法都收：

| 参数 | 位置 | 含义 |
|---|---|---|
| `interrupt` | `?interrupt=1` 或 `"interrupt": true` | 打断：清空待说的，**并让正在说的那句当场作废** |
| `ttl` | `?ttl=30` 或 `"ttl": 30` | 排队寿命，**单位是秒**，可带小数 |
| `ttl_ms` | `?ttl_ms=30000` | 同上，单位毫秒。怕把秒当毫秒用的场合写这个 |

```bash
# 改口：立刻停下改说这句
curl -X POST 'http://<音箱IP>:8788/say?interrupt=1' --data-binary "等一下，我说错了"

# 不太急的话，最多等 5 秒，过期就别说了
curl -X POST 'http://<音箱IP>:8788/say?ttl=5' --data-binary "顺便看看窗外"
```

`ttl` 从**入队**那一刻算，不是从开口算。已经开口的那句不算超时，让她说完——
话说一半掐掉比说过期的话更难受。

响应里会回显 `interrupt` 和 `ttl_ms`，但**不回队列深度**：队列在主线程上，
这个 HTTP 响应比主线程先返回，如实答只能是猜。要深度就查 `/state` 的 `queued`。

---

## 嘴型怎么来的

桌宠没有录音，它注释写得很清楚：「她的嘴跟着字动（没有录音了，只看在不在说话）」。
所以算法是一支正弦，不是音频包络：

```
level = 正在说话 ? 0.2 + 0.35 * |sin(开机毫秒 / 70)| : 0
```

周期 ≈ 440ms，约 2.3Hz，正好是一句话里音节的自然节拍。`src/com/amber/speaker/cue/Mouth.java`
照抄了这个公式，`test/MouthDump` 用 1834 帧的输入逐值对照过：
和独立实现的最大相对误差 2.48e-16（1 个 ulp），**两位小数输出 0 条不同**。

命令是单独一条 `mouth <0~1>`，不是 `set ParamMouthOpenY`。因为它必须**叠在**嘴型通道上：
「大笑」把 ParamMouthOpenY 按住 0.6，这时她开口说话，嘴还得在 0.6 的基础上继续动。

---

## 词表的名字和这个模型对不上（已修，但值得记住）

词表是从桌宠抄的，用的自然是**桌宠那个模型**的参数名。这个模型的参数名是自定义的，
于是有一批词在真机上**发出去等于没发**：

| 桌宠的词 | 这个模型有吗 | 后果 |
|---|---|---|
| `FaceAngleX` | ❌ 只有 `AngleX` | 转头、看左边、看右边、摇头、左右张望… 全不动 |
| `FaceAngleY` | ❌ 只有 `AngleY` | 抬头、低头、点头、看上面、看下面… 全不动 |
| `FaceAngleZ` | ❌ 只有 `AngleZ` | 歪头、晃头、慢歪头、歪一下头… 全不动 |
| `ParamAngleX` / `ParamAngleZ` | ⚠️ **存在但是死的** | 设了一点反应都没有，像素级 0 差异 |
| `EyeOpen` | ❌ 不是参数 | 渲染器自己拆成 `ParamEyeLOpen × ParamEyeROpen`，这个是对的 |

`SetParameterValue` 遇到不存在的 id **不报错、不打日志**。所以这 30 来个词
在她的身体上就是一片安静——而她本来也在呼吸眨眼，看画面看不出来。
这是靠 `tools/param_audit.py` 把 69 个词实际发出的参数名逐个跟 moc3 里的 id 对账才抓到的：

```bash
javac -cp ... test/ParamAudit.java && java -cp ... ParamAudit > out/word_params.txt
python tools/param_audit.py
```

**修法是在渲染器里加名字映射，不是改词表**——词表要和桌宠逐字一致，那是验收标准。
映射表在 `Model.cpp` 的 `kAliases`，三条，而且**都要反号**：

```
FaceAngleX → -ParamAngleX      FaceAngleY → -ParamAngleY      FaceAngleZ → -ParamAngleZ
```

反号是实测出来的：这个骨架 `ParamAngleX` 正数把脸转向**画面右侧**、`ParamAngleY` 正数是**低头**，
而桌宠的约定是「正数转屏幕左边、正数抬头」。三个轴一起反，说明它是个镜像过的骨架。
不翻的话转头会左右颠倒，`看左边` 和 `看右边` 会说反。

**⚠️ 一个花了很久才坐实的坑（2026-11-21）**：这个模型里有<b>两套</b>角度参数，名字极易混淆，
早期注释写反了，导致头部旋转（歪头/转头/低头）全部空转：

| 参数 | Name | 真相 |
|---|---|---|
| `ParamAngleX/Y/Z` | `AngleX/Y/Z` | **真正驱动头部旋转**（`--sweep` 实测 moved 327~331 像素） |
| `AngleX/Y/Z` | `OX/OY/OZ` | **死参数**（原点偏移，`--sweep` 实测 moved=0） |

而且 `ParamAngleX/Y/Z` 还是这个模型的**物理输出**（physics3.json 的 PhysicsSetting Output），
物理模拟每帧按 `AngleX/Y/Z` 输入重算它们。所以渲染器必须在物理更新（`OnLateUpdate`）**之后**
再设置这三个参数，否则刚设上去就被物理覆盖回 0。`Model.cpp` 的 `Tick` 里有 `headTurns`/`headMoves`
分离逻辑专门处理这件事。

定位手段：桌宠渲染器（`大肥鱼插件/renderer`）自带 `--sweep` 参数扫描，能输出每个参数实际驱动
多少像素（`moved` 列），一眼就能区分「真参数」和「死参数」：

```bash
l2d-render.exe --model 大肥鱼.model3.json --width 600 --height 800 --sweep out.tsv
```

**还没映射的**：`LeanInput`（前倾/后仰/叹气）和 `ViewX/Y/Roll/Squash`（摇摆类舞步）。
这些在桌宠里是**视图变换**，不是模型参数；音箱是固定机位，本来就不该有这些。
但副作用是「前倾」「后仰」这两个词现在不做任何事。要不要接到 `ParamBodyAngleX` 上，
等音响到了看实际画面再定——现在猜没意义。

---

## 接渲染器时踩到的三个坑

**1. Cubism Core 没有 armeabi-v7a。** 官方 `Core/lib/android/` 只有 arm64-v8a、x86、x86_64。
音箱是 MSM8909（Cortex-A7，**纯 32 位**），arm64 的库连加载都不会加载。
转机在 `harmonyos/armeabi-v7a/libLive2DCubismCore.a`：打开看是标准 ARM EABI5 的 ELF32 目标文件
（`machine=40`、`e_flags=0x5000000`），未定义符号只有 `memcpy`/`sinf`/`__aeabi_*` 这类标准符号，
bionic 全都有。换成它之后一次链通。**这件事没人写在文档里。**

**2. 贴图 8192×8192 传不上去。** Adreno 304 的 `GL_MAX_TEXTURE_SIZE` 够不到 8192，
而且**不报错**——只是白屏缺块。`tools/make_assets.py` 统一按 1/4 缩，正好等于之前量过的
「2048 档」（PSNR 38.4dB），显存从 155M 像素降到 9.7M（约 37MB RGBA）。
缩完之后用桌宠的 D3D11 渲染器渲了一张图复核：完整、无缺块、UV 没错位。

**3. 中文文件名不进 zip 路径。** 表情和动作的名字是中文（`脸红`、`竖手指切出来动画`），
直接当 assets 路径走 `AAssetManager_open` 有转义风险。改成 `e0.exp3.json` 这种 ASCII 名，
原名写进 `index.txt`，原生读一次索引即可。

**还有一个不报错只黑屏的**：模型必须在 GL 上下文活着的时候建。切出去再回来
`onSurfaceCreated` 会再来一次，旧贴图全失效——这时候如果还用旧句柄渲染，
画的是一堆已删除的纹理编号，屏上只剩黑。`GlSurface` 在 `onSurfaceCreated` 里整个丢弃重建。

---

## 真机验证清单（插上就知道）

`python deploy.py` 之后看 `adb logcat -s AmberGL`：

| 日志 | 说明 |
|---|---|
| `解析 xxx ms，句柄 N` | 不为 0 说明 moc3/物理/表情/动作都读到了 |
| `渲染器就绪（含着色器编译）xxx ms，着色器 program N 个` | N 正常是 **8**。变成几十上百说明补丁没生效（换了 SDK 或 CMake 里的替换被绕过） |
| `贴图 i WxH（上限 N）` | 括号里是这台机器的 `GL_MAX_TEXTURE_SIZE`（Fold3 实测 16384），比 2048 小就说明 1/4 还不够 |
| `渲染就绪：WxH` | 到这里应该已经看见她了 |

看不见她的时候按顺序排除：句柄为 0 → 资源没进去；`建纹理 -> false` → GL 那边失败；
都正常但还是黑 → 大概率是镜像/合成的问题，跟逻辑无关。

---

## 启动耗时（真机实测，不是估的）

在 Galaxy Z Fold3（骁龙 888 / Android 14 / arm32 进程）上，`am start` 到「她出现在屏幕上」：

| 阶段 | 冷启（装完第一次） | 热启（之后每次） |
|---|---|---|
| `Prepare` 解析（后台线程） | 228 ms | 196 ms |
| `CreateGl` 里的着色器编译 | **61 ms** | **22–26 ms** |
| 贴图（5 张，含解码 + mipmap） | 158 ms | 160–185 ms |
| **合计** | **≈0.45 s** | **≈0.39 s** |

系统口径的 `Displayed` 只有 **+164 ms**——因为第一帧画的是黑底加「加载模型…」，
她根本没在里面。所以「Displayed 很快」是个假象，别拿它当启动指标。

### 之前是 6.4 秒：穷举编译 474 个 program

`CubismShader_OpenGLES2::GenerateShaders()` 是**穷举**的：
16 种颜色混合 × 5 种 alpha 混合 × 6 种遮罩形态，加上基础着色器，一共 **518 个槽位、
实际编译 474 个 program**，每一个都要单独编译链接。实测 `2750ms ÷ 474 ≈ 5.8ms/个`。

而这个模型 715 个 drawable 只用到了 3 种混合模式，全部落在 `Normal` / `Add` / `Mult`
三个**固定槽位**上（`AddCompatible`、`MultiplyCompatible` 在 SDK 里就是复用 `Normal` 的程序）。
也就是说 **470 多个 program 编完一次都没用过**。

| | 改之前 | 改之后 |
|---|---|---|
| 编译的 program | 474 | **8** |
| 冷启着色器阶段 | 5735 ms | **61 ms** |
| 热启着色器阶段 | 2750 ms | **23 ms** |

把贴图从 8192 缩到 2048 对启动时间的帮助是 **0**——它只省显存，不省这次编译。

### 怎么改的：`jni/local/CubismShader_OpenGLES2.cpp`

SDK 目录里那份一个字没动（`E:\toolchain\sdk` 不在版本管理里，改了升级就丢）。
工程里放了一份拷贝，`jni/CMakeLists.txt` 和 `win/CMakeLists.txt` 都把它从 Framework
的源列表里换进去——**两端编的是同一份**。改动共三处：

1. `GenerateShaders()` 里三处穷举循环清空，只留固定槽位（Copy / SetupMask / Normal /
   Add / Mult / NormalAtop… 共 32 个槽位、8 个真程序）。
2. `SetupShaderProgramForDrawable()`：真画到某个组合而它还没编过时，**就地补编那一个**。
   所以「按需」不会漏——任何模型、任何混合模式都仍然正确，只是没预编的组合会在第一次
   用到时付一次编译费。索引反推公式对全部 474 个条目做过穷举校验，0 处不符。
3. `SetShaderSet()`：程序号为 0 的槽位直接返回，省掉启动时几百次注定失败的
   `glGetUniformLocation`（它只会返回 -1 并置 `GL_INVALID_VALUE`）。

文件开头有完整说明，`grep amber` 能找到全部改动。**升级 SDK 时**：重新拷一份上游文件，
照 `tools/patch_shader.py` 里那几步重做一遍。

验证方式是桌面端逐像素对照：待机、`exp 黑脸`、`motion 竖手指切出来动画` 三种情况与改动前
**差异均为 0 像素**，着色器 program 从 482 降到 8。

### 对音响（MSM8909 / Cortex-A7 1.1GHz / Adreno 304）的折算

着色器编译是驱动在 CPU 上单线程干的活，按单核性能折算 A7 约为 X1 的 1/10~1/4。
改完之后这一项在音响上落在 **0.6–1.0 秒**，不再是风险；剩下的启动时间主要是贴图
（解码 + mipmap + 上传），音响上按同比例折算约 **1.6–2.5 秒**，仍在 5 秒 ANR 线内。
**到货后第一个要量的仍然是这个总数**，但最坏情况已经从「15–40 秒」变成「2–3 秒」。



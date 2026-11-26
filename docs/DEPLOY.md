# 从零部署

这篇是**主教程**：从一台原厂音箱，走到「喊她名字她会应你」。
每一步都给了**验收命令**——上一步没通过就别往下走，后面所有现象都会是它的连锁反应。

> 全文假设 Linux/macOS 或 Windows + Git Bash；命令里的 `<音箱IP>`、`<主机IP>` 自己替换。
> 设备端只认 IPv4 局域网地址，别填 `localhost`。

---

## 0. 你会得到什么

一台能独立工作的音箱：

- 断网也能被唤醒、应声（预录应答在 APK 里）；
- 联网后走 AstrBot 的完整流水线（人设、记忆、工具），回复合成语音播回来；
- 半夜自己熄屏关灯，早上自己亮；
- 进程被杀、画面全黑会自己恢复。

以及一个本地调参台：改灯带、改作息、改搭话频率、换模型、投视频，都不用重新编译。

---

## 1. 前置条件

| 项 | 要求 | 说明 |
|---|---|---|
| 音箱 | 狗尾草琥珀全息音箱（la0920 / MSM8909） | 别的机器要自己改灯带、传感器、屏幕方向的适配 |
| 系统 | Android 5.1（API 22），**已 root（Magisk）** | 没有 root 也能跑，但**熄屏、人体感应、开机保活会失效**（见 §3） |
| 电脑 | JDK 17 + Android SDK（build-tools / platform 33） | 构建用；不需要 Android Studio，也不需要 Gradle |
| Cubism SDK | Cubism Core 静态库 + 两个渲染器 `.cpp` | **不含在仓库里**（授权不允许再分发），从官网下载后放 `jni/local/`，见 §5 |
| Live2D 模型 | 一个 Cubism 4 模型（`.moc3` + 贴图 + `model3.json`） | **不含在仓库里**，先用官方免费样例跑通链路，见 §6.1 |
| 主机 | 能跑 Docker 的 NAS / 小主机 / 树莓派 | 跑 AstrBot；语音识别与 TTS 都在它上面 |
| 网络 | 同一个局域网 | 音箱要能访问主机的 8789，主机要能访问音箱的 8788 |

先用手机/电脑确认能 ping 通音箱：

```bash
ping <音箱IP>
```

---

## 2. 打开 adb（USB 或无线）

### 2.1 USB

音箱一般有一个 micro-USB 口（能传数据的那种）。插上后：

```bash
adb devices          # 应该看到一行 device
adb shell getprop ro.build.version.sdk    # 应该是 22
adb shell getprop ro.product.cpu.abilist  # 应该含 armeabi-v7a
```

看到 `unauthorized` 就在音箱屏幕上点「允许 USB 调试」。

### 2.2 无线（推荐，盖盖子之后只能这样）

```bash
adb tcpip 5555
adb connect <音箱IP>:5555
adb devices          # 应该看到 <音箱IP>:5555  device
```

固化（重启后仍然生效）：

```bash
adb shell su -c 'setprop persist.adb.tcp.port 5555'
```

> 强烈建议在路由器里给音箱做 **DHCP 静态绑定**（按 MAC）。IP 一变，调参台、保活脚本、AstrBot 的 `device_url` 全要跟着改。
>
> 仓库里的脚本都用 **`AMBER_ADB`** 指无线 adb 地址、**`AMBER_DEVICE`** 指 HTTP 地址，默认值只是占位
> （`192.168.1.100`）。设一次省得每次都输：
>
> ```bash
> export AMBER_ADB=<音箱IP>:5555
> export AMBER_DEVICE=<音箱IP>:8788
> ```
>
> Windows 用 `set AMBER_ADB=<音箱IP>:5555`（当前窗口有效）。

**验收**：拔掉 USB，`adb -s <音箱IP>:5555 shell echo ok` 能回 `ok`。

---

## 3. Magisk 与权限（决定「完整体验」还是「能跑」）

这个 App 有三个地方需要比普通应用更高的权限：

| 需要什么 | 为什么 | 没有会怎样 |
|---|---|---|
| `/sys/class/leds/lcd-backlight/brightness` 可写 | 真的关背光（熄屏休眠） | 到点「熄屏」只是画面变黑，背光还亮着 |
| `/dev/input/eventN`（hum_sensor）可读 | 读 PIR 人体感应 | 走近唤醒、在场搭话全部失效 |
| Magisk `service.d` 开机脚本 | chmod 节点 + 保活拉起 | 重启后权限丢失；重装 APK 后没人拉起 |

### 3.1 装 Magisk 并确认 service.d 真的在跑

root 的具体做法随固件版本不同（解锁 → 提取 boot.img → Magisk patch → fastboot flash），这里不提供一键脚本。

装好后**先验证 Magisk 环境完整**——这一步踩过坑：

```bash
adb shell su -c 'ls -l /data/adb/magisk/busybox'
adb shell su -c 'cat /cache/magisk.log | tail -20'
```

如果日志里出现 `environment incomplete, abort` 或 `execve .../busybox failed`，
**所有 `service.d` 脚本都会被静默跳过**（现象：重启后熄屏不黑、走近唤醒失效）。
修法：

```bash
adb shell su -c 'cp /system/bin/busybox /data/adb/magisk/busybox'
```

### 3.2 装四个脚本

仓库 `tools/` 下已经有写好的：

```bash
adb push tools/backlight_perms.sh   /data/local/tmp/
adb push tools/hum_sensor_perms.sh  /data/local/tmp/
adb push tools/amber_keepalive.sh   /data/local/tmp/
adb push tools/amber_autostart.sh   /data/local/tmp/

adb shell su -c 'for f in backlight_perms hum_sensor_perms amber_keepalive amber_autostart; do
  cp /data/local/tmp/$f.sh /data/adb/service.d/$f.sh
  chmod 755 /data/adb/service.d/$f.sh
done'
```

> ⚠️ 脚本必须是 **LF 换行 + 纯 ASCII**。在 Windows 上用记事本改过会变成 CRLF，Magisk 执行时报
> `not found`，而且日志里看不出原因。

立即生效一次（不用重启）：

```bash
adb shell su -c 'sh /data/adb/service.d/backlight_perms.sh'
adb shell su -c 'sh /data/adb/service.d/hum_sensor_perms.sh'
```

**验收**：

```bash
adb shell su -c 'ls -l /sys/class/leds/lcd-backlight/brightness'   # 应该是 -rw-rw-rw-
adb shell getevent -lp /dev/input/event5 | head -5                 # 不用 root 也能读，name 应该是 hum_sensor
```

---

## 4. 装构建环境

只需要 JDK 与 Android SDK，**不需要 Gradle**。构建链是 `aapt2 → javac → d8 → zipalign → apksigner`。

```bash
# Ubuntu / Debian 举例
sudo apt install -y openjdk-17-jdk unzip
# 下载 commandline-tools 后
sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-33" "ndk;26.3.11579264" "cmake;3.22.1"
```

Windows 装 Android Studio 或单独装 SDK 都行。`build.py` 会按下面顺序自动找：

- SDK：`ANDROID_HOME` / `ANDROID_SDK_ROOT` → `C:\android\sdk` → `%LOCALAPPDATA%\Android\Sdk` → `~/Android/Sdk`
- JDK：`JAVA_HOME` → PATH 里的 `javac` → `C:\android\jdk17`

**验收**：

```bash
python build.py     # 还没放模型时会失败在 aapt2，能看到它打印的 SDK/build-tools/platform 路径
```

---

## 5. 准备 Live2D Cubism Core（不能随仓库分发）

`jni/local/` 需要两个东西：

1. **Cubism Core 静态库**：从 [Live2D 官网](https://www.live2d.com/sdk/download/native/) 下载
   Cubism SDK for Native，取出 `Core/lib/android/armv7/libLive2DCubismCore.a`，
   放到 `jni/local/libLive2DCubismCore_armv7_vfp.a`。
2. **渲染器源码**：`CubismRenderer_OpenGLES2.cpp` / `CubismShader_OpenGLES2.cpp`
   （同样在 SDK 包里，`Framework/src/Rendering/OpenGLES2/`）。

然后必须跑一次补丁脚本，否则**旋转会整片塌掉**：

```bash
python tools/patch_core_armv7.py
python jni/build.py
```

`patch_core_armv7.py` 做两件事：把 softfp 的 libm 引用改名到 `__amber_hf_*`，并链接
`LibmHardFloatArmv7.cpp`。原因见 [DEVELOPMENT.md](DEVELOPMENT.md#为什么必须打这个补丁)。

**验收**：

```bash
ls -l jni/build/libambergl.so     # 应该几百 KB
```

---

## 6. 准备模型

**仓库里没有模型，这是第一步最容易卡住的地方。**

没有 `assets/model/` 时 App 照样能装能启动，但画面会停在「模型加载失败」——
`adb logcat -d -s AmberGL:V | tail` 里是 `读不到 model3.json`。不是装坏了，是确实没东西可画。

### 6.1 先用官方样例模型把链路跑通

别急着找大肥鱼。先用 Live2D 官方免费样例验证「能解析、能出图、能动」：

1. 打开 <https://www.live2d.com/en/learn/sample/>，挑一个（**Hiyori Momose** 或 **Haru** 最省事），下载 zip；
2. 解压，找到里面的 `xxx.model3.json`；
3. 建目录 `assets/model/`，把解压出的**全部内容**拷进去；
4. **把 `xxx.model3.json` 改名成 `model3.json`** —— 代码按固定文件名找（`jni/src/Model.cpp` 里拼的就是 `directory + "model3.json"`），名字不对就还是「读不到 model3.json」；
5. 其余文件保持相对路径不变（`moc3`、`textures/`、`motion/`、`exp/` 等）。json 内部引用的是相对路径，不用改。

这些样例适用 [Live2D Free Material License](https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html)：学习、验证 SDK 集成没问题，商用前务必读一遍条款。

跑通这一步，屏幕上出现角色了，再往下做才有意义。

### 6.2 换成你自己的模型

换成**你有权使用**的 Live2D 模型，同样放进 `assets/model/`，结构：

```
assets/model/
  model3.json          # 必需
  model.moc3           # 必需
  textures/            # 必需
  motion/  exp/  physics3.json   # 可选
  index.txt            # 可选，中文名与动作/表情映射，格式见 examples/model-index.txt
```

如果贴图是 4096，先降采样——这台机器 1GB 内存，六张 4096 会直接爆：

```bash
python tools/pack_models.py
```

想放多套模型就再建 `assets/model_miku/`、`assets/model_sakura/`，调参台会自动列出来。

---

## 7. 编译与安装

```bash
python jni/build.py     # 只有改了 C++ 才需要
python build.py         # 产物：amber-speaker.apk
adb install -r amber-speaker.apk
adb shell am start -n com.amber.speaker/.MainActivity
```

### 第一次启动要看的三行日志

```bash
adb logcat -d -s AmberGL:V AmberCue:V | tail -20
```

| 日志 | 含义 |
|---|---|
| `解析 xx ms` | moc3 解析成功 |
| `建纹理 xx ms -> true` | 贴图上传成功 |
| `[W]RotationDeformer: Not found transformed Direction.` | **libm 补丁没打**，旋转会塌 |

### 屏幕方向

这台机器的屏是「系统转 180° + 光路上下翻」，应用侧必须补一次镜像。仓库里已经固化：

```bash
adb shell su -c 'settings put system user_rotation 2'
adb shell su -c 'settings put system accelerometer_rotation 0'
```

**验收判据只有一个：看实体屏。** 尾巴朝右、字幕在右、字正着读。
**截图不能当参照系**——`screencap` 的结果和实体屏差一次翻转，照着截图调会越调越反。

---

## 8. 首启验收

```bash
curl http://<音箱IP>:8788/state
```

逐项对照：

| 字段 | 期望 | 不对时看 |
|---|---|---|
| `"ok":true` | 服务起来了 | 8788 不通 → 见 [TROUBLESHOOTING](TROUBLESHOOTING.md#8788-完全没响应) |
| `"frames"` | 在涨 | 不涨 = GL 线程卡死 |
| `"content_age"` | 小于 30 | 一直涨 = 画面全黑（着色器编译失败） |
| `"clock_ok"` | true | false 就先手动对时（§9） |
| `"screen"` | on | off 且现在是白天 → 时钟不对 |
| `"volume"` | 0~1 | — |
| `"model"` | 你放的目录名 | 空 = 模型没打进 APK |

再让她说一句：

```bash
curl -X POST http://<音箱IP>:8788/say -d '你好呀，我是大肥鱼'
```

应该：字幕出现 → 出声 → 口型动 → 随机一段待机动作 → 灯带跟着电平跳。

---

## 9. 对时与调参台

这台机器**没有 RTC 电池**，开机时间停在 1977 年。时间不可信时她会「不熄屏、不播报」——
这是有意的保护，不是 bug。

```bash
# 手动推一次（毫秒！秒会被静默拒收）
curl -X POST -H 'Content-Type: application/json' \
     -d "{\"epoch\":$(date +%s000)}" http://<音箱IP>:8788/clock
```

之后接上 AstrBot，插件会每 6 小时自动推一次。

调参台：

```bash
# Windows：双击 tools/model_console/start_console.cmd
# 其它平台：
python tools/model_console/server.py <音箱IP>:8788
# 也可以先 export AMBER_DEVICE=<音箱IP>:8788，然后不带参数启动
# 浏览器打开 http://127.0.0.1:8790/
```

第一次打开如果页面说「设备连不上」，在**「连接与运维」**里把地址改成你的音箱 IP，保存即可
（会写进 `console.json`，不用改代码）。

---

## 10. 接 AstrBot

完整说明在 **[ASTRBOT.md](ASTRBOT.md)**，这里是最短路径：

1. 把 `contrib/astrbot_plugin_amber_speaker/` 整个目录放进 AstrBot 的 `data/plugins/`；
2. 重启 AstrBot 容器；
3. 面板 →「平台」→ 新增「琥珀音箱」，填：
   - `device_url`：`http://<音箱IP>:8788`
   - `host`：`0.0.0.0`，`port`：`8789`
   - `audio_base`：`http://<主机IP>:8789`（**必须是设备能访问到的地址**，不能写 localhost）
4. 把 8789 端口映射出来；
5. 确认 `owner_id` 已进 `admins_id`（插件启动时会自动加）。

**验收**：

```bash
curl http://<主机IP>:8789/state          # 插件活着
# 在 AstrBot 里给音箱发一句话，音箱应出声
```

语音闭环（她主动听）需要本地 ASR，配置见 [ASTRBOT.md#本地-asr](ASTRBOT.md#本地-asr)。

---

## 11. 整机验收清单

全部打勾，才算部署完成：

- [ ] `adbd` 无线连接，路由器已做静态绑定
- [ ] `/data/adb/service.d/` 里四个脚本都在，`/cache/magisk.log` 没有 `environment incomplete`
- [ ] 背光节点是 `-rw-rw-rw-`，`getevent -lp` 能找到 `hum_sensor`
- [ ] `/state` 的 `frames` 在涨、`content_age` 小于 30
- [ ] 实体屏：尾巴朝右、字幕在右、字正着读
- [ ] `POST /say` 有字幕、有声音、有口型
- [ ] 喊「大肥鱼」她能应（不喊名字直接说话，她不理）
- [ ] 走近她，PIR 触发后她会打招呼
- [ ] 调参台能连上，灯带/音量滑块能动
- [ ] `curl /settings` 返回 13 项设置；改一项后重启应用仍在
- [ ] 熄屏时间窗外她亮着，窗内（默认 00:00–08:00）自动黑屏
- [ ] 断电重插，25 秒内自己回到 8788（`/data/adb/service.d/amber_autostart.sh`）
- [ ] `adb install -r` 之后进程被强杀，40 秒内自己回来（kevalive 生效）

---

## 12. 升级与卸载

```bash
# 升级（保留设置与视频）
adb install -r amber-speaker.apk
adb shell am start -n com.amber.speaker/.MainActivity

# 卸载
adb uninstall com.amber.speaker
adb shell su -c 'rm -f /data/adb/service.d/amber_keepalive.sh /data/adb/service.d/amber_autostart.sh'
```

> ⚠️ **重装 APK 会让系统 force-stop 应用**，之后没人拉起它。
> 这就是 `amber_keepalive.sh` 存在的原因；没装这个脚本的话，表现是「装完就再也连不上 8788」。

---

## 13. 卡住了？

按现象查 **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)**。最常见的五个：

| 现象 | 先看这一条 |
|---|---|
| 喊她完全没反应 | 是不是被软关机了：`/state` 里 `Standby: yes`（正常 no） |
| 8788 连不上 | 应用被 force-stop 了，`adb shell am start` 拉一下 |
| 有画面但全黑 | `logcat` 搜 `program 0 个`（着色器编译失败，重启应用） |
| 有字幕没声音 | TTS 的 WAV 头坏了（流式输出的坑），看插件日志 |
| 第一遍说话她没听见 | VAD 底噪取错帧，见 TROUBLESHOOTING |

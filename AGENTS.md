# AGENTS.md — 给 AI／自动化协作者的说明

这个文件是给编码代理（Claude Code、Codex、Cline 等）看的：**先读这一页，再动手**。
人类读者看 [README](README.md) 和 [docs/DEPLOY.md](docs/DEPLOY.md) 就够了。

---

## 这是什么

一台 Android 5.1（API 22）的桌面音箱，跑一个 Live2D 角色 + 手写 HTTP 服务，
通过 AstrBot 插件接入大模型。设备端是 Java + C++（JNI），构建**不用 Gradle**。

- 设备端代码：`src/`（Java）、`jni/`（C++ / Cubism Core）
- 主机侧：`contrib/astrbot_plugin_amber_speaker/`（Python，AstrBot 平台插件）
- 工具：`tools/`（构建辅助、调参台、打包）

## 构建与验证

```bash
python jni/build.py     # 只在改了 C++ 时需要。产物 jni/build/libambergl.so
python build.py         # 出 amber-speaker.apk（几秒）
adb install -r amber-speaker.apk
adb shell am start -n com.amber.speaker/.MainActivity
curl http://<设备IP>:8788/state        # 验收：ok=true、frames 在涨、content_age<30
```

**失败判据**：`build.py` 失败会打 `!! 失败`，中文在 GBK 终端里是乱码。
**不要用 `grep error` 过滤输出**——匹配不上会让你以为成功了，实际装的是旧包。看最后 20 行。

前置：JDK 17、Android SDK（build-tools / platform-33 / NDK / cmake）。
`build.py` 与 `jni/build.py` 会自动探测，也可以用 `ANDROID_HOME` / `JAVA_HOME` /
`ANDROID_NDK_HOME` 指定。

## 改代码前必须知道的硬约束

| 约束 | 违反的后果 |
|---|---|
| **不能用 `java.util.function.*`** | API 22 + 裸 d8 不脱糖，运行时 `NoClassDefFoundError`。用 `com.amber.speaker.fn.Suppliers` |
| **SN3218 的 `write()` count 必须是 36** | 传 144 内核 oops，设备直接重启 |
| **`motion3.json` 必须 `indent=1`** | `ParseNumeric` 解析失败 → `LoadMotion` 返回 nullptr，表象只是「没有这个动作」（`grep '[0-9][\]\}]'` 必须 0 处） |
| **`set` 是按住，只有 `clear` 松开** | 绕过 `Body` 直发的按住值永不回收 →「走近一次，眼睛永远半睁」 |
| **方向：系统 rotation=2 + 应用侧只翻一次** | 字幕层不能再逐字翻。**截图不能当方向参照系**，只看实体屏 |
| **屏幕方向、VAD 底噪、合批批次键** | 改动前先读 `docs/HARDWARE.md` 与 `docs/TROUBLESHOOTING.md` 对应章节 |
| **播放期间不能开麦** | HAL 会拆采集流，`AudioRecord.read()` 连续失败 |

## 代码风格

- 注释写**中文**，重点写「为什么」和「踩过什么坑」，不写「这行做了什么」；
- 纯逻辑类（`WakeState`、`SleepState`、`Settings`）**不 import android.\***，要能在 PC 上测；
- 新增可调参数：优先放进 `Settings`（运行时可改），而不是写死常量；
- 行为类设置的默认值必须与旧常量**逐字一致**——升级不能悄悄改变行为。

## 改完之后

1. `python build.py` 通过；
2. 真机 `curl /state`：`ok=true`、`frames` 在涨、`content_age` 合理；
3. 改了设置项就 `curl /settings`，改一项 → 重启应用 → 确认还在；
4. 改了渲染/方向 → **站到实体屏前看**，不要看截图；
5. 更新 `CHANGELOG.md` 与相关 `docs/`。

## 不要做的事

- 不要把模型、音色、`jni/local/libLive2DCubismCore*.a` 提交进仓库（有版权，`.gitignore` 已排除）；
- 不要把密码、token、私钥写进任何文件；`contrib/deploy_plugin.py` 的历史版本曾硬编码过 NAS 密码，
  现在全部走环境变量；
- 不要 `git push --force` 到主分支；
- 不要为了「看起来能跑」而跳过真机验证——这个项目一半的坑只在真机上出现。

# 第三方组件与资源

本项目的**代码**是 MIT（见 [LICENSE](LICENSE)），但下列组件与资源不在此许可范围内，各自的授权由权利人决定。

## 1. Live2D Cubism SDK for Native / Cubism Core

本仓库**不包含** Cubism SDK 的任何文件，具体是：

- `jni/local/libLive2DCubismCore*.a` —— Cubism Core 静态库
- `jni/local/CubismRenderer_OpenGLES2.cpp`
- `jni/local/CubismShader_OpenGLES2.cpp` —— Framework 的 OpenGL ES2 渲染器源码

它们版权属 Live2D Inc.，受其专有许可约束，**不允许随本项目再分发**。请自行从
<https://www.live2d.com/sdk/download/native/> 下载并同意其许可后放入 `jni/local/`
（说明见该目录下的 README）。本项目只以**补丁脚本**的形式（`tools/patch_*.py`）
描述对它做的修改，脚本本身是 MIT 的。商用发布前请阅读 Live2D 的出版许可条款。

## 2. Live2D 模型（.moc3 / 贴图 / 动作 / 表情）

本仓库不含任何模型。你放入 `assets/` 的模型必须是你自己拥有或已获得授权的作品；
模型作者通常禁止再分发，请注意遵守。

## 3. 声音（TTS 音色、RVC 模型、预录音频）

本仓库不含音色模型与预录音频。CosyVoice / GPT-SoVITS / RVC 训练出的音色，
其使用范围由素材来源与训练者授权决定。

## 4. 依赖的开源项目

| 项目 | 用途 | 许可 |
| --- | --- | --- |
| [AstrBot](https://github.com/AstrBotDevs/AstrBot) | 消息平台、人设、记忆、插件 | AGPL-3.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) / SenseVoice | 本地语音识别 | Apache-2.0 |
| [CosyVoice](https://github.com/FunAudioLLM/CosyVoice) | 语音合成 | Apache-2.0 |
| [RVC](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI) | 音色转换 | MIT |

它们各自的许可证约束其自身代码与模型。

## 5. 角色形象

「大肥鱼」等角色形象的权利归原作者所有。本仓库公开的是**驱动这些形象的技术实现**，
不是形象本身。请勿在未获授权的情况下将角色形象用于商业用途。

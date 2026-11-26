# 琥珀音箱 · AstrBot 插件

把音箱做成 AstrBot 的一个**消息平台**：外面送进来的每句话都走完整流水线
（人设、记忆、工具、记账，和 QQ 里一模一样），回复再由这里合成语音、连同音频地址推回设备。

详细配置见仓库的 [docs/ASTRBOT.md](../../docs/ASTRBOT.md)。

## 安装

```bash
cp -r astrbot_plugin_amber_speaker <AstrBot>/data/plugins/
docker restart astrbot
```

面板 →「平台」→ 新增「琥珀音箱」，至少填这两项：

| 键 | 例子 | 说明 |
|---|---|---|
| `device_url` | `http://192.168.1.100:8788` | 音箱的局域网地址 |
| `audio_base` | `http://192.168.1.10:8789` | **设备能访问到**的地址，不能写 localhost |

再把容器的 8789 映射到宿主机。

## 最小验收

```bash
curl http://<主机IP>:8789/state        # 插件活着
curl -X POST http://<主机IP>:8789/utter -d '你好'   # 设备应该出声
```

## 依赖

- AstrBot ≥ 4.16
- 一个可用的 TTS provider（`tts_provider` 填你的）
- 想做语音闭环还需要本地 ASR（sherpa-onnx + SenseVoice），见 docs

插件本体只用标准库，不需要额外 pip 包。

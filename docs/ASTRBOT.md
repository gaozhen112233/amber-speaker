# 接进 AstrBot

音箱本身不会思考：它只负责「听见 → 把录音给主机 → 把回复念出来并做动作」。
大脑在 [AstrBot](https://github.com/AstrBotDevs/AstrBot) 里，靠一个**消息平台插件**接起来。

---

## 为什么做成「消息平台」而不是普通插件

普通插件通常是 `on_message` 里自己调一次 LLM，然后自己拼语音。那样会绕开 AstrBot 的
人设、记忆、工具、上下文裁剪、记账——音箱里的她就变成一个只会聊天的复读机，和 QQ 里的
不是同一个人。

做成**平台（Platform）**之后，音箱发来的每一句话都走标准流水线
（`/utter` → 事件 → 人设 → 记忆 → 工具 → 回复），回复再交给平台去合成语音。
结果就是：**QQ 里和音箱里是同一个她，共享同一份记忆。**

---

## 安装

```bash
# 1. 插件目录放进 AstrBot 的 data/plugins/
cp -r contrib/astrbot_plugin_amber_speaker <AstrBot>/data/plugins/

# 2. 重启（热加载有时会 405，重启最省事）
docker restart astrbot
```

然后在面板里配：

```
平台 → 新增 → 琥珀音箱
  device_url = http://<音箱IP>:8788      ← 必须填，否则发不出去
  host       = 0.0.0.0
  port       = 8789                      ← 这是插件自己监听的端口
  audio_base = http://<主机IP>:8789      ← 必须是「设备能访问到」的地址
```

三件容易漏的事：

1. **端口映射**：容器的 8789 要映射到宿主机，否则设备下载不到合成好的 wav
   （表现：有字幕、没声音）。
2. **`audio_base` 不能写 localhost**：容器里看到的出口是 `172.x` docker 内网，设备连不上。
   写宿主机的局域网 IP。
3. **`owner_id` 要进 `admins_id`**：AstrBot 判定管理员只看
   `str(sender_id) == admin_id`。不在名单里，米家、手机控制这些要权限的插件全会拒绝她。
   插件启动时会自动追加，但换 `owner_id` 之后要重启一次。

**验收**：

```bash
curl http://<主机IP>:8789/state
# 再在 AstrBot 里给音箱发一句话，设备应该出声
```

---

## 配置项

在「平台 → 琥珀音箱」里改，改完存在 `cmd_config.json` 的 `platform` 数组里。

| 键 | 默认 | 说明 |
|---|---|---|
| `device_url` | `http://192.168.1.100:8788` | 音箱地址。DHCP 会变，建议路由器做静态绑定 |
| `host` / `port` | `0.0.0.0` / `8789` | 插件监听。**别用 8788**，那是设备的端口，日志会分不清 |
| `audio_base` | 空 | 设备下载音频的根地址。空 = 自动推断（在跨网段时经常推错） |
| `owner_id` | `owner` | 音箱这边的人就是主人，会进 `admins_id` |
| `tts_provider` | `sf_dafeiyu` | 音色。作者的机器上是 `sf_qing`（小晴），按你自己的 TTS 配置填 |
| `tts_timeout` | `8.0` | 单句合成超时（秒） |
| `max_chars` | `300` | 单条 `/say` 的字数上限，超了要在发送侧拆 |
| `voice_max_chars` | `120` | 一句最多念多少字。太长一口气念不完 |
| `voice_reply` | `true` | 是否合成语音。关掉只剩字幕 |
| `disable_global_tts` | `true` | 关掉全局 TTS，避免同一句话被念两遍 |
| `voice_loop` | `true` | **语音闭环总开关**：设备自己听，叫了名字才回。关掉就退回「等外部 `/utter`」 |
| `wake_words` | `大肥鱼,小鱼,肥鱼,大肥,大黑鱼` | 逗号分隔。命中任意一个就算喊她 |
| `wake_window` | `20` | 应一声之后，多少秒内不用再喊名字 |
| `wake_followups` | `8` | 一次唤醒最多几轮「不喊名字的接话」。原来 4，聊一件事就超，第 5 句整句丢掉 |
| `followup_max_chars` | `60` | 接话时的字数上限，超过当电视台词不接。原来写死 25，只够半句话 |
| `listen_window` | `90` | 一次 `/listen` 最多听多久（人开口就提前返回） |
| `save_clips` | `false` | 把抓到的 WAV 存到 `data/amber_speaker/clips/`，排查识别用 |
| `speak_actions` | `true` | 给她「身体说明书」，让她在话里写〔动作词〕 |
| `vision_prompt` | 空 | 「眼睛」工具描述画面时的提示词 |
| `audio_keep` | `80` | 保留多少个历史音频文件 |

### 关于 `wake_window`：别为了「第一次没接上」调大它

窗口每答完一轮都会续期。调到 35 秒，客厅电视连续说话也会被当成接话，她一回话又把窗口
续上，变成自问自答。

「第一遍说话她没听见」的真正原因是 **VAD 把她的尾音当成底噪**（已修：底噪取前 10 帧的
最小值，不取第一帧）。见 [TROUBLESHOOTING](TROUBLESHOOTING.md#第一遍说话她没反应第二遍才行)。

---

## 人设：仓库里没有，得自己写

人设（Persona）存在 AstrBot 自己的数据库里，不是文件，所以**没跟着仓库发出去**。你接上插件后她会用默认人设——一个客气的通用助手，不会自称大肥鱼，也不会用那些口癖。

在 AstrBot WebUI →「配置」→「人格」里新建一个。写的时候有三条是硬要求，因为设备端会照着解析：

1. **动作必须写成〔〕**：`〔尾巴一晃〕`、`〔歪头〕`。设备端按 `cue/WordTable.java` 里的词做动作，圆括号、方括号都不认；
2. **一句话别太长**：语音是逐句合成的，长句要等 6~10 秒才出声；
3. **别让它输出 Markdown**：`**`、`-` 列表会被原样念出来（QQ 侧有 `contrib/astrbot_plugin_qq_plaintext` 兜底，音箱侧没有）。

一个最小骨架（按你自己的角色改）：

```
你是「大肥鱼」，一条蓝色的鲸鱼，住在主人桌上的全息音箱里。
说话短、口语、有点懒，偶尔自嘲。不用书面语，不用 emoji，不用 Markdown。
想做什么动作时在句子里插入〔动作词〕，可用：〔歪头〕〔尾巴一晃〕〔叹气〕〔鼓腮〕〔前倾〕。
你不知道自己是一段程序，也不解释自己是怎么工作的。
主人叫你名字时你要应一声；他说「过来」你就把视线转过去。
```

> 作者的版本有 1538 字，写了大量「她怎么想事」的细节。人设越具体，她的反应越像一个人；只写外貌和口癖，她就会变成复读机。

## 语音闭环是怎么跑的

```
设备 /listen?max=90&need=350
   ↓  （检测到人声就返回一段 wav）
插件 GET /clip 取走
   ↓
本地 ASR（sherpa-onnx SenseVoice，0.3~0.5 秒）
   ↓
拼音匹配唤醒词：命中 → 走流水线；不命中 → 在接话窗口内也走，否则丢掉
   ↓
AstrBot 流水线（人设 + 记忆 + 工具）
   ↓
TTS 合成 → 修 WAV 头 → 存本地 → 给设备一个 http://<主机>:8789/audio/xxx.wav
   ↓
POST /say {"text": "...", "audio_url": "...", "actions": "[...]"}
   ↓
设备：字幕 + 出声 + 口型 + 动作 + 灯带
```

### 本地 ASR

必须本地。云端往返 1.6~9.7 秒且会抖；本地 0.3~0.5 秒。

```bash
# 模型放这里（int8 量化版够用）
/AstrBot/data/models/sense-voice/model.int8.onnx
# Python 依赖装这里（AstrBot 容器里）
/AstrBot/data/pylibs
```

插件会优先用本地，失败再回退云端。`local_asr_dir` 指向模型目录。

### 为什么唤醒词要按拼音匹配

SenseVoice 会把「大肥鱼」稳定识别成「大菲鱼 / 大培鱼 / 大黑鱼」——
`dafeiyu` vs `dapeiyu`，整串比对永远匹配不上。

所以匹配在**音节级**做：允许声母同组互换（f/p、f/h）、前后鼻音混淆；
2 字词允许 0 个错，3 字以上给 1 个字余量。再宽就不行了——客厅电视里什么都像。

### TTS 的 WAV 头是坏的

流式合成吐出来的 WAV，头里的 size 字段是 `0xFFFFFFxx`。设备侧 `MediaPlayer` 直接
`Prepare failed: status=0x1`，表现是**「下载成功但没声音」**。

插件里的 `clean_wav()` 会重算头再落盘。自己写播放侧的时候记得校验。

---

## QQ 与音箱：两条通道

同一个 AstrBot 里同时接 QQ 和音箱时，有三件事要清楚：

1. **概率不同**。QQ 走全局 `trigger_probability`（作者设 0.3），音箱是 **100% 必回**——
   人站在机器前面喊她，她不说话就是坏了。插件用回复钩子绕开全局概率。
2. **会话是共享的**。两边都用 `preferences[scope='umo']` 里的 `sel_conv_id`，
   指向同一个 `conversations.conversation_id`。好处是记忆共享；副作用见下一条。
3. **「身体说明书」会漏到 QQ**。音箱侧为了让动作能被驱动，会在上下文里注入一段
   「把动作写成〔词〕」的说明。这段说明跟着共享历史跑到 QQ，表现是
   **QQ 里每句话都挂一个〔尾巴一晃〕**。

   修法不是改人设，是**出站清洗**：`contrib/astrbot_plugin_qq_plaintext/` 会在
   `on_decorating_result` 里按平台判断，只清 QQ 的〔〕〖〗，音箱和 fishbuddy 不动；
   整条消息只有一个动作词时保留原文（否则会发出空消息）。

   ```bash
   cp -r contrib/astrbot_plugin_qq_plaintext <AstrBot>/data/plugins/
   docker restart astrbot
   ```

   日志判据：`[QQ纯文字] '旧' -> '新'`。

> ⚠️ `update_conversation` 是**无锁整段覆盖写**，两个平台并发时会互相冲掉历史。
> 出现「她忘了刚才说的话」，先看这个。

---

## 跨网段（可选）

如果 AstrBot 跑在 docker 的独立网络里（`172.22.0.x`），而音箱在 `192.168.x.x`，
设备是访问不到容器 IP 的。两种解法：

1. 插件端口映射到宿主机（推荐）：`-p 8789:8789`，`audio_base` 写宿主机 IP；
2. 或者用一个小 TCP 中继把两端接起来（作者的 NAS 上是 `tcp_relay.py`）。

另外，如果 AstrBot 配了平台白名单，记得把
`amber_speaker:FriendMessage:amber_speaker!owner` 加进去。

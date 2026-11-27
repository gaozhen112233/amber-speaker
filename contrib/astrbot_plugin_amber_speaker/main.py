"""琥珀音箱（C2 / 大肥鱼）接进 AstrBot —— 让她真的在这个音箱里说话。

链路
----
    外部（手机 / 米家场景 / 调试 curl）
        │   POST http://<NAS>:8789/utter   {"text": "今天好累啊"}
        ▼
    AstrBot 完整流水线（人设 / 记忆 / 工具 / 记账，和 QQ 里一模一样）
        │   回复钩子
        ▼
    强制 TTS（sf_dafeiyu，绕开 trigger_probability）
        │   POST http://<设备>:8788/say
        │   {"text": "〔皱眉〕…", "audio_url": "http://<NAS>:8789/audio/s-xxx.wav"}
        ▼
    音箱：字幕 + 语音 + 口型 + 身体动作

为什么做成「消息平台」而不是「自己调大模型」
--------------------------------------------
如果这里自己拼一次 LLM 请求，她就会变成一个没有记忆、没有人设、不会用工具的复读机。
做成 Platform 之后，`/utter` 进来的每一句话就是一条正常消息，走的是和 QQ 完全相同的
流水线——这才是用户要的「同一个她」。

不能复用的两件事
----------------
* **全局 TTS 的概率开关**（`trigger_probability = 0.3`）：那条是给 QQ 的。音箱是纯语音
  交互，「七成时候没声音」等于没回答。所以这里 `disable_global_tts` 把会话级 TTS 关掉，
  改由自己 100% 合成（见契约 §14.5.1）。
* **音色**：必须显式取 `sf_dafeiyu`。全局当前是 `sf_qing`（小晴），直接用就会换人。
"""

from __future__ import annotations

import asyncio
import hashlib
import io
import json
import re
import socket
import struct
import sys
import time
import urllib.parse
import wave
from array import array
from pathlib import Path
from typing import Any

import aiohttp
from aiohttp import web

try:  # 识别结果是同音字（「大菲鱼」），拼音比对才认得出来
    from pypinyin import lazy_pinyin
except Exception:  # noqa: BLE001
    lazy_pinyin = None

from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, MessageChain, filter
from astrbot.api.message_components import Plain
from astrbot.api.platform import (
    AstrBotMessage,
    MessageMember,
    MessageType,
    Platform,
    PlatformMetadata,
    register_platform_adapter,
)
from astrbot.api.provider import ProviderRequest, ProviderType
from astrbot.api.star import Context, Star, register
from astrbot.core.star.session_llm_manager import SessionServiceManager
from astrbot.core.utils.astrbot_path import get_astrbot_data_path

# 本地识别（sherpa-onnx 的 SenseVoice）。装在 /AstrBot/data/pylibs（pip --target 打的），
# 运行时得先把那个目录塞进 sys.path，否则 import 不到。import 失败就是没装——
# _load_local_asr 会留空，回退云端。
# 注意：这段必须放在 get_astrbot_data_path import 之后（它就在上面），
# 之前把它写在 import 之前，模块顶层执行时 get_astrbot_data_path 还没定义，
# NameError 被 except 吞掉，sherpa_onnx 静默变 None，本地识别悄悄失效、退回云端。
try:
    _PYLIBS = str(Path(get_astrbot_data_path()) / "pylibs")
    if _PYLIBS not in sys.path:
        sys.path.insert(0, _PYLIBS)
except Exception:  # noqa: BLE001
    _PYLIBS = ""
try:
    import sherpa_onnx  # noqa: F401
except Exception:  # noqa: BLE001
    sherpa_onnx = None

PLATFORM_TYPE = "amber_speaker"

# 平台实例（Platform）拿不到 Context，而 TTS provider 在 Context 的 provider_manager 里。
# 插件先加载、平台后实例化，所以这里存一份给平台用。
_PLUGIN_CONTEXT: Context | None = None

# 平台实例也存一份：插件要在「整轮发完」时叫平台把话送出去，而 Star 里
# 拿不到平台对象（star_map 里放的是 Star 类，不是 Platform）。
_PLATFORM_INST: "AmberSpeakerPlatform | None" = None

# 插件设置页里能看到的默认值。改完存进 cmd_config.json 的 platform 数组。
PLATFORM_TMPL: dict[str, Any] = {
    "type": PLATFORM_TYPE,
    "enable": True,
    "id": PLATFORM_TYPE,
    "host": "0.0.0.0",
    # 插件自己监听的端口。契约 §13 原来写 8788，但设备的 8788 也是这个数——
    # 两台机器同一个端口号，看日志时会分不清是谁在说话，所以错开成 8789。
    "port": 8789,
    # 设备在哪。DHCP 可能变，变了就在这里改，或者给路由器绑个静态租约。
    # ↓ 改成你的音箱 IP（面板里也能改）
    "device_url": "http://192.168.1.100:8788",
    # 设备下载 wav 用的根地址。空 = 自动推断本机在设备那个网段的 IP。
    "audio_base": "",
    "owner_id": "owner",
    # 音箱永远是「大肥鱼」，不是当前全局那个音色（现在全局是 sf_qing 小晴）。
    "tts_provider": "sf_dafeiyu",
    "tts_timeout": 8.0,
    # 契约 §3：单条 /say 的 text 上限 300 字，超了要在发送侧拆开。
    "max_chars": 300,
    "disable_global_tts": True,
    "voice_reply": True,
    # 语音对话闭环：设备自己听，听到就叫醒大模型回话。
    # 关掉就退回「只能等外部 /utter」的老状态。
    "voice_loop": True,
    # 叫她的名字。识别文本里出现任意一个就算喊她；只喊名字不带内容时，
    # 设备会应一声「小鱼在」再等下一句。
    # 「大肥」是给识别截断留的兜底：真机出现过只吐「大肥」两个字的情况。
    "wake_words": "大肥鱼,小鱼,肥鱼,大肥,大黑鱼",
    # 一次 /listen 最多听多少秒。人开口就立刻返回，这个值只是上限。
    "listen_window": 90,
    # 唤醒后的接话窗口（秒）。保持 20 秒，不要为了「第一次没接上」盲目放大：
    # 窗口每答完一轮都会重新续期，35 秒会让客厅电视连续说话也被当成接话，
    # 她一回话又把窗口续上，形成自问自答。第一遍丢的真正原因是 VAD 把她的尾音
    # 当成底噪（已修），不是窗口不够长。
    "wake_window": 20,
    # 一次唤醒后最多允许这么多轮「不喊名字的接话」，超过就必须重新喊名字。
    # 挡电视连续对话的最后一道闸：就算误接了一句，也不会无限续下去。
    "wake_followups": 4,
    # 语音每句最多念多少字。太长的话一口气念不完，也占满 TTS 配额。
    "voice_max_chars": 120,
    "audio_keep": 80,
    # 调试：把每次 /listen 抓到的 WAV 存一份到 data/amber_speaker/clips/，
    # 用来对照「同一段录音为什么识别成这个字」。默认关，排查唤醒词时临时开。
    "save_clips": False,
    # 「眼睛」工具：视觉模型描述画面时用的提示词。留空用默认。
    "vision_prompt": "",
    # 说话带动作：给她一份身体说明书（她自己在话里写〔点头〕这类），
    # 再在她整句一个都没写时由插件补一个一次性小动作。关掉就退回纯字幕 + 纯嘴型。
    "speak_actions": True,
}

# 〔〕里的动作词：既不上屏也不朗读，只触发动作。
_ACTION_RE = re.compile(r"〔[^〕]*〕")
_URL_RE = re.compile(r"https?://\S+")
_MD_RE = re.compile(r"[*_`#>|~]+")
_EMOJI_RE = re.compile(r"[\U0001F300-\U0001FAFF\u2600-\u27BF\uFE0F]")
_HSPACE_RE = re.compile(r"[ \t\u3000]+")
_SAFE_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")

# 唤醒时补给她的一句上下文。契约 §13 第 4 条。
WAKE_NOTE = "（有人在很近的地方叫了你一声，你刚应过。直接用一句话答，别长篇大论。）"

# ---------------------------------------------------------------- 说话时的动作

# 「你的身体」说明书。这是说话带动作的总开关式的文本：
# 2026-11-24 之前这里只有一句「想让她做动作就写〔动作词〕」，但**没给她词表**，
# 于是大模型既不知道能写什么、也不知道写了会不会被念出来，实测几乎从不写。
# 桌宠原作者的两条经验搬过来了：① 必须给词表，② 必须说清「保持类要自己收」，
# 否则她会写一个〔歪头〕然后整段话都歪着头（设备端 set 是按住语义，只有 clear 才松）。
SPEAK_BODY_NOTE = (
    "【你的身体】你回的话会被念出来，也会被演出来——你说话时尾巴会晃、头会点、眉毛会动，"
    "这些都要写成〔词〕才看得见，括号里的字不会被念。\n"
    "短动作（做完自动回原样，随便用）：点头 摇头 眨单眼 瞟一眼 挑一下眉 歪一下头 晃头 缩一下 叹气\n"
    "会一直保持（要用右边的词收回）：歪头→正视、前倾→站直、皱眉→挑一下眉、眯眼笑→默认眼、竖手指→放下手\n"
    "表情开关（开着不关）：脸红 冷汗 眼泪 屑屑眼 ＞＜，用完写〔-脸红〕关掉\n"
    "整套情绪：高兴 无语 得瑟 抓狂 犯困 感动 撒娇 鬼点子 警觉\n"
    "尾巴和鳍可以写（〔尾巴摇〕会变成晃头），腿和翅膀不行。只能写上面这些词，别的会被丢掉。\n"
    "两句里至少一句带动作，第一句最好就有；想强调就在前面加个「大」（大点头 大摇头）。\n"
    "例：「〔挑一下眉〕真的假的？那我可要记下来了〔歪一下头〕。」「好呀〔点头〕，我等你。」"
)

# 兜底点缀用的词，全部是「做完自动回原样」的一次性轨道（设备端走 anim，播完丢弃）。
# 刻意不碰「歪头 / 皱眉 / 眯眼笑」这类保持类：自动补上去没人收，三句话下来
# 就是一个一直歪着头、皱着眉、眯着眼的人——那不是表演，是坏了。
#
# 都用「大」修饰（WordTable 里 = 1.4 倍）：这台机器是小屏 + 480p，10 度的点头
# 在实体屏上几乎看不出来，兜底要的就是「一眼能看见」。
_SPARK_ASK = ("大歪一下头", "大挑一下眉", "大瞟一眼:左")
_SPARK_OH = ("大点头", "大挑一下眉", "大晃头")
_SPARK_PLAIN = ("大点头", "大歪一下头", "大挑一下眉", "大瞟一眼:右",
                "大瞟一眼:左", "叹气", "大缩一下")

# 句末标点（含换行）：切句用，标点跟着前一句走
_SENTENCE_END = re.compile(r"[。！？!?…\n]+")

# 设备端「身体」认识的词。**必须和 Android 的 cue/WordTable.java 保持一致**，
# 改了那边记得同步这里——不一致的后果是「她写了个动作，设备回一句身体做不出」。
# 这不是信任校验，是止损：模型天天现编（2026-11-24 实测第一句就写了〔尾巴一抬〕，
# 而她这条鱼没有尾巴参数），现编的词设备端会整段丢掉，
# 于是「这一句看起来有动作、实际一动不动」。
_BODY_WORDS = frozenset("""
歪头 转头 低头 抬头 正视 前倾 后仰 站直
看过来 看你 看左边 看右边 看上面 看下面
睁大 半睁 闭眼 眯眼笑 笑眼 斜眼 眨单眼 单眨眼 快眨单眼 单左眨眼 默认眼
挑眉 皱眉 八字眉
微笑 大笑 坏笑 瘪嘴 张嘴 嘟嘴 鼓腮 抿嘴 吐舌
脸红 黑脸 冷汗 眼泪 爱心眼 圈圈眼 加载 ＞＜ 屑屑眼
抚胸 竖手指 狗尾草 放下手
点头 摇头 眨眼 瞟一眼 挑一下眉 歪一下头 晃头 慢歪头 缩一下 叹气 吓一跳 恢复
左右张望
高兴 偷笑 傻眼 无语 得瑟 抓狂 犯困 感动 害怕 鬼点子 撒娇 得救 警觉
""".split())

_TOKEN_SPLIT = re.compile(r"[ 　、，,]+")
_MODIFIER = "微大快慢"


# 声母听错的分组。语音识别把这几组互换是常态，不必动整个匹配逻辑。
# 「大肥鱼 → 大培鱼」就是 f/p 互换（同组）；「大肥鱼 → 大灰鱼」是 f/h。
_INITIAL_GROUPS = (
    "bpmf",     # 唇音
    "dtnl",     # 舌尖
    "gkh",      # 舌根
    "jqx",      # 舌面
    "zcs",      # 平舌
    "zhchsh",   # 卷舌
)
_INITIALS = (
    "zh", "ch", "sh",
    "b", "p", "m", "f", "d", "t", "n", "l",
    "g", "k", "h", "j", "q", "x", "r", "z", "c", "s",
    "y", "w",
)


def _split_syllable(s: str) -> tuple[str, str]:
    """把音节拆成「声母 + 韵母」。拆不出来就返回空声母。"""
    for ini in _INITIALS:
        if s.startswith(ini) and len(s) > len(ini):
            return ini, s[len(ini):]
    return "", s


def _same_initial_group(a: str, b: str) -> bool:
    for grp in _INITIAL_GROUPS:
        if a in grp and b in grp:
            return True
    # 卷舌和平舌互认：zh/z、ch/c、sh/s 只差一个 h
    return a.replace("h", "") == b.replace("h", "") and a and b


def _near_syllable(a: str, b: str) -> bool:
    """两个音节算不算「很可能被听成彼此」。

    <p>只放行两种最有把握的错法，其余一律不认——唤醒词就三四个字，
    放宽一点就会把电视里的话当成叫她。
    """
    if a == b:
        return True
    ia, fa = _split_syllable(a)
    ib, fb = _split_syllable(b)
    if not ia or not ib:
        return False
    # 1）声母听错、韵母没变（fei/pei、fei/hei）
    if fa == fb and _same_initial_group(ia, ib):
        return True
    # 2）前后鼻音听错、声母韵腹都对（jin/jing、fen/feng）
    if ia == ib and fa.replace("ng", "n") == fb.replace("ng", "n"):
        return True
    return False


def _syllables(text: str) -> list[tuple[str, int]]:
    """逐字转拼音音节，返回 [(音节, 原文字符下标)]。

    <p>带上字符下标是为了命中之后还能把唤醒词从原句里剪掉——
    「大培鱼，现在几点」要剩下「现在几点」。标点数字转不出音节，直接跳过。
    """
    out: list[tuple[str, int]] = []
    for i, ch in enumerate(text):
        if lazy_pinyin is None:
            out.append((ch.lower(), i))
            continue
        try:
            parts = lazy_pinyin(ch)
        except Exception:  # noqa: BLE001
            parts = [ch]
        for p in parts:
            if p and p.isascii() and p.isalpha():
                out.append((p.lower(), i))
    return out


def _window_matches(win: list[str], target: list[str]) -> bool:
    """一窗口音节和目标唤醒词音节是否算同一个词。

    <p>容错语义（2026-11-22 重写，之前有个自相矛盾的 bug）：
    <ul>
      <li>音节完全相同 → 不算错；</li>
      <li>近音（声母同组且韵母同 / 前后鼻音混淆）→ 不算错，识别器就是这么听的；</li>
      <li>声母同组但韵母差远了（「肥 fei」→「盘 pan」，p/f 都唇音、韵母 ei/an）→
          仍算「错一个字」记进 bad，靠 3 字词的 1 字余量兜住；</li>
      <li>声母都跨组了（「肥 fei」→「灰 hui」，f 唇音 vs h 舌根）→ 直接否，不占用余量，
          这是挡电视误听的最后一道坎。</li>
    </ul>
    之前的写法是「不近音就 return False」，bad 的余量形同虚设——「大肥鱼」被识别成
    「大盘鱼」时，pan 与 fei 既不近音也不相同，直接 return False，用户喊破了喉咙也不应。
    """
    if len(win) != len(target):
        return False
    bad = 0
    for a, b in zip(win, target):
        if a == b:
            continue
        if _near_syllable(a, b):
            continue
        # 既不相等也不近音：只有声母还在同一组（唇音/舌尖/舌根…）才算「错一个字」，
        # 声母跨组说明根本不是那个字，直接否定，别拿余量去兜电视里的话。
        ia, _fa = _split_syllable(a)
        ib, _fb = _split_syllable(b)
        if not (ia and ib and _same_initial_group(ia, ib)):
            return False
        bad += 1
    # 两个字的词错一个字就等于错一半，不容错（不然「小云」会被当成「小鱼」）；
    # 三个字以上才给一个字的余量。
    return bad <= (1 if len(target) >= 3 else 0)


def find_wake(text: str, words: list[str]) -> tuple[str, int, int] | None:
    """在识别文本里找唤醒词，容忍同音字和近音字，返回 (原文里的词, 起, 止)。

    <p>为什么必须按拼音：SiliconFlow 的 SenseVoice 把「大肥鱼」稳定识别成
    「大菲鱼」（2026-11-22 实测，同一段录音换了识别器才写成「大肥鱼」）。
    按字面匹配的话，她永远叫不醒——而且失败得很安静，看日志只会以为「没说话」。

    <p>为什么还要再放宽一层：同一天晚上同一个词又被识别成「大**培**鱼」，
    拼音是 dapeiyu，而「大肥鱼」是 dafeiyu，**整串比对一样匹配不上**。
    所以再退一步做音节比对：允许一个字声母听错（f/p、f/h 这类），
    以及前后鼻音混淆。再宽就不行了——唤醒词只有三四个字，宽一点电视里的
    普通话会被当成在叫她。
    """
    if not text:
        return None
    # 长的先试：「达肥鱼」既算「大肥鱼」的近音，也包含字面的「肥鱼」。
    # 先认长的，剪掉的就是整个词，不会只剩一个「达」被当成正文。
    ordered = sorted([w for w in words if w], key=len, reverse=True)
    heard = _syllables(text)

    for w in ordered:
        # 第一层：原字直接命中，最快也不会乱扩
        at = text.find(w)
        if at >= 0:
            return w, at, at + len(w)

        # 第二层：按音节窗口比，容忍近音（同音字「大菲鱼」也走这一层）
        want = [p for p, _ in _syllables(w)]
        if not want or len(want) > len(heard):
            continue
        n = len(want)
        for i in range(len(heard) - n + 1):
            if _window_matches([p for p, _ in heard[i:i + n]], want):
                start = heard[i][1]
                end = heard[i + n - 1][1] + 1
                return text[start:end], start, end
    return None


def has_content(text: str, min_chars: int = 1) -> bool:
    """这句话里有没有「真正的内容」，而不只是标点/空白/SenseVoice 标签。

    <p>为什么必须有这一道：真机日志里大量出现「听到一段 620ms 有声 → 识别成『。』」。
    这些段多半是环境噪声、她自己的尾音、或者人轻咳一声。以前只要接话窗口开着，
    {@code _handle_heard} 就会把「。」当成用户说的话送进大模型，于是她回一句
    「怎么又不吭声了……」——看起来像在自问自答，实际是把标点当成了用户输入。

    <p>{@code str.isalnum()} 对中文汉字返回 True，所以这里顺带把汉字算作内容；
    只数「字母/数字/汉字」，标点、空格、换行一律不算。
    """
    return sum(1 for c in str(text or "") if c.isalnum()) >= max(1, min_chars)


def speech_text(text: str) -> str:
    """把一段带动作词的正文压成「该念出来的部分」。

    这三步的顺序是有讲究的：
      1. 先剥 ``〔〕``——里面的字是动作指令，念出来就是「皱眉 黑脸 正视」这种怪话。
         契约 §14.6 记着实测：不剥会把 4.92 秒的句子合成出 13.24 秒。
      2. 再去 URL / markdown / emoji——它们念不出来，只会变成一串噪音。
      3. 最后才规整标点空格。反过来做的话，剥 ``〔〕`` 留下的空格会被并进上一句。
    """
    s = _ACTION_RE.sub("", str(text or ""))
    s = s.replace("／", "\n").replace("/", "，")
    s = _URL_RE.sub("", s)
    s = _MD_RE.sub("", s)
    s = _EMOJI_RE.sub("", s)
    s = _HSPACE_RE.sub("", s)          # 中文里那些空格只会在 TTS 里变成尴尬的停顿
    s = re.sub(r"\n{2,}", "\n", s)
    s = re.sub(r"[，,]{2,}", "，", s)
    s = re.sub(r"^[，。！？、\s]+", "", s)
    s = re.sub(r"[，。！？、\s]+$", "", s)
    return s


def _shorten(line: str, limit: int) -> str:
    """超长就断在标点上，不要断在字中间。"""
    if limit <= 0 or len(line) <= limit:
        return line
    head = line[:limit]
    cut = -1
    for sep in ("。", "！", "？", "；", "…", "，", "、", ",", ".", "!", "?"):
        idx = head.rfind(sep)
        if idx >= limit * 0.45:
            cut = max(cut, idx + 1)
    return (head[:cut] if cut > 0 else head).strip()


def _known_token(token: str) -> bool:
    """这个动作词身体做不做得到。认「-脸红」「微点头」「快眨眼×2」「瞟一眼:右」这些写法。"""
    name = token.lstrip("-－")
    name = name.split("：")[0].split(":")[0]
    name = re.sub(r"[×xX*]\d+$", "", name)
    if name in _BODY_WORDS:
        return True
    while len(name) > 1 and name[0] in _MODIFIER:
        name = name[1:]
        if name in _BODY_WORDS:
            return True
    return False


def _tail_to_body(token: str) -> str | None:
    """把「尾巴 / 鱼鳍」翻译成她真做得出来的动作，不是认识的词就返回 None。

    <p>为什么不是直接禁掉：人设里写着「身后拖着一条宽大柔软的鲸鱼尾巴，<b>尾巴是情绪的直接表现</b>」，
    模型天天写尾巴（2026-11-24 第一句就是〔尾巴一抬〕）。但这套 rig 没有尾巴参数，
    硬禁的结果是它干脆一个动作都不写——等于把整句话的表演都丢了。
    翻译成等价的身体语言，既顺人设，画面上也真的在动。
    """
    if "尾" not in token and "鳍" not in token:
        return None
    if any(k in token for k in ("搭", "靠", "贴", "蹭", "卷", "绕", "缠", "抱")):
        return "大歪一下头"     # 尾巴搭过来 / 缠上来 → 撒娇似的歪头
    if any(k in token for k in ("耷", "垂", "扁", "软")):
        return "低头"           # 尾巴耷拉 → 蔫了
    if any(k in token for k in ("绷", "紧", "僵", "竖", "炸")):
        return "站直"           # 尾巴绷紧 → 紧张戒备
    if any(k in token for k in ("摇", "摆", "甩", "翘", "抬", "晃", "动")):
        return "晃头"           # 尾巴摇 → 高兴得晃
    return "挑一下眉"           # 只写「尾巴」两个字：有个反应就够了


def filter_cues(text: str) -> tuple[str, list[str]]:
    """整理 〔〕：尾巴翻成身体动作，做不出来的词去掉。

    @return (新文本, 改动记录) 改动记录是给人看的，例如「尾巴一抬→挑一下眉」「翅膀扇（做不出）」
    """
    notes: list[str] = []

    def fix(m: re.Match) -> str:
        keep = []
        for token in _TOKEN_SPLIT.split(m.group()[1:-1]):
            if not token:
                continue
            tail = _tail_to_body(token)
            if tail:
                keep.append(tail)
                notes.append("%s→%s" % (token, tail))
            elif _known_token(token):
                keep.append(token)
            else:
                notes.append("%s（做不出）" % token)
        return "〔%s〕" % " ".join(keep) if keep else ""

    return _ACTION_RE.sub(fix, text), notes


def _sentences(text: str) -> list[str]:
    """按句末标点切句，标点留在前一句末尾。换行也算断句。"""
    out: list[str] = []
    start = 0
    for m in _SENTENCE_END.finditer(text):
        out.append(text[start:m.end()])
        start = m.end()
    if start < len(text):
        out.append(text[start:])
    return [s for s in out if s.strip()]


def _put(sentence: str, word: str) -> str:
    """把 ``〔词〕`` 放到句内第一个停顿后面；一句到底没停顿就放句首。

    为什么落在停顿后面：落在句首是「先摆姿势再开口」，看起来像提线木偶；
    落在逗号后面才是「说着说着动了一下」，也就是原作者的桌宠一直在追求的那个效果。
    """
    tag = "〔%s〕" % word
    pause = re.search(r"[，,、；;]", sentence)
    if pause and pause.end() < len(sentence) - 1:
        return sentence[:pause.end()] + tag + sentence[pause.end():]
    return tag + sentence


def decorate(text: str, most: int = 2, pick: int = 0) -> tuple[str, int]:
    """整段一个 ``〔〕`` 都没有时，按句子的情绪补一两个一次性小动作。

    <p>为什么需要这一层：让大模型写动作是概率事件——给了词表它多半会写，但总有
    整段一个都不写的时候（尤其短回复）。那种时候她在屏幕上一动不动，只有嘴在动，
    就退回了「语音播报器」。这一层保证<b>只要有一段话，就一定有一点身体动静</b>。

    <p>为什么只在「整段零动作」时补，而不逐句补：她已经在自己演的时候再插一手，
    等于把她的节奏打乱成固定节拍，反而假。有动作就完全交给模型。

    @param most 最多补几个
    @param pick 选词轮转的位置，跨句累计，避免连着两句同一个词
    @return (新文本, 下一次的 pick)
    """
    if most <= 0 or not text or _ACTION_RE.search(text):
        return text, pick

    lines = _sentences(text)
    usable = [i for i, s in enumerate(lines) if has_content(s, 4)]
    if not usable:
        return text, pick

    # 只补靠后的句子：第一句先留白，动作才像「说着说着动起来」，而不是「一开口就抽一下」
    targets = (usable[len(usable) // 3:] or usable)[-most:]

    picked: dict[int, str] = {}
    for index in targets:
        sentence = lines[index]
        # 前一句是问句也算：动作是插在「那你怎么不早说」前面的，它更像是在回应
        # 上一句的疑问，而不是在回答自己这一句——用陈述池（点头）会显得敷衍。
        before = lines[index - 1] if index > 0 else ""
        ask = "？" in sentence or "?" in sentence or "？" in before or "?" in before
        oh = "！" in sentence or "!" in sentence
        pool = _SPARK_ASK if ask else _SPARK_OH if oh else _SPARK_PLAIN
        picked[index] = pool[pick % len(pool)]
        pick += 1

    return "".join(_put(s, picked[i]) if i in picked else s
                   for i, s in enumerate(lines)), pick


def clean_wav(data: bytes) -> bytes:
    """把「流式 WAV」重建成标准 WAV。

    TTS 是边合成边推的，所以头部两个长度字段写的是哨兵值：

        RIFF size = 0xFFFFFFA6 (4294967206)   ← 负数补码，意思是「还不知道」
        data size = 0xFFFFFF00 (4294967040)

    ffmpeg／桌面播放器大多能忍——它们以「读到文件尾」为准。Android 不行：
    MediaPlayer 直接 ``Prepare failed: status=0x1``，半点声音都没有；
    设备端算口型包络走的 AudioInputStream 也一样，日志里是「解不出包络」。

    真机上就是这么挂的：音频下载成功了（「配音就绪」），然后**播不响**。

    所以这里不光改长度，还顺手丢掉 ``LIST`` 之类的元数据块（里面是
    ``{"Label": ...}`` 这种 AI 标签，播放器不需要，老设备的解析器还容易出错），
    只留 ``fmt `` + ``data``，拼一个最小合法 WAV 出来。
    """
    if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        return data

    pos = 12
    fmt: bytes | None = None
    pcm: bytes | None = None
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = struct.unpack_from("<I", data, pos + 4)[0]
        # 哨兵长度（0xFFFFFFxx）或超出实际文件，一律按「到尾巴为止」算
        avail = len(data) - pos - 8
        if size == 0 or size > avail:
            size = avail
        body = data[pos + 8:pos + 8 + size]
        if cid == b"fmt " and fmt is None:
            fmt = body
        elif cid == b"data" and pcm is None:
            pcm = body
            break
        pos += 8 + size + (size & 1)

    if fmt is None or pcm is None:
        return data      # 认不出来就原样给出去，至少不比现在更糟

    head = b"RIFF" + struct.pack("<I", 4 + 8 + len(fmt) + 8 + len(pcm)) + b"WAVE"
    head += b"fmt " + struct.pack("<I", len(fmt)) + fmt
    head += b"data" + struct.pack("<I", len(pcm))
    return head + pcm


def _load_local_asr(config: dict) -> Any | None:
    """加载本地 SenseVoice 识别器（sherpa-onnx），失败返回 None（回退云端）。

    <p>模型文件放在数据卷 ``models/sense-voice/`` 下，加载一次常驻内存。
    返回的是一个同步可调用对象：传入 WAV 字节，返回识别文本。因为 sherpa-onnx
    是阻塞的，调用方用 ``asyncio.to_thread`` 包一层，别卡事件循环。

    <p>装不上（sherpa_onnx 没装 / 模型缺文件 / 加载失败）都静默返回 None，
    由 _transcribe 走云端兜底，绝不因为本地识别失败而让唤醒功能整个坏掉。
    """
    if sherpa_onnx is None:
        return None
    model_dir = str(config.get("local_asr_dir") or "").strip()
    if not model_dir:
        model_dir = str(
            Path(get_astrbot_data_path()) / "models" / "sense-voice"
        )
    model_path = Path(model_dir) / "model.int8.onnx"
    tokens_path = Path(model_dir) / "tokens.txt"
    if not model_path.exists() or not tokens_path.exists():
        logger.info("[音箱] 本地识别模型没找到（%s），先走云端", model_dir)
        return None
    try:
        rec = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=str(model_path),
            tokens=str(tokens_path),
            num_threads=2,
            use_itn=True,
            language="zh",
            debug=False,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("[音箱] 本地识别模型加载失败，回退云端：%s", exc)
        return None

    def transcribe(wav_bytes: bytes) -> str:
        with wave.open(io.BytesIO(wav_bytes)) as w:
            n = w.getnframes()
            rate = w.getframerate()
            pcm = array("h")
            pcm.frombytes(w.readframes(n))
        samples = [x / 32768.0 for x in pcm]
        # 前后各补 300ms 静音。实测（2026-11-23，真机同一段录音）：
        #   「打翻鱼」→ 补静音后「大肥鱼」；「大飞云」→「大肥鱼」。
        # SenseVoice 对「贴着边」的短音频会啃掉字头/字尾，补静音等于给它一点上下文。
        # 识别耗时只多几十毫秒，换唤醒词命中率很划算。
        pad = [0.0] * int(rate * 0.3)
        samples = pad + samples + pad
        s = rec.create_stream()
        s.accept_waveform(rate, samples)
        rec.decode_stream(s)
        return s.result.text

    logger.info("[音箱] 本地 SenseVoice 就绪（%d 线程），唤醒识别改走本地", 2)
    return transcribe


class AmberSpeakerEvent(AstrMessageEvent):
    """一条来自音箱的消息。回复时推回设备。"""

    def __init__(self, message_str, message_obj, platform_meta, session_id, adapter):
        super().__init__(message_str, message_obj, platform_meta, session_id)
        self._adapter = adapter

    async def send(self, message: MessageChain | None) -> None:
        if message is None or not message.chain:
            return
        texts = [str(comp.text) for comp in message.chain if isinstance(comp, Plain)]
        body = "".join(texts).strip()
        if body:
            # queue_reply 是同步的：它只把文本塞进队列、定一个兜底定时器。
            # 这里曾经写成 `await queue_reply(...)`，于是每段都抛 TypeError，
            # 全靠 8 秒兜底定时器把话送出去——功能「看起来正常」，但每条回复都
            # 慢 8 秒，而且异常把正常收尾路径整个跳过了。
            self._adapter.queue_reply(body)
        else:
            logger.debug(
                "[音箱] 这一轮没有文字（%s），设备收不到",
                [str(getattr(c, "type", c)) for c in message.chain],
            )
        # 基类的 send 会走平台自己的发送逻辑，这里已经自己处理过了，调一次保证
        # 别的钩子（比如分段回复计数）还能正常跑。
        try:
            await super().send(message)
        except Exception as exc:  # noqa: BLE001
            logger.debug("[音箱] super().send 出错（不影响推送）：%s", exc)


@register_platform_adapter(
    PLATFORM_TYPE,
    "琥珀音箱（大肥鱼在这台机器里说话 / 做动作）",
    default_config_tmpl=PLATFORM_TMPL,
    adapter_display_name="琥珀音箱",
    support_streaming_message=False,
)
class AmberSpeakerPlatform(Platform):
    def __init__(self, platform_config: dict, platform_settings: dict, event_queue):
        super().__init__(platform_config, event_queue)
        self.settings = platform_settings or {}
        self.host = str(self.config.get("host") or "0.0.0.0")
        self.port = int(self.config.get("port") or 8789)
        self.device_url = str(self.config.get("device_url") or "").rstrip("/")
        self.owner_id = str(self.config.get("owner_id") or "owner")
        self.tts_provider = str(self.config.get("tts_provider") or "sf_dafeiyu")
        self.voice_reply = bool(self.config.get("voice_reply", True))
        self.disable_global_tts = bool(self.config.get("disable_global_tts", True))
        try:
            self.tts_timeout = float(self.config.get("tts_timeout") or 8.0)
        except (TypeError, ValueError):
            self.tts_timeout = 8.0
        try:
            self.max_chars = int(self.config.get("max_chars") or 300)
        except (TypeError, ValueError):
            self.max_chars = 300
        try:
            self.voice_max_chars = int(self.config.get("voice_max_chars") or 120)
        except (TypeError, ValueError):
            self.voice_max_chars = 120
        try:
            self.audio_keep = int(self.config.get("audio_keep") or 80)
        except (TypeError, ValueError):
            self.audio_keep = 80
        # 说话带动作：她没写〔〕的时候由插件补一两个一次性小动作。
        # 关掉就是纯字幕 + 纯嘴型（老行为），排查「动作是不是插件乱插的」时用。
        self.speak_actions = bool(self.config.get("speak_actions", True))
        # 自动补动作的选词游标：跨段累计，避免连着两句都是「点头」。
        self._spark_pick = 0

        self.voice_loop = bool(self.config.get("voice_loop", True))
        self.wake_words = [
            w.strip()
            for w in str(self.config.get("wake_words") or "大肥鱼,小鱼,肥鱼,大肥,大黑鱼").split(",")
            if w.strip()
        ]
        try:
            self.listen_window = int(self.config.get("listen_window") or 90)
        except (TypeError, ValueError):
            self.listen_window = 90
        # 唤醒后的接话窗口（秒）：这段时间里不用再喊「大肥鱼」，直接说话也算对她说的。
        # 保持 20 秒：放大到 35 秒会让窗口每轮续期，电视连续说话也可能被当成接话。
        try:
            self.wake_window = int(self.config.get("wake_window") or 20)
        except (TypeError, ValueError):
            self.wake_window = 20
        try:
            self.wake_followups = int(self.config.get("wake_followups") or 4)
        except (TypeError, ValueError):
            self.wake_followups = 4

        self.data_dir = Path(get_astrbot_data_path()) / "amber_speaker"
        self.audio_dir = self.data_dir / "audio"
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        self.save_clips = bool(self.config.get("save_clips", False))
        self.clip_dir = self.data_dir / "clips"

        self.audio_base = str(self.config.get("audio_base") or "").rstrip("/")
        if not self.audio_base:
            self.audio_base = self._guess_base()

        self._stop = asyncio.Event()
        self._runner: web.AppRunner | None = None
        self._http: aiohttp.ClientSession | None = None
        self._seq = 0
        self._tts_off: set[str] = set()
        self._tts_fail_at = 0.0

        # 回复先攒着：AstrBot 可能把一句话拆成几个 send 分段发，
        # 每段都丢一次 /say 就会变成好几个气泡、好几段语音。
        self._pending: list[str] = []
        self._pending_timer: asyncio.TimerHandle | None = None
        self._last_reply = ""
        self._last_reply_at = 0.0
        # 「这轮回复真的送出去了」的序号。连续对话必须等它推进，否则会在
        # 大模型还在想 / TTS 还没合成 / 第一段刚播完的时候就去开麦克风——
        # 真机日志里「她说完 1.4 秒后麦克风录到『查了一下』」就是这么来的。
        self._reply_seq = 0
        self._reply_at = 0.0

        # 唤醒标记：下一次 /utter 进来时补一句「有人在叫你」。
        self._awaiting_wake = False
        # 这次唤醒后已经接了几轮「不喊名字」的话。到上限就必须重新喊名字，
        # 防止电视连续说话被误接后无限续期。
        self._followups = 0
        # 刚才那声「小鱼在的」是什么时候应的。用来开一段「不用再喊名字」的接话窗口。
        self._wake_at = 0.0
        self._turns = 0
        self._last_error = ""

        # 语音闭环的任务句柄。它一直在跑：听 → 识别 → 叫醒大模型 → 等她说完。
        self._voice_task: asyncio.Task | None = None
        # 定期对时任务。设备没有 RTC 电池，开机时间停在 1977 年；它自己会读 HTTP 的
        # Date 头兜底，但 NAS 就在局域网里、时间有 NTP 保着，由我们主动推更准也更省事。
        self._clock_task: asyncio.Task | None = None
        # 正在处理一轮（识别/生成/播放），这期间不再开新的监听，
        # 否则会把她自己的喇叭声当成「有人在说话」录进来。
        self._voice_busy = False

        # 关机态（米家 / 手机一键）。关了就灭屏 + 不调 /listen、丢弃 /utter，
        # 别人再怎么喊也不应。开机恢复。
        self._powered_off = False

        # 本地 SenseVoice 识别器（sherpa-onnx）。云端识别 1.6~9.7 秒、还抖得厉害，
        # 本地同一个模型只要 0.3~0.5 秒、稳定（2026-11-22 实测，NAS Intel N150）。
        # 加载一次常驻内存，别每次现建。失败就置 None，_transcribe 会回退云端。
        self._local_asr = _load_local_asr(self.config)

        self.metadata = PlatformMetadata(
            name=PLATFORM_TYPE,
            description="琥珀音箱",
            id=str(self.config.get("id") or PLATFORM_TYPE),
            support_streaming_message=False,
            support_proactive_message=True,
        )
        global _PLATFORM_INST
        _PLATFORM_INST = self
        logger.info(
            "[音箱] 设备 = %s，音频根地址 = %s，本插件监听 %s:%d",
            self.device_url or "（没填，发不出去）",
            self.audio_base or "（推断失败）",
            self.host,
            self.port,
        )

    # ------------------------------------------------------------ 平台接口

    def meta(self) -> PlatformMetadata:
        return self.metadata

    def run(self):
        return self._serve()

    async def terminate(self) -> None:
        self._stop.set()
        if self._voice_task is not None:
            self._voice_task.cancel()
            self._voice_task = None
        if self._clock_task is not None:
            self._clock_task.cancel()
            self._clock_task = None
        if self._pending_timer is not None:
            self._pending_timer.cancel()
            self._pending_timer = None
        if self._runner:
            await self._runner.cleanup()
        if self._http and not self._http.closed:
            await self._http.close()
        logger.info("[音箱] 平台已停止")

    def create_event(self, message: AstrBotMessage) -> AmberSpeakerEvent:
        event = AmberSpeakerEvent(
            message_str=message.message_str,
            message_obj=message,
            platform_meta=self.meta(),
            session_id=message.session_id,
            adapter=self,
        )
        # 音箱这边没有「@机器人」这个概念，喊一声就是唤醒。
        # 不置这两个标记会被 friend_message_needs_wake_prefix 拦掉，她就当没听见。
        event.is_at_or_wake_command = True
        event.is_wake = True
        return event

    async def handle_msg(self, message: AstrBotMessage) -> None:
        event = self.create_event(message)
        umo = event.unified_msg_origin

        # 全局 TTS 必须关掉：否则 AstrBot 会按 0.3 概率把回复整条换成 Record，
        # send() 里就收不到 Plain 文字了——人也没字、音频也送不出去。
        # 语音由我们自己合成（100%），见 _synthesize()。
        if self.disable_global_tts and umo not in self._tts_off:
            try:
                await SessionServiceManager.set_tts_status_for_session(umo, False)
                self._tts_off.add(umo)
                logger.info("[音箱] 已关掉 %s 的全局 TTS（改由本插件强制合成）", umo)
            except Exception as exc:  # noqa: BLE001
                logger.debug("[音箱] 关全局 TTS 失败：%s", exc)

        self._turns += 1
        self.commit_event(event)

    # ------------------------------------------------------------ 收回复

    def queue_reply(self, text: str) -> None:
        """攒一句，等这一轮发完再一次送过去。

        为什么要攒：分段回复会把「一定累坏了吧」和「先去洗个热水澡」拆成两次 send。
        每段各调一次 /say，设备就会排两句话、念两段语音，中间多出没必要的停顿。
        攒到 after_message_sent 一起发，才是一整句。
        """
        line = str(text or "").strip()
        if not line:
            return
        if self._pending and self._pending[-1] == line:
            return      # send 和 send_by_session 可能各来一次
        self._pending.append(line)
        if self._pending_timer is not None:
            self._pending_timer.cancel()
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        # 兜底：万一没等到 after_message_sent（主动推送那条路），自己发。
        #
        # 2026-11-22 实测：在自定义 platform 上 `after_message_sent` 钩子**不会触发**
        # （AstrBot 只对内置适配器的发送收尾派发它），所以这条兜底一直是「主路径」——
        # 每轮回复都固定等满 8 秒才出声，端到端 15 秒里有 8 秒是它。
        # 现在缩到 0.6 秒：足够把同一轮被拆成两三条的 send 聚成一句，又不会让人干等。
        self._pending_timer = loop.call_later(0.6, self._flush_soon)

    def _flush_soon(self) -> None:
        try:
            asyncio.get_running_loop().create_task(self.flush_reply())
        except RuntimeError:
            pass

    async def flush_reply(self) -> None:
        if self._pending_timer is not None:
            self._pending_timer.cancel()
            self._pending_timer = None
        parts = [p for p in self._pending if p.strip()]
        self._pending.clear()
        if not parts:
            return
        text = "\n".join(parts)
        self._last_reply = text[:200]
        self._last_reply_at = time.time()
        await self.say_to_device(text)
        # 序号在**真正下发之后**才推进：`say_to_device` 里要先 TTS 合成（2~8 秒），
        # 合成完才算「回复到了设备」。提前推进的话，等待方会在合成期间看到「有回复了」，
        # 然后等 1.5 秒发现设备空闲就误判成「播完了」。
        self._reply_seq += 1
        self._reply_at = self._last_reply_at

    # ------------------------------------------------------------ 发到设备

    def _next_id(self) -> str:
        self._seq += 1
        return "s-%d-%03d" % (int(time.time() * 1000), self._seq % 1000)

    def _split(self, text: str) -> list[str]:
        """按设备契约拆成多条 /say：一行一个气泡，单条不超过 max_chars。"""
        out: list[str] = []
        for part in str(text or "").split("\n"):
            part = part.strip()
            if not part:
                continue
            while len(part) > self.max_chars:
                head = part[: self.max_chars]
                cut = -1
                for sep in ("。", "！", "？", "；", "…"):
                    idx = head.rfind(sep)
                    if idx >= self.max_chars * 0.4:
                        cut = max(cut, idx + 1)
                if cut <= 0:
                    cut = self.max_chars
                out.append(part[:cut].strip())
                part = part[cut:].strip()
            if part:
                out.append(part)
        return out

    async def say_to_device(self, text: str, interrupt: bool = False) -> bool:
        """把一段话送到设备：先合成语音，再下发（text 保留〔〕给设备做动作）。

        分段回复常常把一句话拆成两三段（「唔……」「快过来，歇会儿嘛。」「今天都忙什么了？」
        各走一次）。

        2026-11-22 改成**首段先行**：第一段合成完立刻发出去，她就能尽早开口；
        剩下的段并发合成、按序补发。设备是排队播放，后面的段晚几秒到也不会断——
        每段音频的时长都远大于合成耗时。之前是「全部合成完再一起发」，
        等于让首句去等最慢的那一段，白等 1~2 秒。
        """
        if self.speak_actions:
            # 先整理动作词（尾巴翻成身体动作、做不出的去掉），再补兜底动作。
            # 顺序不能反：先补的话，补完照样可能被「其实一个有效动作都没有」漏掉。
            text, notes = filter_cues(text)
            if notes:
                logger.info("[音箱] 动作整理：%s", "、".join(notes))

        chunks = self._split(text)
        if not chunks:
            return False

        if self.speak_actions:
            # 这句一个〔〕都没有就补一个一次性小动作。逐段判断而不是整段一起判断：
            # _split 已经把长回复拆成一屏一句，整段判断的话「第一句有动作」会把
            # 后面几句全赦免掉，人就还是只看得到一句在动。
            decorated: list[str] = []
            for chunk in chunks:
                # 长句多补一个：24 字以上往往要念五六秒，只在中间动一下太寡。
                most = 2 if has_content(chunk, 24) else 1
                chunk, self._spark_pick = decorate(chunk, most, self._spark_pick)
                decorated.append(chunk)
            chunks = decorated
            logger.debug("[音箱] 下发（带动作）：%s", " / ".join(chunks))

        ids = [self._next_id() for _ in chunks]

        first_wav = await self._synthesize(speech_text(chunks[0]), ids[0])
        ok = await self._deliver(chunks[0], ids[0], first_wav, interrupt)
        if not ok or len(chunks) == 1:
            return ok

        rest, rest_ids = chunks[1:], ids[1:]
        wavs = await asyncio.gather(
            *[self._synthesize(speech_text(c), i) for c, i in zip(rest, rest_ids)],
            return_exceptions=True,
        )
        for i, (chunk, say_id) in enumerate(zip(rest, rest_ids)):
            wav = wavs[i]
            if isinstance(wav, BaseException):
                logger.warning("[音箱] 第 %d 段合成异常：%s", i + 2, wav)
                wav = ""
            # 只打断第一段：后一段是被切开的前半句的续，打断自己没意义
            if not await self._deliver(chunk, say_id, wav, False):
                return False
        return True

    async def _deliver(self, chunk: str, say_id: str, wav: str, interrupt: bool) -> bool:
        payload: dict[str, Any] = {
            "id": say_id,
            "text": chunk,
            "interrupt": bool(interrupt),
            "ttl": 30,
        }
        if wav:
            payload["audio_url"] = wav
        else:
            logger.info("[音箱] 这句只有字幕，没有声音：%s", chunk[:40])
        return (await self._post_device("/say", payload)) is not None

    async def _synthesize(self, line: str, name: str) -> str:
        """合成一段语音，返回设备能下载的 URL。失败返回空串（只出字幕，不影响整轮）。"""
        if not self.voice_reply:
            return ""
        line = _shorten(line, self.voice_max_chars)
        if not line:
            return ""
        # TTS 挂了别每句都去撞一遍，30 秒内先跳过——撞一次要等满超时，很拖
        now = time.time()
        if now - self._tts_fail_at < 30:
            return ""

        ctx = _PLUGIN_CONTEXT
        if ctx is None:
            logger.debug("[音箱] 还没拿到 Context，这句不合成")
            return ""

        provider = None
        try:
            provider = await ctx.provider_manager.get_provider_by_id(self.tts_provider)
        except Exception as exc:  # noqa: BLE001
            logger.debug("[音箱] 按 id 取 %s 失败：%s", self.tts_provider, exc)
        if provider is None:
            # 拿不到指定音色时退回全局那个，总比失声强——但会换人，所以记一条警告。
            try:
                provider = await ctx.provider_manager.get_using_provider_async(
                    ProviderType.TEXT_TO_SPEECH
                )
                if provider is not None:
                    logger.warning(
                        "[音箱] %s 不可用，退回全局 TTS（音色可能不是大肥鱼）",
                        self.tts_provider,
                    )
            except Exception as exc:  # noqa: BLE001
                logger.warning("[音箱] 取全局 TTS 也失败：%s", exc)
        if provider is None:
            self._tts_fail_at = time.time()
            return ""

        try:
            path = await asyncio.wait_for(provider.get_audio(line), timeout=self.tts_timeout)
        except asyncio.TimeoutError:
            self._tts_fail_at = time.time()
            self._last_error = "TTS 超时 %.0fs" % self.tts_timeout
            logger.warning("[音箱] TTS 超时（%.0fs），这句只出字幕", self.tts_timeout)
            return ""
        except Exception as exc:  # noqa: BLE001
            self._tts_fail_at = time.time()
            self._last_error = "TTS 失败：%s" % exc
            logger.warning("[音箱] TTS 失败：%s", exc)
            return ""

        try:
            data = await asyncio.to_thread(Path(path).read_bytes)
            try:
                Path(path).unlink()
            except OSError:
                pass
        except Exception as exc:  # noqa: BLE001
            logger.warning("[音箱] 读合成结果失败：%s", exc)
            return ""
        if not data:
            return ""
        # 流式 WAV 的头是坏的，Android 播不了（见 clean_wav 的注释）
        data = clean_wav(data)

        out = self.audio_dir / ("%s.wav" % name)
        try:
            out.write_bytes(data)
        except Exception as exc:  # noqa: BLE001
            logger.warning("[音箱] 写音频文件失败：%s", exc)
            return ""

        self._prune_audio()
        if not self.audio_base:
            self._last_error = "audio_base 推断失败，设备下载不到音频"
            logger.warning("[音箱] %s", self._last_error)
            return ""
        logger.info(
            "[音箱] 合成 %.1fKB / %d 字：%s",
            len(data) / 1024.0,
            len(line),
            line[:30],
        )
        return "%s/audio/%s.wav" % (self.audio_base, name)

    def _prune_audio(self) -> None:
        try:
            files = sorted(
                self.audio_dir.glob("*.wav"),
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )
            for p in files[self.audio_keep:]:
                try:
                    p.unlink()
                except OSError:
                    pass
        except Exception:  # noqa: BLE001
            pass

    def _guess_base(self) -> str:
        """推断「设备能访问到我」的地址。

        容器里 socket 拿到的会是容器内网 IP（172.x），设备根本连不上。
        所以先看配置，再看 device_url 同网段，最后才用 socket 兜一下。
        """
        try:
            host = urllib.parse.urlsplit(self.device_url).hostname
        except Exception:
            host = None
        if host:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.settimeout(0.4)
                s.connect((host, 80))
                ip = s.getsockname()[0]
                s.close()
                # 172.x / 10.x 多半是 docker 内网，设备够不着，宁可留空让日志喊出来
                if not (ip.startswith("172.") or ip.startswith("10.")):
                    return "http://%s:%d" % (ip, self.port)
            except Exception:
                pass
        return ""

    async def _post_device(self, path: str, payload: dict) -> dict | None:
        if not self.device_url:
            self._last_error = "没配 device_url"
            return None
        url = self.device_url + path
        try:
            if self._http is None or self._http.closed:
                self._http = aiohttp.ClientSession(
                    timeout=aiohttp.ClientTimeout(total=10)
                )
            async with self._http.post(url, json=payload) as resp:
                body = await resp.text()
                if resp.status >= 400:
                    self._last_error = "%s → HTTP %d" % (path, resp.status)
                    logger.warning(
                        "[音箱] 设备拒绝 %s（HTTP %d）：%s", path, resp.status, body[:120]
                    )
                    return None
                try:
                    return json.loads(body) if body else {}
                except Exception:  # noqa: BLE001
                    return {}
        except Exception as exc:  # noqa: BLE001
            self._last_error = "设备连不上：%s" % exc
            logger.warning("[音箱] 设备连不上（%s）：%s", url, exc)
            return None

    async def snapshot_and_describe(self, question: str = "") -> str:
        """调设备取一帧画面，交给视觉模型描述，返回「你看到了什么」的文本。

        <p>这是契约 §7.1「眼睛」工具的 AstrBot 侧实现：大模型在对话里想看看主人
        在做什么时，会调 {@code camera_snapshot} 工具，走到这里。设备端 {@code /snapshot}
        已经做好额度闸门（间隔 snap_cool / 每日 snap_day，都是设备端可调设置），
        这里只负责「取到 base64 → 喂视觉模型 → 拿描述」。

        <p>隐私铁律（§16.1）在这里的落点：只取一帧、不落盘（base64 直接转 data URL
        塞进请求）、画面只喂视觉模型、只返回描述文字不回原图。
        """
        # 设备端 /snapshot 是 POST 端点（取帧要起流 + takePicture，不是幂等 GET）。
        data = await self._post_device("/snapshot", {})
        if not data:
            return "【眼睛】设备连不上，看不到画面。"
        if not data.get("ok"):
            err = data.get("error") or "未知"
            if err == "cooldown":
                # 间隔是设备端可调设置（snap_cool），别再写死「10 分钟」——
                # 回执里带 retry_after_sec，按真实剩余秒数说，免得她说错话。
                wait = data.get("retry_after_sec")
                if isinstance(wait, (int, float)) and wait > 0:
                    return "【眼睛】冷却中，还要再等 %d 秒才能看下一眼。" % int(wait)
                return "【眼睛】冷却中，还不能再看。"
            if err in ("daily_limit", "hour_limit"):
                return "【眼睛】今天/这小时的取帧额度用完了，看不到画面。"
            if err == "clock_untrusted":
                return "【眼睛】墙钟不可信（设备没对时），暂时不让取帧。"
            return "【眼睛】取帧失败：%s" % err

        b64 = str(data.get("base64") or "").strip()
        if not b64:
            return "【眼睛】设备回了个空画面。"
        data_url = "data:image/jpeg;base64," + b64
        desc = await self._describe_image(data_url, question)
        return desc if desc else "【眼睛】取到了画面，但视觉模型没给出描述。"

    async def _describe_image(self, data_url: str, question: str = "") -> str:
        """把一张 data URL 图片交给当前对话模型分析，返回文字描述。

        <p>{@code question} 是主人这一轮的原话，用来把描述对准他问的东西；
        单独拆成一个带默认值的参数，是因为这里不是 {@code snapshot_and_describe}
        的作用域——上一次把拼 prompt 的代码放进来却忘了传参，工具直接抛
        {@code NameError: name 'question' is not defined}，表象是「摄像头又出错了」。
        """
        ctx = _PLUGIN_CONTEXT
        if ctx is None:
            return ""
        try:
            provider = await ctx.provider_manager.get_using_provider_async(
                ProviderType.CHAT_COMPLETION
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("[音箱] 取视觉模型失败：%s", exc)
            return ""
        if provider is None:
            logger.warning("[音箱] 没有可用的对话模型做视觉分析")
            return ""
        prompt = self.config.get("vision_prompt") or (
            "你透过音箱的摄像头看到了一帧画面。用一句自然的话描述画面里有什么、"
            "有没有人、那人在做什么。不要编造，看不清楚就说看不清。"
        )
        # 把主人这句话带进去：他说「你看看我手里拿的什么」时，描述才会对准那个东西；
        # 只说「你看看」时，行为和以前一样。截断到 120 字，防止有人贴一大段把 prompt 撑爆。
        q = (question or "").strip()
        if q:
            prompt = prompt + (
                "\n\n主人刚说的话是：「%s」。如果他在问画面里的某样东西，"
                "就针对那个东西回答；如果只是随口一句，就按平常描述。" % q[:120]
            )
        try:
            resp = await asyncio.wait_for(
                provider.text_chat(prompt=prompt, image_urls=[data_url]),
                timeout=20.0,
            )
        except asyncio.TimeoutError:
            logger.warning("[音箱] 视觉分析超时")
            return ""
        except Exception as exc:  # noqa: BLE001
            logger.warning("[音箱] 视觉分析失败：%s", exc)
            return ""
        text = str(getattr(resp, "completion_text", "") or "").strip()
        return text

    async def _get_device(self, path: str, timeout: float = 6.0) -> dict | None:
        if not self.device_url:
            return None
        url = self.device_url + path
        try:
            if self._http is None or self._http.closed:
                self._http = aiohttp.ClientSession(
                    timeout=aiohttp.ClientTimeout(total=6)
                )
            # /listen 会挂很久（等到人说话为止），超时必须按请求给，不能吃默认的 6 秒
            async with self._http.get(
                url, timeout=aiohttp.ClientTimeout(total=timeout)
            ) as resp:
                body = await resp.text()
                if resp.status >= 400:
                    return None
                return json.loads(body) if body else {}
        except Exception as exc:  # noqa: BLE001
            logger.debug("[音箱] 读设备 %s 失败：%s", path, exc)
            return None

    async def _http_get_bytes(self, path: str, timeout: float = 20.0) -> bytes:
        """拉设备上的原始字节（/clip 的 WAV）。和 _get_device 分开，因为这里要二进制。"""
        if not self.device_url:
            raise RuntimeError("没配 device_url")
        url = self.device_url + path
        if self._http is None or self._http.closed:
            self._http = aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=20)
            )
        async with self._http.get(
            url, timeout=aiohttp.ClientTimeout(total=timeout)
        ) as resp:
            if resp.status >= 400:
                raise RuntimeError("HTTP %d" % resp.status)
            return await resp.read()

    # ------------------------------------------------------------ HTTP 服务

    async def _serve(self) -> None:
        app = web.Application(client_max_size=8 * 1024 * 1024)
        app.router.add_post("/utter", self._h_utter)
        app.router.add_post("/wake", self._h_wake)
        app.router.add_post("/say", self._h_say)
        app.router.add_get("/state", self._h_state)
        app.router.add_get("/health", self._h_health)
        app.router.add_get("/audio/{name}", self._h_audio)
        # 开关机：米家 / 手机快捷指令都打这个。GET 是为了快捷指令能直接打开 URL。
        app.router.add_post("/power", self._h_power)
        app.router.add_get("/power", self._h_power)

        self._runner = web.AppRunner(app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.host, self.port)
        await site.start()
        logger.info("[音箱] 网关已起在 %s:%d", self.host, self.port)
        if self.voice_loop and self.device_url:
            self._voice_task = asyncio.create_task(self._voice_loop())
        if self.device_url:
            self._clock_task = asyncio.create_task(self._clock_loop())
        await self._stop.wait()

    # ------------------------------------------------------------ 对时

    # 推送间隔。和设备侧「6 小时重校一次」对齐：晶振一天漂十几秒，6 小时校一次
    # 累计误差不到 1 秒，代价只是每 6 小时一个 localhost 级别的请求。
    CLOCK_PUSH_S = 6 * 3600

    async def _clock_loop(self) -> None:
        """定期把 NAS 的时间推给设备（{@code POST /clock}）。

        <p>为什么插件要掺和：设备没有 RTC 电池，开机时间停在 1977 年。它自己会读
        HTTP 响应头里的 {@code Date} 兜底（见 ClockSync），那条路依赖外网或 NAS 的
        dashboard 端口；而 AstrBot 本来就要跟设备说话，顺手把时间带上最稳——容器起来的
        那一刻设备通常也刚断电重启过，正是它最需要时间的时候。

        <p>不因为「关机态」跳过：关机只闭麦，熄屏/作息仍依赖墙钟。
        """
        # 稍等一下再推：平台刚起来时 WiFi 可能还没连上，白推一次会刷一条失败日志
        await asyncio.sleep(3.0)
        while not self._stop.is_set():
            await self._push_clock()
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self.CLOCK_PUSH_S)
            except asyncio.TimeoutError:
                continue

    async def _push_clock(self) -> None:
        """推一次时间。设备收毫秒（13 位），收秒会被静默拒收。"""
        epoch_ms = int(time.time() * 1000)
        data = await self._post_device("/clock", {"epoch": epoch_ms})
        if data is None:
            logger.info("[音箱] 对时没送到（设备离线？），6 小时后再试")
            return
        logger.info(
            "[音箱] 已对时：%s", time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
        )

    # ------------------------------------------------------------ 语音闭环

    async def _voice_loop(self) -> None:
        """设备自己听：听到人声 → 识别 → 判断是不是叫她 → 交给流水线。

        <p>为什么循环放在插件这边而不是设备里：识别要模型，模型在 NAS 上；
        设备只负责「采到有人说话就截一段」，判定和解码都在这里，改起来不用重刷 APK。

        <p>三个必须守住的规矩：
        1. **不监听自己的喇叭**。整轮（识别→生成→播放）串行，播放期间不开新监听。
        2. **没叫名字就当没听见**。否则电视里说句话她也会接。
        3. **只喊名字不带内容时**，先让设备应一声，再等下一句，不要硬编一句话凑上去。
        """
        logger.info(
            "[音箱] 语音闭环已启动：唤醒词 %s，单次最多听 %d 秒",
            "/".join(self.wake_words) or "（没配置）",
            self.listen_window,
        )
        failures = 0
        while not self._stop.is_set():
            if self._powered_off:
                # 关机态：不调 /listen，设备就不会开麦克风。叫也叫不醒。
                await asyncio.sleep(1.0)
                continue
            if self._voice_busy:
                await asyncio.sleep(0.5)
                continue
            try:
                res = await self._get_device(
                    # need=350：「大肥鱼」三个字正常语速约 500~600ms 有声，但音量偏轻时
                    # 只有半数帧能过阈值，累计会掉到 400 上下；「现在几点」这种四字短句
                    # 贴着 400 也常差几十毫秒。设备端已经加了「连续 2 帧才算人声 +
                    # 最长连续人声 ≥160ms 的稀疏噪声闸门」，这里可以再放宽一点。
                    "/listen?max=%d&need=350" % self.listen_window,
                    timeout=self.listen_window + 20,
                )
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                failures += 1
                logger.warning("[音箱] 监听失败（第 %d 次）：%s", failures, exc)
                await asyncio.sleep(min(15.0, 2.0 * failures))
                continue

            failures = 0
            if res and res.get("busy"):
                # 设备正在念话，等她说完（不然会把自己的声音当成人说话）
                await asyncio.sleep(0.6)
                continue
            if not res or not res.get("hit"):
                # 等满一个窗口没人说话是正常现象，但「听见了却不够长」要留一笔——
                # 这正是「喊了她没反应、日志里还什么都没有」的那一类。
                if res and res.get("sparse"):
                    logger.info(
                        "[音箱] 稀疏噪声，没算数：%s 毫秒有声 / 跨度太长 / 峰值 %s",
                        res.get("voice_ms"), res.get("peak"),
                    )
                elif res and res.get("voice_ms"):
                    logger.info(
                        "[音箱] 有声音但不够长，没算数：%s 毫秒有声 / 峰值 %s",
                        res.get("voice_ms"), res.get("peak"),
                    )
                continue

            self._voice_busy = True
            try:
                await self._handle_heard(res)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                logger.warning("[音箱] 处理听到的话失败：%s", exc)
            finally:
                self._voice_busy = False
                # 刚说完话，别立刻接着听——尾巴、椅子响容易被当成新的一句
                await asyncio.sleep(0.8)

    async def _handle_heard(self, res: dict) -> None:
        """处理一段抓到的人声。"""
        # 每次都说清楚「听到了多响多长的一段」，识别错的时候才分得清是
        # 「麦克风没听见」还是「听见了但认成别的字」——这两种修法完全不同。
        logger.info(
            "[音箱] 听到一段：%s 毫秒有声 / 峰值 %s / 等了 %s 毫秒",
            res.get("voice_ms"), res.get("peak"), res.get("wait_ms"),
        )
        text = await self._transcribe()
        if not text:
            logger.info("[音箱] 听到了声音，但没识别出字（%s 毫秒有声）", res.get("voice_ms"))
            return
        if not has_content(text):
            # 只有标点 / 空白：不是人说的话，别把它当成「用户内容」送进大模型。
            # 以前这里直接往下走，接话窗口开着时就会把「。」发过去，她回一句
            # 「怎么又不吭声了……」——用户看到的是「她自问自答」。
            # 注意：不要清 `_awaiting_wake`，接话窗口还应该继续有效。
            logger.info("[音箱] 这段只有标点，忽略：%r", text[:20])
            return

        # 同音字也算：识别器会把「大肥鱼」写成「大菲鱼」，字面匹配会静默失效
        hit = find_wake(text, self.wake_words)
        if hit is None:
            # 刚应过一声「小鱼在的」，接下来这一句就不用再喊名字了。
            # 不认这个窗口的话，人喊完名字接着说「现在几点」会被当成「没叫她」丢掉，
            # 前面那声应答就白应了——这也是「喊了唤醒词没下文」最容易被误判成坏掉的地方。
            if self._followups >= self.wake_followups:
                # 已经连续接了几轮，必须重新喊名字。窗口继续开着太危险：
                # 客厅电视连续说话会一句接一句被当成接话，她回话又把窗口续上。
                self._awaiting_wake = False
                logger.info("[音箱] 连续接话已达 %d 轮，需要重新喊名字：%s",
                            self._followups, text[:40])
                return
            if (
                self._awaiting_wake
                and time.time() - self._wake_at <= self.wake_window
                # 长句不接：窗口开着的那十几秒里，客厅电视说的话也在这条路上，
                # 一句话几十个字的基本不可能是对着音箱讲的。
                and len(text) <= 25
                and has_content(text, 2)
            ):
                logger.info("[音箱] 刚叫醒，直接接话（第 %d 轮）：%s",
                            self._followups + 1, text[:60])
                self._followups += 1
                self._awaiting_wake = False
                seq0 = self._reply_seq
                await self.dispatch_text(
                    WAKE_NOTE + text, source="voice", duration_ms=res.get("voice_ms")
                )
                await self._finish_turn(seq0)
                self._awaiting_wake = True
                self._wake_at = time.time()
                return
            self._awaiting_wake = False
            if len(text) <= 8:
                # 短句又没匹配上，是最值得看的一类失败：「大肥鱼」被识别成「第」
                # 就长这样。日志里留原样，排查时不用再猜。
                logger.info("[音箱] 没叫她，忽略（短句）：%s", text[:20])
            else:
                logger.info("[音箱] 没叫她，忽略：%s", text[:40])
            return
        keyword = hit[0]
        # 喊了名字：接话轮数归零，重新获得完整的接话额度。
        self._followups = 0
        # 把唤醒词从原句里剪掉，剩下的才是「人要她回答的内容」。
        #
        # 必须**剪干净**：识别器经常把同一句吐两遍（「大肥鱼。大肥鱼」），
        # 只剪一次的话，剩下的那个「大肥鱼」会被当成正文送进大模型——
        # 于是她答的是这三个字本身（日志里就是「内容是：小鱼」→ 回「哎，我在」），
        # 而不是回答人真正说的话。这是被用户一眼看出来的那种错。
        rest = text
        while True:
            h = find_wake(rest, self.wake_words)
            if h is None:
                break
            rest = (rest[:h[1]] + " " + rest[h[2]:]).strip()
            # 剪完可能留下来一个前导标点（「，现在几点」），一起去掉
            for sep in ("，", "。", "！", "？", ",", ".", "!", "?", "、", " "):
                while rest.startswith(sep):
                    rest = rest[len(sep):].strip()
            rest = rest.strip()

        if not rest:
            # 只喊了名字：设备应一声（预录的「小鱼在的」），下一句才算正式开口
            logger.info("[音箱] 只喊了名字：%s", keyword)
            await self._wake_device(keyword)
            self._awaiting_wake = True
            # 这声应答是设备本地播的，不经过 _reply_seq，所以不能等回复；
            # 但设备端 busyForListen 已经把「刚收到 /wake」也算忙，这里只要等它播完
            # 再让出一个很短的静默即可。不能多等：人听完「小鱼在的」往往紧接着就说话，
            # 多等 1 秒就会把那句整句错过（18:39 实测就是这样丢掉「现在几点」的）。
            await self._wait_until_idle(min_wait=0.1, quiet_for=0.1, timeout=30.0)
            # 窗口从「重新开始听」这一刻起算，而不是从 /wake 那一下起算。
            self._wake_at = time.time()
            return

        logger.info("[音箱] 叫她「%s」，内容是：%s", keyword, rest[:60])
        # 连着说的（「大肥鱼，现在几点了」）也要先应一声再答。
        # 不然她全程静默 8 秒才出声，人根本不知道自己那句有没有被听见。
        if self._awaiting_wake:
            self._awaiting_wake = False
        else:
            await self._wake_device(keyword)
        rest = WAKE_NOTE + rest
        seq0 = self._reply_seq
        await self.dispatch_text(rest, source="voice", duration_ms=res.get("voice_ms"))
        await self._finish_turn(seq0)
        # 答完继续开着窗口：人常常一口气连着说（「大肥鱼」→「帮我关一下灯」），
        # 每句都喊名字太累。窗口由 wake_window 控制，嫌吵就设 0。
        self._awaiting_wake = True
        self._wake_at = time.time()

    async def _wake_device(self, keyword: str) -> None:
        """让设备应一声（本地预录的「小鱼在的」，不走 TTS）。

        <p>这是唤醒的唯一应答口：只喊名字、连着说一句，两种都要经过这里，
        保证「喊了就一定有一声固定的回应」。设备侧应答固定一句、不随机。
        """
        await self._post_device(
            "/wake",
            {
                "event": "wake",
                "source": "voice",
                "keyword": keyword,
                "direction": "unknown",
                "distance": "near",
                "confidence": 0.9,
            },
        )

    async def _transcribe(self) -> str:
        """把设备上最后那段录音转成文字。优先本地 SenseVoice，失败回退云端 STT。

        <p>为什么上本地：云端识别同一段话要 1.6~9.7 秒、抖动极大（冷启动、排队都算），
        唤醒应答就变成「喊完等五秒」。本地 sherpa-onnx 的 SenseVoice 只要 0.3~0.5 秒
        且稳定（2026-11-22 实测）。模型和云端是同一个，识别质量不打折。
        """
        if _PLUGIN_CONTEXT is None:
            return ""
        clip_url = self.device_url + "/clip"

        if self._local_asr is not None:
            # 注意：_http_get_bytes 的入参是「相对路径」，它内部会自己拼 device_url。
            # 这里不能传 clip_url（那已经是完整 URL），否则会拼成 device_url 出现两次。
            try:
                wav = await self._http_get_bytes("/clip")
            except Exception as exc:  # noqa: BLE001
                logger.warning("[音箱] 下载录音失败（%s）：%s", clip_url, exc)
                wav = None
            if wav:
                if self.save_clips:
                    self._save_clip(wav)
                try:
                    text = await asyncio.to_thread(self._local_asr, wav)
                except Exception as exc:  # noqa: BLE001
                    logger.warning("[音箱] 本地识别失败，回退云端：%s", exc)
                    self._local_asr = None      # 别再反复撞同一堵墙
                else:
                    return self._strip_sensevoice_tags(text)

        # 云端回退：走 AstrBot 配好的 STT（sf_stt）。
        if not self.audio_base:
            return ""
        try:
            prov = await _PLUGIN_CONTEXT.get_using_stt_provider_async()
        except Exception as exc:  # noqa: BLE001
            logger.warning("[音箱] 取不到 STT provider：%s", exc)
            return ""
        if prov is None:
            logger.warning("[音箱] 没有可用的语音识别（检查 provider 里的 sf_stt）")
            return ""
        try:
            text = await prov.get_text(clip_url)
        except Exception as exc:  # noqa: BLE001
            logger.warning("[音箱] 识别失败（%s）：%s", clip_url, exc)
            return ""
        return self._strip_sensevoice_tags(text)

    def _save_clip(self, wav: bytes) -> None:
        """调试用：把这一轮抓到的 WAV 存下来（默认关，排查唤醒词时临时开）。

        <p>文件名带毫秒时间戳，和日志里的「听到一段」时间对得上。只留最近 200 段，
        避免长期开着把 NAS 塞满。
        """
        try:
            self.clip_dir.mkdir(parents=True, exist_ok=True)
            name = "%d.wav" % int(time.time() * 1000)
            (self.clip_dir / name).write_bytes(wav)
            files = sorted(self.clip_dir.glob("*.wav"), key=lambda p: p.name)
            for old in files[:-200]:
                try:
                    old.unlink()
                except OSError:
                    pass
        except Exception as exc:  # noqa: BLE001
            logger.debug("[音箱] 存调试录音失败：%s", exc)

    @staticmethod
    def _strip_sensevoice_tags(raw: str) -> str:
        """SenseVoice 会带情绪/事件标签，比如 <|zh|><|NEUTRAL|>，去掉再判断唤醒词。

        <p>剥之前先留个原样：有时候识别器只回了一串标签、没有正文，
        剥完看着像「什么都没识别出来」，其实是「它认为这段不是人说话」——
        两件事的修法不同，日志里必须分得开。
        """
        raw = (raw or "").strip()
        text = re.sub(r"<\|[^|]*\|>", "", raw).strip()
        if not text and raw:
            logger.info("[音箱] 识别器只回了标签：%s", raw[:80])
        return text

    async def _wait_for_reply(self, seq: int, timeout: float = 35.0) -> bool:
        """等这一轮的大模型回复真的送到设备（{@link #_reply_seq} 推进）。

        <p>为什么不能直接等 /state 空闲：大模型要想 3~6 秒、TTS 再合成 2~4 秒，
        这段时间设备是「空闲」的（没在说话、队列也空）。以前 `_wait_until_idle`
        一看到空闲就返回，于是监听在她还在想的时候就被打开了；等回复真的开始播，
        `/say` 又把监听叫停，来回抖。更糟的是第一段播完、第二段还在合成的那几秒
        也会被当成空闲，麦克风正好把她自己的话录进去。
        """
        deadline = time.time() + timeout
        while time.time() < deadline and not self._stop.is_set():
            if self._reply_seq > seq:
                return True
            await asyncio.sleep(0.2)
        logger.info("[音箱] 等回复超时（%.0fs），按空闲继续", timeout)
        return False

    async def _wait_until_idle(self, min_wait: float = 0.1, quiet_for: float = 0.1,
                               timeout: float = 90.0) -> None:
        """等设备真的安静下来：连续 `quiet_for` 秒不忙，且至少过了 `min_wait` 秒。

        <p>「忙」的判断不只看 speaking：还要看排队、配音、以及设备端的**尾音保护**
        `tail_ms`（刚播完的一小段静默）。播放一开始 HAL 会拆掉采集流，而且喇叭紧贴
        麦克风，差半秒都会把她自己的尾音录进去。
        """
        start = time.time()
        deadline = start + timeout
        idle_since = None
        while time.time() < deadline and not self._stop.is_set():
            st = await self._get_device("/state")
            if st is None:
                return
            busy = bool(st.get("busy"))
            if not busy:
                busy = bool(st.get("speaking") or st.get("queued") or st.get("audio"))
            tail = st.get("tail_ms")
            if isinstance(tail, (int, float)) and 0 <= tail < 900:
                busy = True
            now = time.time()
            if busy:
                idle_since = None
            else:
                if idle_since is None:
                    idle_since = now
                if (now - idle_since) >= quiet_for and (now - start) >= min_wait:
                    return
            await asyncio.sleep(0.1)

    async def _finish_turn(self, reply_seq: int) -> None:
        """一轮对话收尾：先等回复真的出来，再等它真的播完（含尾音保护）。"""
        await self._wait_for_reply(reply_seq, timeout=35.0)
        await self._wait_until_idle(min_wait=0.1, quiet_for=0.1, timeout=90.0)

    # ------------------------------------------------------------ 对外 HTTP

    async def _h_utter(self, request: web.Request):
        """一句话的识别结果 —— 这是语音对话的入口（契约 §5）。"""
        try:
            data = await request.json()
        except Exception:  # noqa: BLE001
            text = (await request.text()).strip()
            data = {"text": text}
        text = str(data.get("text") or "").strip()
        if not text:
            return web.json_response({"ok": False, "error": "text 为空"}, status=400)
        if self._powered_off:
            # 关机态：外部（米家/手机/HASS）再推话也不理，和麦克风保持一致。
            return web.json_response({"ok": False, "error": "已关机"}, status=409)
        if len(text) > 2000:
            return web.json_response({"ok": False, "error": "text 太长"}, status=400)

        if self._awaiting_wake:
            self._awaiting_wake = False
            text = WAKE_NOTE + text

        # 不 await：完整流水线（大模型 + 工具）可能跑十几秒，HTTP 调用方（米家/手机）
        # 等不起，超时还会以为失败。事件提交后立刻返回，回复照旧走 /say。
        asyncio.create_task(self._dispatch_safe(text, source="utter",
                                                duration_ms=data.get("duration_ms")))
        return web.json_response({"ok": True, "turns": self._turns})

    async def _dispatch_safe(self, text: str, source: str, duration_ms=None) -> None:
        """后台跑一轮，异常只记日志，不把事件循环带崩。"""
        try:
            await self.dispatch_text(text, source=source, duration_ms=duration_ms)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("[音箱] 后台处理 /utter 失败：%s", exc)

    async def dispatch_text(self, text: str, source: str = "utter", duration_ms=None) -> None:
        """把一句话交给 AstrBot 的完整流水线（人设 / 记忆 / 工具 / TTS 都在里面）。

        <p>抽出来是因为有两个入口：外部 {@code /utter}（调试、Home Assistant 之类），
        和设备麦克风自己听到的（{@link #_voice_loop}）。两个入口之后的处理必须一模一样，
        否则「用嘴说」和「接口发」会得到两种人格。
        """
        abm = AstrBotMessage()
        abm.self_id = PLATFORM_TYPE
        abm.sender = MessageMember(self.owner_id, "音箱前的人")
        abm.type = MessageType.FRIEND_MESSAGE
        # 会话固定一个：这台机器上只有一个她，记忆不该分叉
        abm.session_id = "%s!%s" % (PLATFORM_TYPE, self.owner_id)
        abm.message_id = str(int(time.time() * 1000))
        # 不再标通道来源（2026-11-24 定）：主人明确说「音箱那边也算我说的话」，
        # 两边本来就是一个会话，再加前缀反而让她去想「这句算不算他的」。
        # 唤醒提示照旧拼在最前，那是给模型的上下文，不是身份标记。
        abm.message = [Plain(text)]
        abm.message_str = text
        abm.timestamp = int(time.time())
        abm.raw_message = {"source": source, "duration_ms": duration_ms}

        await self.handle_msg(abm)

    async def _h_wake(self, request: web.Request):
        """外部触发的唤醒（手机 / 米家 / 调试）。

        设备自己检测到唤醒时会本地反应，但设备端还没有唤醒词识别，所以由外面叫醒：
        先让设备应一声（它播预录的「小鱼在」），再把「下一句话要带唤醒上下文」的标记挂上。
        """
        try:
            data = await request.json()
        except Exception:  # noqa: BLE001
            data = {}
        payload = {
            "event": "wake",
            "source": data.get("source") or "voice",
            "keyword": data.get("keyword") or "大肥鱼",
            "direction": data.get("direction") or "unknown",
            "distance": data.get("distance") or "near",
            "confidence": data.get("confidence") or 0.9,
        }
        res = await self._post_device("/wake", payload)
        self._awaiting_wake = True
        self._wake_at = time.time()
        return web.json_response({"ok": res is not None, "device": res})

    async def _h_say(self, request: web.Request):
        """直接让她说一句，不经过大模型。调试链路、或者定时播报用。"""
        try:
            data = await request.json()
        except Exception:  # noqa: BLE001
            data = {"text": (await request.text()).strip()}
        text = str(data.get("text") or "").strip()
        if not text:
            return web.json_response({"ok": False, "error": "text 为空"}, status=400)
        ok = await self.say_to_device(text, interrupt=bool(data.get("interrupt")))
        return web.json_response({"ok": ok, "error": "" if ok else self._last_error})

    async def _h_state(self, request: web.Request):
        state = await self._get_device("/state")
        return web.json_response(
            {
                "ok": state is not None,
                "device": state,
                "turns": self._turns,
                "last_reply": self._last_reply,
                "last_reply_at": self._last_reply_at,
                "awaiting_wake": self._awaiting_wake,
                "powered_off": self._powered_off,
                "last_error": self._last_error,
            }
        )

    async def _h_power(self, request: web.Request):
        """关机 / 开机。

        <p>关机 = 灭屏（真熄背光）+ 闭麦：不再调设备的 /listen，也不接 /utter，
        别人再怎么喊「大肥鱼」都没反应。开机反向恢复。

        <p>为什么不动 Android 端：灭屏走已有的 /control display，闭麦在这边不调 /listen
        就够了，零 APK 改动。设备那头不用重刷。
        """
        try:
            data = await request.json()
        except Exception:  # noqa: BLE001
            data = {}
        raw = str(
            (data or {}).get("value")
            or (data or {}).get("power")
            or request.query.get("value")
            or request.query.get("power")
            or ""
        ).strip().lower()
        if raw in ("off", "0", "false", "close", "关", "关机"):
            off = True
        elif raw in ("on", "1", "true", "open", "开", "开机"):
            off = False
        else:
            return web.json_response(
                {"ok": False, "error": "value 只认 on / off"}, status=400
            )

        self._powered_off = off
        # 灭屏 / 亮屏。真熄屏（背光归 0）由设备的 /control display 负责。
        await self._post_device(
            "/control?action=display&value=%s" % ("off" if off else "on"), {}
        )
        logger.info("[音箱] %s", "关机：灭屏 + 闭麦" if off else "开机：亮屏 + 恢复监听")
        return web.json_response({"ok": True, "power": "off" if off else "on"})

    async def _h_health(self, request: web.Request):
        return web.json_response(
            {
                "ok": True,
                "device": bool(self.device_url),
                "audio_base": self.audio_base,
                "audio_files": len(list(self.audio_dir.glob("*.wav"))),
            }
        )

    async def _h_audio(self, request: web.Request):
        name = request.match_info.get("name", "")
        if not _SAFE_NAME_RE.match(name) or not name.endswith(".wav"):
            raise web.HTTPBadRequest(text="bad name")
        path = self.audio_dir / name
        if not path.exists():
            raise web.HTTPNotFound(text="no such audio")
        return web.FileResponse(
            path,
            headers={"Cache-Control": "no-store", "Content-Type": "audio/wav"},
        )


# ----------------------------------------------------------------- 插件本体

@register(
    "astrbot_plugin_amber_speaker",
    "local",
    "把琥珀音箱（C2 / 大肥鱼）接进 AstrBot：说话、做动作、被叫醒。",
    "0.1.0",
)
class AmberSpeakerPlugin(Star):
    """平台适配器本体在 AmberSpeakerPlatform，这里管三件事：

    1. 把 Context 借给平台（Platform 拿不到它，但 TTS provider 在里面）
    2. 一轮发完了，把攒着的回复一次送出去
    3. 提供 ``/音箱`` 命令，在没有屏幕的情况下也能查状态、试发声
    """

    def __init__(self, context: Context, config=None):
        super().__init__(context)
        self.config = config
        # 见过的 umo，只为了「跳过」日志不刷屏
        self._seen_umo: set[str] = set()
        global _PLUGIN_CONTEXT
        _PLUGIN_CONTEXT = context

    @filter.after_message_sent()
    async def flush_reply_after_send(self, event: AstrMessageEvent):
        """一轮的分段都发完了，把攒着的话一起送出去。"""
        umo = str(getattr(event, "unified_msg_origin", "") or "")
        if not umo.startswith("%s:" % PLATFORM_TYPE):
            return
        inst = _PLATFORM_INST
        if inst is None:
            return
        try:
            await inst.flush_reply()
        except Exception as exc:  # noqa: BLE001
            logger.warning("[音箱] 发送收尾失败：%s", exc)

    @filter.on_llm_request()
    async def inject_presence(self, event: AstrMessageEvent, req: ProviderRequest):
        """补一句「你现在在哪、你有什么身体」。没有这句她会在音箱里自称在 QQ 上。"""
        umo = str(getattr(event, "unified_msg_origin", "") or "")
        # 精确到平台前缀，不用 in：QQ 那边的会话是 aiocqhttp:...，绝不能沾上一点身体说明。
        if not umo.startswith("%s:" % PLATFORM_TYPE):
            # 每个 umo 只记一次：QQ 那边每句话都会走到这里，逐条打会把日志刷满。
            # 这条「跳过」本身也是排查信息——它证明钩子跑了，只是不是音箱的会话。
            if umo not in self._seen_umo:
                self._seen_umo.add(umo)
                logger.info("[音箱] on_llm_request 跳过（umo=%r）", umo)
            return
        note = (
            "【在场】你现在在客厅的琥珀音箱里说话，不在 QQ 里，但对面是同一个人。"
            "回复会被直接念出来：句子要短、要口语，不要用列表、代码块、表情符号。"
        )
        # speak_actions 是 platform 条目的配置，挂在平台实例上；这个 Star 类读不到，
        # 只能从全局那份平台实例拿。之前直接写 self.speak_actions，每轮都在这里
        # AttributeError 被 call_event_hook 吞掉——症状是「说明一个字都没进去，
        # 但日志里看不出异常」，查了很久。改动这里务必看一眼容器日志有没有 Traceback。
        inst = _PLATFORM_INST
        speak = bool(getattr(inst, "speak_actions", True)) if inst is not None else True
        if speak:
            note = note + "\n\n" + SPEAK_BODY_NOTE
        else:
            note = note + "\n想让她做动作就把〔动作词〕写在句子里，那些字不会被念出来。"
        current = req.system_prompt or ""
        if note not in current:
            # 放在人设**前面**而不是后面：1538 字人设 + 记忆 + 工具说明之后，
            # 末尾的格式要求基本被稀释掉了（实测连试三轮她不写）。
            # 放前面她才真的照着做；人设本身没被改，只是语言环境先于它。
            req.system_prompt = (note + "\n\n" + current).strip()
            # 用 info 而不是 debug：这段是「说话带动作」的唯一来源，
            # 排查「她为什么不写动作」时第一眼就要能看到它到底注进去了没有。
            logger.info("[音箱] 已注入在场说明（umo=%s，%s %d 字，system_prompt 共 %d 字）",
                        umo, "含身体说明" if speak else "无身体说明",
                        len(note), len(req.system_prompt))

    @filter.llm_tool(name="camera_snapshot")
    async def camera_snapshot(self, event: AstrMessageEvent) -> str:
        """透过音箱的摄像头看一眼主人此刻在做什么。

        当你想「看看主人在干嘛」「确认对面的人还在不在」时调用，取一帧画面
        交给视觉模型描述。返回的是画面描述文字，不是原图。

        注意：取帧有额度和间隔限制（设备端 snap_cool / snap_day 可调，默认隔 30 秒、
        每天 100 次）。拿不到画面时会返回「【眼睛】…」的说明，此时不要编造画面内容。

        Returns:
            string: 一帧画面的文字描述
        """
        inst = self._platform()
        if inst is None:
            return "【眼睛】音箱平台没起来，看不到画面。"
        # 带上主人那句原话，识物时才能对准他问的东西（「我手里拿的是什么」）。
        return await inst.snapshot_and_describe(event.message_str or "")

    @filter.command("音箱")
    async def speaker_cmd(self, event: AstrMessageEvent):
        """查音箱状态，或者让它说一句：/音箱 说 你好"""
        args = event.message_str.split(None, 2)
        inst = self._platform()
        if inst is None:
            yield event.plain_result("❌ 音箱平台没起来，去面板里看一下「琥珀音箱」有没有启用")
            return

        if len(args) >= 3 and args[1] == "说":
            ok = await inst.say_to_device(args[2])
            yield event.plain_result(
                ("✅ 已送出：" + args[2][:40]) if ok else ("❌ " + (inst._last_error or "发送失败"))
            )
            return

        state = await inst._get_device("/state")
        lines = ["🔈 音箱网关：%s:%d" % (inst.host, inst.port)]
        lines.append("　设备地址：%s" % (inst.device_url or "未配置"))
        lines.append("　音频根地址：%s" % (inst.audio_base or "⚠️ 推断失败，设备下载不到语音"))
        lines.append("　音色：%s" % inst.tts_provider)
        lines.append("　本轮累计：%d 句" % inst._turns)
        if state:
            lines.append(
                "　设备在线：phase=%s batery=%s"
                % (state.get("phase", "?"), state.get("battery", "?"))
            )
            if state.get("clock_ok") is False:
                lines.append("　⚠️ 设备墙钟不可信（RTC 没电池），时间相关功能已自动停用")
        else:
            lines.append("　设备：❌ 连不上（检查 IP 变没变）")
        if inst._last_reply:
            lines.append("　最后一句：%s" % inst._last_reply[:60])
        if inst._last_error:
            lines.append("　最后错误：%s" % inst._last_error)
        yield event.plain_result("\n".join(lines))

    def _platform(self):
        inst = _PLATFORM_INST
        return inst if isinstance(inst, AmberSpeakerPlatform) else None

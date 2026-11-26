# -*- coding: utf-8 -*-
"""QQ 纯文字：把〔动作〕旁白从 QQ 发出去的文字里去掉。

为什么需要它
------------
音箱平台（`astrbot_plugin_amber_speaker`）在 LLM 请求前会注入一份「身体说明书」，
要求她把动作写成〔词〕（〔点头〕〔尾巴摇〕），设备端再把〔…〕抠掉、拿去驱动动作。

但 QQ 私聊和音箱**共用同一个会话**（`preferences` 里 `sel_conv_id` 都指向同一条
`conversations.conversation_id`），那段说明书教会她的写法就顺着共享历史跑到 QQ 上，
表现是每句话都挂一个〔尾巴一晃〕——QQ 上没人负责把括号抠掉，于是原样发出来。

职责边界
--------
- **只管 QQ 出口**：按平台类型 `aiocqhttp`（id `dafeiyu`）判断，音箱 / fishbuddy 一律不碰。
- **不改人设、不改历史、不改 LLM 请求**：只改「这一条要发出去的文字」。
- 删掉本插件目录就完全恢复原样，不留副作用。

判据（排查用）
--------------
容器日志里 `[QQ纯文字]` 开头的行会打出「去掉了什么」，看到它就说明钩子跑到了。
"""
import re

from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.message_components import Plain
from astrbot.api.star import Context, Star, register

TAG = "[QQ纯文字]"

# 〔…〕 是音箱的动作标记；〖…〗 一并处理，模型偶尔会换一种括号写法。
# 长度封顶 30：真被误伤也只可能切掉一小段，不会吞掉正文。
_ACTION_RE = re.compile(r"[〔〖][^〔〕〖〗]{0,30}[〕〗]")
# 去掉标记后常留下两个空格或「空格 + 标点」，顺手抹平。
_MULTI_SPACE_RE = re.compile(r"[ \t\u3000]{2,}")
_SPACE_BEFORE_PUNCT_RE = re.compile(r"[ \t\u3000]+([，。！？、；：,.!?;:）】」』])")


def strip_actions(text: str) -> str:
    """去掉〔动作〕并抹平留下的空格。纯函数，便于单测/手验。"""
    out = _ACTION_RE.sub("", text)
    out = _MULTI_SPACE_RE.sub(" ", out)
    out = _SPACE_BEFORE_PUNCT_RE.sub(r"\1", out)
    return out.strip()


def is_qq(event: AstrMessageEvent) -> bool:
    """QQ 走 aiocqhttp 适配器（平台 id 一般是 dafeiyu）。

    两个都判：id 是用户自己起的名字（可能改），type 才是适配器种类。
    音箱是自定义平台类型（amber_speaker / fishbuddy），不会被这两条命中。
    """
    try:
        if event.get_platform_name() == "aiocqhttp":
            return True
        return event.get_platform_id() == "dafeiyu"
    except Exception:  # noqa: BLE001
        return False


@register(
    "astrbot_plugin_qq_plaintext",
    "local",
    "QQ 发出的文字里去掉〔动作〕旁白（音箱平台不受影响）",
    "1.0.0",
    "",
)
class QqPlaintextPlugin(Star):
    def __init__(self, context: Context):
        super().__init__(context)

    @filter.on_decorating_result()
    async def strip_on_send(self, event: AstrMessageEvent):
        if not is_qq(event):
            return
        result = event.get_result()
        if not result or not result.chain:
            return
        plains = [c for c in result.chain if isinstance(c, Plain) and c.text]
        if not plains:
            return

        # 先整体算一遍：整条都是动作（去完一个字不剩）就原样发出，
        # 宁可漏一条带括号的，也不要发空消息——空消息在很多适配器上会报错或静默丢。
        cleaned = [strip_actions(c.text) for c in plains]
        if not any(t.strip() for t in cleaned):
            logger.info("%s 整条都是动作旁白，保留原文：%r", TAG, plains[0].text[:60])
            return

        changed = False
        for comp, new in zip(plains, cleaned):
            if new != comp.text:
                if not changed:
                    logger.info("%s %r -> %r", TAG, comp.text[:120], new[:120])
                comp.text = new
                changed = True

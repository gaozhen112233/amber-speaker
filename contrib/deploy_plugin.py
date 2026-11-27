#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把音箱插件推到 NAS 的 AstrBot 里，并在 platform 数组里挂上它。

    python deploy_plugin.py            上传 + 挂配置（不重启）
    python deploy_plugin.py --restart  上传 + 挂配置 + 重启容器

为什么要单独一个脚本：插件本体在 workspace 里改，AstrBot 那边只是副本。
每次改完重跑一遍，比手工 sftp 可靠——尤其是那份 cmd_config.json，
手滑一次会带走整个 QQ 机器人的配置。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import paramiko

HOST = os.environ.get("NAS_HOST", "")
USER = os.environ.get("NAS_USER", "")
# 认证二选一：NAS_KEY 指向私钥（推荐），或 NAS_PASS 给密码。
# 这里**绝不写默认密码**——这个文件是要公开的，历史上就因为硬编码密码差点泄露。
PASS = os.environ.get("NAS_PASS", "")
KEY = os.environ.get("NAS_KEY", "")
# AstrBot 的数据目录（宿主机路径）。按自己的安装位置改，或用 ASTRBOT_DATA 覆盖。
DATA = os.environ.get("ASTRBOT_DATA", "/AstrBot/data")
PLUGIN = "astrbot_plugin_amber_speaker"
LOCAL = Path(__file__).resolve().parent / PLUGIN

# 设备地址：音箱在局域网里的 IP，别人装的时候一定要改。
DEVICE_URL = os.environ.get("AMBER_DEVICE_URL", "http://192.168.1.100:8788")

PLATFORM_ENTRY = {
    "id": "amber_speaker",
    "type": "amber_speaker",
    "enable": True,
    "host": "0.0.0.0",
    "port": 8789,
    "device_url": DEVICE_URL,
    # 音频地址必须是「设备能访问到」的，不能靠容器自省：
    # 容器里 socket 看到的出口是 172.x docker 内网，设备根本连不上，
    # _guess_base() 会返回空 → 每句都退化成「只有字幕，没有声音」。
    # 端口映射在 NAS 宿主上，所以这里写宿主 IP。
    "audio_base": os.environ.get("AMBER_AUDIO_BASE", "http://%s:8789" % HOST),
    "owner_id": "owner",
    # 音箱用小晴（sf_qing）。2026-11-22 用户定案：之前错用了 sf_dafeiyu（小羽/大肥鱼）。
    # 想临时对比就用 AMBER_TTS=sf_dafeiyu python deploy_plugin.py 这样跑一次。
    "tts_provider": os.environ.get("AMBER_TTS", "sf_qing"),
    "tts_timeout": 8.0,
    "max_chars": 300,
    "disable_global_tts": True,
    "voice_reply": True,
    # 语音闭环：设备自己听，听到人声就送去识别，叫了名字才回话。
    # 想临时关掉（比如在开会）就 voice_loop=False 再部署一次。
    "voice_loop": True,
    "wake_words": "大肥鱼,小鱼,肥鱼,大肥,大黑鱼",
    "listen_window": 90,
    # 唤醒后 20 秒内不用再喊名字，直接说话也算对她说的（应一声之后就该这样）。
    # 20 秒实测太紧：她答完一句、人想两秒再说下一句，经常差几秒就过期。
    "wake_window": 20,
    # 一次唤醒后最多允许几轮不喊名字的接话，超过必须重新喊名字。
    # 挡电视连续对话：误接了一句也不会无限续下去。
    # 4 太紧——聊一件事正常就超过 4 轮，第 5 句被整句丢掉（2026-11-27 真机日志）。
    "wake_followups": 8,
    # 接话时的字数上限，超过当电视台词不接。原来写死 25 字，只够半句话。
    "followup_max_chars": 60,
    "voice_max_chars": 120,
    "audio_keep": 80,
    # 调试：把每次 /listen 抓到的 WAV 存到 data/amber_speaker/clips/。
    # 排查唤醒词时用 `AMBER_SAVE_CLIPS=1 python deploy_plugin.py --restart` 打开。
    "save_clips": os.environ.get("AMBER_SAVE_CLIPS", "0") == "1",
    # 「眼睛」工具视觉描述提示词，留空用默认。
    "vision_prompt": "",
    # 说话带动作：给她身体说明书 + 没写动作时自动补一次性小动作。
    # 排查「动作是不是插件乱插的」时设 AMBER_SPEAK_ACTIONS=0 再部署一次。
    "speak_actions": os.environ.get("AMBER_SPEAK_ACTIONS", "1") != "0",
}


def connect():
    if not HOST or not USER:
        raise SystemExit(
            "缺少 NAS 连接信息。先设环境变量：\n"
            "  NAS_HOST=<NAS 的 IP>  NAS_USER=<登录名>\n"
            "  NAS_KEY=~/.ssh/id_ed25519        （推荐，私钥）\n"
            "  或 NAS_PASS=<密码>               （二选一）")
    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    common = dict(port=22, timeout=20, banner_timeout=30, auth_timeout=30)
    if KEY:
        cli.connect(HOST, username=USER, key_filename=os.path.expanduser(KEY),
                    look_for_keys=True, allow_agent=True, **common)
    else:
        if not PASS:
            raise SystemExit("没给 NAS_KEY 也没给 NAS_PASS，无法登录 NAS。")
        cli.connect(HOST, username=USER, password=PASS,
                    look_for_keys=False, allow_agent=False, **common)
    return cli


def run(cli, cmd: str) -> tuple[str, str]:
    _, out, err = cli.exec_command(cmd, timeout=120)
    return (out.read().decode("utf-8", "replace"),
            err.read().decode("utf-8", "replace"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--restart", action="store_true", help="装完重启容器")
    args = ap.parse_args()

    if not LOCAL.is_dir():
        print("找不到插件目录：%s" % LOCAL, file=sys.stderr)
        return 2

    cli = connect()
    sftp = cli.open_sftp()
    remote_dir = "%s/plugins/%s" % (DATA, PLUGIN)

    run(cli, "mkdir -p '%s'" % remote_dir)
    for name in ("main.py", "metadata.yaml"):
        sftp.put(str(LOCAL / name), "%s/%s" % (remote_dir, name))
        print("↑ %s" % name)

    # ---- 挂到 platform 数组（幂等：已存在就覆盖那一条，不重复加）
    cfg_path = "%s/cmd_config.json" % DATA
    out, err = run(cli, "cp '%s' /tmp/cmd_config.amber.bak && echo 备份完成" % cfg_path)
    print(out.strip() or err.strip())

    payload = json.dumps(PLATFORM_ENTRY, ensure_ascii=False)
    # 哪些键是「部署时决定的」，每次都以本次为准；其余的（唤醒词、接话轮数、
    # 音量、音色之外的调优项）**必须保留容器里现有的值**。
    #
    # 原来这里是 `ps[i]=entry` 整条替换，于是每部署一次，所有手动调过的参数
    # 都被打回模板默认值：2026-11-27 就把 wake_followups=8 冲回了 4，
    # 表现是「刚改好的东西一部署又不对了」，而且没人会想到是部署脚本干的。
    forced = ["id", "type", "host", "port", "owner_id"]
    if os.environ.get("AMBER_DEVICE_URL"):
        forced.append("device_url")
    if os.environ.get("AMBER_AUDIO_BASE"):
        forced.append("audio_base")
    if os.environ.get("AMBER_TTS"):
        forced.append("tts_provider")
    script = (
        "import json,io\n"
        "p=%r\n"
        "c=json.load(io.open(p,encoding='utf-8-sig'))\n"
        "entry=json.loads(%r)\n"
        "forced=set(%r)\n"
        "ps=c.setdefault('platform',[])\n"
        "hit=False\n"
        "for i,x in enumerate(ps):\n"
        "    if x.get('type')=='amber_speaker' or x.get('id')=='amber_speaker':\n"
        "        m=dict(entry)\n"
        "        for k,v in x.items():\n"
        "            if k not in forced:\n"
        "                m[k]=v\n"
        "        ps[i]=m; hit=True\n"
        "if not hit:\n"
        "    ps.append(entry)\n"
        # 音箱上的人就是主人，必须算管理员。
        # AstrBot 判定管理员只有一条依据：sender_id 在 admins_id 里
        # （pipeline/waking_check/stage.py 里 event.role = "admin" 的那一段），
        # 不看平台、不看是不是私聊。owner_id 不在这个名单里，米家、手机控制这些
        # 要权限的插件就全被挡在门外。
        "admins=c.setdefault('admins_id',[])\n"
        "owner=%r\n"
        "if owner not in admins:\n"
        "    admins.append(owner)\n"
        "io.open(p,'w',encoding='utf-8').write(json.dumps(c,ensure_ascii=False,indent=2))\n"
        "print('platform 条目:', [(x.get('type'), x.get('port')) for x in ps])\n"
        "print('admins_id:', admins)\n"
    ) % (cfg_path, payload, forced, PLATFORM_ENTRY["owner_id"])
    sftp.putfo(__import__("io").BytesIO(script.encode("utf-8")), "/tmp/amber_cfg.py")
    sftp.close()
    out, err = run(cli, "python3 /tmp/amber_cfg.py")
    print(out.strip() or err.strip())

    # ---- 校验 json 还能读
    out, err = run(cli, "python3 -c \"import json,io;json.load(io.open('%s',encoding='utf-8'));print('json OK')\"" % cfg_path)
    print(out.strip() or err.strip())

    if args.restart:
        print("重启 astrbot ...")
        out, err = run(cli, "docker restart astrbot")
        print(out.strip() or err.strip())
    else:
        print("（没重启。AstrBot 需要重载才能看到新平台：docker restart astrbot）")

    cli.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

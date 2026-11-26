# -*- coding: utf-8 -*-
"""生成「大幅度」待机动作（m30~m36）。

背景（2026-11-24）：
用户反馈原来的 m8~m25「打哈欠/伸懒腰/揉眼睛/拨头发都看不出，哼歌摇头只是摇一下就没了」。
量过才知道原因：那批动作的曲线幅度 6~19（Angle 系列量程 ±30），而且只驱单参数做慢正弦，
在「待机本来就在晃 ±10 度」的背景里等于没有。

这批的原则：
  1. 幅度用满 —— AngleX/Y/Z、PositionX/Y、LeanInput、HipAngleXq 都往 ±30 打。
  2. 多参数叠加 —— 头 + 腰 + 重心 + 表情同时动，单看一个参数只有 1~4% 屏幕变化，
     叠起来才有 15%+。
  3. 节奏有起伏 —— 不是一条匀速正弦，而是「快起 - 停住 - 收回」，8fps 下也看得出。
  4. 只有 HipAngleXq 能动下半身（±30 实测 6.7~7% 画面变化，是唯一非物理输出且改裙/腰的），
     所以「蹲下」= HipAngleXq 拉正 + PositionY 下沉，配合抬头看镜头。

格式（motion3.json，全线性段）：
  Segments = [t0, v0, 0, t1, v1, 0, t2, v2, ...]
  第一个点直接 t,v，之后每个点前面加一个 0（段类型 = 线性）。
"""

import io
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "assets", "model", "motion")


def curve(pid, keys):
    seg = []
    for i, (t, v) in enumerate(keys):
        seg += [float(t), float(v)] if i == 0 else [0, float(t), float(v)]
    return {"Target": "Parameter", "Id": pid, "Segments": seg}


def build(name, file_id, duration, curves):
    # Segments = [t0, v0, 类型, t1, v1, 类型, t2, v2, ...]，长度 = 2 + 3*(n-1)
    # 所以点数 n = (len-2)/3 + 1。之前写成 len//2，Meta 里的统计值和实际曲线对不上，
    # Cubism 的 Motion3Json 解析按这个数分配/校验，值不对就整个动作丢掉（日志只有
    # 「没有这个动作：xxx」，因为 LoadMotion 返回了 nullptr 而加载处是 continue）。
    def points(c):
        n = len(c["Segments"])
        return 1 if n <= 2 else (n - 2) // 3 + 1
    total_points = sum(points(c) for c in curves)
    total_segments = sum(points(c) - 1 for c in curves)
    doc = {
        "Version": 3,
        "Meta": {
            "Duration": duration,
            "Fps": 60.0,
            "Loop": False,
            "AreBeziersRestricted": False,
            "CurveCount": len(curves),
            "TotalSegmentCount": total_segments,
            "TotalPointCount": total_points,
            "UserDataCount": 0,
            "TotalUserDataSize": 0,
        },
        "Curves": curves,
    }
    path = os.path.join(OUT, file_id + ".motion3.json")
    # 必须 indent=1，让每个数字独占一行，**不能用紧凑格式**。
    # Cubism 的 CubismJson::ParseNumeric 只认 `\n` 和 `,` 两个终止符：
    #     case '\n': case ',': *outEndPos = i; return ...
    #     default: _error = "non-numeric charactor found";
    # 紧凑写法里 `0.0]`、`"TotalUserDataSize":0}` 这些数字后面跟的是 `]` / `}`，
    # 直接判非法 → CubismJson 解析失败 → CubismMotion::Create 返回 nullptr，
    # 外面只能看到「没有这个动作」。这个坑吃掉了一整轮排查（11-24）。
    with io.open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    print("  %-8s %-10s %.1fs %2d 条曲线 %6d 字节" % (
        file_id, name, duration, len(curves), os.path.getsize(path)))


# ---------------------------------------------------------------- 动作定义
C = curve

build("蹲下看看", "m30", 4.2, [
    # 腰/裙压下去 + 整个人下沉 = 蹲；抬头看镜头
    C("HipAngleXq", [(0, 0), (0.9, 30), (3.2, 30), (4.2, 0)]),
    C("PositionY", [(0, 0), (0.9, -22), (3.2, -21), (4.2, 0)]),
    C("LeanInput", [(0, 0), (0.9, 9), (3.2, 7), (4.2, 0)]),
    C("AngleY", [(0, 0), (1.2, -14), (3.2, -12), (4.2, 0)]),
    C("ParamEyeROpen", [(0, 1), (1.0, 1.12), (3.2, 1.12), (4.2, 1)]),
    C("ParamEyeLOpen", [(0, 1), (1.0, 1.12), (3.2, 1.12), (4.2, 1)]),
    C("ParamEyeBallY", [(0, 0), (1.2, 0.42), (3.2, 0.4), (4.2, 0)]),
    C("ParamEyeBallX", [(0, 0), (1.4, 0.18), (2.6, -0.18), (3.6, 0.1), (4.2, 0)]),
    C("ParamBreath", [(0, 0.15), (1.4, 0.55), (3.2, 0.5), (4.2, 0.15)]),
])

build("蹦两下", "m31", 3.2, [
    C("PositionY", [(0, 0), (0.35, 26), (0.7, 0), (1.2, 22), (1.6, 0), (3.2, 0)]),
    C("AngleY", [(0, 0), (0.35, -11), (0.7, 2), (1.2, -9), (1.6, 2), (3.2, 0)]),
    C("AngleZ", [(0, 0), (0.5, 7), (1.3, -7), (2.0, 0), (3.2, 0)]),
    C("ParamBreath", [(0, 0.15), (0.4, 0.95), (1.3, 0.9), (2.2, 0.4), (3.2, 0.15)]),
    C("EyeSmile", [(0, 0), (0.3, 0.95), (2.4, 0.9), (3.2, 0)]),
    C("MouthSmile_L", [(0, 0), (0.3, 0.95), (2.4, 0.9), (3.2, 0)]),
    C("MouthSmile_R", [(0, 0), (0.3, 0.95), (2.4, 0.9), (3.2, 0)]),
])

build("大伸懒腰", "m32", 4.6, [
    C("LeanInput", [(0, 0), (1.3, -21), (3.4, -18), (4.6, 0)]),
    C("AngleY", [(0, 0), (1.3, -20), (3.4, -17), (4.6, 0)]),
    C("PositionY", [(0, 0), (1.3, 15), (3.4, 11), (4.6, 0)]),
    C("ParamBreath", [(0, 0.15), (1.3, 1.0), (3.4, 0.92), (4.6, 0.15)]),
    C("ParamEyeROpen", [(0, 1), (1.1, 0.08), (3.3, 0.1), (4.6, 1)]),
    C("ParamEyeLOpen", [(0, 1), (1.1, 0.08), (3.3, 0.1), (4.6, 1)]),
    C("ParamMouthOpenY", [(0, 0), (1.1, 0.9), (3.3, 0.85), (4.6, 0)]),
    C("JawOpen", [(0, 0), (1.1, 0.75), (3.3, 0.7), (4.6, 0)]),
    C("AngleZ", [(0, 0), (2.0, 5), (3.0, -5), (4.6, 0)]),
])

build("左右大张望", "m33", 4.4, [
    C("AngleX", [(0, 0), (0.7, -28), (1.9, 28), (3.1, -20), (4.4, 0)]),
    C("AngleZ", [(0, 0), (0.7, 9), (1.9, -9), (3.1, 6), (4.4, 0)]),
    C("PositionX", [(0, 0), (0.7, 13), (1.9, -13), (3.1, 9), (4.4, 0)]),
    C("ParamEyeBallX", [(0, 0), (0.7, -0.92), (1.9, 0.92), (3.1, -0.7), (4.4, 0)]),
    C("AngleY", [(0, 0), (1.0, 5), (2.6, 5), (4.4, 0)]),
    C("ParamBreath", [(0, 0.15), (1.5, 0.5), (4.4, 0.15)]),
])

build("歪头杀", "m34", 3.4, [
    C("AngleZ", [(0, 0), (0.8, 28), (2.4, 25), (3.4, 0)]),
    C("AngleX", [(0, 0), (0.9, 9), (2.4, 7), (3.4, 0)]),
    C("AngleY", [(0, 0), (0.9, -6), (2.4, -5), (3.4, 0)]),
    C("EyeSmile", [(0, 0), (0.8, 0.85), (2.4, 0.8), (3.4, 0)]),
    C("ParamEyeBallY", [(0, 0), (1.2, 0.3), (2.6, 0.25), (3.4, 0)]),
    C("ParamBreath", [(0, 0.15), (1.6, 0.45), (3.4, 0.15)]),
])

build("鞠躬行礼", "m35", 3.6, [
    C("LeanInput", [(0, 0), (0.9, 26), (2.4, 24), (3.6, 0)]),
    C("AngleY", [(0, 0), (0.9, 22), (2.4, 20), (3.6, 0)]),
    C("PositionY", [(0, 0), (0.9, -10), (2.4, -9), (3.6, 0)]),
    C("ParamEyeROpen", [(0, 1), (0.9, 0.35), (2.4, 0.35), (3.6, 1)]),
    C("ParamEyeLOpen", [(0, 1), (0.9, 0.35), (2.4, 0.35), (3.6, 1)]),
    C("ParamBreath", [(0, 0.15), (1.0, 0.6), (2.4, 0.55), (3.6, 0.15)]),
])

build("转个圈", "m36", 5.0, [
    C("AngleX", [(0, 0), (1.0, 30), (2.2, 0), (3.4, -30), (5.0, 0)]),
    C("PositionX", [(0, 0), (1.0, 17), (2.2, 0), (3.4, -17), (5.0, 0)]),
    C("AngleZ", [(0, 0), (1.0, 11), (2.2, 0), (3.4, -11), (5.0, 0)]),
    C("ParamEyeBallX", [(0, 0), (1.0, 0.85), (2.2, 0), (3.4, -0.85), (5.0, 0)]),
    C("AngleY", [(0, 0), (2.2, 6), (5.0, 0)]),
    C("ParamBreath", [(0, 0.15), (1.6, 0.55), (3.6, 0.5), (5.0, 0.15)]),
])

print("\n共 7 段，已写入", OUT)

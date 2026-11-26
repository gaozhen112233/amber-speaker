# -*- coding: utf-8 -*-
"""把 E:\\live2d 里下载的 vtube 模型包折成应用用的资源目录。

为什么需要这一步：那些包是给 VTube Studio 用的，文件名是中文、贴图是 4096 方图、
model3.json 里写的是它们自己的路径。而设备上这套渲染器只认一种布局：

    <目录>/model3.json        只引用 model.moc3 / textures/tN.png / physics3.json
    <目录>/model.moc3
    <目录>/physics3.json
    <目录>/textures/tN.png
    <目录>/exp/eN.exp3.json   + index.txt 里的 [exp] 段（中文名 -> 文件）
    <目录>/motion/mN.motion3.json + index.txt 里的 [motion] 段

中文文件名不能直接进 APK：aapt2 打包和解包在不同系统上转码不一致，
所以中文名字留在 index.txt 里当「显示名」，磁盘上一律 ASCII 编号。

贴图必须缩：4096×4096 的 RGBA 一张就 64MB，6 张 384MB，
这台机器总内存才 915MB、GPU 和系统共用，直接爆。屏只有 1024×600，
角色占屏高 600px 上下，2048 长边已经够用（大肥鱼现在最大也就 2048）。
"""
import io
import json
import os
import shutil
import struct
import sys

from PIL import Image

SRC = r"E:\live2d\_x"
DST = r"C:\android\amber\assets"
MAX_SIDE = 2048

# 默认隐藏的部件（按 cdi3 里的中文显示名写，脚本自己查成 Part18 这种 Id）。
# 这几个 miku 模型的源文件带着作者水印和使用须知图层，VTube Studio 里由按键手动关，
# 默认是开的；放到音箱上她就顶着一行「禁止商用 二传二改」。
# 注意不能改成「关整个 part 的不透明度」——part 是分层的，关错了整个角色黑掉。
HIDE_PARTS = ["水印.psd", "水印 (1).psd", "[ 参考图 ]"]

# 每个模型另外点名关掉的 drawable 编号。
#
# 为什么还需要这个：miku 那行「雪初音miku 2022」的小字藏在 part 48（r4）里，
# 而那个 part 下挂着 42 个部件 —— 按 part 关会把角色一起关掉。实测单独 hidedraw 202
# 之后，小字没了、角色一处不缺，所以按编号点它。
# 编号只对同一份 moc3 稳定：换了模型文件就要重新量（blenddraws all + 屏幕定位）。
HIDE_DRAWS = {
    "model_miku": [],
    "model_sakura": [],
}

# 目录名 -> (源目录, 源 model3.json, profile, 动作映射)
# profile: dafeiyu = 待机驱 AngleX/Y/Z 这类「物理输入死参数」；standard = 标准 Cubism 参数
MODELS = [
    ("model_miku", "miku/miku", "standard", "初音未来"),
    ("model_sakura", "sakura/樱花miku", "standard", "樱花初音"),
]


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(33)
    return struct.unpack(">II", head[16:24])


def pack(name, source, profile, title):
    src = os.path.join(SRC, source)
    out = os.path.join(DST, name)
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, "textures"))
    os.makedirs(os.path.join(out, "exp"))
    os.makedirs(os.path.join(out, "motion"))

    # model3.json：读源的，重写三个资源引用，其它（Groups 等）原样留着
    with open(os.path.join(src, os.path.basename(src) + ".model3.json"), encoding="utf-8") as f:
        setting = json.load(f)

    # moc3：改名为 model.moc3
    moc_name = setting["FileReferences"]["Moc"]
    shutil.copyfile(os.path.join(src, moc_name), os.path.join(out, "model.moc3"))
    print("  moc3 %.1fMB" % (os.path.getsize(os.path.join(out, "model.moc3")) / 1e6))

    # 物理：文件名统一
    physics = setting["FileReferences"].get("Physics")
    if physics:
        shutil.copyfile(os.path.join(src, physics), os.path.join(out, "physics3.json"))

    # 贴图：缩到长边 2048，改名为 textures/tN.png
    textures = []
    for index, relative in enumerate(setting["FileReferences"]["Textures"]):
        path = os.path.join(src, relative)
        width, height = png_size(path)
        image = Image.open(path).convert("RGBA")
        scale = min(1.0, float(MAX_SIDE) / max(width, height))
        if scale < 1.0:
            image = image.resize((max(1, int(width * scale)), max(1, int(height * scale))),
                                 Image.LANCZOS)
        target = "textures/t%d.png" % index
        image.save(os.path.join(out, target), optimize=True)
        textures.append(target)
        print("  贴图 %d %dx%d -> %dx%d %.1fMB" % (
            index, width, height, image.width, image.height,
            os.path.getsize(os.path.join(out, target)) / 1e6))

    setting["FileReferences"]["Moc"] = "model.moc3"
    setting["FileReferences"]["Textures"] = textures
    if physics:
        setting["FileReferences"]["Physics"] = "physics3.json"
    setting["FileReferences"].pop("DisplayInfo", None)   # 应用不读，省得再拷一份

    with open(os.path.join(out, "model3.json"), "w", encoding="utf-8") as f:
        json.dump(setting, f, ensure_ascii=False, indent=1)

    # 表情：只挑 .exp3.json，编号 e0..eN，名字进 index.txt
    exp_lines = []
    for file in sorted(os.listdir(src)):
        if not file.endswith(".exp3.json"):
            continue
        short = file[:-len(".exp3.json")]
        target = "e%d.exp3.json" % len(exp_lines)
        shutil.copyfile(os.path.join(src, file), os.path.join(out, "exp", target))
        exp_lines.append("%s\texp/%s" % (short, target))

    # 动作：Scene1 是那个包唯一的动作（2.667s 循环，呼吸/耳朵类的微动），当 IDLE 用。
    # 标准模型没有成套动作库，待机的活气靠渲染层的正弦编排 + 视线扫视，不靠关键帧。
    motion_lines = []
    for file in sorted(os.listdir(src)):
        if not file.endswith(".motion3.json"):
            continue
        with open(os.path.join(src, file), encoding="utf-8") as f:
            motion = json.load(f)
        # 只收循环型、参数曲线够多的：那才是「待机」，一次性的道具动画收进来也没词喊它
        if not motion.get("Meta", {}).get("Loop"):
            continue
        target = "m0.motion3.json"
        shutil.copyfile(os.path.join(src, file), os.path.join(out, "motion", target))
        motion_lines.append("IDLE\tmotion/%s" % target)
        print("  动作 %s -> IDLE（%.2fs，%d 条曲线）" % (
            file, motion["Meta"]["Duration"], len(motion["Curves"])))

    with open(os.path.join(out, "index.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("[meta]\n")
        # title 只给调参台看（模型切换按钮上的中文名）。原生层只认 profile，其余忽略。
        f.write("title\t%s\n" % title)
        f.write("profile\t%s\n" % profile)
        # 水印这类图层：缺省可见，加载后就按 drawable 关掉。
        # 表里写的是 cdi3 的中文名，这里换成 Part18 这种模型内部 Id —— 渲染器
        # 是按 Id 找 part 的，中文名只是给人看的。
        cdi_path = os.path.join(src, os.path.basename(src) + ".cdi3.json")
        if os.path.exists(cdi_path):
            with open(cdi_path, encoding="utf-8") as handle:
                cdi = json.load(handle)
            by_name = {p.get("Name"): p.get("Id") for p in cdi.get("Parts", [])}
            hidden = [by_name[name] for name in HIDE_PARTS if name in by_name]
            if hidden or HIDE_DRAWS.get(name):
                f.write("[hide]\n")
                for part_id in hidden:
                    f.write(part_id + "\n")
                for drawable in HIDE_DRAWS.get(name, []):
                    f.write("draw %d\n" % drawable)
                print("  隐藏部件 %s%s" % (
                    ", ".join(hidden),
                    "，drawable " + ",".join(str(d) for d in HIDE_DRAWS.get(name, []))
                    if HIDE_DRAWS.get(name) else ""))
        f.write("[exp]\n")
        for line in exp_lines:
            f.write(line + "\n")
        f.write("[motion]\n")
        for line in motion_lines:
            f.write(line + "\n")

    total = sum(os.path.getsize(os.path.join(root, file))
                for root, _, files in os.walk(out) for file in files)
    print("  => %s %.1fMB，表情 %d，动作 %d" % (name, total / 1e6, len(exp_lines), len(motion_lines)))


def main():
    for name, source, profile in MODELS:
        print("==", name)
        pack(name, source, profile, title)
    print("完成")


if __name__ == "__main__":
    main()

# 把模型搬进 APK 的 assets/model，顺便把三件事处理掉：
#
#   1. **贴图缩到 1/4**。原图最大 8192×8192，Adreno 304（MSM8909）的 GL_MAX_TEXTURE_SIZE
#      撑不到那里，传上去不报错、只是白屏缺块。1/4 正好等于之前量过的「2048 档」，
#      PSNR 38.4dB，肉眼分不出；显存从 155M 像素降到 9.7M（约 29MB RGBA）。
#   2. **表情/动作文件改成 ASCII 名字**，中文原名写进 index.txt。AAsset 走的是 zip 里的
#      UTF-8 路径，中文名在某些打包链上会被转义坏掉，索引文件把这件事彻底绕开。
#   3. **重写 model3.json**：贴图路径改成 textures/tN.png，并且保留 EyeBlink 组——
#      CubismEyeBlink 靠这个组找眼皮参数，丢了就不会眨眼。
#   4. **把 GLSL 一起打进来**。Cubism 5-r.5 的着色器是外部文件，程序里没有内嵌源码；
#      漏了这一堆 .vert/.frag，渲染会静默失败，屏幕上只剩纯黑，连日志都只有一句
#      "Failed to load vertex shader"。注意要拿 **StandardES** 那一份（#version 100），
#      Standard 是桌面用的 #version 120，GLES2 编不过。注意要拿 **StandardES** 那一份（#version 100），
#      Standard 是桌面用的 #version 120，GLES2 编不过。
import json
import os
import shutil
import sys

from PIL import Image

SRC = r"C:\Users\Administrator\大肥鱼插件\model"
SDK = r"C:\Users\Administrator\大肥鱼插件\sdk\CubismSdkForNative-5-r.5"
SHADER_SRC = os.path.join(SDK, r"Framework\src\Rendering\OpenGL\Shaders\StandardES")
DST = r"C:\android\amber\assets\model"
SCALE = 4      # 1/4；改成 2 就是 1/2（约 116MB 显存，这台机器别试）


def main():
    if os.path.isdir(DST):
        shutil.rmtree(DST)
    os.makedirs(os.path.join(DST, "textures"))
    os.makedirs(os.path.join(DST, "exp"))
    os.makedirs(os.path.join(DST, "motion"))

    moc = [f for f in os.listdir(SRC) if f.endswith(".moc3")][0]
    physics = [f for f in os.listdir(SRC) if f.endswith(".physics3.json")][0]
    shutil.copy(os.path.join(SRC, moc), os.path.join(DST, "model.moc3"))
    shutil.copy(os.path.join(SRC, physics), os.path.join(DST, "physics3.json"))
    print("moc3  %.1f MB" % (os.path.getsize(os.path.join(DST, "model.moc3")) / 1048576))

    with open(os.path.join(SRC, "大肥鱼.model3.json"), encoding="utf-8") as handle:
        original = json.load(handle)

    textures = []
    total = 0
    for i, name in enumerate(original["FileReferences"]["Textures"]):
        source = os.path.join(SRC, name)
        image = Image.open(source).convert("RGBA")
        out_size = (max(1, image.width // SCALE), max(1, image.height // SCALE))
        image = image.resize(out_size, Image.LANCZOS)
        out = os.path.join(DST, "textures", "t%d.png" % i)
        image.save(out, optimize=True)
        total += os.path.getsize(out)
        textures.append("textures/t%d.png" % i)
        print("  t%d  %dx%d -> %dx%d  %.1f MB" % (i, image.width * SCALE, image.height * SCALE,
                                                  image.width, image.height, os.path.getsize(out) / 1048576))
    print("贴图合计 %.1f MB" % (total / 1048576))

    index = []
    index.append("[exp]")
    for i, name in enumerate(sorted(os.listdir(os.path.join(SRC, "Exp3")))):
        if not name.endswith(".exp3.json"):
            continue
        short = "e%d.exp3.json" % i
        shutil.copy(os.path.join(SRC, "Exp3", name), os.path.join(DST, "exp", short))
        index.append("%s\t%s" % (name[:-len(".exp3.json")], "exp/" + short))

    index.append("[motion]")
    for i, name in enumerate(sorted(os.listdir(os.path.join(SRC, "Animation")))):
        if not name.endswith(".motion3.json"):
            continue
        short = "m%d.motion3.json" % i
        shutil.copy(os.path.join(SRC, "Animation", name), os.path.join(DST, "motion", short))
        index.append("%s\t%s" % (name[:-len(".motion3.json")], "motion/" + short))

    with open(os.path.join(DST, "index.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(index) + "\n")

    # 着色器：Cubism 的 Android 分支传进来的是纯文件名，jni 侧统一加 model/shaders/ 前缀。
    shader_dir = os.path.join(DST, "shaders")
    os.makedirs(shader_dir)
    shaders = 0
    for name in sorted(os.listdir(SHADER_SRC)):
        if not name.endswith((".vert", ".frag")):
            continue
        # Tegra 变体只在 SetExtMode(true) 时才用，默认 false；拷进来也没人读。
        if "Tegra" in name:
            continue
        # Tegra 变体只在 SetExtMode(true) 时才用，默认 false；拷进来也没人读。
        if "Tegra" in name:
            continue
        shutil.copy(os.path.join(SHADER_SRC, name), os.path.join(shader_dir, name))
        shaders += 1
    print("着色器 %d 个" % shaders)

    model3 = {
        "Version": 3,
        "FileReferences": {
            "Moc": "model.moc3",
            "Textures": textures,
            "Physics": "physics3.json",
        },
        "Groups": [
            {"Target": "Parameter", "Name": "EyeBlink", "Ids": ["ParamEyeROpen", "ParamEyeLOpen"]},
            {"Target": "Parameter", "Name": "LipSync", "Ids": []},
        ],
    }
    with open(os.path.join(DST, "model3.json"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(model3, handle, ensure_ascii=False, indent=1)

    print("表情 %d，动作 %d" % (len(os.listdir(os.path.join(DST, "exp"))),
                               len(os.listdir(os.path.join(DST, "motion")))))

    pixels = 0
    for i, name in enumerate(textures):
        with Image.open(os.path.join(DST, name)) as image:
            pixels += image.width * image.height
    print("显存估算 %.0f MB（RGBA）" % (pixels * 4 / 1048576))

    size = 0
    for root, _, files in os.walk(DST):
        for name in files:
            size += os.path.getsize(os.path.join(root, name))
    print("assets/model 合计 %.1f MB" % (size / 1048576))


if __name__ == "__main__":
    sys.exit(main())

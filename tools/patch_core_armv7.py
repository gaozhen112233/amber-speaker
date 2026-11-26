# 把树莓派版 Cubism Core 静态库里对 libm 的四个引用改名成 __amber_hf_*。
#
# 为什么要改：那份库是硬浮点（-mfloat-abi=hard）编的，而 Android 的 libm 是 softfp。
# 它调用 sincosf/atan2f/floorf/powf 时按 VFP 寄存器传参数，libm 却按 r0 读，
# 结果旋转变形器（头、手臂、尾巴）全塌掉。详见 jni/src/LibmHardFloatArmv7.cpp 开头。
#
# 为什么不直接顶掉系统符号：Framework 自己也在调 atan2f / floorf / powf，
# 它是 softfp 编的，顶掉会把 Framework 弄坏。改名之后库去找 __amber_hf_*（我们提供
# 硬浮点壳），Framework 继续用系统的 softfp 版。
#
# 用法：
#   python tools/patch_core_armv7.py          # 从 .orig.a 生成补丁版 .a
#   python tools/patch_core_armv7.py --check   # 只检查现状
#
# 输入是 jni/local/libLive2DCubismCore_armv7_vfp.orig.a（从 SDK 的
# Core/lib/experimental/rpi/libLive2DCubismCore.a 原样拷来的，没有动过），
# 输出是 libLive2DCubismCore_armv7_vfp.a（构建实际链接的那份）。
# 这样换机器、换 SDK 都能重放，不依赖谁记得手动跑过什么命令。

import os
import shutil
import subprocess
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCAL = os.path.join(PROJ, "jni", "local")
SRC = os.path.join(LOCAL, "libLive2DCubismCore_armv7_vfp.orig.a")
DST = os.path.join(LOCAL, "libLive2DCubismCore_armv7_vfp.a")

SDK_ORIGIN = r"E:\toolchain\sdk\Core\lib\experimental\rpi\libLive2DCubismCore.a"

# 库里对这四者的引用改成带前缀的名字，由 LibmHardFloatArmv7.cpp 提供硬浮点实现
RENAMES = [
    ("atan2f", "__amber_hf_atan2f"),
    ("floorf", "__amber_hf_floorf"),
    ("powf", "__amber_hf_powf"),
    ("sincosf", "__amber_hf_sincosf"),
]


def find_llvm(tool):
    ndk = os.environ.get("AMBER_NDK", r"E:\toolchain\ndkx\android-ndk-r26d")
    path = os.path.join(ndk, "toolchains", "llvm", "prebuilt", "windows-x86_64", "bin", tool)
    if os.path.exists(path):
        return path
    found = shutil.which(tool)
    if found:
        return found
    sys.exit("找不到 %s，设置环境变量 AMBER_NDK 指向 NDK 目录" % tool)


def symbols(archive):
    out = subprocess.run([find_llvm("llvm-nm.exe"), "-u", archive],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = out.stdout.decode("utf-8", "ignore")
    names = set()
    for line in text.splitlines():
        if " U " in line:
            names.add(line.split(" U ", 1)[1].strip())
    return names


def main():
    if not os.path.exists(SRC):
        if not os.path.exists(SDK_ORIGIN):
            sys.exit("原始库不见了：%s（也找不到 SDK 里的 %s）" % (SRC, SDK_ORIGIN))
        shutil.copyfile(SDK_ORIGIN, SRC)
        print("已从 SDK 拷入原始库 -> %s" % SRC)

    original = symbols(SRC)
    missing = [old for old, _ in RENAMES if old not in original]
    if missing:
        sys.exit("原始库里没有这些符号，说明不是同一份库：%s" % ", ".join(missing))

    if "--check" in sys.argv:
        now = symbols(DST) if os.path.exists(DST) else set()
        left = [old for old, _ in RENAMES if old in now]
        done = [new for _, new in RENAMES if new in now]
        print("当前产物引用系统 libm 的还有：%s" % (", ".join(left) or "无"))
        print("已改名指向 __amber_hf_* 的：%s" % (", ".join(done) or "无"))
        return

    shutil.copyfile(SRC, DST)
    args = [find_llvm("llvm-objcopy.exe")]
    for old, new in RENAMES:
        args += ["--redefine-sym", "%s=%s" % (old, new)]
    args.append(DST)
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if p.returncode != 0:
        sys.exit("objcopy 失败：%s" % p.stdout.decode("utf-8", "ignore"))

    after = symbols(DST)
    for old, new in RENAMES:
        assert old not in after, "改名没生效：%s" % old
        assert new in after, "没找到新名字：%s" % new
    print("已生成 %s（%d 字节）" % (DST, os.path.getsize(DST)))
    print("库现在引用：%s" % ", ".join(new for _, new in RENAMES))


if __name__ == "__main__":
    main()

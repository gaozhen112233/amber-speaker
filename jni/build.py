# 原生库的构建入口。和 build.py 分开：那边改一行 Java 两秒出包，这边动了 C++ 才需要跑，
# 一次十几秒。产物是 jni/build/libambergl.so，由 build.py 塞进 APK。
#
# 工具链自动探测（别人的 ndk 不一定装在作者那个路径）：
#   NDK   ：ANDROID_NDK_HOME / ANDROID_NDK_ROOT，或 <SDK>/ndk/<版本>，或 PATH 里的 ndk-build
#   CMake ：PATH 里的 cmake，或 <SDK>/cmake/<版本>/bin/cmake
#   Ninja ：PATH 里的 ninja，或 <SDK>/cmake/<版本>/bin/ninja
import glob
import os
import shutil
import subprocess
import sys

PROJ = os.path.dirname(os.path.abspath(__file__))
IS_WIN = os.name == "nt"


def exe(name):
    return name + ".exe" if IS_WIN else name


def first_file(paths):
    for p in paths:
        if p and os.path.isfile(p):
            return p
    return ""


def sdk_dir():
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(key)
        if v and os.path.isdir(v):
            return v
    for p in (r"C:\android\sdk",
              os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk"),
              os.path.expanduser("~/Android/Sdk"),
              os.path.expanduser("~/Library/Android/sdk"),
              "/opt/android-sdk"):
        if os.path.isdir(p):
            return p
    return ""


def find_ndk():
    for key in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        v = os.environ.get(key)
        if v and os.path.isdir(v):
            return v
    sdk = sdk_dir()
    if sdk:
        cands = sorted(glob.glob(os.path.join(sdk, "ndk", "*")))
        if cands:
            return cands[-1]
    return ""


def find_cmake():
    found = shutil.which("cmake")
    if found:
        return found
    sdk = sdk_dir()
    if sdk:
        found = first_file(sorted(glob.glob(os.path.join(sdk, "cmake", "*", "bin", exe("cmake")))))
        if found:
            return found
    return ""


def find_ninja_dir():
    found = shutil.which("ninja")
    if found:
        return os.path.dirname(found)
    sdk = sdk_dir()
    if sdk:
        cands = sorted(glob.glob(os.path.join(sdk, "cmake", "*", "bin", exe("ninja"))))
        if cands:
            return os.path.dirname(cands[-1])
    return ""


NDK = find_ndk()
CMAKE = find_cmake()
NINJA = find_ninja_dir()
if not NDK:
    sys.exit("找不到 Android NDK。装 NDK（r23+），再 export ANDROID_NDK_HOME=<ndk 路径>。")
if not CMAKE:
    sys.exit("找不到 cmake。装 CMake 3.22+，或用 sdkmanager 装 cmake。")
LLVM = os.path.join(NDK, "toolchains", "llvm", "prebuilt",
                    "windows-x86_64" if IS_WIN else
                    ("darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"), "bin")

print("NDK   : %s" % NDK)
print("CMake : %s" % CMAKE)
if NINJA:
    print("Ninja : %s" % NINJA)


def run(args):
    print("  $ " + " ".join(args)[:170], flush=True)
    env = dict(os.environ)
    path = []
    if NINJA:
        path.append(NINJA)
    if os.path.isdir(LLVM):
        path.append(LLVM)
    env["PATH"] = os.pathsep.join(path + [env.get("PATH", "")])
    process = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = process.stdout.decode("utf-8", "ignore")
    if process.returncode != 0:
        # 只留错误和它前后几行，否则几十个目标的编译日志会把人埋掉
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if "error" in line.lower() or "FAILED" in line:
                print("\n".join(lines[max(0, i - 2):i + 6]))
                print("-" * 60)
        sys.exit(process.returncode)
    return text


def main():
    build = os.path.join(PROJ, "build")
    if not os.path.isdir(build):
        run([CMAKE, "-G", "Ninja", "-S", PROJ, "-B", build,
             "-DCMAKE_TOOLCHAIN_FILE=%s" % os.path.join(
                 NDK, "build", "cmake", "android.toolchain.cmake").replace("\\", "/"),
             "-DANDROID_ABI=armeabi-v7a",
             "-DANDROID_PLATFORM=android-21",
             "-DANDROID_STL=c++_static",
             "-DCMAKE_BUILD_TYPE=Release"])
    run([CMAKE, "--build", build])
    so = os.path.join(build, "libambergl.so")
    print("\n=== 产物 ===")
    print("  %s  (%.0f KB)" % (so, os.path.getsize(so) / 1024))


if __name__ == "__main__":
    main()

import glob
import os
import shutil
import subprocess
import sys
import zipfile

# 构建链沿用探针那一套：不装 Gradle，直接 aapt2 -> javac -> d8 -> zipalign -> apksigner。
# 好处是依赖只有 JDK 和 SDK，产物 20KB 级；坏处是没有增量编译，改一行也要全编。
# 现在这个规模（几十个源文件）全编也就几秒，不值得为增量引一整套 Gradle。
#
# 路径全部自动探测，别人克隆下来只要装了 Android SDK + JDK 就能 build：
#   SDK：ANDROID_HOME / ANDROID_SDK_ROOT，或下面的常见安装位置
#   JDK：JAVA_HOME，或 PATH 里的 javac，或 C:\android\jdk17
# 要指定就 export ANDROID_HOME=... / JAVA_HOME=...
PROJ = os.path.dirname(os.path.abspath(__file__))
IS_WIN = os.name == "nt"


def tool(name):
    """Windows 上要带 .exe，其它平台不带。"""
    return name + ".exe" if IS_WIN else name


def first_dir(paths):
    for p in paths:
        if p and os.path.isdir(p):
            return p
    return ""


def find_sdk():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v and os.path.isdir(v):
            return v
    return first_dir([
        r"C:\android\sdk",
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk"),
        os.path.expanduser("~/Android/Sdk"),
        os.path.expanduser("~/Library/Android/sdk"),
        "/opt/android-sdk",
        "/usr/lib/android-sdk",
    ])


def find_jdk():
    v = os.environ.get("JAVA_HOME")
    if v and os.path.isfile(os.path.join(v, "bin", tool("javac"))):
        return v
    javac = shutil.which("javac")
    if javac:
        return os.path.dirname(os.path.dirname(os.path.abspath(javac)))
    return first_dir([r"C:\android\jdk17", r"C:\Program Files\Java\jdk-17",
                      r"C:\Program Files\Android\Android Studio\jbr"])


SDK = find_sdk()
JDK = find_jdk()
if not SDK:
    sys.exit("找不到 Android SDK。装 platform-tools + build-tools + platforms，"
             "然后 export ANDROID_HOME=<sdk 路径>。")
if not JDK:
    sys.exit("找不到 JDK（需要 javac）。装 JDK 17，然后 export JAVA_HOME=<jdk 路径>。")

# build-tools / platform 取版本号最高的那个，不写死 34.0.0
_bt = sorted(glob.glob(os.path.join(SDK, "build-tools", "*")),
             key=lambda p: [int(x) for x in os.path.basename(p).split(".") if x.isdigit()])
if not _bt:
    sys.exit("SDK 里没有 build-tools。用 sdkmanager 装一个（aapt2 / d8 / zipalign / apksigner）。")
BT = _bt[-1]
_plat = sorted(glob.glob(os.path.join(SDK, "platforms", "android-*")),
               key=lambda p: int(p.rsplit("-", 1)[-1]) if p.rsplit("-", 1)[-1].isdigit() else 0)
if not _plat:
    sys.exit("SDK 里没有 platforms/android-*。用 sdkmanager 装 platforms;android-33。")
AJAR = os.path.join(_plat[-1], "android.jar")
APKSIGNER = os.path.join(BT, "apksigner.bat" if IS_WIN else "apksigner")
OUT = os.path.join(PROJ, "build")

print("SDK       : %s" % SDK)
print("build-tools: %s" % os.path.basename(BT))
print("platform  : %s" % os.path.basename(_plat[-1]))
print("JDK       : %s" % JDK)

env = dict(os.environ)
env["JAVA_HOME"] = JDK
env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")


def sh(args, cwd=None):
    printable = " ".join('"%s"' % a if " " in a else a for a in args)
    print("  $ " + printable[:170], flush=True)
    p = subprocess.run(args, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.decode("utf-8", "ignore").strip()
    if out:
        for line in out.splitlines()[-30:]:
            print("    " + line, flush=True)
    if p.returncode != 0:
        print("\n!! 失败: %s" % printable, flush=True)
        sys.exit(p.returncode)
    return out


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    classes = os.path.join(OUT, "classes")
    dexdir = os.path.join(OUT, "dex")
    os.makedirs(classes)
    os.makedirs(dexdir)

    unsigned = os.path.join(OUT, "unsigned.apk")
    aligned = os.path.join(OUT, "aligned.apk")
    final = os.path.join(PROJ, "amber-speaker.apk")

    print("\n[1/6] aapt2 link", flush=True)
    sh([os.path.join(BT, tool("aapt2")), "link",
        "-o", unsigned,
        "-I", AJAR,
        "-A", os.path.join(PROJ, "assets"),      # 模型：moc3 + 贴图 + 表情 + 动作（19MB）
        "--manifest", os.path.join(PROJ, "AndroidManifest.xml"),
        "--min-sdk-version", "21",
        "--target-sdk-version", "30",
        "--version-code", "1",
        "--version-name", "0.2"])

    print("\n[2/6] javac (Java 8 字节码)", flush=True)
    srcs = []
    for root, _, files in os.walk(os.path.join(PROJ, "src")):
        for f in files:
            if f.endswith(".java"):
                srcs.append(os.path.join(root, f))
    print("    源文件 %d 个" % len(srcs), flush=True)
    sh([os.path.join(JDK, "bin", tool("javac")),
        "-source", "8", "-target", "8",
        "-encoding", "UTF-8",
        "-nowarn", "-Xlint:-options",
        "-classpath", AJAR,
        "-d", classes] + srcs)

    print("\n[3/6] d8 -> dex", flush=True)
    cls = glob.glob(os.path.join(classes, "**", "*.class"), recursive=True)
    print("    class 文件 %d 个" % len(cls), flush=True)
    # 直接用 java 调 d8.jar，不走 d8.bat：class 一多（现在 114 个）时，d8.bat 里
    # `set params=%params% %1` 的 cmd 批处理变量累加会在某个含 $ 的参数上出岔子，
    # 报一个被 GBK 糊成乱码的错、returncode 非零，其实 d8 编译器本身一点问题没有。
    sh([os.path.join(JDK, "bin", tool("java")),
        "-Xmx1024M", "-Xss1m",
        "-cp", os.path.join(BT, "lib", "d8.jar"),
        "com.android.tools.r8.D8",
        "--lib", AJAR,
        "--min-api", "21",
        "--output", dexdir] + cls)

    print("\n[4/6] 塞进 apk", flush=True)
    dex = os.path.join(dexdir, "classes.dex")
    if not os.path.exists(dex):
        print("!! 没有 classes.dex", flush=True)
        sys.exit(1)
    so = os.path.join(PROJ, "jni", "build", "libambergl.so")
    if not os.path.exists(so):
        print("!! 没有 libambergl.so，先跑 jni/build.py", flush=True)
        sys.exit(1)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")
    # .so 要 STORED（不压缩）+ 后面 zipalign -p 做页对齐，否则 5.1 装得上但加载会失败
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_STORED) as z:
        z.write(so, "lib/armeabi-v7a/libambergl.so")
    print("    classes.dex %.0f KB, libambergl.so %.0f KB" % (
        os.path.getsize(dex) / 1024, os.path.getsize(so) / 1024), flush=True)

    print("\n[5/6] zipalign（-p 让 .so 页对齐）", flush=True)
    sh([os.path.join(BT, tool("zipalign")), "-f", "-p", "4", unsigned, aligned])

    print("\n[6/6] 签名（v1 + v2）", flush=True)
    # 这里踩过一次，记清楚：
    #   Android 5.1 校验的是 v1（JAR 签名），v2 是 API 24+ 才有的机制，5.1 根本不去看它。
    #   所以「5.1 只认 v1」的正确理解是【必须有 v1】，不是【只能有 v1】。
    #   只签 v1 的话，apksigner verify 会因为 targetSdk 30 报
    #   "requires a minimum of signature scheme v2"，而新系统上装也会被拒。
    #   两个都签：5.1 用 v1，新系统用 v2，各取所需。v3 用不到，关掉。
    ks = os.path.join(PROJ, "debug.keystore")
    if not os.path.exists(ks):
        sh([os.path.join(JDK, "bin", tool("keytool")),
            "-genkeypair", "-v", "-keystore", ks,
            "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=Android Debug,O=Android,C=US"])
    sh([APKSIGNER, "sign",
        "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "false",
        "--out", final, aligned])

    print("\n=== 产物 ===", flush=True)
    print("  %s  (%.0f KB)" % (final, os.path.getsize(final) / 1024), flush=True)

    print("\n=== 校验 ===", flush=True)
    info = sh([os.path.join(BT, tool("aapt2")), "dump", "badging", final])
    for line in info.splitlines():
        if line.startswith(("package:", "sdkVersion", "targetSdkVersion",
                            "application-label", "launchable-activity")):
            print("  " + line, flush=True)
    ver = sh([APKSIGNER, "verify", "--print-certs", final])
    for line in ver.splitlines():
        if "Signer #1" in line or "v1 scheme" in line or "v2 scheme" in line:
            print("  " + line.strip(), flush=True)


if __name__ == "__main__":
    main()

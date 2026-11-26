# 装到音箱上并盯日志。设备用 USB 连好、adb 认到了再跑。
#
# 判据分三层，出问题时按顺序看：
#   1. AmberGL  「解析 xx ms」「建纹理 xx ms -> true」  —— 模型有没有起来
#   2. AmberGL  「贴图 0 1024x2048（上限 4096）」      —— 显卡吃不吃得下这个尺寸
#   3. AmberCue 「set ParamAngleX ...」                —— Body 的命令有没有真的发出去
import os
import subprocess
import sys

ADB = r"C:\android\sdk\platform-tools\adb.exe"
APK = r"C:\android\amber\amber-speaker.apk"


def run(args, **kwargs):
    return subprocess.run(args, **kwargs)


def main():
    devices = run([ADB, "devices"], stdout=subprocess.PIPE).stdout.decode("utf-8", "ignore")
    lines = [l for l in devices.splitlines() if l.strip() and "List of" not in l]
    if not lines:
        print("没有设备。先用 USB 连上音箱，确认 adb devices 能看到它。")
        return 1
    print("设备：", "; ".join(lines))

    print("\n[1/3] 安装")
    code = run([ADB, "install", "-r", APK]).returncode
    if code != 0:
        print("安装失败（常见原因：签名不同，先 adb uninstall com.amber.speaker 再装）")
        return code

    print("\n[2/3] 清日志并启动")
    run([ADB, "logcat", "-c"])
    run([ADB, "shell", "am", "start", "-n", "com.amber.speaker/.MainActivity"])

    print("\n[3/3] 盯 25 秒日志（Ctrl+C 退出）")
    run([ADB, "logcat", "-v", "time", "-s", "AmberGL:V", "AmberCue:V", "AndroidRuntime:E"],
        timeout=25)
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""在真机上验证口型：拍一张闭嘴的基准，再边说边连拍几张，比面部区域有没有变化。

为什么要连拍而不是只拍一张：嘴型是 2.3Hz 的正弦，单张截图很可能正好落在闭合点，
那样看起来就像「没动」。连拍 4 张、两两比较，只有全都不变才是真的没动。
"""
import os
import subprocess
import sys
import time
import urllib.request

# 设备地址默认只是占位，用之前请设环境变量：
#   AMBER_DEVICE=<音箱IP>:8788   AMBER_ADB=<音箱IP>:5555
_DEV = os.environ.get("AMBER_DEVICE", "192.168.1.100:8788")
_DEV = _DEV.replace("http://", "").rstrip("/")
BASE = "http://" + _DEV
IP = os.environ.get("AMBER_ADB", _DEV.split(":")[0] + ":5555")
ADB = os.environ.get("ADB") or r"C:\android\sdk\platform-tools\adb.exe"
DISPLAY = "4630947232161729154"
OUT = r"C:\android\amber\out"


def adb(*args, binary=False):
    cmd = [ADB] + list(args)
    r = subprocess.run(cmd, capture_output=True)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def connect():
    adb("connect", IP)
    time.sleep(2)


def shot(name):
    path = os.path.join(OUT, name)
    data = adb("exec-out", "screencap", "-p", "-d", DISPLAY, binary=True)
    if not data.startswith(b"\x89PNG"):
        raise SystemExit("截图不是 PNG（%d 字节）：%r" % (len(data), data[:40]))
    with open(path, "wb") as f:
        f.write(data)
    return path


def say(text, query=""):
    req = urllib.request.Request(BASE + "/say" + query, data=text.encode("utf-8"),
                                 headers={"Content-Type": "text/plain; charset=utf-8"})
    return urllib.request.urlopen(req, timeout=5).read().decode()


def state():
    return urllib.request.urlopen(BASE + "/state", timeout=5).read().decode()


def main():
    connect()
    print("state:", state())
    shot("mouth_idle.png")
    print("基准已拍")

    # 够长，能撑住 4 秒以上的说话时间
    text = "〔歪头〕今天天气真好呀，我们一起去海边散步好不好，我想看看浪花"
    print("say:", say(text))
    paths = []
    for i in range(4):
        paths.append(shot("mouth_talk_%d.png" % i))
        time.sleep(0.55)
    print("state:", state())
    print("\n".join(paths))


if __name__ == "__main__":
    main()

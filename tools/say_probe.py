"""在真机上跑一遍 /say 的排队、打断、过期，以及启动耗时。

为什么必须在真机上跑：这三条语义在 Java 单测里都对，但单测里的时钟是假的。
真机上「主线程 post 延迟」「Choreographer 首帧什么时候来」「GL 线程什么时候 ready」
这三件事叠在一起，才决定打断到底是不是当场生效。
"""
import json
import re
import subprocess
import time
import urllib.error
import urllib.request

ADB = r"C:\android\sdk\platform-tools\adb.exe"
IP = "192.168.31.168:35361"
BASE = "http://192.168.31.168:8788"


def adb(*args):
    return subprocess.run([ADB] + list(args), capture_output=True).stdout.decode("utf-8", "replace")


def connect():
    adb("connect", IP)
    time.sleep(2)


def say(text, query=""):
    req = urllib.request.Request(BASE + "/say" + query, data=text.encode("utf-8"),
                                 headers={"Content-Type": "text/plain; charset=utf-8"})
    return json.loads(urllib.request.urlopen(req, timeout=5).read().decode())


def state():
    return json.loads(urllib.request.urlopen(BASE + "/state", timeout=5).read().decode())


def clear_log():
    adb("logcat", "-c")


def find(pattern):
    out = adb("logcat", "-d", "-v", "time", "-s", "AmberCue")
    return [line for line in out.splitlines() if re.search(pattern, line)]


def main():
    connect()
    adb("install", "-r", "-t", r"C:/android/amber/amber-speaker.apk")
    clear_log()
    adb("shell", "am", "start", "-n", "com.amber.speaker/.MainActivity")
    time.sleep(12)

    # ---- 启动耗时：系统自己记的 Displayed，是我们能拿到的最权威的数 ----
    out = adb("logcat", "-d", "-v", "time")
    for line in out.splitlines():
        if "Displayed com.amber.speaker" in line:
            print("启动：", line.split("): ", 1)[-1])

    print("初始 state:", state())

    # ---- 排队：连发三句，看 queued 累加 ----
    print("\n=== 排队 ===")
    for i in range(3):
        say("第 %d 句测试排队，这句话够长能占住一阵子。" % (i + 1))
    time.sleep(0.3)
    print("连发三句后:", state())

    # ---- 打断：立刻再发一句 interrupt ----
    print("\n=== 打断 ===")
    before = state()
    say("打断你，现在说这句！", "?interrupt=1")
    time.sleep(0.4)
    after = state()
    print("打断前 queued=%s，打断后 queued=%s" % (before["queued"], after["queued"]))
    dropped = find("打断：丢弃待说")
    print("设备日志:", dropped[-1].split("): ")[-1] if dropped else "（没打）")

    # ---- 过期：排一批 1 秒寿命的，等它们超时 ----
    print("\n=== 过期 ===")
    say("先来一句长的占住她，这句话要说得久一点才好观察后面的过期。")
    time.sleep(0.5)
    for i in range(3):
        say("短命第 %d 句" % (i + 1), "?ttl=1")
    time.sleep(0.2)
    print("刚排进去:", state())
    time.sleep(6)
    print("六秒后:", state())

    # ---- 响应字段 ----
    print("\n=== 响应 ===")
    r = say("带参数的这一句", "?interrupt=1&ttl=2.5")
    print("查询串响应:", r)

    print("\n最终 state:", state())


if __name__ == "__main__":
    main()

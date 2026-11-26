"""给音箱对时间。

为什么需要这个：这台机器的 **RTC 没有电池**，断电后系统时间从出厂基准起算
（实测固定停在 1977-10-30 05:0x），断网时也拉不到 NTP，于是「现在几点」永远是凌晨。
坏时间会误导两套逻辑 —— 00:00–08:00 的熄屏休眠（表现为「用着用着灯灭了」）和
08:40 / 23:59 的定点播报。设备端有闸门（Clock：时间不可信就不熄屏、不播报），
但想让她真的按作息运行，还是得把真实时间推过去。

用法：
    python tools/clock_sync.py              # 通过 adb forward 推一次
    python tools/clock_sync.py --watch 300  # 每 300 秒推一次（常开）
    python tools/clock_sync.py --host 192.168.1.100:8788   # 走局域网直连（AstrBot 侧）

设备端只记偏移量、不改系统时间（改系统时间要 root，Magisk 在这台机器上静默拒绝），
所以这个脚本可以随时重复跑，没有副作用。
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.request

ADB = r"C:\android\sdk\platform-tools\adb.exe"
LOCAL_PORT = 18788
DEVICE_PORT = 8788


def adb(*args):
    return subprocess.run([ADB] + list(args), capture_output=True).stdout.decode("utf-8", "replace")


def forward():
    """把设备的 8788 映射到本机 18788。已映射过会报错，忽略即可。"""
    adb("forward", "tcp:%d" % LOCAL_PORT, "tcp:%d" % DEVICE_PORT)


def http(base, path, data=None, timeout=6):
    url = base + path
    body = None
    if data is not None:
        body = data.encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST" if body is not None else "GET")
    if body is not None:
        req.add_header("Content-Type", "text/plain; charset=utf-8")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8", "replace")


def push(base):
    now = int(time.time())
    status, text = http(base, "/clock?epoch=%d" % now, data="")
    print("[%s] 推 %s -> %s %s" % (time.strftime("%H:%M:%S"), now, status, text.strip()))
    return status == 200


def state(base):
    status, text = http(base, "/state")
    try:
        return json.loads(text)
    except ValueError:
        return {}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=None, help="设备地址，如 192.168.1.100:8788；不给就走 adb")
    parser.add_argument("--watch", type=int, default=0, help="每隔多少秒推一次；0 表示只推一次")
    args = parser.parse_args()

    if args.host:
        base = "http://" + args.host
    else:
        forward()
        base = "http://127.0.0.1:%d" % LOCAL_PORT

    if not push(base):
        sys.exit(1)

    st = state(base)
    if st:
        print("设备时间：%s 分钟（clock_ok=%s, screen=%s）"
              % (st.get("minute"), st.get("clock_ok"), st.get("screen")))

    if args.watch <= 0:
        return
    while True:
        time.sleep(args.watch)
        try:
            push(base)
        except Exception as e:  # 设备重启 / 断线都当临时故障，下一轮再试
            print("推送失败：%s" % e)


if __name__ == "__main__":
    main()

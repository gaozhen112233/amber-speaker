# -*- coding: utf-8 -*-
"""solo 诊断：只绘制单个 drawable，把结果拼成一张对照图。

用途：下巴那块灰白圆到底是谁画的。逐块 solo 一眼就能认出来。
"""
import json
import sys
import subprocess
import time
import urllib.request

from PIL import Image, ImageDraw

ADB = "C:/android/sdk/platform-tools/adb.exe"
DEV = "192.168.31.43:5555"
BASE = "http://192.168.31.43:8788"
CROP = (400, 360, 640, 560)   # 未翻转截图里的头部区域


def diag(cmd):
    req = urllib.request.Request(BASE + "/control",
                                 data=json.dumps({"action": "diag", "value": cmd}).encode(),
                                 headers={"Content-Type": "application/json"})
    return urllib.request.urlopen(req, timeout=8).read()


def shot(path="out/solo.png"):
    subprocess.run([ADB, "connect", DEV], capture_output=True)
    data = subprocess.run([ADB, "-s", DEV, "exec-out", "screencap", "-p"],
                          capture_output=True).stdout
    open(path, "wb").write(data)
    return Image.open(path).convert("RGB")


def main():
    targets = [int(x) for x in sys.argv[1:]] or [7, 61, 56, 386, 292, 5, 62, 57, 646, 645, 1, 0]
    tiles = []
    for index in targets:
        diag("solo %d" % index)
        time.sleep(0.9)
        im = shot()
        tile = im.crop(CROP).transpose(Image.FLIP_TOP_BOTTOM).resize((240, 200), Image.LANCZOS)
        canvas = Image.new("RGB", (240, 220), (20, 20, 24))
        canvas.paste(tile, (0, 20))
        ImageDraw.Draw(canvas).text((6, 5), "solo %d" % index, fill=(120, 220, 140))
        tiles.append(canvas)
        print("solo %d ok" % index)
    diag("solo -1")

    cols = 4
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (240 * cols, 220 * rows), (0, 0, 0))
    for i, tile in enumerate(tiles):
        sheet.paste(tile, ((i % cols) * 240, (i // cols) * 220))
    sheet.save("out/solo_sheet.png")
    print("拼图 out/solo_sheet.png")


if __name__ == "__main__":
    main()

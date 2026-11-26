"""生成一份「刻意的输入」给 C# 和 Java 两边同时喂，用来对照嘴型算法。

为什么要造这种输入而不是随便跑一段：
  - 要覆盖正弦的峰、谷、过零点，否则永远碰不到取整边界
  - 要覆盖帧间隔不均匀（真实掉帧就是这样），因为去重逻辑跟帧率有关
  - 要覆盖「说话→停→再说」，因为那是唯一会触发 0.00 的路径
"""
import os
import random

random.seed(20261121)
rows = []

# 1) 均匀 60fps，跑 8 秒：覆盖 2.3Hz 正弦的十几个完整周期
for ms in range(0, 8000, 16):
    rows.append((ms, 1))

# 2) 不均匀帧：模拟卡到 5~40fps，且中间停过两段
ms = 8000
talking = 1
while ms < 16000:
    step = random.choice([16, 16, 33, 50, 100, 200])
    if 11000 < ms < 12000:
        talking = 0
    elif 13500 < ms < 14200:
        talking = 0
    else:
        talking = 1
    ms += step
    rows.append((ms, talking))

# 3) 极端：每毫秒一步跑 1200 步——把「取整边界」密集扫一遍
for ms in range(20000, 21200):
    rows.append((ms, 1))

# 4) 长时间沉默，再去重逻辑应该只吐一条 0.00
for ms in range(22000, 24000, 250):
    rows.append((ms, 0))

here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(here, "..", "out", "ticks.txt")
with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write("# ms\ttalking   —— 由 tools/gen_ticks.py 生成，供 mouthtest 与 MouthDump 共用\n")
    for ms, talking in rows:
        f.write("%d\t%d\n" % (ms, talking))

print("%s  %d 行" % (os.path.normpath(out), len(rows)))

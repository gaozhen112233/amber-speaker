"""把词表发出去的参数名跟模型真实的参数名对一遍，找出静默无效的词。

名字对不上时 SetParameterValue 不会报错，也不会打日志，她只是不做那个动作——
这种缺陷只能靠对名字抓，看画面永远看不出来（她本来也在动）。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# 桌宠渲染器自己实现的「视图」类命令，不是模型参数。音箱这边视图是固定机位，不实现是有意的。
VIEW_ONLY = {"ViewX", "ViewY", "ViewSquash", "ViewRoll", "LeanInput", "anchor", "settle", "view"}

# 渲染器里做了名字映射的：桌宠的词 -> 这个模型的词（见 Model.cpp 的 kAliases）。
# EyeOpen 是「两只眼一起」，渲染器里拆成 ParamEyeLOpen / ParamEyeROpen 相乘。
RENDER_ALIASES = {
    "EyeOpen": "ParamEyeLOpen × ParamEyeROpen",
    "FaceAngleX": "AngleX（反号）",
    "FaceAngleY": "AngleY（反号）",
    "FaceAngleZ": "AngleZ（反号）",
}


def model_ids():
    data = open(os.path.join(ROOT, "assets", "model", "model.moc3"), "rb").read()
    ids = set()
    for m in re.finditer(rb"[A-Za-z][A-Za-z0-9_]{2,31}\x00", data):
        ids.add(m.group()[:-1].decode("ascii"))
    return ids


def main():
    ids = model_ids()

    # 表情名和动作名也要对——但它们走的是另外的索引（exp / motion），单独看
    expressions = set()
    motions = set()
    index = os.path.join(ROOT, "assets", "model", "index.txt")
    section = ""
    for line in open(index, encoding="utf-8"):
        line = line.rstrip("\r\n")
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            continue
        if "\t" not in line:
            continue
        name = line.split("\t")[0]
        (expressions if section == "exp" else motions).add(name)

    missing = {}          # 参数名 -> 用到它的词
    view_used = {}
    alias_used = {}
    bad_exp = []
    bad_motion = []
    rows = 0

    for line in open(os.path.join(ROOT, "out", "word_params.txt"), encoding="utf-8"):
        line = line.rstrip("\n")
        if "\t" not in line:
            continue
        word, names = line.split("\t", 1)
        rows += 1
        for name in names.split(","):
            if not name:
                continue
            if name.startswith("@"):
                if name[1:] not in expressions:
                    bad_exp.append((word, name[1:]))
            elif name.startswith("#"):
                if name[1:] not in motions:
                    bad_motion.append((word, name[1:]))
            elif name in VIEW_ONLY:
                view_used.setdefault(name, []).append(word)
            elif name in RENDER_ALIASES:
                alias_used.setdefault(name, []).append(word)
            elif name not in ids:
                missing.setdefault(name, []).append(word)

    print("检查了 %d 个词" % rows)
    print()
    if missing:
        print("!! 模型里没有这些参数（这些词会静默失效）：")
        for name in sorted(missing):
            print("   %-22s <- %s" % (name, "、".join(sorted(set(missing[name])))))
    else:
        print("参数名全部对得上")
    print()
    if bad_exp:
        print("!! 表情不存在：", bad_exp)
    else:
        print("表情名全部存在（%d 个）" % len(expressions))
    if bad_motion:
        print("!! 动作不存在：", bad_motion)
    else:
        print("动作名全部存在（%d 段）" % len(motions))
    print()
    if alias_used:
        print("渲染器映射过的（桌宠的名字 -> 这个模型的）：")
        for name in sorted(alias_used):
            print("   %-22s -> %-26s <- %s" % (
                name, RENDER_ALIASES[name], "、".join(sorted(set(alias_used[name])))))
    print()
    if view_used:
        print("视图类命令（有意的空操作，音箱是固定机位）：")
        for name in sorted(view_used):
            print("   %-22s <- %s" % (name, "、".join(sorted(set(view_used[name])))))

    return 1 if (missing or bad_exp or bad_motion) else 0


if __name__ == "__main__":
    sys.exit(main())

# -*- coding: utf-8 -*-
"""打一个可以发给别人的发布包。

    python tools/pack_release.py                # 代码 + 文档 + 插件（不含模型）
    python tools/pack_release.py --with-assets  # 连 assets 里的台词示例一起（仍不含模型）

产物：
    dist/amber-speaker-<版本>.zip
    dist/amber-speaker-<版本>/MANIFEST.txt     （每个文件的 SHA-256）
    dist/amber-speaker-<版本>/SHA256SUMS.txt   （zip 本身的校验值）

**默认不打包**：模型、预录音频、Live2D 专有静态库、APK、构建产物、本机配置。
理由见 LICENSE：这些要么是别人的版权作品，要么是专有 SDK，要么含个人信息。

用法上等价于「git archive + 一点额外过滤」，好处是不依赖 git，且能按白名单/黑名单精确控制。
"""
import argparse
import hashlib
import os
import re
import shutil
import sys
import zipfile

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION = "0.2.0"

# 明确要带上的顶层条目
INCLUDE = [
    "README.md", "LICENSE", "THIRD-PARTY.md", "CHANGELOG.md", ".gitignore", ".gitattributes", "AGENTS.md",
    "build.py", "deploy.py", "AndroidManifest.xml",
    "src", "jni/src", "jni/CMakeLists.txt", "jni/build.py", "jni/local",
    "tools", "docs", "examples", "contrib",
    "assets/mutter.txt", "assets/skits.txt",
]

# 这些永远不带（哪怕在 INCLUDE 的目录里）
EXCLUDE_DIRS = {
    "__pycache__", ".git", ".idea", ".vscode", "build", "out", "dist",
}
EXCLUDE_SUFFIX = (
    ".apk", ".idsig", ".a", ".o", ".so", ".pyc", ".log",
    ".moc3", ".motion3.json", ".exp3.json", ".physics3.json", ".png", ".wav", ".mp3",
)
EXCLUDE_NAMES = {
    "console.json", "console.log", "console.out.log", "console.err.log",
    "params.txt", "_check.js",
}
# 一次性调试脚本 / 临时脚本
EXCLUDE_PATTERNS = [
    r"^tmp_", r"^chin_", r"^_",
]


def keep(rel, include_models=False):
    parts = rel.replace("\\", "/").split("/")
    if any(p in EXCLUDE_DIRS for p in parts):
        return False
    name = parts[-1]
    if name in EXCLUDE_NAMES:
        return False
    if rel.lower().endswith(EXCLUDE_SUFFIX):
        # 文档配图要留
        if rel.startswith("docs/images/"):
            return True
        return False
    if any(re.match(p, name) for p in EXCLUDE_PATTERNS):
        return False
    if not include_models and parts[0] == "assets" and parts[1:2] and parts[1].startswith("model"):
        return False
    if parts[0] == "assets" and parts[1:2] == ["voice"]:
        return False
    # jni/local/ 放的是 Live2D SDK 的渲染器源码与 Core 静态库，许可证禁止再分发。
    # 只留一份说明文件，告诉使用者该往这里放什么。
    if rel.replace("\\", "/").startswith("jni/local/") and not rel.endswith(".md"):
        return False
    return True


def collect(include_models=False):
    files = []
    for entry in INCLUDE:
        path = os.path.join(PROJ, entry)
        if os.path.isfile(path):
            rel = os.path.relpath(path, PROJ).replace("\\", "/")
            if keep(rel, include_models):
                files.append(rel)
        elif os.path.isdir(path):
            for root, dirs, names in os.walk(path):
                dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
                for n in names:
                    full = os.path.join(root, n)
                    rel = os.path.relpath(full, PROJ).replace("\\", "/")
                    if keep(rel, include_models):
                        files.append(rel)
        else:
            print("  !! 找不到：%s（跳过）" % entry, file=sys.stderr)
    return sorted(set(files))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(256 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--with-assets", action="store_true",
                    help="把 assets 下的模型也带上（默认不带，注意版权）")
    ap.add_argument("--version", default=VERSION)
    args = ap.parse_args()

    files = collect(args.with_assets)
    if not files:
        sys.exit("没有可打包的文件，检查 INCLUDE。")

    dist = os.path.join(PROJ, "dist")
    os.makedirs(dist, exist_ok=True)
    base = "amber-speaker-%s" % args.version
    zip_path = os.path.join(dist, base + ".zip")

    manifest = []
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel in files:
            full = os.path.join(PROJ, rel.replace("/", os.sep))
            z.write(full, base + "/" + rel)
            manifest.append("%s  %s" % (sha256(full), rel))

    # 清单单独放一份，方便不装 Python 的人核对
    man_path = os.path.join(dist, base + "-MANIFEST.txt")
    with open(man_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# %s 文件清单（SHA-256）\n# 生成时间：%s\n\n" %
                (base, __import__("time").strftime("%Y-%m-%d %H:%M:%S")))
        f.write("\n".join(manifest) + "\n")

    size = os.path.getsize(zip_path)
    print("\n=== 发布包 ===")
    print("  %s" % zip_path)
    print("  %d 个文件，%.1f KB" % (len(files), size / 1024))
    print("  校验值 %s" % sha256(zip_path))
    print("  清单   %s" % man_path)

    # 体积最大的十个，方便发现「不小心把模型打进去了」
    tops = sorted(((os.path.getsize(os.path.join(PROJ, r.replace("/", os.sep))), r)
                   for r in files), reverse=True)[:10]
    print("\n最大的 10 个文件：")
    for n, r in tops:
        print("  %7.1f KB  %s" % (n / 1024, r))
    if any(r.startswith("assets/model") for _, r in tops):
        print("\n!! 注意：包里含 assets/model*，确认你有权分发这些模型。")


if __name__ == "__main__":
    main()

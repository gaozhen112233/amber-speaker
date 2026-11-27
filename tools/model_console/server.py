# -*- coding: utf-8 -*-
"""琥珀模型调参台 —— 本地服务 + 设备代理。

浏览器直连音箱 8788 会被拦：设备回的是 200 但没有 Access-Control-Allow-Origin，
OPTIONS 又是 404（只认 POST/GET 那几个路径）。所以页面里的所有请求都先打到
127.0.0.1:8790，由这里转发到设备，同源就没跨域问题了。

数据来源：
  params.txt   —— 设备 `diag params` 吐出来的全表（name[min,default,max]=current）
  index.txt    —— 表情（exp）与动作（motion）的中文名 → 文件映射

用法：
  python tools/model_console/server.py                       # 用 console.json 里的地址
  python tools/model_console/server.py 192.168.1.100:8788 8790
  AMBER_DEVICE=192.168.1.100:8788 python tools/model_console/server.py
"""
import http.client
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import quote, unquote
from urllib.request import Request, urlopen

HERE = os.path.dirname(os.path.abspath(__file__))
AMBER = os.path.dirname(os.path.dirname(HERE))
PARAMS_FILE = os.path.join(AMBER, "out", "params.txt")
INDEX_FILE = os.path.join(AMBER, "assets", "model", "index.txt")
PAGE_FILE = os.path.join(HERE, "index.html")
MODEL_ROOT = os.path.join(AMBER, "assets")
CONFIG_FILE = os.path.join(HERE, "console.json")

# ---------------------------------------------------------------------------
# 配置
#
# 别人拿到这个项目时，设备 IP 一定和作者不一样。所以地址不再写死在代码里，
# 按下面的顺序找，靠前的优先：
#
#   1. 命令行参数      python server.py 192.168.1.20:8788 8790
#   2. 环境变量        AMBER_DEVICE / AMBER_PORT / ADB
#   3. console.json    {"device": "...", "port": 8790, "adb": "..."}
#   4. 内置默认值      192.168.1.100:8788（占位，改了就能用）
#
# 占位值等同于「没填」：启动脚本不带参数时会传这个地址，如果直接采信，
# 网页里存进 console.json 的地址就永远轮不到。所以占位值一律跳过，继续往下找。
#
# 网页顶上也能直接改地址，保存后写回 console.json 并立即生效，不用重启。
# ---------------------------------------------------------------------------
def load_config():
    if not os.path.isfile(CONFIG_FILE):
        return {}
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


CFG = load_config()

PLACEHOLDER = "192.168.1.100:8788"


def _norm(value):
    """把「没填」和「还是占位值」同样看待。

    启动脚本在没有参数时会传一个占位地址过来，如果直接采信，网页里改过并
    存进 console.json 的地址就永远轮不到——表现为「每次双击都跳回默认 IP」。
    """
    text = (value or "").strip().rstrip("/")
    if text.startswith("http://"):
        text = text[7:]
    return "" if text in ("", PLACEHOLDER) else text


DEVICE = (_norm(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else "")
          or _norm(os.environ.get("AMBER_DEVICE"))
          or _norm(CFG.get("device"))
          or PLACEHOLDER)
PORT = int(os.environ.get("AMBER_PORT") or
           (sys.argv[2] if len(sys.argv) > 2 else 0) or
           CFG.get("port") or 8790)


def find_adb():
    """找到 adb。抓图/回读要靠它，找不到也不致命（其它功能照用）。"""
    for candidate in (os.environ.get("ADB"), CFG.get("adb")):
        if candidate and os.path.isfile(candidate):
            return candidate
    found = shutil.which("adb")
    if found:
        return found
    guesses = [
        r"C:\android\sdk\platform-tools\adb.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
        os.path.expandvars(r"%ANDROID_HOME%\platform-tools\adb.exe"),
        os.path.expandvars(r"%ANDROID_SDK_ROOT%\platform-tools\adb.exe"),
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
        os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
        "/usr/local/bin/adb",
        "/opt/homebrew/bin/adb",
        "/usr/bin/adb",
    ]
    for path in guesses:
        if path and os.path.isfile(path):
            return path
    return ""


ADB = find_adb()


def save_config(patch):
    """合并写回 console.json（只改传进来的键）。"""
    data = load_config()
    data.update(patch)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def normalize_device(addr):
    """把用户填的东西规整成 host:port。允许只填 IP（补 8788）、带 http:// 前缀。"""
    s = (addr or "").strip()
    s = re.sub(r"^https?://", "", s, flags=re.I).rstrip("/")
    if not s:
        return ""
    if ":" not in s:
        s += ":8788"
    host, _, port = s.partition(":")
    if not host or not port.isdigit() or not (0 < int(port) < 65536):
        return ""
    return "%s:%s" % (host, port)

# name[min,default,max]=current，一条日志里挤了 10 个，所以用 finditer 而不是按行切
PARAM_RE = re.compile(r"([^\s\[\]=]+)\[(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\]=(-?[\d.]+)")


def load_params():
    """读参数全表。文件不在就返回空表，让前端提示「先跑一次 diag params」。"""
    if not os.path.isfile(PARAMS_FILE):
        return []
    text = open(PARAMS_FILE, encoding="utf-8", errors="ignore").read()
    seen, out = set(), []
    for m in PARAM_RE.finditer(text):
        pid = m.group(1)
        if pid in seen:          # 文件是几次 dump 拼起来的，会有重号
            continue
        seen.add(pid)
        out.append({
            "id": pid,
            "min": float(m.group(2)),
            "def": float(m.group(3)),
            "max": float(m.group(4)),
            "cur": float(m.group(5)),
        })
    return out


def load_index():
    """解析 index.txt：[meta] / [exp] / [motion] 三段，后两段每行「中文名<TAB>相对路径」。"""
    exp, motion = [], []
    if not os.path.isfile(INDEX_FILE):
        return {"exp": exp, "motion": motion}
    section = None
    for line in open(INDEX_FILE, encoding="utf-8", errors="ignore"):
        line = line.strip()
        if not line:
            continue
        if line.startswith("["):
            section = line.strip("[]")
            continue
        if "\t" not in line:
            continue
        name, path = line.split("\t", 1)
        if section == "exp":
            exp.append(name)
        elif section == "motion":
            motion.append(name)
    return {"exp": exp, "motion": motion}


def load_models():
    """列出 assets 下的模型目录（有 model3.json 的才算）。

    名字取每个模型 index.txt 的 [meta] title —— 那是 assets 里唯一一处「人看的名字」，
    放那儿的好处是以后加模型只要把目录丢进 assets 并写一行 title，这里和界面都不用改。
    读不到就退回目录名。
    """
    models = []
    if not os.path.isdir(MODEL_ROOT):
        return models
    for name in sorted(os.listdir(MODEL_ROOT)):
        folder = os.path.join(MODEL_ROOT, name)
        if not os.path.isdir(folder) or not name.startswith("model"):
            continue
        if not os.path.isfile(os.path.join(folder, "model3.json")):
            continue
        title, profile = name, ""
        index = os.path.join(folder, "index.txt")
        if os.path.isfile(index):
            section = None
            for line in open(index, encoding="utf-8", errors="ignore"):
                line = line.strip()
                if not line:
                    continue
                if line.startswith("["):
                    section = line.strip("[]")
                    continue
                if section == "meta" and "\t" in line:
                    key, value = line.split("\t", 1)
                    if key == "title":
                        title = value.strip()
                    elif key == "profile":
                        profile = value.strip()
        models.append({"dir": name + "/", "name": name, "title": title, "profile": profile})
    return models


def device_get(path, timeout=5):
    with urlopen("http://%s%s" % (DEVICE, path), timeout=timeout) as r:
        raw = r.read().decode("utf-8", "replace")
    try:
        return json.loads(raw)
    except ValueError:
        # 设备那边 JSON 少个括号之类：直接把原文带出来，别让整个接口 500 掉。
        # 这里曾经因为没兜住，一个残缺的 /state 就让「模型」整张卡片变空白。
        raise ValueError("设备 %s 返回的 JSON 不完整：%s" % (path, raw[:160]))


def device_control(action, value, timeout=6):
    """直接转发一个 /control 动作（light / brightness / volume / display ...）。

    走 POST + JSON body 而不是 GET 查询串：设备对查询串的 %XX 不解码（diag 那条踩过），
    走 body 就没有编码问题，且和手写 curl 测出来的行为一致。
    """
    payload = json.dumps({"action": str(action), "value": str(value)}).encode("utf-8")
    req = Request("http://%s/control" % DEVICE, data=payload,
                  headers={"Content-Type": "application/json"}, method="POST")
    with urlopen(req, timeout=timeout) as r:
        body = r.read().decode("utf-8").strip()
    try:
        return json.loads(body)
    except ValueError:
        return {"ok": True, "raw": body}


def device_command(command, timeout=6):
    """把一条原生命令塞进 /control 的 diag 直通车。

    注意只能走 POST + JSON body：GET 查询串设备不认，而且 %20 不会被解码成空格，
    value 会变成字面 `clear%20ParamEyeROpen` 然后被静默忽略（踩过）。
    """
    payload = json.dumps({"action": "diag", "value": command}).encode("utf-8")
    req = Request("http://%s/control" % DEVICE, data=payload,
                  headers={"Content-Type": "application/json"}, method="POST")
    with urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def device_post(path, timeout=20):
    """向设备发一个「没有 body、参数都在查询串里」的 POST（/video/delete、/video/clean）。

    这类请求的设备端只读 query，不读 body：所以别把参数塞进 body，否则会静默丢参数。
    """
    req = Request("http://%s%s" % (DEVICE, path), data=b"", method="POST")
    with urlopen(req, timeout=timeout) as r:
        body = r.read().decode("utf-8").strip()
    try:
        return json.loads(body or "{}")
    except ValueError:
        return {"ok": False, "raw": body}


def device_post_json(path, obj, timeout=8):
    """向设备发一段 JSON body 的 POST（/settings 这类「有参数要传」的端点）。"""
    payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    req = Request("http://%s%s" % (DEVICE, path), data=payload,
                  headers={"Content-Type": "application/json"}, method="POST")
    with urlopen(req, timeout=timeout) as r:
        body = r.read().decode("utf-8", "replace").strip()
    try:
        return json.loads(body or "{}")
    except ValueError:
        return {"ok": False, "raw": body}


def adb_serial():
    """挑一个已连上的设备。无线（192.168.x.x:5555）优先，其次 USB 序列号。

    adb server 常被这边反复起停，devices 列表一空就自己 connect 一次，
    不然「回读」在每次新开的会话里都会失败。
    """
    if not ADB:
        return None

    def list_devices():
        out = subprocess.run([ADB, "devices"], capture_output=True, timeout=10)
        return [l.split("\t")[0].strip()
                for l in out.stdout.decode("utf-8", "ignore").splitlines() if "\tdevice" in l]

    lines = list_devices()
    if not lines:
        host = DEVICE.split(":")[0]
        subprocess.run([ADB, "connect", "%s:5555" % host], capture_output=True, timeout=15)
        time.sleep(1.5)
        lines = list_devices()
    if not lines:
        return None
    wireless = [l for l in lines if ":" in l]
    return (wireless or lines)[0]


def readback():
    """读回设备上每个参数的**当前值**。

    diag params 只往 logcat 里写、不返回 HTTP body，所以走 adb：
    清缓冲 → 触发一次 dump → 抓 AmberGL 的日志 → 解析。
    比盲猜 UI 数值准，也是查「某个参数被按住没松开」最快的手段。
    """
    serial = adb_serial()
    if not serial:
        raise RuntimeError("adb 上找不到设备（先 adb connect <设备IP>:5555；地址在 server.py 顶部或 console.json 里配）")
    base = [ADB, "-s", serial, "logcat"]
    subprocess.run(base + ["-c"], capture_output=True, timeout=10)
    device_command("params")
    time.sleep(0.45)                       # 509 个参数分 51 条日志，得等它写完
    out = subprocess.run(base + ["-d", "-s", "AmberGL"], capture_output=True, timeout=15)
    text = out.stdout.decode("utf-8", "ignore")
    values = {}
    for m in PARAM_RE.finditer(text):
        values[m.group(1)] = float(m.group(5))
    if not values:
        raise RuntimeError("日志里没抓到参数表（应用在跑吗？）")
    return values


def screenshot():
    """经 adb 抓一张屏幕（1024×600 的 dev framebuffer 合成结果）。

    用 adb 而不是设备的 /snapshot：那条是「她的眼睛」（后摄取帧，还带每日额度和冷却），
    和摆台这件事无关。截屏只能走 adb，但 adb 常年连着，代价只有 ~0.4s。
    """
    serial = adb_serial()
    if not serial:
        raise RuntimeError("adb 上找不到设备（先 adb connect <设备IP>:5555；地址在 server.py 顶部或 console.json 里配）")
    out = subprocess.run([ADB, "-s", serial, "exec-out", "screencap", "-p"],
                         capture_output=True, timeout=25)
    data = out.stdout
    if not data.startswith(b"\x89PNG"):
        raise RuntimeError("截屏失败：%s" % out.stderr.decode("utf-8", "ignore")[-200:])
    return data


def heal_camera():
    """重启设备上的相机守护进程（init 服务 qcamerasvr）。

    这台机器的 QCamera HAL 会「假活」：进程在、`dumpsys media.camera` 也正常，但任何
    `Camera.open(0)` 都抛 `Fail to connect to camera service`——用户看到的就是
    「摄像头打不开」，连走近唤醒的人脸确认也一起失效。`stop/start qcamerasvr` 能立刻恢复。

    走 adb（电脑这边有 root），不依赖设备上那个 App 的 su 授权，所以是首选的修法。
    """
    serial = adb_serial()
    if not serial:
        raise RuntimeError("adb 上找不到设备（先 adb connect <设备IP>:5555）")
    # 整条命令作为一个参数传给远端 sh：分开传的话分号会被 host shell 解释，
    # 变成「su -c stop」（只跑了一半）＋「start qcamerasvr」（非 root 起不来）。
    cmd = "su -c 'stop qcamerasvr; start qcamerasvr'"
    try:
        out = subprocess.run([ADB, "-s", serial, "shell", cmd],
                             capture_output=True, timeout=30)
    except subprocess.TimeoutExpired:
        raise RuntimeError("adb 执行超时（设备上可能在等 su 授权框）")
    tail = (out.stdout + out.stderr).decode("utf-8", "replace").strip()
    time.sleep(2.5)  # 等 daemon 重新起来，紧接着取帧才不会又失败
    return {"ok": True, "detail": tail[:200] or "qcamerasvr 已重启"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # 调参时一秒几十条，别刷屏

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj, ensure_ascii=False))

    def upload_video(self):
        """把浏览器选中的视频原样转给音箱（POST /upload），再由她落盘播放。

        不走「让电脑起个 HTTP 服务让她来拉」：Windows 防火墙会拦入站端口，
        而且播放要从头到尾拉流，网上一抖就卡。传完播本地文件最稳。
        """
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return self._json({"ok": False, "error": "空文件"}, 400)
        name = unquote(self.path.split("name=")[-1]) if "name=" in self.path else "upload.mp4"
        name = os.path.basename(name) or "upload.mp4"
        host, _, port = DEVICE.partition(":")
        conn = http.client.HTTPConnection(host, int(port or 8788), timeout=600)
        try:
            conn.putrequest("POST", "/upload?name=" + quote(name))
            conn.putheader("Content-Length", str(length))
            conn.putheader("Content-Type", "application/octet-stream")
            conn.endheaders()
            left = length
            while left > 0:
                chunk = self.rfile.read(min(256 * 1024, left))
                if not chunk:
                    break
                conn.send(chunk)
                left -= len(chunk)
            resp = conn.getresponse()
            body = resp.read().decode("utf-8", "ignore")
            return self._send(resp.status, body)
        except Exception as e:                                       # noqa: BLE001
            return self._json({"ok": False, "error": str(e)}, 502)
        finally:
            conn.close()

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            if not os.path.isfile(PAGE_FILE):
                return self._send(500, "index.html 不见了", "text/plain; charset=utf-8")
            return self._send(200, open(PAGE_FILE, "rb").read(), "text/html; charset=utf-8")
        if path == "/api/model":
            meta = load_index()
            return self._json({
                "device": DEVICE,
                "stats": {"params": len(load_params())},
                "params": load_params(),
                "expressions": meta["exp"],
                "motions": meta["motion"],
            })
        if path == "/api/models":
            # 模型列表（目录 + 中文名）+ 设备当前用的那个。设备离线也照样给列表，
            # 界面至少能显示按钮，只是点了会报错。
            try:
                current = device_get("/state").get("model", "")
            except Exception:                                        # noqa: BLE001
                current = ""
            return self._json({"device": DEVICE, "models": load_models(), "current": current})
        if path == "/api/snapshot":
            try:
                png = screenshot()
            except Exception as e:                                  # noqa: BLE001
                return self._json({"ok": False, "error": str(e)}, 502)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(png)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(png)
            return
        if path == "/api/video/list":
            # 设备上已存的视频（/sdcard/amber/video/）+ 总占用，用来做清理
            try:
                return self._json(device_get("/video/list", timeout=10))
            except Exception as e:                                  # noqa: BLE001
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/api/state":
            try:
                return self._json(device_get("/state"))
            except Exception as e:                                  # noqa: BLE001
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/api/settings":
            # 行为类设置（作息 / 定时播报 / 在场搭话 / 接话窗口 / 视线跟随）
            try:
                return self._json(device_get("/settings", timeout=10))
            except Exception as e:                                  # noqa: BLE001
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/api/info":
            # 「我这台到底连到哪儿了」：设备地址、adb 路径、在线与固件版本。
            # 别人装完第一件事就是来这儿确认地址填对没有。
            info = {
                "device": DEVICE,
                "port": PORT,
                "adb": ADB or "",
                "adb_found": bool(ADB),
                "config_file": CONFIG_FILE,
                "online": False,
            }
            try:
                st = device_get("/state", timeout=3)
                info["online"] = True
                info["contract"] = st.get("contract", "")
                info["model"] = st.get("model", "")
            except Exception:                                       # noqa: BLE001
                pass
            return self._json(info)
        if path == "/api/readback":
            try:
                values = readback()
                return self._json({"ok": True, "values": values, "count": len(values)})
            except Exception as e:                                  # noqa: BLE001
                return self._json({"ok": False, "error": str(e)}, 500)
        return self._send(404, "no such endpoint", "text/plain; charset=utf-8")

    def do_POST(self):
        global DEVICE
        path = self.path.split("?")[0]
        # 传视频是「边收边转发」：几十上百 MB 不能先读进内存再处理。
        # 这一条必须放在读 body 之前，否则会先把整段 json 读坏了。
        if path == "/api/video/upload":
            return self.upload_video()
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            data = json.loads(raw.decode("utf-8") or "{}")
        except ValueError:
            return self._json({"ok": False, "error": "body 不是 JSON"}, 400)

        if path == "/api/video/delete":
            name = (data.get("name") or "").strip()
            if not name:
                return self._json({"ok": False, "error": "缺 name"}, 400)
            try:
                return self._json(device_post("/video/delete?name=" + quote(name)))
            except (URLError, HTTPError, OSError) as e:
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/api/video/clean":
            try:
                return self._json(device_post("/video/clean"))
            except (URLError, HTTPError, OSError) as e:
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/api/camera-heal":
            # 「摄像头打不开」的急救：重启相机守护进程（走 adb root，不用设备端 su 授权）。
            try:
                return self._json(heal_camera())
            except Exception as e:                                  # noqa: BLE001
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/api/settings":
            key = (data.get("key") or "").strip()
            if not key:
                return self._json({"ok": False, "error": "缺 key"}, 400)
            try:
                r = device_post_json("/settings", {"key": key, "value": str(data.get("value", ""))})
                return self._json(r, 200 if r.get("ok") else 400)
            except (URLError, HTTPError, OSError) as e:
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/api/config":
            # 改设备地址：写 console.json 并立即生效（不用重启这个脚本）
            addr = normalize_device(data.get("device", ""))
            if not addr:
                return self._json({"ok": False, "error": "地址要写成 192.168.1.20:8788"}, 400)
            DEVICE = addr
            try:
                save_config({"device": addr})
            except OSError as e:
                return self._json({"ok": False, "error": "写 console.json 失败：%s" % e}, 500)
            return self._json({"ok": True, "device": addr})
        if path == "/api/clock":
            # 手动对时：设备没有 RTC 电池，开机停在 1977 年。这条给「刚装好还没接 AstrBot」用。
            try:
                epoch = int(data.get("epoch") or 0)
            except (TypeError, ValueError):
                epoch = 0
            if epoch <= 0:
                return self._json({"ok": False, "error": "缺 epoch（毫秒）"}, 400)
            try:
                return self._json(device_post_json("/clock", {"epoch": epoch}))
            except (URLError, HTTPError, OSError) as e:
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/api/shutdown":
            # 只关本机的调参台，不动设备。绑的是 127.0.0.1，外部碰不到。
            self._json({"ok": True, "bye": True})
            threading.Timer(0.35, lambda: os._exit(0)).start()
            return
        if path == "/api/control":
            action = data.get("action")
            if not action:
                return self._json({"ok": False, "error": "缺 action"}, 400)
            try:
                return self._json(device_control(action, data.get("value", "")))
            except (URLError, HTTPError, OSError) as e:
                return self._json({"ok": False, "error": str(e)}, 502)
        if path == "/api/cmd":
            commands = data.get("commands")
            if commands is None:
                commands = [data.get("command", "")]
            results = []
            try:
                for cmd in commands:
                    cmd = (cmd or "").strip()
                    if not cmd:
                        continue
                    device_command(cmd)
                    results.append({"command": cmd, "ok": True})
            except (URLError, HTTPError, OSError) as e:
                results.append({"command": "", "ok": False, "error": str(e)})
            return self._json({"ok": all(r["ok"] for r in results), "results": results})
        return self._send(404, "no such endpoint", "text/plain; charset=utf-8")


def main():
    params = load_params()
    meta = load_index()
    print("设备      : %s" % DEVICE)
    print("参数表    : %s（%d 个）" % (PARAMS_FILE, len(params)))
    print("表情/动作 : %d / %d" % (len(meta["exp"]), len(meta["motion"])))
    if not params:
        print("!! 参数表是空的：先 POST /control 发 {\"action\":\"diag\",\"value\":\"params\"}，"
              "再把 logcat 里的参数表存成上述文件")
    print("打开      : http://127.0.0.1:%d/" % PORT)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()

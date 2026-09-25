# Chromecast 送信の下調べ（memo/cast.md の「進め方」1）。
#
# ffmpeg で作ったライブストリームを HTTP で流しっぱなしにし、Cast V2 を
# 手書きした最小のクライアントで Default Media Receiver に LOAD させる。
# 再生が始まるか・遅れは何秒か・止まらずに流れ続けるかを、受信側の
# MEDIA_STATUS（currentTime / playerState）から読む。
#
#   python casttest.py <TV の IP> [秒数] [形式]
#   形式: webm（VP8+Opus、既定） / webm9（VP9+Opus） / mp4（H.264+AAC の fMP4）
#
# 依存は Python の標準ライブラリと ffmpeg だけ（pychromecast は使わない。
# C++ 版の試作を兼ねる）。

import json
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TV = sys.argv[1] if len(sys.argv) > 1 else "192.168.2.36"
SECONDS = int(sys.argv[2]) if len(sys.argv) > 2 else 90
FORMAT = sys.argv[3] if len(sys.argv) > 3 else "webm"
HTTP_PORT = 8765
APP_ID = "CC1AD845"  # Default Media Receiver

NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat"
NS_RECV = "urn:x-cast:com.google.cast.receiver"
NS_MEDIA = "urn:x-cast:com.google.cast.media"

t0 = time.time()


def log(*a):
    print("%7.2f" % (time.time() - t0), *a, flush=True)


# ---------------------------------------------------------------------------
# ライブストリーム（ffmpeg）
# ---------------------------------------------------------------------------
def ffmpeg_cmd():
    src = [
        "-re", "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30",
        "-re", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:beep_factor=4",
    ]
    if FORMAT == "webm":
        enc = ["-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8",
               "-b:v", "2M", "-g", "60",
               "-c:a", "libopus", "-b:a", "128k", "-ac", "2",
               "-f", "webm", "-live", "1", "-cluster_time_limit", "1000"]
        ctype = "video/webm"
    elif FORMAT == "webm9":
        enc = ["-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8",
               "-b:v", "2M", "-g", "60",
               "-c:a", "libopus", "-b:a", "128k", "-ac", "2",
               "-f", "webm", "-live", "1", "-cluster_time_limit", "1000"]
        ctype = "video/webm"
    else:
        enc = ["-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
               "-pix_fmt", "yuv420p", "-b:v", "2M", "-g", "60",
               "-c:a", "aac", "-b:a", "128k", "-ac", "2",
               "-f", "mp4", "-movflags", "frag_keyframe+empty_moov+default_base_moof"]
        ctype = "video/mp4"
    return ["ffmpeg", "-hide_banner", "-loglevel", "error"] + src + enc + ["pipe:1"], ctype


CMD, CTYPE = ffmpeg_cmd()
stream_start = {}  # 要求ごとの流し始めの時刻


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        pass

    def _head(self):
        self.send_response(200)
        self.send_header("Content-Type", CTYPE)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

    def do_HEAD(self):
        log("HTTP HEAD", self.path, dict(self.headers))
        self._head()

    def do_GET(self):
        log("HTTP GET", self.path, "Range=%s" % self.headers.get("Range"),
            "UA=%s" % self.headers.get("User-Agent"))
        self._head()
        p = subprocess.Popen(CMD, stdout=subprocess.PIPE)
        key = id(self)
        stream_start[key] = time.time()
        sent = 0
        try:
            while True:
                b = p.stdout.read(16384)
                if not b:
                    break
                self.wfile.write(b)
                sent += len(b)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError) as e:
            log("HTTP client closed", type(e).__name__, "sent", sent)
        finally:
            p.kill()
            log("HTTP stream ended after %.1fs, %d bytes" % (time.time() - stream_start[key], sent))


def local_ip_toward(host):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect((host, 8009))
    ip = s.getsockname()[0]
    s.close()
    return ip


# ---------------------------------------------------------------------------
# Cast V2（CastMessage を手で符号化・復号する）
# ---------------------------------------------------------------------------
def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def field_str(num, s):
    b = s.encode("utf-8")
    return varint((num << 3) | 2) + varint(len(b)) + b


def field_int(num, v):
    return varint((num << 3) | 0) + varint(v)


def encode(src, dst, ns, payload):
    body = (field_int(1, 0) + field_str(2, src) + field_str(3, dst) + field_str(4, ns) +
            field_int(5, 0) + field_str(6, json.dumps(payload)))
    return struct.pack(">I", len(body)) + body


def decode(body):
    i = 0
    out = {}
    while i < len(body):
        key = 0
        shift = 0
        while True:
            b = body[i]
            i += 1
            key |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        num, wt = key >> 3, key & 7
        if wt == 0:
            v = 0
            shift = 0
            while True:
                b = body[i]
                i += 1
                v |= (b & 0x7F) << shift
                shift += 7
                if not b & 0x80:
                    break
            out[num] = v
        elif wt == 2:
            n = 0
            shift = 0
            while True:
                b = body[i]
                i += 1
                n |= (b & 0x7F) << shift
                shift += 7
                if not b & 0x80:
                    break
            out[num] = body[i:i + n]
            i += n
        else:
            raise ValueError("wire type %d" % wt)
    return out


class Cast:
    def __init__(self, host):
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # 受信側は自己署名
        raw = socket.create_connection((host, 8009), timeout=10)
        self.s = ctx.wrap_socket(raw)
        self.s.settimeout(None)
        self.lock = threading.Lock()
        self.req = 0
        self.handlers = []
        threading.Thread(target=self._reader, daemon=True).start()
        threading.Thread(target=self._pinger, daemon=True).start()

    def send(self, dst, ns, payload, src="sender-0"):
        with self.lock:
            self.s.sendall(encode(src, dst, ns, payload))

    def request(self, dst, ns, payload):
        self.req += 1
        payload["requestId"] = self.req
        self.send(dst, ns, payload)
        return self.req

    def _read_exact(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c:
                raise EOFError
            b += c
        return b

    def _reader(self):
        try:
            while True:
                (n,) = struct.unpack(">I", self._read_exact(4))
                m = decode(self._read_exact(n))
                ns = m.get(4, b"").decode()
                src = m.get(2, b"").decode()
                payload = json.loads(m.get(6, b"{}").decode("utf-8"))
                if ns == NS_HEART and payload.get("type") == "PING":
                    self.send(src, NS_HEART, {"type": "PONG"}, src=m.get(3, b"").decode())
                    continue
                for h in self.handlers:
                    h(src, ns, payload)
        except Exception as e:
            log("CAST reader ended", type(e).__name__, e)

    def _pinger(self):
        while True:
            time.sleep(5)
            try:
                self.send("receiver-0", NS_HEART, {"type": "PING"})
            except Exception:
                return


def main():
    ip = local_ip_toward(TV)
    httpd = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    url = "http://%s:%d/stream.%s" % (ip, HTTP_PORT, "mp4" if FORMAT == "mp4" else "webm")
    log("serving", url, CTYPE)

    c = Cast(TV)
    state = {"transport": None, "session": None, "media": None, "last": None}
    got_app = threading.Event()

    def on_msg(src, ns, p):
        t = p.get("type")
        if ns == NS_RECV and t == "RECEIVER_STATUS":
            apps = p.get("status", {}).get("applications", [])
            names = [(a.get("appId"), a.get("displayName")) for a in apps]
            vol = p.get("status", {}).get("volume")
            log("RECEIVER_STATUS apps=%s volume=%s" % (names, vol))
            for a in apps:
                if a.get("appId") == APP_ID:
                    state["transport"] = a.get("transportId")
                    state["session"] = a.get("sessionId")
                    got_app.set()
        elif ns == NS_MEDIA and t == "MEDIA_STATUS":
            for st in p.get("status", []):
                state["media"] = st.get("mediaSessionId", state["media"])
                cur = st.get("currentTime")
                ps = st.get("playerState")
                idle = st.get("idleReason")
                key = (ps, idle)
                since = None
                if stream_start:
                    since = time.time() - max(stream_start.values())
                lag = (since - cur) if (since is not None and cur is not None) else None
                if key != state["last"] or True:
                    log("MEDIA_STATUS %s%s currentTime=%s streamed=%s lag=%s" % (
                        ps, (" idle=" + idle) if idle else "",
                        "%.2f" % cur if cur is not None else None,
                        "%.2f" % since if since is not None else None,
                        "%.2f" % lag if lag is not None else None))
                state["last"] = key
        elif t in ("LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "LAUNCH_ERROR"):
            log("ERROR", ns, json.dumps(p))
        elif t == "CLOSE":
            log("CLOSE from", src)

    c.handlers.append(on_msg)
    c.send("receiver-0", NS_CONN, {"type": "CONNECT"})
    c.request("receiver-0", NS_RECV, {"type": "GET_STATUS"})
    c.request("receiver-0", NS_RECV, {"type": "LAUNCH", "appId": APP_ID})
    if not got_app.wait(20):
        log("app did not start")
        return
    tr = state["transport"]
    log("launched transport=%s session=%s" % (tr, state["session"]))
    c.send(tr, NS_CONN, {"type": "CONNECT"})
    c.request(tr, NS_MEDIA, {
        "type": "LOAD",
        "autoplay": True,
        "media": {"contentId": url, "contentType": CTYPE, "streamType": "LIVE",
                  "metadata": {"metadataType": 0, "title": "mxv2 cast test (%s)" % FORMAT}},
    })
    end = time.time() + SECONDS
    while time.time() < end:
        time.sleep(3)
        if state["media"] is not None:
            c.request(tr, NS_MEDIA, {"type": "GET_STATUS", "mediaSessionId": state["media"]})
    log("stopping app")
    c.request("receiver-0", NS_RECV, {"type": "STOP", "sessionId": state["session"]})
    time.sleep(2)
    httpd.shutdown()


if __name__ == "__main__":
    main()

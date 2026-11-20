# Apre il programma delle curve (companion/index.html) sul PC e in rete locale.
#
#   python companion/server.py
#
# PC:       http://127.0.0.1:8080   (Bluetooth ok, prova con Simula)
# Telefono: http://IP-DEL-PC:8080   (stesso Wi-Fi; Chrome va segnato come
#           origine sicura, le istruzioni compaiono nella pagina)
import json
import socket
import threading
import time
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LOG = ROOT.parent / "logs" / "pc.log"
LOG_LOCK = threading.Lock()


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/api/geocode":
            return super().do_GET()
        qs = urllib.parse.parse_qs(parsed.query)
        q = (qs.get("q") or [""])[0].strip()[:120]
        if len(q) < 2:
            body = b'{"features":[]}'
            self._json(200, body)
            return
        params = {"q": q, "limit": "6"}
        if "lat" in qs and "lon" in qs:
            params["lat"] = qs["lat"][0]
            params["lon"] = qs["lon"][0]
        url = "https://photon.komoot.io/api/?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={"User-Agent": "HUD-Moto-companion/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=12) as resp:
                body = resp.read()
            self._json(200, body)
        except Exception as exc:
            self._json(502, json.dumps({"error": str(exc)}).encode())

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/api/log":
            self.send_error(404)
            return
        n = int(self.headers.get("Content-Length", "0") or 0)
        raw = self.rfile.read(max(0, min(n, 8000)))
        try:
            data = json.loads(raw.decode("utf-8"))
        except Exception:
            data = {"level": "ERROR", "msg": raw.decode("utf-8", "replace")[:500]}
        level = str(data.get("level", "?"))[:12]
        msg = str(data.get("msg", "")).replace("\n", " ")[:500]
        line = time.strftime("%Y-%m-%d %H:%M:%S") + f" {level} {msg}\n"
        LOG.parent.mkdir(exist_ok=True)
        with LOG_LOCK:
            with LOG.open("a", encoding="utf-8") as f:
                f.write(line)
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _json(self, code, body):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    port = 8080
    ip = lan_ip()
    print(f"PC:       http://127.0.0.1:{port}")
    print(f"Telefono: http://{ip}:{port}")
    print(f"Log PC:   {LOG}")
    print("Ctrl+C per fermare.")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()

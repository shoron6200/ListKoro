# CI test server: ROOT er file serve kore; /slow/<file> dhire dhire pathay (cancel test er jonno)
import http.server, os, sys, time
ROOT = sys.argv[1]
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=ROOT, **k)
    def log_message(self, *a): pass
    def do_GET(self):
        if not self.path.startswith("/slow/"): return super().do_GET()
        p = os.path.join(ROOT, self.path[6:]); data = open(p, "rb").read()
        self.send_response(200); self.send_header("Content-Type", "video/mp4"); self.send_header("Content-Length", str(len(data))); self.end_headers()
        for i in range(0, len(data), 8192):
            try: self.wfile.write(data[i:i+8192]); self.wfile.flush()
            except Exception: return
            time.sleep(0.25)
http.server.ThreadingHTTPServer(("0.0.0.0", 8077), H).serve_forever()

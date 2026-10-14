#!/usr/bin/env python3
"""
Contract adapter for the on-device model runtime (Termux, phone-only).

Binds 127.0.0.1:8080 and provides the fixed contract the ZCode app and the
agent harness build against:

    GET  /health                -> {"status": "ok", ...}
    GET  /                      -> built-in offline chat page (WebView-ready)
    ANY  /*                     -> transparent proxy to Ollama on 127.0.0.1:11434

stdlib only. Start it after `ollama serve` (start-model-server.sh does both).
"""

import json
import os
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OLLAMA = "http://127.0.0.1:11434"
PORT = int(os.environ.get("ADAPTER_PORT", "8080"))

CHAT_HTML = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZCode on-device AI</title><style>
body{background:#0b0e14;color:#e6e6f0;font-family:system-ui;margin:0;display:flex;
flex-direction:column;height:100vh}header{padding:12px 16px;font-weight:600;
border-bottom:1px solid #232838}#log{flex:1;overflow-y:auto;padding:12px 16px}
.msg{margin:8px 0;padding:10px 12px;border-radius:10px;max-width:88%;white-space:
pre-wrap}.user{background:#1c2030;margin-left:auto}.ai{background:#151a26}
footer{display:flex;gap:8px;padding:10px 12px;border-top:1px solid #232838}
textarea{flex:1;background:#12161f;color:#e6e6f0;border:1px solid #2a3045;
border-radius:8px;padding:10px;resize:none;height:52px}button{background:#6c5ce7;
color:#fff;border:0;border-radius:8px;padding:0 18px;font-size:15px}
</style></head><body><header>ZCode on-device AI &#9679; offline</header>
<div id="log"></div><footer><textarea id="in" placeholder="Ask anything — runs on this phone"></textarea>
<button onclick="send()">Send</button></footer><script>
const log=document.getElementById('log'),input=document.getElementById('in');
const hist=[];
function add(t,c){const d=document.createElement('div');d.className='msg '+c;
d.textContent=t;log.appendChild(d);log.scrollTop=log.scrollHeight;return d;}
input.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send();}});
async function send(){const q=input.value.trim();if(!q)return;input.value='';
add(q,'user');hist.push({role:'user',content:q});const blob=add('…','ai');
try{const r=await fetch('/v1/chat/completions',{method:'POST',headers:
{'Content-Type':'application/json'},body:JSON.stringify({model:'qwen3b',
messages:hist})});const j=await r.json();const t=j.choices?.[0]?.message?.content
||('error: '+JSON.stringify(j).slice(0,200));blob.textContent=t;
hist.push({role:'assistant',content:t});}catch(e){blob.textContent='model server '
+'unreachable — is ollama running? (start-model-server.sh)';}}
</script></body></html>"""


class Adapter(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _direct(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _proxy(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        payload = self.rfile.read(length) if length else None
        req = urllib.request.Request(OLLAMA + self.path, data=payload,
                                     method=self.command)
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=600) as response:
                body = response.read()
                self._direct(response.status, "application/json", body)
        except urllib.error.HTTPError as exc:
            self._direct(exc.code, "application/json", exc.read())
        except (urllib.error.URLError, OSError) as exc:
            payload = json.dumps({"status": "error",
                                  "message": "ollama down: %s" % exc}).encode()
            self._direct(503, "application/json", payload)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            body = json.dumps({"status": "ok", "backend": "ollama",
                               "model": "qwen3b"}).encode()
            self._direct(200, "application/json", body)
        elif path in ("/", "/index.html", "/chat"):
            self._direct(200, "text/html; charset=utf-8",
                         CHAT_HTML.encode("utf-8"))
        else:
            self._proxy()

    def do_POST(self):
        self._proxy()


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Adapter)
    print("contract adapter on 127.0.0.1:%d -> %s" % (PORT, OLLAMA),
          flush=True)
    server.serve_forever()

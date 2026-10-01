"""Small HTTP server for the touchscreen mixer. Stdlib only."""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from .audio import Engine
from .link import PicoLink
from .model import Store

STATIC = Path(__file__).resolve().parent / "static"


def make_handler(store: Store, engine: Engine, link: PicoLink):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args) -> None:
            return

        def do_GET(self) -> None:
            path = urlparse(self.path).path
            if path == "/api/state":
                self._json(self._status())
                return
            if path == "/api/meters":
                self._json(engine.meters())
                return
            if path == "/":
                path = "/index.html"
            self._static(path)

        def do_POST(self) -> None:
            path = urlparse(self.path).path
            try:
                body = self._body()
            except ValueError as exc:
                self._json({"error": str(exc)}, 400)
                return
            try:
                if path == "/api/channel":
                    index = int(body["channel"]) - 1
                    store.update_channel(index, body)
                elif path == "/api/master":
                    store.set_master(int(body["master"]))
                elif path == "/api/preset/save":
                    store.save_preset(str(body["slot"]))
                elif path == "/api/preset/load":
                    store.load_preset(str(body["slot"]))
                else:
                    self._json({"error": "not found"}, 404)
                    return
            except (KeyError, TypeError, ValueError) as exc:
                self._json({"error": str(exc)}, 400)
                return
            self._json(self._status())

        def _status(self) -> dict:
            state = store.snapshot()
            payload = state.to_json()
            payload["presets"] = store.preset_slots()
            payload["pico"] = link.connected
            payload["demo"] = engine.demo
            return payload

        def _body(self) -> dict:
            length = int(self.headers.get("Content-Length", "0"))
            if length > 65536:
                raise ValueError("body too large")
            raw = self.rfile.read(length) if length else b"{}"
            data = json.loads(raw.decode())
            if not isinstance(data, dict):
                raise ValueError("expected an object")
            return data

        def _json(self, payload: dict, code: int = 200) -> None:
            data = json.dumps(payload).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def _static(self, path: str) -> None:
            rel = path.lstrip("/")
            file_path = (STATIC / rel).resolve()
            if STATIC not in file_path.parents and file_path != STATIC:
                self.send_error(404)
                return
            if not file_path.is_file():
                self.send_error(404)
                return
            kind = "text/plain"
            if file_path.suffix == ".html":
                kind = "text/html; charset=utf-8"
            elif file_path.suffix == ".js":
                kind = "text/javascript; charset=utf-8"
            elif file_path.suffix == ".css":
                kind = "text/css; charset=utf-8"
            data = file_path.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

    return Handler


def serve(store: Store, engine: Engine, link: PicoLink, host: str, port: int) -> None:
    httpd = ThreadingHTTPServer((host, port), make_handler(store, engine, link))
    httpd.serve_forever()

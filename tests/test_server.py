import json
import sys
import threading
import unittest
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pi"))

from http.server import ThreadingHTTPServer

from p16mix.audio import Engine
from p16mix.link import PicoLink
from p16mix.model import Store
from p16mix.server import make_handler


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.store = Store()
        self.engine = Engine(self.store, demo=True)
        self.link = PicoLink(self.store, None)
        self.httpd = ThreadingHTTPServer(
            ("127.0.0.1", 0), make_handler(self.store, self.engine, self.link)
        )
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.httpd.server_address
        self.base = f"http://{host}:{port}"

    def tearDown(self):
        self.httpd.shutdown()
        self.thread.join(timeout=2)
        self.httpd.server_close()

    def test_fader_roundtrip_and_page(self):
        page = urllib.request.urlopen(self.base + "/")
        self.assertEqual(page.status, 200)
        self.assertIn(b"P16", page.read())

        body = json.dumps({"channel": 1, "vol": 500, "name": "Vocal"}).encode()
        request = urllib.request.Request(
            self.base + "/api/channel",
            data=body,
            headers={"Content-Type": "application/json"},
        )
        saved = json.load(urllib.request.urlopen(request))
        self.assertEqual(saved["channels"][0]["vol"], 500)
        self.assertEqual(saved["channels"][0]["name"], "Vocal")
        self.assertFalse(saved["pico"])
        self.assertTrue(saved["demo"])

        self.store.save_preset("A")
        self.store.update_channel(0, {"vol": 0})
        request = urllib.request.Request(
            self.base + "/api/preset/load",
            data=json.dumps({"slot": "A"}).encode(),
            headers={"Content-Type": "application/json"},
        )
        loaded = json.load(urllib.request.urlopen(request))
        self.assertEqual(loaded["channels"][0]["vol"], 500)

    def test_demo_render_is_silent_until_a_fader_is_up(self):
        quiet = self.engine.render(8)
        self.assertEqual(quiet, b"\x00" * len(quiet))
        self.store.update_channel(0, {"vol": 1000, "pan": -100})
        self.store.set_master(1000)
        loud = self.engine.render(64)
        self.assertTrue(any(loud))


if __name__ == "__main__":
    unittest.main()

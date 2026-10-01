"""USB serial link to the Pico. Missing hardware just retries."""

from __future__ import annotations

import os
import select
import termios
import threading
import time

from .model import Store


class PicoLink:
    def __init__(self, store: Store, path: str | None) -> None:
        self.store = store
        self.path = path
        self.connected = False
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if not self.path or self._thread is not None:
            return
        self._thread = threading.Thread(target=self._run, name="p16-link", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                fd = _open_serial(self.path or "")
            except OSError:
                self.connected = False
                self._stop.wait(1.0)
                continue
            self.connected = True
            try:
                self._session(fd)
            finally:
                self.connected = False
                os.close(fd)
            self._stop.wait(0.5)

    def _session(self, fd: int) -> None:
        last_ping = 0.0
        buf = b""
        while not self._stop.is_set():
            line = self.store.take_mix_line()
            if line:
                if not _write(fd, (line + "\n").encode()):
                    return
            now = time.monotonic()
            if now - last_ping > 1.0:
                if not _write(fd, b"PING\n"):
                    return
                last_ping = now
            readable, _, _ = select.select([fd], [], [], 0.05)
            if not readable:
                continue
            try:
                chunk = os.read(fd, 256)
            except OSError:
                return
            if not chunk:
                return
            buf += chunk
            while b"\n" in buf:
                raw, buf = buf.split(b"\n", 1)
                text = raw.decode("ascii", "ignore").strip()
                if text == "PONG":
                    self.connected = True


def _open_serial(path: str) -> int:
    fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    attrs = termios.tcgetattr(fd)
    attrs[0] = 0
    attrs[1] = 0
    attrs[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
    attrs[3] = 0
    attrs[6][termios.VMIN] = 0
    attrs[6][termios.VTIME] = 0
    termios.tcsetattr(fd, termios.TCSANOW, attrs)
    return fd


def _write(fd: int, data: bytes) -> bool:
    try:
        view = data
        while view:
            _ready, writable, _err = select.select([], [fd], [], 0.2)
            if not writable:
                return False
            count = os.write(fd, view)
            if count <= 0:
                return False
            view = view[count:]
        return True
    except OSError:
        return False

"""Local headphone preview.

Demo mode synthesizes sixteen tones so the faders do something audible
on the Pi headphone jack before Ultranet audio is wired. The Pico is
still what plays the real Ultranet mix.
"""

from __future__ import annotations

import math
import shutil
import subprocess
import threading

from .mix import mix_frame
from .model import Store

RATE = 48000
CHUNK = 256
# Half of 22-bit full scale, so a centered fader at full is loud but
# a hard-panned full-scale Ultranet sample still fits in 16-bit.
DEMO_AMP = 1 << 20

NOTES = (
    110.00,
    130.81,
    146.83,
    164.81,
    174.61,
    196.00,
    220.00,
    246.94,
    261.63,
    293.66,
    329.63,
    349.23,
    392.00,
    440.00,
    493.88,
    523.25,
)


class Engine:
    def __init__(self, store: Store, demo: bool = True) -> None:
        self.store = store
        self.demo = demo
        self.phases = [0.0] * 16
        self.peaks = [0.0] * 16
        self.out_peak = [0.0, 0.0]
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._run, name="p16-audio", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2)

    def meters(self) -> dict:
        return {
            "in": [round(p, 1) for p in self.peaks],
            "out": [round(p, 1) for p in self.out_peak],
        }

    def render(self, frames: int) -> bytes:
        state = self.store.snapshot()
        gains = state.gains()
        pcm = bytearray()
        for _ in range(frames):
            samples = self._sources()
            self._decay_peaks(samples)
            left, right = mix_frame(samples, gains, state.master)
            self.out_peak[0] = max(self.out_peak[0] * 0.92, min(100.0, abs(left) / 327.67))
            self.out_peak[1] = max(self.out_peak[1] * 0.92, min(100.0, abs(right) / 327.67))
            pcm += int(left).to_bytes(2, "little", signed=True)
            pcm += int(right).to_bytes(2, "little", signed=True)
        return bytes(pcm)

    def _sources(self) -> list[int]:
        if not self.demo:
            return [0] * 16
        samples = []
        for index, freq in enumerate(NOTES):
            self.phases[index] = (self.phases[index] + freq / RATE) % 1.0
            samples.append(int(math.sin(self.phases[index] * math.tau) * DEMO_AMP))
        return samples

    def _decay_peaks(self, samples: list[int]) -> None:
        for index, sample in enumerate(samples):
            level = min(100.0, abs(sample) / float(1 << 21) * 100.0)
            self.peaks[index] = max(self.peaks[index] * 0.90, level)

    def _run(self) -> None:
        player = _open_aplay()
        try:
            while not self._stop.is_set():
                pcm = self.render(CHUNK)
                if player is None:
                    self._stop.wait(CHUNK / RATE)
                    continue
                try:
                    player.stdin.write(pcm)
                except (BrokenPipeError, OSError):
                    player = None
        finally:
            if player is not None and player.stdin:
                player.stdin.close()
            if player is not None:
                player.wait(timeout=1)


def _open_aplay():
    if shutil.which("aplay") is None:
        return None
    try:
        return subprocess.Popen(
            ["aplay", "-q", "-f", "S16_LE", "-r", str(RATE), "-c", "2", "-"],
            stdin=subprocess.PIPE,
        )
    except OSError:
        return None

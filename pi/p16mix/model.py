"""Mixer state: sixteen strips plus a master, saved as JSON."""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass, field
from pathlib import Path

from .mix import VOL_FULL, channel_gains, clamp
from .protocol import encode_mix


@dataclass
class Channel:
    name: str
    vol: int = 0
    pan: int = 0
    mute: bool = False
    solo: bool = False

    def to_json(self) -> dict:
        return {
            "name": self.name,
            "vol": self.vol,
            "pan": self.pan,
            "mute": self.mute,
            "solo": self.solo,
        }


@dataclass
class MixState:
    master: int = 800
    channels: list[Channel] = field(default_factory=list)

    def __post_init__(self) -> None:
        if not self.channels:
            self.channels = [Channel(name=f"Ch {i + 1}") for i in range(16)]
        if len(self.channels) != 16:
            raise ValueError("expected 16 channels")

    def gains(self) -> list[tuple[int, int]]:
        return channel_gains(
            [c.vol for c in self.channels],
            [c.pan for c in self.channels],
            [c.mute for c in self.channels],
            [c.solo for c in self.channels],
        )

    def mix_line(self) -> str:
        return encode_mix(self.master, self.gains())

    def to_json(self) -> dict:
        return {
            "master": self.master,
            "channels": [c.to_json() for c in self.channels],
        }


def _channel_from_json(index: int, raw: dict) -> Channel:
    name = str(raw.get("name") or f"Ch {index + 1}")[:24]
    return Channel(
        name=name,
        vol=clamp(int(raw.get("vol", 0)), 0, VOL_FULL),
        pan=clamp(int(raw.get("pan", 0)), -100, 100),
        mute=bool(raw.get("mute", False)),
        solo=bool(raw.get("solo", False)),
    )


def state_from_json(raw: dict) -> MixState:
    channels_raw = raw.get("channels")
    if not isinstance(channels_raw, list) or len(channels_raw) != 16:
        raise ValueError("state needs 16 channels")
    return MixState(
        master=clamp(int(raw.get("master", 800)), 0, VOL_FULL),
        channels=[_channel_from_json(i, c) for i, c in enumerate(channels_raw)],
    )


class Store:
    """Thread-safe mix, plus named presets in the same file."""

    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self.lock = threading.Lock()
        self.state = MixState()
        self.presets: dict[str, dict] = {}
        self.dirty = True
        if path is not None:
            self.load()

    def preset_slots(self) -> list[str]:
        with self.lock:
            return sorted(self.presets)

    def snapshot(self) -> MixState:
        with self.lock:
            return state_from_json(self.state.to_json())

    def update_channel(self, index: int, fields: dict) -> MixState:
        if index < 0 or index > 15:
            raise ValueError("channel out of range")
        with self.lock:
            channel = self.state.channels[index]
            if "name" in fields and fields["name"] is not None:
                channel.name = str(fields["name"])[:24] or channel.name
            if "vol" in fields and fields["vol"] is not None:
                channel.vol = clamp(int(fields["vol"]), 0, VOL_FULL)
            if "pan" in fields and fields["pan"] is not None:
                channel.pan = clamp(int(fields["pan"]), -100, 100)
            if "mute" in fields and fields["mute"] is not None:
                channel.mute = bool(fields["mute"])
            if "solo" in fields and fields["solo"] is not None:
                channel.solo = bool(fields["solo"])
            self.dirty = True
            self._save_locked()
            return state_from_json(self.state.to_json())

    def set_master(self, master: int) -> MixState:
        with self.lock:
            self.state.master = clamp(int(master), 0, VOL_FULL)
            self.dirty = True
            self._save_locked()
            return state_from_json(self.state.to_json())

    def save_preset(self, slot: str) -> None:
        slot = _slot(slot)
        with self.lock:
            self.presets[slot] = self.state.to_json()
            self._save_locked()

    def load_preset(self, slot: str) -> MixState:
        slot = _slot(slot)
        with self.lock:
            raw = self.presets.get(slot)
            if raw is None:
                raise KeyError(slot)
            self.state = state_from_json(raw)
            self.dirty = True
            self._save_locked()
            return state_from_json(self.state.to_json())

    def take_mix_line(self) -> str | None:
        with self.lock:
            if not self.dirty:
                return None
            self.dirty = False
            return self.state.mix_line()

    def load(self) -> None:
        if self.path is None or not self.path.exists():
            return
        try:
            raw = json.loads(self.path.read_text())
        except (OSError, json.JSONDecodeError):
            return
        try:
            self.state = state_from_json(raw.get("mix", raw))
        except (TypeError, ValueError, KeyError):
            self.state = MixState()
        presets = raw.get("presets", {})
        if isinstance(presets, dict):
            self.presets = {k: v for k, v in presets.items() if k in ("A", "B", "C", "D")}

    def _save_locked(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"mix": self.state.to_json(), "presets": self.presets}
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload))
        tmp.replace(self.path)


def _slot(slot: str) -> str:
    name = str(slot).strip().upper()
    if name not in ("A", "B", "C", "D"):
        raise ValueError("preset slot must be A, B, C, or D")
    return name

"""Line protocol between the Pi and the Pico.

A mix snapshot is one ASCII line:

    MIX <master> <l0>,<r0> <l1>,<r1> ... <l15>,<r15>

master is 0..1000. Each gain is 0..1024. Mute and solo are already
baked into the gains, so the Pico only multiplies and adds.
"""

from __future__ import annotations

from .mix import GAIN_FULL, VOL_FULL, clamp


def encode_mix(master: int, gains: list[tuple[int, int]]) -> str:
    if len(gains) != 16:
        raise ValueError("expected 16 channels")
    master = clamp(int(master), 0, VOL_FULL)
    parts = [f"MIX {master}"]
    for left, right in gains:
        left = clamp(int(left), 0, GAIN_FULL)
        right = clamp(int(right), 0, GAIN_FULL)
        parts.append(f"{left},{right}")
    return " ".join(parts)


def decode_mix(line: str) -> tuple[int, list[tuple[int, int]]]:
    text = line.strip()
    parts = text.split()
    if len(parts) != 18 or parts[0] != "MIX":
        raise ValueError("expected MIX and 16 gain pairs")
    master = int(parts[1])
    if master < 0 or master > VOL_FULL:
        raise ValueError("master out of range")
    gains: list[tuple[int, int]] = []
    for token in parts[2:]:
        left_s, right_s = token.split(",")
        left = int(left_s)
        right = int(right_s)
        if left < 0 or left > GAIN_FULL or right < 0 or right > GAIN_FULL:
            raise ValueError("gain out of range")
        gains.append((left, right))
    return master, gains

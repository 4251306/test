"""16-channel personal-monitor mix.

Volumes are 0..1000. Pan is -100 (hard left) .. 100 (hard right).
Channel gains are equal-power and already include mute and solo.
The frame mixer matches the Pico firmware: 22-bit samples in, 16-bit out.
"""

from __future__ import annotations

import math

GAIN_FULL = 1024
VOL_FULL = 1000
# 22-bit PCM down to 16-bit PCM.
BIT_SHIFT = 64


def clamp(value: int, lo: int, hi: int) -> int:
    return lo if value < lo else hi if value > hi else value


def pan_gains(pan: int) -> tuple[int, int]:
    """Equal-power left/right gains in 0..1024."""
    pan = clamp(pan, -100, 100)
    angle = (pan + 100) / 200.0 * (math.pi / 2.0)
    left = int(round(math.cos(angle) * GAIN_FULL))
    right = int(round(math.sin(angle) * GAIN_FULL))
    return clamp(left, 0, GAIN_FULL), clamp(right, 0, GAIN_FULL)


def channel_gains(
    volumes: list[int],
    pans: list[int],
    mutes: list[bool],
    solos: list[bool],
) -> list[tuple[int, int]]:
    """Fold fader, pan, mute, and solo into sixteen (left, right) gains."""
    if not (len(volumes) == len(pans) == len(mutes) == len(solos) == 16):
        raise ValueError("expected 16 channels")
    any_solo = any(solos)
    gains: list[tuple[int, int]] = []
    for vol, pan, mute, solo in zip(volumes, pans, mutes, solos):
        vol = clamp(int(vol), 0, VOL_FULL)
        if mute or vol == 0 or (any_solo and not solo):
            gains.append((0, 0))
            continue
        left, right = pan_gains(pan)
        gains.append((left * vol // VOL_FULL, right * vol // VOL_FULL))
    return gains


def _trunc_div(value: int, denom: int) -> int:
    """Match C integer division, toward zero."""
    if value >= 0:
        return value // denom
    return -((-value) // denom)


def _to_i16(value: int) -> int:
    return clamp(_trunc_div(value, BIT_SHIFT), -32768, 32767)


def mix_frame(
    samples: list[int],
    gains: list[tuple[int, int]],
    master: int,
) -> tuple[int, int]:
    """Mix one sample frame. samples are signed 22-bit, master is 0..1000."""
    if len(samples) != 16 or len(gains) != 16:
        raise ValueError("expected 16 channels")
    master = clamp(int(master), 0, VOL_FULL)
    left = 0
    right = 0
    for sample, (gain_l, gain_r) in zip(samples, gains):
        sample = int(sample)
        left += sample * int(gain_l)
        right += sample * int(gain_r)
    denom = GAIN_FULL * VOL_FULL
    left = _trunc_div(left * master, denom)
    right = _trunc_div(right * master, denom)
    return _to_i16(left), _to_i16(right)

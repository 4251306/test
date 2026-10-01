"""Ultranet framing from the public reverse-engineering notes.

Ultranet is two AES3-like streams on two twisted pairs. Each stream
runs at 192 kHz and carries eight 48 kHz channels. A subframe holds
22-bit audio. Time slots 4 and 5 (the two LSBs of a 24-bit AES word)
are the stereo-pair index. The AES left/right subframe is the side
of that pair.

Stream 0 (RJ45 pins 1-2) is channels 1-8.
Stream 1 (RJ45 pins 3 and 6) is channels 9-16.

This module turns captured biphase-mark cells back into samples, and
it also demuxes a 24-bit word from an AES receiver chip (AK4114 /
DIX9211), which is the practical way to get Ultranet into a Pico.
"""

from __future__ import annotations

PREAMBLE_X = (0b11100010, 0b00011101)
PREAMBLE_Y = (0b11100100, 0b00011011)
PREAMBLE_Z = (0b11101000, 0b00010111)

_PREAMBLES: list[tuple[str, list[int]]] = []
for _name, _patterns in (("X", PREAMBLE_X), ("Y", PREAMBLE_Y), ("Z", PREAMBLE_Z)):
    for _pattern in _patterns:
        _cells = [(_pattern >> (7 - i)) & 1 for i in range(8)]
        _PREAMBLES.append((_name, _cells))


def _sign_extend_22(value: int) -> int:
    value &= (1 << 22) - 1
    if value & (1 << 21):
        value -= 1 << 22
    return value


def channel_index(stream: int, pair: int, is_left: bool) -> int:
    """Return the 0-based channel for one Ultranet subframe."""
    if stream not in (0, 1) or pair not in (0, 1, 2, 3):
        raise ValueError("stream or pair out of range")
    return stream * 8 + pair * 2 + (0 if is_left else 1)


def demux_word(stream: int, is_left: bool, word24: int) -> tuple[int, int]:
    """Split a 24-bit AES word into (channel, signed 22-bit sample).

    The AES receiver maps wire slots 4..27 onto the 24-bit word, LSB
    first, so the two LSBs are the pair index.
    """
    word24 &= (1 << 24) - 1
    pair = word24 & 0b11
    audio = _sign_extend_22(word24 >> 2)
    return channel_index(stream, pair, is_left), audio


def pack_subframe(
    pair: int,
    sample: int,
    *,
    is_left: bool,
    validity: int = 1,
    user: int = 0,
    status: int = 0,
    previous_level: int = 0,
) -> tuple[list[int], int]:
    """Encode one subframe to biphase-mark cells. Returns (cells, last_level)."""
    sample_u = sample & ((1 << 22) - 1)
    pair &= 0b11
    bits = [pair & 1, (pair >> 1) & 1]
    for shift in range(22):
        bits.append((sample_u >> shift) & 1)
    bits.append(validity & 1)
    bits.append(user & 1)
    bits.append(status & 1)
    bits.append(sum(bits) & 1)

    patterns = PREAMBLE_X if is_left else PREAMBLE_Y
    pattern = patterns[0] if previous_level == 0 else patterns[1]
    cells = [(pattern >> (7 - i)) & 1 for i in range(8)]
    level = cells[-1]
    for bit in bits:
        level ^= 1
        cells.append(level)
        if bit:
            level ^= 1
            cells.append(level)
        else:
            cells.append(level)
    return cells, level


def _cells_to_bits(cells: list[int]) -> list[int]:
    bits = []
    for i in range(0, len(cells) - 1, 2):
        bits.append(0 if cells[i] == cells[i + 1] else 1)
    return bits


def parse_payload(bits: list[int], is_left: bool, stream: int) -> tuple[int, int] | None:
    """Parse the 28 bits that follow a preamble. None if parity fails."""
    if len(bits) < 28 or (sum(bits[:28]) & 1) != 0:
        return None
    pair = bits[0] | (bits[1] << 1)
    sample = 0
    for shift in range(22):
        sample |= bits[2 + shift] << shift
    sample = _sign_extend_22(sample)
    return channel_index(stream, pair, is_left), sample


class StreamDecoder:
    """Find AES preambles in a cell stream and emit (channel, sample)."""

    def __init__(self, stream: int) -> None:
        self.stream = stream
        self._cells: list[int] = []

    def push(self, cells: list[int]) -> list[tuple[int, int]]:
        self._cells.extend(int(c) & 1 for c in cells)
        found: list[tuple[int, int]] = []
        while True:
            start, kind = self._find_preamble()
            if start < 0 or kind is None:
                if len(self._cells) > 16:
                    self._cells = self._cells[-16:]
                break
            need = start + 8 + 56
            if len(self._cells) < need:
                if start > 0:
                    self._cells = self._cells[start:]
                break
            payload = self._cells[start + 8 : start + 8 + 56]
            parsed = parse_payload(
                _cells_to_bits(payload),
                is_left=kind in ("X", "Z"),
                stream=self.stream,
            )
            if parsed is not None:
                found.append(parsed)
            self._cells = self._cells[start + 8 + 56 :]
        return found

    def _find_preamble(self) -> tuple[int, str | None]:
        window = self._cells
        limit = len(window) - 7
        for index in range(max(0, limit)):
            eight = window[index : index + 8]
            for name, cells in _PREAMBLES:
                if eight == cells:
                    return index, name
        return -1, None

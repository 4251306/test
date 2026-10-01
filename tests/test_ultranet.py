import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pi"))

from p16mix.ultranet import StreamDecoder, channel_index, demux_word, pack_subframe


class UltranetTests(unittest.TestCase):
    def test_demux_matches_pair_index(self):
        channel, sample = demux_word(0, True, (1000 << 2) | 0)
        self.assertEqual((channel, sample), (0, 1000))
        channel, sample = demux_word(1, False, (5 << 2) | 3)
        self.assertEqual((channel, sample), (15, 5))
        channel, sample = demux_word(0, False, (1 << 23) | 1)
        self.assertEqual(channel, 3)
        self.assertEqual(sample, -(1 << 21))

    def test_cell_roundtrip(self):
        decoder = StreamDecoder(0)
        level = 0
        cells = []
        expected = []
        for pair in range(4):
            for is_left, sample in ((True, 123456), (False, -2000)):
                frame, level = pack_subframe(
                    pair, sample, is_left=is_left, previous_level=level
                )
                cells.extend(frame)
                expected.append((channel_index(0, pair, is_left), sample))
        self.assertEqual(decoder.push(cells), expected)

    def test_second_stream_is_channels_9_to_16(self):
        self.assertEqual(channel_index(1, 0, True), 8)
        self.assertEqual(channel_index(1, 3, False), 15)


if __name__ == "__main__":
    unittest.main()

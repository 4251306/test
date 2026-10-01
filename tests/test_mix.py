import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pi"))

from p16mix.mix import channel_gains, mix_frame, pan_gains
from p16mix.protocol import decode_mix, encode_mix


class MixTests(unittest.TestCase):
    def test_center_pan_is_equal_power(self):
        left, right = pan_gains(0)
        self.assertEqual(left, right)
        self.assertEqual(left, 724)

    def test_hard_left(self):
        self.assertEqual(pan_gains(-100), (1024, 0))
        self.assertEqual(pan_gains(100), (0, 1024))

    def test_solo_and_mute(self):
        volumes = [1000] * 16
        pans = [0] * 16
        mutes = [False] * 16
        solos = [False] * 16
        solos[2] = True
        mutes[2] = True
        gains = channel_gains(volumes, pans, mutes, solos)
        self.assertEqual(gains, [(0, 0)] * 16)

        mutes[2] = False
        gains = channel_gains(volumes, pans, mutes, solos)
        self.assertNotEqual(gains[2], (0, 0))
        self.assertEqual(gains[0], (0, 0))

    def test_full_scale_matches_pico(self):
        gains = [(1024, 0)] + [(0, 0)] * 15
        left, right = mix_frame([64 * 32767] + [0] * 15, gains, 1000)
        self.assertEqual((left, right), (32767, 0))
        left, right = mix_frame([64 * -32768] + [0] * 15, gains, 1000)
        self.assertEqual((left, right), (-32768, 0))

    def test_protocol_line_matches_firmware_fixture(self):
        line = encode_mix(1000, [(1024, 0)] + [(0, 0)] * 15)
        expected = (
            "MIX 1000 1024,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0 "
            "0,0 0,0 0,0 0,0 0,0 0,0 0,0 0,0"
        )
        self.assertEqual(line, expected)
        self.assertEqual(decode_mix(line), (1000, [(1024, 0)] + [(0, 0)] * 15))

    def test_protocol_rejects_short_line(self):
        with self.assertRaises(ValueError):
            decode_mix("MIX 10 1,2")


if __name__ == "__main__":
    unittest.main()

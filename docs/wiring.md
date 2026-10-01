# Wiring the Pi 4 as a P16

The mixer page runs on the Pi. The Ultranet audio does not.

Ultranet uses a Cat5 plug, but it is not Ethernet. It is two AES3 audio streams, plus DC power on the spare pairs (about 15 V). The Pi 4 network jack is a normal gigabit port. Plugging the Ultranet cable into it will not give you audio, and the power pairs can damage the Pi.

Use two different cables:

| Cable | Where it goes |
| --- | --- |
| Ultranet Cat5, from the X32 / M32 / P16-I / P16-D | RJ45 on the receiver below. Never the Pi. |
| Normal Ethernet, optional | Pi network jack, so a phone on your Wi-Fi can open the mixer page. |

## What you are building

```
Ultranet out (X32, Wing, P16-I, P16-D)
        |
      Cat5
        |
   RJ45 breakout
   pins 1-2  -> AM26LV32 pair A -> Pico GP0   channels 1-8
   pins 3,6  -> AM26LV32 pair B -> Pico GP1   channels 9-16
   pins 4,5,7,8  leave unconnected            about 15 V
        |
      Pico  -------- USB --------  Pi 4  (this screen is the mixer)
        |
      I2S from two AES chips (AK4114 or DIX9211)
        |
      PCM5102 DAC -> headphones
```

The Pi sends fader, pan, mute, solo, and master over USB serial. The Pico applies that mix to the 16 Ultranet channels and plays stereo.

Until the AES receiver is fitted, run the Pi with demo tones. Raising a fader plays that channel on the Pi headphone jack so you can confirm the screen.

## Parts

- Raspberry Pi 4, and a touchscreen if you want the faders on the Pi itself
- Raspberry Pi Pico
- AM26LV32, 3.3 V RS-422 receiver
- RJ45 jack with magnetics
- Two AK4114 or one DIX9211 per Ultranet pair (the same kind of AES receiver Behringer uses)
- PCM5102A module for the headphone out
- USB cable, Pico to Pi
- 100 ohm resistors across each audio pair

Power the Pi and the Pico from a normal USB supply. Do not tap pins 4, 5, 7, and 8 to feed the Pico. Polarity on those pairs is not something to guess, and the voltage is there to run a P16-M.

## RJ45

Pin 1 is on the left of a jack looking into the contacts, latch down. T568B colors:

| Pin | Pair color | Ultranet |
| --- | --- | --- |
| 1 | white/orange | channels 1-8 + |
| 2 | orange | channels 1-8 − |
| 3 | white/green | channels 9-16 + |
| 4 | blue | DC power, do not connect to logic |
| 5 | white/blue | DC power, do not connect to logic |
| 6 | green | channels 9-16 − |
| 7 | white/brown | DC power, do not connect to logic |
| 8 | brown | DC power, do not connect to logic |

Put 100 ohms across pins 1-2, and 100 ohms across pins 3-6, at the receiver. Wire those pairs to the AM26LV32 A/B inputs. The AM26LV32 outputs go to Pico GP0 (channels 1-8) and GP1 (channels 9-16). VCC is Pico 3.3 V. Grounds are common. Enable pins tied high.

Those two GPIO signals are the raw AES streams. A Pico can sample them with PIO, but the reliable bench build is an AK4114 or DIX9211 on each pair. Those chips turn AES into I2S at 192 kHz. The low two bits of each 24-bit word are the stereo-pair index, and the upper 22 bits are the audio. Left/right is the other half of the pair. `p16_demux_word()` in the firmware does that split.

## Pico pins

| Pico | Function |
| --- | --- |
| GP0 | Ultranet channels 1-8, after the line receiver |
| GP1 | Ultranet channels 9-16, after the line receiver |
| USB | To the Pi. Shows up as `/dev/ttyACM0` |
| I2S in | BCLK / LRCLK / DATA from each AES receiver |
| I2S out | PCM5102, stereo headphone mix |

The firmware in `firmware/pico` already speaks the mix protocol and demuxes AES words. The I2S pin program is the part you finish on the bench, because it depends on the receiver chip you fit. The mix math itself is the same code the Pi uses, and it is tested on this repo.

## Pi software

On the Pi:

```bash
sudo apt install python3 alsa-utils
sudo usermod -aG dialout "$USER"
# log out and back in so dialout applies
cd /path/to/this/repo/pi
python3 -m p16mix --demo --serial /dev/ttyACM0
```

Open `http://127.0.0.1:8080` on the Pi, or `http://<pi-ip>:8080` from a phone. `--demo` plays test tones through the Pi headphone jack. When the Pico is doing the real mix, start it with `--no-demo` so the Pi does not also play tones.

`scripts/install-pi.sh` installs a systemd service that starts the page on boot.

Presets A-D are stored in `data/state.json`.

## Why this is not "plug the cable into the Pi"

A P16-M has no IP address and no control port. The cable into a P16-M is audio and power only. The Pi's Ethernet controller only understands normal Ethernet frames, and Ultranet is not frames. Working DIY receivers (Pico PIO projects and FPGA boards) all tap the pairs with a line receiver. They do not use a computer's network port.

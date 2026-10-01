# Pi 4 as a Behringer P16

A P16-M is a 16-channel personal mixer on the end of an Ultranet cable. This project is that mixer on a Raspberry Pi 4: faders, pan, mute, solo, master, and four presets.

The Ultranet cable does **not** plug into the Pi's Ethernet port. Ultranet is not network traffic. It is two digital-audio streams and about 15 V of power inside a Cat5 jacket. The Pi's network jack cannot read it, and that voltage can damage the Pi.

```
Ultranet cable -> line receiver -> Pico -> USB -> Pi 4
                                              |
                                         mixer screen
Pi headphone jack plays demo tones until the Pico has real audio.
Pi Ethernet is only for your normal network.
```

The wiring, the parts, and the pinout are in [docs/wiring.md](docs/wiring.md).

## Try the screen

```bash
cd pi
python3 -m p16mix --demo
```

Open http://127.0.0.1:8080

Channels start down. Raise channel 1 and the master. With `--demo`, that channel is a tone on the Pi headphone jack. Mute, solo, and pan work on the same mix the Pico firmware uses.

`--no-demo` turns the tones off once the Pico is playing the real Ultranet mix through a DAC.

From the Pi, `scripts/install-pi.sh` starts the page on boot.

## What is already tested

- The mix math (fader, equal-power pan, mute, solo, master, 22-bit to 16-bit)
- The `MIX ...` line the Pi sends the Pico
- Ultranet channel numbering, including the 2-bit pair index inside each AES word
- A round trip of a synthetic Ultranet stream back to the sixteen samples
- The mixer page's save/load API

```bash
python3 -m unittest discover -s tests
make -C firmware test
```

## What still needs the bench

The Pico firmware speaks the control protocol and knows how to demux AES words. Hearing the real Ultranet channels still needs the receiver circuit in [docs/wiring.md](docs/wiring.md): an AM26LV32 on the audio pairs, then an AK4114 or DIX9211 per pair into the Pico, then a PCM5102 for headphones. That last I2S hookup depends on the chip you fit, so it is not pretended to be done here.

A P16-M you already own cannot be remote-controlled. It has no control port. This replaces it.

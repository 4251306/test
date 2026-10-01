# Pico firmware

The Pico is the audio end of the mixer. The Pi is the screen.

`common/mix.c`, `common/protocol.c`, and `common/ultranet.c` are plain C. They build and run on this machine:

```bash
make -C firmware test
```

That checks three things the Pico will do:

- parse `MIX 1000 1024,0 0,0 ...` from the Pi
- mix sixteen 22-bit samples down to a 16-bit stereo pair
- split an AES receiver word into a channel number and a 22-bit sample

`pico/src/main.c` is the USB serial loop. It answers `PING` with `PONG` and applies every `MIX` line. Build it on a machine with the Pico SDK:

```bash
export PICO_SDK_PATH=/path/to/pico-sdk
cmake -S firmware/pico -B firmware/pico/build
cmake --build firmware/pico/build
```

Copy `firmware/pico/build/p16mix.uf2` onto the Pico.

The sample buffer starts at zero. Fill `p16_samples[]` from the I2S output of an AK4114 or DIX9211, using `p16_demux_word()`. Pinout and why the Ultranet cable must not go into the Pi are in `docs/wiring.md`.

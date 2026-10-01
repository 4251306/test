"""Run the mixer: python3 -m p16mix"""

from __future__ import annotations

import argparse
from pathlib import Path

from .audio import Engine
from .link import PicoLink
from .model import Store
from .server import serve


def main() -> None:
    parser = argparse.ArgumentParser(description="Pi personal mixer for Ultranet")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--state", type=Path, default=Path("data/state.json"))
    parser.add_argument("--serial", default="/dev/ttyACM0", help="Pico USB serial device")
    parser.add_argument(
        "--demo",
        action="store_true",
        help="Play test tones on the Pi headphone jack (this is the default)",
    )
    parser.add_argument(
        "--no-demo",
        action="store_true",
        help="Do not play test tones on the Pi headphone jack",
    )
    args = parser.parse_args()

    store = Store(args.state)
    engine = Engine(store, demo=not args.no_demo)
    link = PicoLink(store, args.serial)
    engine.start()
    link.start()
    print(f"P16 mixer on http://{args.host}:{args.port}")
    if engine.demo:
        print("Demo tones are on. Raise a fader and listen on the Pi headphone jack.")
    print("The Ultranet cable does not plug into the Pi Ethernet port. See docs/wiring.md.")
    try:
        serve(store, engine, link, args.host, args.port)
    except KeyboardInterrupt:
        pass
    finally:
        engine.stop()
        link.stop()


if __name__ == "__main__":
    main()

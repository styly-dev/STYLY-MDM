#!/usr/bin/env python3
"""Watch for client UDP discovery broadcasts on the LAN.

Prints one timestamped line per received datagram. Useful to prove a client
has gone silent after its connection window expired (issue #67): run this with
the server STOPPED (it binds the same discovery port) and confirm broadcasts
stop arriving once the client shows "Standby (no server found)".

Usage:
    python scripts/watch_discovery.py            # dev port 7081
    python scripts/watch_discovery.py --port 7071  # production port
"""

import argparse
import datetime
import socket


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--port",
        type=int,
        default=7081,
        help="discovery port to listen on (default: 7081, the dev port)",
    )
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", args.port))
    print(f"Listening for discovery broadcasts on UDP {args.port} (Ctrl-C to stop)", flush=True)
    while True:
        data, addr = sock.recvfrom(1024)
        now = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
        print(f"{now}  {addr[0]}  {data!r}", flush=True)


if __name__ == "__main__":
    main()

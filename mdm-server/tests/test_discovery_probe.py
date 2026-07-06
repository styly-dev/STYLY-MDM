"""Tests for the startup discovery-port conflict probe.

These exercise real UDP sockets on the loopback interface, mirroring the
STYLY-NetSync probe tests. A fake server binds the discovery port and answers
``STYLYMDM_DISCOVER`` so the probe has a real responder to detect.
"""

from __future__ import annotations

import json
import socket
import threading
from unittest.mock import patch

from styly_mdm import server


def _find_free_udp_port() -> int:
    """Return a free UDP port on the loopback interface."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("", 0))
        return int(s.getsockname()[1])


def _serve(port: int, payload: bytes, stop_event, ready_event) -> None:
    """Bind ``port`` and reply to each discovery request with ``payload``."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", port))
    sock.settimeout(0.2)
    ready_event.set()
    try:
        while not stop_event.is_set():
            try:
                data, addr = sock.recvfrom(1024)
            except (socket.timeout, TimeoutError):
                continue
            if data.strip() == server.DISCOVERY_REQUEST:
                sock.sendto(payload, addr)
    finally:
        sock.close()


def _start_fake_server(port: int, payload: bytes):
    """Start a fake discovery responder thread; return (thread, stop_event)."""
    stop_event = threading.Event()
    ready_event = threading.Event()
    thread = threading.Thread(
        target=_serve, args=(port, payload, stop_event, ready_event), daemon=True
    )
    thread.start()
    assert ready_event.wait(timeout=2), "Fake discovery server did not start in time"
    return thread, stop_event


def test_no_conflict_returns_none(monkeypatch):
    """No responder on the port -> probe times out and returns None."""
    port = _find_free_udp_port()
    monkeypatch.setattr(server, "DISCOVERY_PORT", port)
    assert server.probe_existing_server(timeout=0.3) is None


def test_detects_existing_server(monkeypatch):
    """A live STYLY-MDM responder is reported by its source IP."""
    port = _find_free_udp_port()
    monkeypatch.setattr(server, "DISCOVERY_PORT", port)
    reply = json.dumps(
        {"service": "stylymdm", "ws_url": "ws://127.0.0.1:7070/ws/device", "version": "1.0"}
    ).encode("utf-8")
    thread, stop_event = _start_fake_server(port, reply)
    try:
        result = server.probe_existing_server(timeout=1.0)
        assert result is not None
        # addr[0] from an AF_INET socket is a dotted-quad IPv4 string.
        assert result.count(".") == 3
    finally:
        stop_event.set()
        thread.join(timeout=2)


def test_ignores_non_matching_reply(monkeypatch):
    """A reply from a non-STYLY-MDM service must not count as a conflict."""
    port = _find_free_udp_port()
    monkeypatch.setattr(server, "DISCOVERY_PORT", port)
    thread, stop_event = _start_fake_server(port, b'{"service":"other"}')
    try:
        assert server.probe_existing_server(timeout=0.5) is None
    finally:
        stop_event.set()
        thread.join(timeout=2)


def test_probe_never_raises_on_socket_error(monkeypatch):
    """A socket failure returns None rather than blocking startup."""
    port = _find_free_udp_port()
    monkeypatch.setattr(server, "DISCOVERY_PORT", port)
    with patch("socket.socket", side_effect=OSError("mock error")):
        assert server.probe_existing_server(timeout=0.3) is None

"""Tests for the address the discovery responder advertises.

A device can only reach the server over the interface it broadcast from, so the
``ws_url`` in a discovery response has to name *that* interface's address. These
cover the two halves of that: picking the source address per requester, and
keeping self-assigned link-local addresses out of the fallback list.
"""

from __future__ import annotations

import json
import socket
from unittest.mock import patch

from styly_mdm import server


class _FakeTransport:
    """Captures what the protocol sends instead of touching the network."""

    def __init__(self) -> None:
        self.sent: list[tuple[bytes, tuple[str, int]]] = []

    def sendto(self, data: bytes, addr: tuple[str, int]) -> None:
        self.sent.append((data, addr))


def _respond_to(peer: str, ip_addresses: list[str]) -> dict:
    """Drive one discovery request from ``peer`` and return the decoded reply."""
    proto = server._UdpDiscoveryProtocol(7070, ip_addresses)
    transport = _FakeTransport()
    proto.connection_made(transport)
    proto.datagram_received(server.DISCOVERY_REQUEST, (peer, 34438))
    assert len(transport.sent) == 1
    payload, addr = transport.sent[0]
    assert addr == (peer, 34438)
    return json.loads(payload.decode("utf-8"))


# ---------------------------------------------------------------------------
# source_ip_for
# ---------------------------------------------------------------------------

def test_source_ip_for_unreachable_peer_is_none_or_routable():
    """Never returns loopback: a client cannot reach the server at 127.0.0.1."""
    ip = server.source_ip_for("127.0.0.1")
    assert ip is None

    # An arbitrary public address either routes out a real interface or does not
    # route at all; both are acceptable, a loopback answer is not.
    ip = server.source_ip_for("203.0.113.1")
    assert ip is None or not ip.startswith("127.")


def test_source_ip_for_malformed_peer_returns_none():
    assert server.source_ip_for("not-an-ip-at-all") is None


# ---------------------------------------------------------------------------
# Discovery response address selection
# ---------------------------------------------------------------------------

def test_response_uses_interface_facing_the_requester():
    """The ICS/multi-homed case: answer with the address on the client's network."""
    with patch.object(server, "source_ip_for", return_value="192.168.137.1"):
        reply = _respond_to("192.168.137.177", ["169.254.23.62", "10.0.0.5"])

    assert reply["service"] == "stylymdm"
    assert reply["ws_url"] == "ws://192.168.137.1:7070/ws/device"


def test_response_falls_back_to_local_list_when_route_lookup_fails():
    with patch.object(server, "source_ip_for", return_value=None):
        reply = _respond_to("192.168.137.177", ["192.168.1.50"])

    assert reply["ws_url"] == "ws://192.168.1.50:7070/ws/device"


def test_response_falls_back_to_wildcard_without_any_address():
    with patch.object(server, "source_ip_for", return_value=None):
        reply = _respond_to("192.168.137.177", [])

    assert reply["ws_url"] == "ws://0.0.0.0:7070/ws/device"


def test_non_discovery_datagram_is_ignored():
    proto = server._UdpDiscoveryProtocol(7070, ["192.168.1.50"])
    transport = _FakeTransport()
    proto.connection_made(transport)
    proto.datagram_received(b"something else", ("192.168.1.9", 5000))
    assert transport.sent == []


# ---------------------------------------------------------------------------
# get_local_ip_addresses
# ---------------------------------------------------------------------------

def _addrinfo(*ips: str) -> list:
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, 0)) for ip in ips]


def test_local_addresses_drop_link_local_and_loopback():
    """169.254.x.x is self-assigned to a dead interface — never advertise it."""
    with patch.object(
        server.socket, "getaddrinfo",
        return_value=_addrinfo("127.0.0.1", "169.254.23.62", "192.168.137.1"),
    ):
        assert server.get_local_ip_addresses() == ["192.168.137.1"]


def test_local_addresses_deduplicate_preserving_order():
    with patch.object(
        server.socket, "getaddrinfo",
        return_value=_addrinfo("10.0.0.5", "192.168.1.50", "10.0.0.5"),
    ):
        assert server.get_local_ip_addresses() == ["10.0.0.5", "192.168.1.50"]


def test_local_addresses_survive_resolution_failure():
    with patch.object(server.socket, "getaddrinfo", side_effect=OSError("no dns")):
        assert server.get_local_ip_addresses() == []

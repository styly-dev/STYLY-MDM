"""Small WebSocket helpers for integration tests."""

from __future__ import annotations

import asyncio
import json
from collections.abc import Callable

import aiohttp


MessagePredicate = Callable[[dict], bool]


def device_row_matches(device_list: dict, device_id: str, **expected: object) -> bool:
    """Return whether a device-list frame has the requested row values."""
    devices = device_list.get("devices")
    if not isinstance(devices, list):
        return False
    row = next(
        (
            device for device in devices
            if isinstance(device, dict) and device.get("device_id") == device_id
        ),
        None,
    )
    if row is None:
        return False
    for key, value in expected.items():
        actual = row.get(key)
        if isinstance(value, dict):
            if not isinstance(actual, dict) or any(
                actual.get(nested_key) != nested_value
                for nested_key, nested_value in value.items()
            ):
                return False
        elif actual != value:
            return False
    return True


async def receive_type(
    ws,
    msg_type: str,
    timeout: float = 2.0,
    predicate: MessagePredicate | None = None,
    seen: list[dict] | None = None,
) -> dict | None:
    """Receive the first matching frame before one total deadline."""
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while True:
        remaining = deadline - loop.time()
        if remaining <= 0:
            return None
        try:
            msg = await asyncio.wait_for(ws.receive(), remaining)
        except asyncio.TimeoutError:
            return None
        if msg.type == aiohttp.WSMsgType.TEXT:
            data = json.loads(msg.data)
            if seen is not None:
                seen.append(data)
            if data.get("type") == msg_type and (predicate is None or predicate(data)):
                return data
        elif msg.type in (
            aiohttp.WSMsgType.CLOSED,
            aiohttp.WSMsgType.CLOSING,
            aiohttp.WSMsgType.ERROR,
        ):
            return None


async def receive_device_list(
    ws,
    device_id: str,
    timeout: float = 2.0,
    **expected: object,
) -> dict | None:
    """Wait for a device-list frame whose row has the requested values."""
    return await receive_type(
        ws,
        "DEVICE_LIST",
        timeout=timeout,
        predicate=lambda message: device_row_matches(message, device_id, **expected),
    )


async def receive_type_recording(
    ws,
    msg_type: str,
    seen: list[dict],
    timeout: float = 2.0,
    predicate: MessagePredicate | None = None,
) -> dict:
    """Receive a matching frame while recording every text frame in order."""
    result = await receive_type(ws, msg_type, timeout, predicate, seen)
    if result is None:
        raise asyncio.TimeoutError
    return result

import asyncio
import json
from types import SimpleNamespace

import pytest
from aiohttp import web

from styly_mdm.device_policy import CommandNotAllowedError
from styly_mdm.push_runtime import RuntimeWebSocketResponse


@pytest.mark.parametrize("message_type", [
    "FUTURE_COMMAND", "PUSH_RESULT_ACK", "PUSH_RESUME_REJECTED",
    "EXECUTE_LAUNCH", "SET_STARTUP_APP",
])
@pytest.mark.parametrize("state", ["canonical", "legacy", "unregistered", "replaced"])
def test_all_device_messages_require_current_authorized_owner(monkeypatch, message_type, state):
    sent = []

    async def send(self, data, compress=None):
        sent.append(json.loads(data))

    monkeypatch.setattr(web.WebSocketResponse, "send_str", send)

    async def body():
        ws = RuntimeWebSocketResponse()
        ws._push_path = "/ws/device"
        ws._push_device_id = "device"
        entry = {
            "ws": ws, "identity_kind": "legacy" if state == "legacy" else "canonical",
            "registration_ready": state != "unregistered",
        }
        session = SimpleNamespace(ws=object() if state == "replaced" else ws)
        ws._push_runtime = SimpleNamespace(
            legacy=SimpleNamespace(provisional_connections={}, devices={"device": entry}),
            sessions={"device": session},
        )
        if state == "canonical":
            await ws.send_str(json.dumps({"type": message_type}))
            assert sent == [{"type": message_type}]
        else:
            with pytest.raises(CommandNotAllowedError):
                await ws.send_str(json.dumps({"type": message_type}))
            assert sent == []

    asyncio.run(body())


@pytest.mark.parametrize("message_type", ["REGISTERED", "REGISTERED_PROVISIONAL", "ERROR"])
def test_registration_responses_can_precede_registration(monkeypatch, message_type):
    sent = []

    async def send(self, data, compress=None):
        sent.append(json.loads(data))

    monkeypatch.setattr(web.WebSocketResponse, "send_str", send)

    async def body():
        ws = RuntimeWebSocketResponse()
        ws._push_path = "/ws/device"
        ws._push_runtime = SimpleNamespace(
            legacy=SimpleNamespace(provisional_connections={}, devices={}), sessions={},
        )
        await ws.send_str(json.dumps({"type": message_type}))
        assert sent == [{"type": message_type}]

    asyncio.run(body())

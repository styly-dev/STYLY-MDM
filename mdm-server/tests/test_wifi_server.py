"""TLS, origin, frozen dispatch, and reconnect behavior for Wi-Fi settings."""

from __future__ import annotations

import asyncio
import ipaddress
import json
import ssl
import uuid
from datetime import datetime, timedelta, timezone

import aiohttp
import pytest
from aiohttp.test_utils import TestServer
from cryptography import x509
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from styly_mdm import server
from styly_mdm.wifi_settings import validate_profile_networks


def _certificate(tmp_path):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = datetime.now(timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=1))
        .not_valid_after(now + timedelta(days=1))
        .add_extension(x509.SubjectAlternativeName([
            x509.DNSName("localhost"),
            x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
        ]), critical=False)
        .sign(key, hashes.SHA256())
    )
    cert_path = tmp_path / "tls-cert.pem"
    key_path = tmp_path / "tls-key.pem"
    cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    ))
    return cert_path, key_path


def test_wifi_tls_delivery_only_frozen_snapshot_and_reconnect(tmp_path, monkeypatch):
    async def body():
        capable_id = "11111111-1111-4111-8111-111111111111"
        old_v1_id = "22222222-2222-4222-8222-222222222222"
        unsupported_id = "33333333-3333-4333-8333-333333333333"
        v2_only_id = "44444444-4444-4444-8444-444444444444"
        offline_id = "55555555-5555-4555-8555-555555555555"
        cert_path, key_path = _certificate(tmp_path)

        monkeypatch.delenv("MDM_ADMIN_TOKEN", raising=False)
        monkeypatch.delenv("MDM_DEVICE_TOKEN", raising=False)
        monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", Fernet.generate_key().decode("ascii"))
        monkeypatch.setenv("MDM_TLS_CERT", str(cert_path))
        monkeypatch.setenv("MDM_TLS_KEY", str(key_path))

        server._apply_data_dir(str(tmp_path / "data"))
        server.devices.clear()
        server.device_registry.clear()
        server.device_groups.clear()
        server.admin_connections.clear()
        server.DATA_DIR.mkdir(parents=True, exist_ok=True)
        server.REGISTRY_PATH.write_text(json.dumps({
            "devices": {},
            "groups": {"Studio": [capable_id, old_v1_id, unsupported_id, v2_only_id, offline_id]},
        }), encoding="utf-8")

        app = server.create_app()
        store = app["wifi_store"]
        profile = store.put_profile("Studio", validate_profile_networks([{
            "ssid": "Studio Net", "security": "WPA_PSK", "password": "studio-secret", "hidden": False,
        }]))
        test_server = TestServer(app)
        await test_server.start_server(ssl=app["tls_context"])
        connector = aiohttp.TCPConnector(ssl=False)
        session = aiohttp.ClientSession(connector=connector, cookie_jar=aiohttp.CookieJar(unsafe=True))
        base_url = str(test_server.make_url("/")).rstrip("/")
        origin = f"https://{test_server.host}:{test_server.port}"

        async def register(device_id, capabilities):
            ws = await session.ws_connect(
                base_url + "/ws/device",
                origin=origin,
            )
            await ws.send_json({
                "type": "REGISTER",
                "identity_scheme": server.IDENTITY_SCHEME,
                "device_id": device_id,
                "model": "Test headset",
                "ip": "127.0.0.1",
                "version_code": 1,
                "version_name": "test",
                "capabilities": capabilities,
            })
            assert (await ws.receive_json(timeout=3))["type"] == "REGISTERED"
            for _ in range(50):
                if server.devices.get(device_id, {}).get("status") == "online":
                    break
                await asyncio.sleep(0.01)
            return ws

        async def assert_no_message(ws):
            try:
                message = await ws.receive(timeout=0.05)
            except asyncio.TimeoutError:
                return
            pytest.fail(f"unexpected WebSocket message: {message.type}")

        class AdminSocket:
            def __init__(self):
                self.messages = []

            async def send_str(self, message):
                self.messages.append(json.loads(message))

        class GatedSocket:
            def __init__(self, wrapped):
                self.original_send = wrapped.send_str
                self.started = asyncio.Event()
                self.release = asyncio.Event()
                self.messages = []
                self.error = None
                self.call_count = 0

            async def send_str(self, message):
                self.messages.append(json.loads(message))
                self.call_count += 1
                if self.call_count == 1:
                    self.started.set()
                    await self.release.wait()
                try:
                    await self.original_send(message)
                except Exception as exc:
                    self.error = repr(exc)
                    raise

        class ReplacementSocket:
            def __init__(self):
                self.closed = False
                self.messages = []

            async def send_str(self, message):
                self.messages.append(json.loads(message))

        sockets = []
        try:
            async with session.get(base_url + "/api/wifi/state") as response:
                assert response.status == 200
                assert (await response.json())["allow_http"] is False

            bad_origin_headers = {"Origin": "https://attacker.example"}
            payload = {"networks": [{
                "ssid": "Studio Net", "security": "WPA_PSK", "password": "studio-secret", "hidden": False,
            }]}
            async with session.put(
                base_url + "/api/wifi/groups/Studio", headers=bad_origin_headers, json=payload,
            ) as response:
                assert response.status == 403
            async with session.delete(
                base_url + "/api/wifi/groups/Studio",
                headers=bad_origin_headers,
                json={"expected_revision": profile["revision"]},
            ) as response:
                assert response.status == 403
            assert store.profile("Studio") == profile

            good_headers = {"Origin": origin}
            async with session.put(
                base_url + "/api/wifi/groups/Studio", headers=good_headers,
                json={**payload, "expected_revision": profile["revision"]},
            ) as response:
                assert response.status == 200
                saved_profile = await response.json()

            capable = await register(capable_id, ["wifi_settings_v3"])
            old_v1 = await register(old_v1_id, ["wifi_settings_v1"])
            unsupported = await register(unsupported_id, [])
            v2_only = await register(v2_only_id, ["wifi_settings_v2"])
            sockets.extend([capable, old_v1, unsupported, v2_only])
            assert server.devices[capable_id]["wifi_settings_v3"] is True
            assert server.devices[v2_only_id]["wifi_settings_v3"] is False

            async with session.post(
                base_url + "/api/wifi/groups/Studio/recovery",
                headers=good_headers,
                json={"expected_revision": profile["revision"]},
            ) as response:
                assert response.status == 409
            assert store.state["operations"] == {}

            stale_revision = profile["revision"]
            stale_change = {"networks": [{
                "ssid": "Stale overwrite", "security": "OPEN", "password": "", "hidden": False,
            }], "expected_revision": stale_revision}
            async with session.put(
                base_url + "/api/wifi/groups/Studio", headers=good_headers, json=stale_change,
            ) as response:
                assert response.status == 409
            assert store.profile("Studio")["revision"] == saved_profile["revision"]
            async with session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers=good_headers,
                json={"expected_revision": stale_revision},
            ) as response:
                assert response.status == 409
            assert store.state["operations"] == {}
            for ws in (capable, old_v1, unsupported, v2_only):
                await assert_no_message(ws)

            async with session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers=good_headers,
                json={"expected_revision": saved_profile["revision"]},
            ) as response:
                assert response.status == 200
                deployed = await response.json()
            assert deployed["online_target_count"] == 4
            assert len(deployed["operations"]) == 4
            result_by_id = {item["device_id"]: item for item in deployed["operations"]}
            assert result_by_id[capable_id]["status"] == "sent"
            assert result_by_id[capable_id]["reason"] is None
            assert result_by_id[old_v1_id] == {
                "operation_id": result_by_id[old_v1_id]["operation_id"],
                "device_id": old_v1_id,
                "status": "failed",
                "reason": "unsupported",
            }
            assert result_by_id[unsupported_id]["status"] == "failed"
            assert result_by_id[unsupported_id]["reason"] == "unsupported"
            assert result_by_id[v2_only_id]["status"] == "failed"
            assert result_by_id[v2_only_id]["reason"] == "unsupported"

            command = await capable.receive_json(timeout=3)
            assert command["type"] == "EXECUTE_WIFI_APPLY"
            assert set(command) == {"type", "operation_id", "revision", "mode", "networks", "recovery_scope"}
            assert command["mode"] == "managed"
            assert command["recovery_scope"] == "mdm_profiles"
            assert command["revision"] == deployed["revision"]
            assert command["networks"][0]["password"] == "studio-secret"
            for ws in (old_v1, unsupported):
                await assert_no_message(ws)

            for device_id, expected in (
                (capable_id, ("sent", None)),
                (old_v1_id, ("failed", "unsupported")),
                (unsupported_id, ("failed", "unsupported")),
                (v2_only_id, ("failed", "unsupported")),
            ):
                delivery = store.state_view({device_id}, set())[device_id]["delivery"]
                assert (delivery["status"], delivery["reason"]) == expected
                operation = store.state["operations"][result_by_id[device_id]["operation_id"]]
                assert "networks" not in operation
                assert "password" not in operation

            async with session.get(base_url + "/api/wifi/state") as response:
                state = await response.json()
            assert state["devices"][capable_id]["delivery"]["status"] == "sent"
            assert "applied_revision" not in state["devices"][capable_id]
            assert "manual" not in state["devices"][capable_id]

            # Recovery registration is a separate, explicit delivery. It sends
            # the saved profile without modifying the group's saved revision.
            before_recovery_profile = store.profile("Studio")
            async with session.post(
                base_url + "/api/wifi/groups/Studio/recovery",
                headers=good_headers,
                json={"expected_revision": saved_profile["revision"]},
            ) as response:
                assert response.status == 200
                recovery_delivery = await response.json()
            assert recovery_delivery["online_target_count"] == 4
            recovery_results = {item["device_id"]: item for item in recovery_delivery["operations"]}
            assert recovery_results[capable_id]["status"] == "sent"
            assert recovery_results[v2_only_id]["reason"] == "unsupported"
            recovery_command = await capable.receive_json(timeout=3)
            assert recovery_command["type"] == "EXECUTE_WIFI_APPLY"
            assert recovery_command["mode"] == "recovery"
            assert recovery_command["recovery_scope"] == "mdm_profiles"
            assert recovery_command["revision"] == saved_profile["revision"]
            assert recovery_command["networks"] == before_recovery_profile["networks"]
            assert store.profile("Studio") == before_recovery_profile
            for ws in (old_v1, unsupported, v2_only):
                await assert_no_message(ws)

            # No result/verification response is required to complete this API call.
            # A late v1 result message is ignored and cannot rewrite delivery state.
            await capable.send_json({
                "type": "WIFI_APPLY_RESULT",
                "operation_id": command["operation_id"],
                "revision": command["revision"],
                "status": "success",
                "reason": "NONE",
                "rolled_back": False,
            })
            await assert_no_message(capable)
            assert store.state_view({capable_id}, set())[capable_id]["delivery"]["status"] == "sent"

            previous_devices = json.loads(json.dumps(store.state["devices"]))
            previous_operation_ids = set(store.state["operations"])
            async with session.delete(
                base_url + "/api/wifi/groups/Studio",
                headers=good_headers,
                json={"expected_revision": deployed["revision"]},
            ) as response:
                assert response.status == 200
                unmanaged_profile = await response.json()
            assert unmanaged_profile["mode"] == "unmanaged"
            assert unmanaged_profile["networks"] == []
            assert unmanaged_profile["revision"] != deployed["revision"]
            assert store.profile("Studio") == {
                "revision": unmanaged_profile["revision"], "mode": "unmanaged", "networks": [],
            }
            assert store.state["devices"] == previous_devices
            assert set(store.state["operations"]) == previous_operation_ids
            async with session.get(base_url + "/api/wifi/groups/Studio") as response:
                assert (await response.json())["mode"] == "unmanaged"
            async with session.get(base_url + "/api/wifi/state") as response:
                state_after_delete = await response.json()
            assert state_after_delete["groups"]["Studio"] == {
                "revision": unmanaged_profile["revision"], "mode": "unmanaged",
            }
            for ws in (capable, old_v1, unsupported, v2_only):
                await assert_no_message(ws)

            async with session.delete(
                base_url + "/api/wifi/groups/Studio",
                headers=good_headers,
                json={"expected_revision": deployed["revision"]},
            ) as response:
                assert response.status == 409
            async with session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers=good_headers,
                json={"expected_revision": deployed["revision"]},
            ) as response:
                assert response.status == 409
            assert set(store.state["operations"]) == previous_operation_ids
            for ws in (capable, old_v1, unsupported, v2_only):
                await assert_no_message(ws)

            async with session.post(
                base_url + "/api/wifi/groups/Studio/recovery",
                headers=good_headers,
                json={"expected_revision": unmanaged_profile["revision"]},
            ) as response:
                assert response.status == 409
            assert set(store.state["operations"]) == previous_operation_ids
            for ws in (capable, old_v1, unsupported, v2_only):
                await assert_no_message(ws)

            async with session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers=good_headers,
                json={"expected_revision": unmanaged_profile["revision"]},
            ) as response:
                assert response.status == 200
                unmanaged_delivery = await response.json()
            unmanaged_command = await capable.receive_json(timeout=3)
            assert unmanaged_command["type"] == "EXECUTE_WIFI_APPLY"
            assert unmanaged_command["mode"] == "unmanaged"
            assert unmanaged_command["revision"] == unmanaged_profile["revision"]
            assert unmanaged_command["networks"] == []
            assert unmanaged_delivery["revision"] == unmanaged_profile["revision"]
            assert store.state["devices"][capable_id]["delivery"]["status"] == "sent"
            for ws in (old_v1, unsupported, v2_only):
                await assert_no_message(ws)

            changed = {"networks": [{
                "ssid": "Studio Net", "security": "WPA_PSK", "password": "studio-secret", "hidden": True,
            }], "expected_revision": unmanaged_profile["revision"]}
            async with session.put(
                base_url + "/api/wifi/groups/Studio", headers=good_headers, json=changed,
            ) as response:
                assert response.status == 200
                current_profile = await response.json()
            assert current_profile["mode"] == "managed"
            assert current_profile["revision"] != unmanaged_profile["revision"]
            for ws in (capable, old_v1, unsupported, v2_only):
                await assert_no_message(ws)

            # A second request exercises a send that is blocked while mutable
            # group/profile/socket state changes. It must retain the captured
            # profile and socket and must not retry against a replacement.
            original_socket = server.devices[capable_id]["ws"]
            gated_socket = GatedSocket(original_socket)
            original_socket.send_str = gated_socket.send_str
            blocked_deploy = asyncio.create_task(session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers=good_headers,
                json={"expected_revision": current_profile["revision"]},
            ))
            await asyncio.wait_for(gated_socket.started.wait(), timeout=3)

            changed_during_send = {"networks": [{
                "ssid": "Studio Net", "security": "WPA_PSK", "password": None, "hidden": False,
            }], "expected_revision": current_profile["revision"]}
            async with session.put(
                base_url + "/api/wifi/groups/Studio", headers=good_headers,
                json=changed_during_send,
            ) as response:
                assert response.status == 200
                profile_during_send = await response.json()
            offline_socket = await register(offline_id, ["wifi_settings_v3"])
            sockets.append(offline_socket)
            replacement_socket = ReplacementSocket()
            server.devices[capable_id]["ws"] = replacement_socket
            mutation_admin = AdminSocket()
            await server.handle_set_group_members(
                mutation_admin,
                {"name": "Studio", "members": [old_v1_id, unsupported_id, v2_only_id, offline_id]},
                app=app,
            )
            assert mutation_admin.messages[-1]["type"] == "GROUP_MEMBERS_SET"

            gated_socket.release.set()
            blocked_response = await blocked_deploy
            async with blocked_response:
                assert blocked_response.status == 200
                blocked_result = await blocked_response.json()
            assert blocked_result["online_target_count"] == 4
            assert blocked_result["revision"] != profile_during_send["revision"]
            blocked_target = next(
                item for item in blocked_result["operations"] if item["device_id"] == capable_id
            )
            assert (blocked_target["status"], blocked_target["reason"]) == (
                "unknown", "socket_send_failed",
            )
            assert gated_socket.messages[0]["networks"][0]["hidden"] is True
            assert gated_socket.error is not None
            assert replacement_socket.messages == []
            await assert_no_message(capable)
            await assert_no_message(offline_socket)
            del original_socket.send_str

            # If a newer attempt exists before an older dispatcher obtains the
            # per-device send lock, the stale command must be skipped.
            server.devices[capable_id]["ws"] = original_socket
            mutation_admin = AdminSocket()
            await server.handle_set_group_members(
                mutation_admin,
                {"name": "Studio", "members": [capable_id, old_v1_id, unsupported_id,
                 v2_only_id, offline_id]},
                app=app,
            )
            stale_lock = app["wifi_send_locks"].setdefault(capable_id, asyncio.Lock())
            await stale_lock.acquire()
            stale_deploy = asyncio.create_task(session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers=good_headers,
                json={"expected_revision": profile_during_send["revision"]},
            ))
            for _ in range(200):
                stale_record = store.state["devices"].get(capable_id, {}).get("delivery", {})
                if stale_record.get("revision") == profile_during_send["revision"]:
                    break
                await asyncio.sleep(0.005)
            else:
                raise AssertionError("stale deployment attempt was not persisted")
            async with session.delete(
                base_url + "/api/wifi/groups/Studio",
                headers=good_headers,
                json={"expected_revision": profile_during_send["revision"]},
            ) as response:
                assert response.status == 200
                stale_test_unmanaged = await response.json()
            newer_deploy = asyncio.create_task(session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers=good_headers,
                json={"expected_revision": stale_test_unmanaged["revision"]},
            ))
            for _ in range(200):
                newer_record = store.state["devices"].get(capable_id, {}).get("delivery", {})
                if newer_record.get("revision") == stale_test_unmanaged["revision"]:
                    break
                await asyncio.sleep(0.005)
            else:
                stale_lock.release()
                raise AssertionError("newer deployment attempt was not persisted")
            stale_lock.release()
            stale_response = await stale_deploy
            async with stale_response:
                assert stale_response.status == 200
                stale_result = await stale_response.json()
            newer_response = await newer_deploy
            async with newer_response:
                assert newer_response.status == 200
                newer_result = await newer_response.json()
            stale_target = next(item for item in stale_result["operations"] if item["device_id"] == capable_id)
            assert (stale_target["status"], stale_target["reason"]) == ("unknown", "superseded")
            stale_skipped_command = await capable.receive_json(timeout=3)
            assert stale_skipped_command["mode"] == "unmanaged"
            assert stale_skipped_command["revision"] == stale_test_unmanaged["revision"]
            for ws in (old_v1, unsupported, v2_only):
                await assert_no_message(ws)

            async with session.put(
                base_url + "/api/wifi/groups/Studio", headers=good_headers,
                json={"expected_revision": stale_test_unmanaged["revision"], "networks": [{
                    "ssid": "Studio Net", "security": "WPA_PSK", "password": "studio-secret", "hidden": False,
                }]},
            ) as response:
                assert response.status == 200
                managed_before_race = await response.json()

            # If a managed send is already in progress, a newer unmanaged send
            # waits for it and is the last command delivered to this device.
            gated_socket = GatedSocket(original_socket)
            original_socket.send_str = gated_socket.send_str
            managed_deploy = asyncio.create_task(session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers=good_headers,
                json={"expected_revision": managed_before_race["revision"]},
            ))
            await asyncio.wait_for(gated_socket.started.wait(), timeout=3)
            async with session.delete(
                base_url + "/api/wifi/groups/Studio",
                headers=good_headers,
                json={"expected_revision": managed_before_race["revision"]},
            ) as response:
                assert response.status == 200
                unmanaged_after_race = await response.json()
            unmanaged_deploy = asyncio.create_task(session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers=good_headers,
                json={"expected_revision": unmanaged_after_race["revision"]},
            ))
            for _ in range(200):
                latest_record = store.state["devices"].get(capable_id, {}).get("delivery", {})
                if latest_record.get("revision") == unmanaged_after_race["revision"]:
                    break
                await asyncio.sleep(0.005)
            else:
                gated_socket.release.set()
                raise AssertionError("unmanaged deployment attempt was not persisted")
            assert len(gated_socket.messages) == 1
            gated_socket.release.set()
            managed_response = await managed_deploy
            async with managed_response:
                assert managed_response.status == 200
                managed_result = await managed_response.json()
            unmanaged_response = await unmanaged_deploy
            async with unmanaged_response:
                assert unmanaged_response.status == 200
                unmanaged_result = await unmanaged_response.json()
            managed_command = await capable.receive_json(timeout=3)
            unmanaged_command = await capable.receive_json(timeout=3)
            managed_target = next(item for item in managed_result["operations"] if item["device_id"] == capable_id)
            unmanaged_target = next(item for item in unmanaged_result["operations"] if item["device_id"] == capable_id)
            assert managed_command["mode"] == "managed"
            assert managed_command["revision"] == managed_before_race["revision"]
            assert managed_command["operation_id"] == managed_target["operation_id"]
            assert unmanaged_command["mode"] == "unmanaged"
            assert unmanaged_command["revision"] == unmanaged_after_race["revision"]
            assert unmanaged_command["networks"] == []
            assert unmanaged_command["operation_id"] == unmanaged_target["operation_id"]
            assert (managed_target["status"], unmanaged_target["status"]) == ("sent", "sent")
            del original_socket.send_str

            # Delivery does not fence later membership, rename, or delete edits.
            admin = AdminSocket()
            await server.handle_set_group_members(
                admin,
                {"name": "Studio", "members": [old_v1_id, unsupported_id, v2_only_id, offline_id]},
                app=app,
            )
            assert admin.messages[-1]["type"] == "GROUP_MEMBERS_SET"
            await server.handle_rename_group(
                admin, {"name": "Studio", "new_name": "Renamed"}, app=app,
            )
            assert admin.messages[-1]["type"] == "GROUP_RENAMED"
            assert store.state_view({capable_id}, set())[capable_id]["delivery"]["status"] == "sent"
            assert store.profile("Renamed") is not None
        finally:
            for ws in sockets:
                await ws.close()
            await session.close()
            await test_server.close()
            server.devices.clear()
            server.device_registry.clear()
            server.device_groups.clear()
            server.admin_connections.clear()

    asyncio.run(body())


def test_wifi_http_still_requires_explicit_opt_in_without_authentication(tmp_path, monkeypatch):
    async def body():
        device_id = "55555555-5555-4555-8555-555555555555"
        cert_path, key_path = _certificate(tmp_path)

        monkeypatch.delenv("MDM_ADMIN_TOKEN", raising=False)
        monkeypatch.delenv("MDM_DEVICE_TOKEN", raising=False)
        monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", Fernet.generate_key().decode("ascii"))
        monkeypatch.setenv("MDM_TLS_CERT", str(cert_path))
        monkeypatch.setenv("MDM_TLS_KEY", str(key_path))
        monkeypatch.delenv("MDM_WIFI_ALLOW_HTTP", raising=False)
        server._apply_data_dir(str(tmp_path / "data"))
        server.devices.clear()
        server.device_registry.clear()
        server.device_groups.clear()
        server.admin_connections.clear()
        server.DATA_DIR.mkdir(parents=True, exist_ok=True)
        server.REGISTRY_PATH.write_text(json.dumps({
            "devices": {}, "groups": {"Studio": [device_id]},
        }), encoding="utf-8")

        # Run the configured TLS app over a deliberately cleartext test listener
        # to verify all Wi-Fi routes fail closed when request.secure is false.
        app = server.create_app()
        assert app["wifi_enabled"] is True
        assert app["wifi_allow_http"] is False
        test_server = TestServer(app)
        await test_server.start_server()
        session = aiohttp.ClientSession()
        base_url = str(test_server.make_url("/")).rstrip("/")
        origin = f"http://{test_server.host}:{test_server.port}"
        ws = None
        admin_ws = None
        try:
            async with session.get(base_url + "/api/wifi/state") as response:
                assert response.status == 403
            async with session.put(
                base_url + "/api/wifi/groups/Studio",
                headers={"Origin": origin},
                json={"networks": [{
                    "ssid": "LAN Wi-Fi", "security": "OPEN", "password": "", "hidden": False,
                }], "expected_revision": None},
            ) as response:
                assert response.status == 403
            admin_ws = await session.ws_connect(base_url + "/ws/admin")
            assert (await admin_ws.receive_json(timeout=3))["type"] == "SERVER_INFO"

            ws = await session.ws_connect(
                base_url + "/ws/device",
                origin=origin,
            )
            await ws.send_json({
                "type": "REGISTER",
                "identity_scheme": server.IDENTITY_SCHEME,
                "device_id": device_id,
                "model": "Test headset",
                "ip": "127.0.0.1",
                "version_code": 1,
                "version_name": "test",
                "capabilities": ["wifi_settings_v3"],
            })
            assert (await ws.receive_json(timeout=3))["type"] == "REGISTERED"
            assert "wifi_authorized" not in server.devices[device_id]
        finally:
            if admin_ws is not None:
                await admin_ws.close()
            if ws is not None:
                await ws.close()
            await session.close()
            await test_server.close()
            server.devices.clear()
            server.device_registry.clear()
            server.device_groups.clear()
            server.admin_connections.clear()

    asyncio.run(body())


def test_wifi_http_opt_in_allows_credential_free_lan_flow(tmp_path, monkeypatch):
    async def body():
        device_id = "66666666-6666-4666-8666-666666666666"
        monkeypatch.delenv("MDM_ADMIN_TOKEN", raising=False)
        monkeypatch.delenv("MDM_DEVICE_TOKEN", raising=False)
        monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", Fernet.generate_key().decode("ascii"))
        monkeypatch.delenv("MDM_TLS_CERT", raising=False)
        monkeypatch.delenv("MDM_TLS_KEY", raising=False)
        monkeypatch.setenv("MDM_WIFI_ALLOW_HTTP", "1")
        server._apply_data_dir(str(tmp_path / "data"))
        server.devices.clear()
        server.device_registry.clear()
        server.device_groups.clear()
        server.admin_connections.clear()
        server.DATA_DIR.mkdir(parents=True, exist_ok=True)
        server.REGISTRY_PATH.write_text(json.dumps({
            "devices": {}, "groups": {"Studio": [device_id], "Blank": []},
        }), encoding="utf-8")

        app = server.create_app()
        assert app["wifi_enabled"] is True
        assert app["wifi_allow_http"] is True
        assert app["tls_context"] is None
        test_server = TestServer(app)
        await test_server.start_server()
        session = aiohttp.ClientSession()
        base_url = str(test_server.make_url("/")).rstrip("/")
        origin = f"http://{test_server.host}:{test_server.port}"
        device_ws = None
        admin_ws = None
        try:
            async with session.get(base_url + "/api/wifi/state") as response:
                assert response.status == 200
                state = await response.json()
                assert state["allow_http"] is True
                assert state["groups"] == {
                    "Blank": {"revision": None, "mode": None},
                    "Studio": {"revision": None, "mode": None},
                }

            async with session.delete(
                base_url + "/api/wifi/groups/Blank",
                headers={"Origin": origin},
                json={"expected_revision": None},
            ) as response:
                assert response.status == 200
                blank_unmanaged = await response.json()
            assert blank_unmanaged["mode"] == "unmanaged"
            assert blank_unmanaged["networks"] == []

            profile = {"networks": [{
                "ssid": "LAN Wi-Fi", "security": "WPA_PSK",
                "password": "lan-secret", "hidden": False,
            }], "expected_revision": None}
            async with session.put(
                base_url + "/api/wifi/groups/Studio",
                headers={"Origin": "http://attacker.example"},
                json=profile,
            ) as response:
                assert response.status == 403
            async with session.put(
                base_url + "/api/wifi/groups/Studio",
                headers={"Origin": origin, "Cookie": "styly_mdm_wifi_session=expired"}, json=profile,
            ) as response:
                assert response.status == 200
                saved = await response.json()
            assert saved["mode"] == "managed"
            assert saved["networks"][0]["password_present"] is True
            assert "password" not in saved["networks"][0]

            admin_ws = await session.ws_connect(base_url + "/ws/admin")
            assert (await admin_ws.receive_json(timeout=3))["type"] == "SERVER_INFO"
            await admin_ws.close()
            admin_ws = await session.ws_connect(base_url + "/ws/admin")
            assert (await admin_ws.receive_json(timeout=3))["type"] == "SERVER_INFO"
            device_ws = await session.ws_connect(base_url + "/ws/device")
            await device_ws.send_json({
                "type": "REGISTER",
                "identity_scheme": server.IDENTITY_SCHEME,
                "device_id": device_id,
                "model": "Test headset",
                "ip": "127.0.0.1",
                "version_code": 1,
                "version_name": "test",
                "capabilities": ["wifi_settings_v3"],
            })
            assert (await device_ws.receive_json(timeout=3))["type"] == "REGISTERED"
            assert "wifi_authorized" not in server.devices[device_id]

            async with session.post(
                base_url + "/api/wifi/groups/Studio/deploy",
                headers={"Origin": origin},
                json={"expected_revision": saved["revision"]},
            ) as response:
                assert response.status == 200
                deployed = await response.json()
            assert deployed["online_target_count"] == 1
            assert deployed["operations"][0]["status"] == "sent"
            command = await device_ws.receive_json(timeout=3)
            assert command["type"] == "EXECUTE_WIFI_APPLY"
            assert command["recovery_scope"] == "mdm_profiles"
            assert command["networks"][0]["password"] == "lan-secret"

            async with session.post(base_url + "/api/wifi/session") as response:
                assert response.status == 404
        finally:
            if device_ws is not None:
                await device_ws.close()
            if admin_ws is not None:
                await admin_ws.close()
            await session.close()
            await test_server.close()
            server.devices.clear()
            server.device_registry.clear()
            server.device_groups.clear()
            server.admin_connections.clear()

    asyncio.run(body())

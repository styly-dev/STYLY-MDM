"""TLS, origin, frozen dispatch, and reconnect behavior for Wi-Fi settings."""

from __future__ import annotations

import asyncio
import ipaddress
import json
import ssl
from datetime import datetime, timedelta, timezone

import aiohttp
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


def test_wifi_tls_origin_dispatch_and_reconnect_verify(tmp_path, monkeypatch):
    async def body():
        admin_token = "test-admin-token"
        device_token = "test-device-token"
        capable_id = "11111111-1111-4111-8111-111111111111"
        unsupported_id = "22222222-2222-4222-8222-222222222222"
        offline_id = "33333333-3333-4333-8333-333333333333"
        cert_path, key_path = _certificate(tmp_path)

        monkeypatch.setenv("MDM_ADMIN_TOKEN", admin_token)
        monkeypatch.setenv("MDM_DEVICE_TOKEN", device_token)
        monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", Fernet.generate_key().decode("ascii"))
        monkeypatch.setenv("MDM_TLS_CERT", str(cert_path))
        monkeypatch.setenv("MDM_TLS_KEY", str(key_path))
        monkeypatch.setenv("MDM_WIFI_OPERATION_TIMEOUT", "60")

        server._apply_data_dir(str(tmp_path / "data"))
        server.devices.clear()
        server.device_registry.clear()
        server.device_groups.clear()
        server.admin_connections.clear()
        server.DATA_DIR.mkdir(parents=True, exist_ok=True)
        server.REGISTRY_PATH.write_text(json.dumps({
            "devices": {},
            "groups": {"Studio": [capable_id, unsupported_id, offline_id]},
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
                headers={"Authorization": f"Bearer {device_token}"},
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
            message = await ws.receive_json(timeout=3)
            assert message["type"] == "REGISTERED"
            for _ in range(50):
                if server.devices.get(device_id, {}).get("status") == "online":
                    break
                await asyncio.sleep(0.01)
            return ws

        sockets = []
        try:
            async with session.get(base_url + "/api/wifi/state") as response:
                assert response.status == 401
                assert await response.json() == {
                    "enabled": True,
                    "error": "authentication required",
                }

            headers = {"Authorization": f"Bearer {admin_token}"}
            bad_origin_headers = {**headers, "Origin": "https://attacker.example"}
            payload = {"networks": [{
                "ssid": "Studio Net", "security": "WPA_PSK", "password": "studio-secret", "hidden": False,
            }]}
            async with session.put(
                base_url + "/api/wifi/groups/Studio", headers=bad_origin_headers, json=payload,
            ) as response:
                assert response.status == 403
            assert store.profile("Studio") == profile

            good_headers = {**headers, "Origin": origin}
            async with session.post(
                base_url + "/api/wifi/session", headers=bad_origin_headers,
            ) as response:
                assert response.status == 403
            async with session.post(
                base_url + "/api/wifi/session", headers=good_headers,
            ) as response:
                assert response.status == 200
                cookie = response.headers["Set-Cookie"]
                assert "HttpOnly" in cookie
                assert "Secure" in cookie
                assert "SameSite=Strict" in cookie
            async with session.get(base_url + "/api/wifi/groups/Studio") as response:
                assert response.status == 200
            # Cookie-authenticated browser mutations still require a same-origin
            # Origin header; an origin-less request cannot reuse the cookie.
            async with session.put(base_url + "/api/wifi/groups/Studio", json=payload) as response:
                assert response.status == 403

            async with session.put(
                base_url + "/api/wifi/groups/Studio", headers=good_headers, json=payload,
            ) as response:
                assert response.status == 200
                saved = await response.json()
            assert saved["revision"] != profile["revision"]
            assert "password" not in saved["networks"][0]

            capable = await register(capable_id, ["wifi_settings_v1"])
            sockets.append(capable)
            unsupported = await register(unsupported_id, [])
            sockets.append(unsupported)

            async with session.post(
                base_url + "/api/wifi/groups/Studio/deploy", headers=good_headers,
            ) as response:
                assert response.status == 200
                deployed = await response.json()
            assert {item["device_id"] for item in deployed["operations"]} == {capable_id, unsupported_id}
            capable_command = await capable.receive_json(timeout=3)
            assert capable_command["type"] == "EXECUTE_WIFI_APPLY"
            assert capable_command["networks"][0]["password"] == "studio-secret"

            ops_by_device = {item["device_id"]: item["operation_id"] for item in deployed["operations"]}
            unsupported_op = store.state["operations"][ops_by_device[unsupported_id]]
            assert unsupported_op["status"] == "failed"
            assert unsupported_op["reason"] == "UNSUPPORTED"
            try:
                unexpected = await unsupported.receive(timeout=0.05)
                assert unexpected.type == aiohttp.WSMsgType.TIMEOUT
            except asyncio.TimeoutError:
                pass

            operation_id = ops_by_device[capable_id]
            operation = store.state["operations"][operation_id]
            frozen_networks = operation["networks"]
            await capable.send_json({
                "type": "WIFI_APPLY_ACCEPTED",
                "operation_id": operation["operation_id"],
            })
            for _ in range(50):
                if store.state["operations"][operation_id]["accepted"]:
                    break
                await asyncio.sleep(0.01)
            assert store.state["operations"][operation_id]["accepted"] is True, {
                "entry_flags": {
                    key: server.devices.get(capable_id, {}).get(key)
                    for key in ("status", "registration_ready", "wifi_settings_v1", "wifi_authorized")
                },
                "ws_closed": capable.closed,
                "dispatch_owner_exists": operation_id in app["wifi_dispatch_owners"],
                "operation": store.state["operations"][operation_id],
            }

            # A later profile edit changes the current revision but cannot mutate
            # the already persisted operation payload.
            changed = {"networks": [{
                "ssid": "Studio Net", "security": "WPA_PSK", "password": None, "hidden": True,
            }]}
            async with session.put(
                base_url + "/api/wifi/groups/Studio", headers=good_headers, json=changed,
            ) as response:
                assert response.status == 200
                current_profile = await response.json()
            assert current_profile["revision"] != operation["revision"]
            assert store.state["operations"][operation_id]["networks"] == frozen_networks

            await capable.close()
            sockets.remove(capable)
            for _ in range(50):
                if capable_id not in server.devices:
                    break
                await asyncio.sleep(0.01)
            assert store.mark_operation_timeout(operation_id)
            assert store.state["operations"][operation_id]["status"] == "unknown"
            assert store.state["operations"][operation_id]["unresolved"] is True

            reconnected = await register(capable_id, ["wifi_settings_v1"])
            sockets.append(reconnected)
            await reconnected.send_json({
                "type": "WIFI_VERIFY_REQUEST",
                "operation_id": operation_id,
                "challenge": "reconnected-challenge",
            })
            verify_ack = await reconnected.receive_json(timeout=3)
            assert verify_ack == {
                "type": "WIFI_VERIFY_ACK",
                "operation_id": operation_id,
                "challenge": "reconnected-challenge",
            }
            await reconnected.send_json({
                "type": "WIFI_APPLY_RESULT",
                "operation_id": operation_id,
                "revision": operation["revision"],
                "status": "unknown",
                "reason": "UNKNOWN",
                "rolled_back": False,
            })
            intermediate_ack = await reconnected.receive_json(timeout=3)
            assert intermediate_ack == {
                "type": "WIFI_RESULT_ACK",
                "kind": "manual",
                "operation_id": operation_id,
                "revision": operation["revision"],
                "status": "unknown",
                "reason": "UNKNOWN",
                "rolled_back": False,
            }
            assert store.state["operations"][operation_id]["unresolved"] is True
            await reconnected.send_json({
                "type": "WIFI_APPLY_RESULT",
                "operation_id": operation_id,
                "revision": operation["revision"],
                "status": "success",
                "reason": "NONE",
                "rolled_back": False,
            })
            result_ack = await reconnected.receive_json(timeout=3)
            assert result_ack == {
                "type": "WIFI_RESULT_ACK",
                "kind": "manual",
                "operation_id": operation_id,
                "revision": operation["revision"],
                "status": "success",
                "reason": "NONE",
                "rolled_back": False,
            }
            assert store.state["operations"][operation_id]["unresolved"] is False
            assert store.state["devices"][capable_id]["applied_revision"] == operation["revision"]

            boot_id = "44444444-4444-4444-8444-444444444444"
            await reconnected.send_json({
                "type": "WIFI_BOOT_RESULT",
                "boot_id": boot_id,
                "revision": operation["revision"],
                "status": "failed",
                "reason": "ADD_FAILED",
            })
            boot_ack = await reconnected.receive_json(timeout=3)
            assert boot_ack == {
                "type": "WIFI_RESULT_ACK",
                "kind": "boot",
                "boot_id": boot_id,
                "revision": operation["revision"],
                "status": "failed",
                "reason": "ADD_FAILED",
            }
            assert offline_id not in {item["device_id"] for item in deployed["operations"]}
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

"""Configuration package lifecycle and policy delivery through the real routes."""

import asyncio
import json
from contextlib import asynccontextmanager
from urllib.parse import urlencode

import aiohttp
from aiohttp.test_utils import TestServer

from styly_mdm import configuration_packages, server


DEVICE_ID = "11111111-1111-4111-8111-111111111111"
OTHER_ID = "22222222-2222-4222-8222-222222222222"
OFFLINE_ID = "33333333-3333-4333-8333-333333333333"
REGISTRATION = {"type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
                "device_id": DEVICE_ID, "capabilities": ["configuration_packages_v1"]}
NO_POLICY = {"type": "CONFIGURATION_POLICY", "package_id": None, "revision": None, "settings": {}}


@asynccontextmanager
async def _running_server(tmp_path, groups=None, **fields):
    server._apply_data_dir(str(tmp_path))
    server.devices.clear()
    server.admin_connections.clear()
    if groups is not None:
        server.REGISTRY_PATH.write_text(json.dumps({"devices": {}, "groups": groups, **fields}))
    async with TestServer(server.create_app()) as test_server, aiohttp.ClientSession() as session:
        yield session, str(test_server.make_url("/")).rstrip("/")


async def _request(session, method, base, path, payload=None, *, status=200, origin=None):
    async with session.request(
        method, base + path, json=payload, headers={"Origin": origin or base},
    ) as response:
        assert response.status == status, await response.text()
        return await response.json()


def _path(profile):
    return "/api/packages/" + profile["package_id"]


async def _create(session, base, name, **kwargs):
    return await _request(session, "POST", base, "/api/packages", {"name": name}, **kwargs)


async def _assign(session, base, group, profile, **kwargs):
    path = "/api/group-package?" + urlencode({"name": group})
    package_id = None if profile is None else profile["package_id"]
    return await _request(session, "PUT", base, path, {"package_id": package_id}, **kwargs)


async def _save(session, base, profile, settings, *, name=None, **kwargs):
    body = {"expected_revision": profile["draft_revision"], "settings": settings}
    if name is not None:
        body["name"] = name
    return await _request(session, "PUT", base, _path(profile), body, **kwargs)


async def _publish(session, base, profile, **kwargs):
    return await _request(session, "POST", base, _path(profile) + "/publish", {
        "expected_revision": profile["draft_revision"], "expected_impact": profile["impact"]["token"],
    }, **kwargs)


def _brightness(value, *, managed=True):
    return {"brightness": {"managed": managed, "value": value}}


def _policy(profile, settings):
    return {"type": "CONFIGURATION_POLICY", "package_id": profile["package_id"],
            "revision": profile["published_revision"], "settings": settings}


async def _receive_type(ws, kind):
    async def receive():
        while True:
            message = await ws.receive_json()
            if message["type"] == kind:
                return message
    return await asyncio.wait_for(receive(), 2)


async def _register(ws, registration=REGISTRATION, *, ack="REGISTERED"):
    await ws.send_json(registration)
    assert (await asyncio.wait_for(ws.receive_json(), 2))["type"] == ack


async def _assert_no_policy(ws):
    async def receive():
        while True:
            frame = await ws.receive()
            if frame.type == aiohttp.WSMsgType.PONG and frame.data == b"processed":
                return
            assert frame.type == aiohttp.WSMsgType.TEXT
            assert json.loads(frame.data)["type"] != "CONFIGURATION_POLICY"
    await ws.ping(b"processed")
    await asyncio.wait_for(receive(), 2)


def test_shared_publish_and_offline_reconnect_use_latest_published_snapshot(tmp_path):
    async def body():
        secret = "office-secret"
        network = {"ssid": "Office", "security": "WPA_PSK", "password": secret, "hidden": False}
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID], "Gallery": [OFFLINE_ID, OTHER_ID]}) as (session, base):
            empty = await _create(session, base, "Office")
            await _assign(session, base, "Studio", empty)
            await _assign(session, base, "Gallery", empty)
            async with (session.ws_connect(base + "/ws/device", autoping=False) as studio,
                        session.ws_connect(base + "/ws/device", autoping=False) as gallery):
                await _register(studio)
                await _register(gallery, {**REGISTRATION, "device_id": OTHER_ID})
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == NO_POLICY
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == NO_POLICY
                first = await _save(session, base, empty, {
                    **_brightness(10),
                    "usbCopy": {"managed": True, "value": True},
                    "developerMenu": {"managed": True, "value": False},
                })
                assert first["impact"]["groups"] == 2 and first["impact"]["devices"] == 3
                await _assert_no_policy(studio)
                old = await _publish(session, base, first)
                first_policy = _policy(old, {"brightness": 10, "usbCopy": True, "developerMenu": False})
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == first_policy
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == first_policy

                second = await _save(session, base, first, {
                    **_brightness(25),
                    "usbCopy": {"managed": True, "value": False},
                    "developerMenu": {"managed": True, "value": True},
                    "wifi": {"managed": True, "value": [network]},
                })
                latest = await _publish(session, base, second)
                assert latest["published_revision"] != old["published_revision"]
                assert set(latest["published_keys"]) == {"brightness", "developerMenu", "usbCopy", "wifi"}
                wanted = _policy(latest, {
                    "brightness": 25, "usbCopy": False, "developerMenu": True, "wifi": [network],
                })
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == wanted
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == wanted

                changed = await _save(session, base, second, {
                    **_brightness(90, managed=False),
                    "usbCopy": {"managed": False, "value": False},
                    "developerMenu": {"managed": False, "value": False},
                    "wifi": {"managed": False, "value": [{k: v for k, v in network.items() if k != "password"}]},
                }, name="New draft name")
                assert changed["published_revision"] == latest["published_revision"]
                assert changed["settings"]["wifi"]["value"][0]["password_present"] is True
                assert secret not in json.dumps([latest, changed])
                await _assert_no_policy(gallery)

        async with _running_server(tmp_path) as (session, base):
            assert await _request(session, "GET", base, _path(changed)) == changed
            async with session.ws_connect(base + "/ws/device") as ws:
                await _register(ws, {**REGISTRATION, "device_id": OFFLINE_ID})
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == wanted
                cleared = await _publish(session, base, changed)
                assert cleared["published_keys"] == []
                assert cleared["settings"] == changed["settings"]
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(cleared, {})
    asyncio.run(body())


def test_mutations_enforce_origin_draft_cas_impact_and_validation(tmp_path):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID], "Gallery": [OTHER_ID]}) as (session, base):
            await _create(session, base, "Forbidden", origin="http://attacker.invalid", status=403)
            initial = await _create(session, base, " Studio ")
            assert initial["name"] == "Studio"
            assert initial["settings"] == {} and initial["draft_revision"] is None
            assert [section["id"] for section in initial["sections"]] == ["basic", "network", "input", "safety", "advanced"]
            await _assign(session, base, "Studio", initial, origin="http://attacker.invalid", status=403)
            await _assign(session, base, "Studio", initial)
            first = await _save(session, base, initial, _brightness(40))
            await _save(session, base, first, _brightness(50), origin="http://attacker.invalid", status=403)
            await _save(session, base, initial, _brightness(50), status=409)
            await _publish(session, base, first, origin="http://attacker.invalid", status=403)

            # A changed offline roster invalidates an impact confirmation.
            async with session.ws_connect(base + "/ws/admin") as admin:
                await admin.send_json({"type": "SET_DEVICE_GROUPS", "device_id": OFFLINE_ID, "groups": ["Studio"]})
                await _receive_type(admin, "DEVICE_GROUPS_SET")
                await _publish(session, base, first, status=409)
                await _assign(session, base, "Gallery", first)
                current = await _request(session, "GET", base, _path(first))
                assert current["impact"]["groups"] == 2 and current["impact"]["devices"] == 3
                # A move between groups sharing a package changes impact even though the counts are the same.
                await admin.send_json({"type": "SET_DEVICE_GROUPS", "device_id": OFFLINE_ID, "groups": ["Gallery"]})
                await _receive_type(admin, "DEVICE_GROUPS_SET")
                await _publish(session, base, current, status=409)

            second = await _save(session, base, first, {
                "screenOff": {"managed": False, "value": -1},
                "sleepDelay": {"managed": True, "value": 15},
                "wifi": {"managed": False, "value": []},
            })
            await _publish(session, base, {**second, "draft_revision": first["draft_revision"]}, status=409)
            published = await _publish(session, base, second)
            await _publish(session, base, second, status=409)
            republished = await _publish(session, base, published)
            assert republished["published_revision"] != published["published_revision"]

            for invalid in (
                _brightness(False),
                {"usbCopy": {"managed": True, "value": "on"}},
                {"developerMenu": {"managed": False, "value": None}},
                {"wifi": {"managed": True, "value": []}},
                {"screenOff": {"managed": True, "value": -1}, "sleepDelay": {"managed": True, "value": 60}},
                {"shutdown": {"managed": True, "value": {"enabled": True, "hour": 23, "minute": 0, "repeat": 0}}},
            ):
                await _save(session, base, republished, invalid, status=400)
            async with session.put(base + _path(first), data=b" " * (server.PACKAGE_REQUEST_MAX_BYTES + 1)) as response:
                assert response.status == 413
            assert await _request(session, "GET", base, _path(first)) == republished
            for path in ("/api/wifi/groups/Studio", "/api/group-settings?name=Studio"):
                async with session.get(base + path) as response:
                    assert response.status == 404
    asyncio.run(body())


def test_group_assignment_and_membership_release_and_referenced_delete(tmp_path):
    async def body():
        group = "A / B? # 日本語"
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID], group: []}) as (session, base):
            first = await _create(session, base, "Office")
            first = await _publish(session, base, await _save(session, base, first, _brightness(30)))
            other = await _create(session, base, "Gallery")
            other = await _publish(session, base, await _save(session, base, other, _brightness(75)))
            assert await _assign(session, base, group, other) == {"package_id": other["package_id"]}
            async with (session.ws_connect(base + "/ws/device", autoping=False) as ws,
                        session.ws_connect(base + "/ws/admin") as admin):
                await _register(ws)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == NO_POLICY
                await _assign(session, base, "Studio", first)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(first, {"brightness": 30})
                await _request(session, "DELETE", base, _path(first), status=409)
                await admin.send_json({"type": "SET_DEVICE_GROUPS", "device_id": DEVICE_ID, "groups": ["Studio", group]})
                assert "only one group" in (await _receive_type(admin, "ERROR"))["message"]
                await admin.send_json({"type": "SET_GROUP_MEMBERS", "name": group, "members": [DEVICE_ID]})
                assert "Reload the console" in (await _receive_type(admin, "ERROR"))["message"]
                await _assert_no_policy(ws)

                await admin.send_json({"type": "MOVE_GROUP_MEMBERS", "name": group, "members": [DEVICE_ID, DEVICE_ID]})
                await _receive_type(admin, "GROUP_MEMBERS_SET")
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(other, {"brightness": 75})
                assert server.device_groups == {"Studio": [], group: [DEVICE_ID]}

                await _assign(session, base, group, None)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == NO_POLICY
                await _assign(session, base, group, first)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(first, {"brightness": 30})

                # Both groups now reference the same publication. The server sends
                # it on the move and the device decides whether to reapply.
                await admin.send_json({"type": "SET_DEVICE_GROUPS", "device_id": DEVICE_ID, "groups": ["Studio"]})
                await _receive_type(admin, "DEVICE_GROUPS_SET")
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(first, {"brightness": 30})

                await admin.send_json({"type": "SET_DEVICE_GROUPS", "device_id": DEVICE_ID, "groups": []})
                await _receive_type(admin, "DEVICE_GROUPS_SET")
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == NO_POLICY
                await _request(session, "DELETE", base, _path(first), status=409)
                assert await _request(session, "DELETE", base, _path(other)) == {"deleted": True}
                await _request(session, "GET", base, _path(other), status=404)
    asyncio.run(body())


def test_publish_updates_admin_metadata_before_device_delivery(tmp_path, monkeypatch):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}) as (session, base):
            secret = "office-secret"
            empty = await _create(session, base, "Office")
            await _assign(session, base, "Studio", empty)
            saved = await _save(session, base, empty, {"wifi": {"managed": True, "value": [{
                "ssid": "Office", "security": "WPA_PSK", "password": secret, "hidden": False,
            }]}})
            released = asyncio.Event()

            async def hold_device_delivery(members):
                assert members == [DEVICE_ID]
                await released.wait()

            monkeypatch.setattr(server, "_publish_configuration", hold_device_delivery)
            async with session.ws_connect(base + "/ws/admin") as other_console:
                assert (await _receive_type(other_console, "GROUP_LIST"))["deployments"] == {}
                request = asyncio.create_task(_publish(session, base, saved))
                try:
                    group_list = await _receive_type(other_console, "GROUP_LIST")
                    assert not request.done()
                    assert group_list["groups"] == {"Studio": [DEVICE_ID]}
                    assert group_list["assignments"] == {"Studio": saved["package_id"]}
                    assert secret not in json.dumps(group_list)
                finally:
                    released.set()
                    published = await request
                assert group_list["deployments"] == {"Studio": {
                    "package_id": saved["package_id"], "revision": published["published_revision"], "keys": ["wifi"],
                }}
                assert await _request(session, "GET", base, "/api/packages") == {"packages": group_list["packages"]}
    asyncio.run(body())


def test_configuration_state_is_sanitized_and_has_no_effect_on_delivery(tmp_path):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID, OTHER_ID, OFFLINE_ID]}) as (session, base):
            empty = await _create(session, base, "Office")
            await _assign(session, base, "Studio", empty)
            published = await _publish(session, base, await _save(session, base, empty, _brightness(45)))
            ids = {"package_id": published["package_id"], "revision": published["published_revision"]}
            reported = {**ids, "status": "complete", "results": {
                "brightness": {"status": "applied", "value": 45, "password": "secret"},
                "wifi": {"status": "failed", "reason": "VERIFY_FAILED", "rolled_back": True,
                         "value": [{"password": "secret"}]},
                "bluetooth": {"status": "unsupported", "value": "secret", "reason": "SECRET_PASSWORD"},
                "developerMenu": {"status": "failed", "reason": "PERMISSION_DENIED"},
                "unexpected": {"status": "applied", "value": "secret"},
            }}
            expected = {**ids, "status": "complete", "results": {
                "brightness": {"status": "applied", "value": 45},
                "wifi": {"status": "failed", "reason": "VERIFY_FAILED", "rolled_back": True},
                "bluetooth": {"status": "unsupported"},
                "developerMenu": {"status": "failed", "reason": "PERMISSION_DENIED"},
            }}
            async with (session.ws_connect(base + "/ws/device", autoping=False) as ws,
                        session.ws_connect(base + "/ws/device", autoping=False) as legacy,
                        session.ws_connect(base + "/ws/device", autoping=False) as old,
                        session.ws_connect(base + "/ws/device", autoping=False) as provisional):
                await _register(ws, {**REGISTRATION, "configuration_state": reported})
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(published, {"brightness": 45})
                await _register(legacy, {"type": "REGISTER", "device_id": OTHER_ID,
                                        "capabilities": REGISTRATION["capabilities"]})
                await _register(old, {**REGISTRATION, "device_id": OFFLINE_ID, "capabilities": ["group_settings_v1"]})
                await _register(provisional, {**REGISTRATION, "device_id": None, "identity": {
                    "state": "provisional", "status": "access_denied", "mint_attempted": False,
                }}, ack="REGISTERED_PROVISIONAL")
                await _assert_no_policy(legacy)
                await _assert_no_policy(old)
                await _assert_no_policy(provisional)
                rows = {row["device_id"]: row for row in json.loads(server.build_device_list_msg())["devices"]}
                assert rows[DEVICE_ID]["configuration_supported"] is True
                assert rows[DEVICE_ID]["configuration_state"] == expected
                assert rows[OTHER_ID]["configuration_supported"] is False
                assert rows[OFFLINE_ID]["configuration_supported"] is False
                await ws.send_json({"type": "CONFIGURATION_STATE",
                                    "configuration_state": {**reported, "package_id": "invalid"}})
                await _assert_no_policy(ws)
                assert server.device_registry[DEVICE_ID]["configuration_state"] == expected

                interrupted = {**ids, "status": "interrupted", "results": {}}
                await ws.send_json({"type": "CONFIGURATION_STATE", "configuration_state": interrupted})
                await _assert_no_policy(ws)
                assert server.device_registry[DEVICE_ID]["configuration_state"] == interrupted

        server.load_registry()
        offline = next(row for row in json.loads(server.build_device_list_msg())["devices"]
                       if row["device_id"] == DEVICE_ID)
        assert offline["status"] == "offline"
        assert offline["configuration_supported"] is False
        assert offline["configuration_state"] == interrupted
    asyncio.run(body())


def test_retry_sends_fresh_id_only_to_current_online_matching_device(tmp_path):
    async def body():
        device_id = "11111111-1111-7111-8111-111111111111"
        async with _running_server(tmp_path, {"Studio": [device_id, OFFLINE_ID]}) as (session, base):
            empty = await _create(session, base, "Office")
            await _assign(session, base, "Studio", empty)
            published = await _publish(session, base, await _save(session, base, empty, _brightness(45)))
            retry = {"device_id": device_id, "package_id": published["package_id"],
                     "revision": published["published_revision"]}
            await _request(session, "POST", base, "/api/package-retry", retry, status=409)
            async with session.ws_connect(base + "/ws/device", autoping=False) as ws:
                await _register(ws, {**REGISTRATION, "device_id": device_id})
                wanted = _policy(published, {"brightness": 45})
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == wanted
                assert await _request(session, "POST", base, "/api/package-retry", retry) == {"sent": True}
                first = await _receive_type(ws, "CONFIGURATION_POLICY")
                first_retry_id = first.pop("retry_id")
                assert configuration_packages.normalize_revision(first_retry_id) is not None
                assert first == wanted
                await _request(session, "POST", base, "/api/package-retry", {**retry, "device_id": OFFLINE_ID}, status=409)
                await _request(session, "POST", base, "/api/package-retry",
                               {**retry, "revision": OTHER_ID}, status=409)
                await _assert_no_policy(ws)
                await _request(session, "POST", base, "/api/package-retry", retry)
                second = await _receive_type(ws, "CONFIGURATION_POLICY")
                second_retry_id = second.pop("retry_id")
                assert configuration_packages.normalize_revision(second_retry_id) is not None
                assert second_retry_id != first_retry_id
                assert second == wanted
                await _assign(session, base, "Studio", None)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == NO_POLICY
                await _request(session, "POST", base, "/api/package-retry", retry, status=409)
                await _assert_no_policy(ws)
    asyncio.run(body())


def test_unreadable_configuration_does_not_release_connected_device(tmp_path):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}, group_settings=None) as (session, base):
            await _request(session, "GET", base, "/api/packages", status=503)
            async with session.ws_connect(base + "/ws/device", autoping=False) as ws:
                await _register(ws)
                await _assert_no_policy(ws)
    asyncio.run(body())


def test_send_gate_resolves_latest_and_rechecks_retry_pair_and_socket(tmp_path):
    class Socket:
        closed = False

        def __init__(self):
            self.messages = []

        async def send_str(self, message):
            self.messages.append(json.loads(message))

    async def body():
        server._apply_data_dir(str(tmp_path))
        server.load_registry()
        package_id = OTHER_ID
        server.device_groups["Studio"] = [DEVICE_ID]
        server._group_assignments["Studio"] = package_id
        lock = asyncio.Lock()
        entry = {"identity_kind": "canonical", "capabilities": REGISTRATION["capabilities"],
                 "status": "online", "registration_ready": True, "ws": Socket(), "configuration_send_lock": lock}
        server.devices[DEVICE_ID] = entry
        try:
            first = configuration_packages.new_snapshot({"brightness": 20})
            latest = configuration_packages.new_snapshot({"brightness": 95})
            server._save_package(package_id, {"name": "Office", "published": first})
            async with lock:
                send = asyncio.create_task(server._send_configuration(DEVICE_ID, entry))
                stale_retry = asyncio.create_task(
                    server._send_configuration(DEVICE_ID, entry, retry=(package_id, first["revision"])),
                )
                await asyncio.sleep(0)
                server._save_package(package_id, {"name": "Office", "published": latest})
            assert await send is True
            assert await stale_retry is False
            assert entry["ws"].messages == [{"type": "CONFIGURATION_POLICY", "package_id": package_id, **latest}]

            async with lock:
                replaced_retry = asyncio.create_task(
                    server._send_configuration(DEVICE_ID, entry, retry=(package_id, latest["revision"])),
                )
                await asyncio.sleep(0)
                server.devices[DEVICE_ID] = {**entry, "ws": Socket()}
            assert await replaced_retry is False
            assert entry["ws"].messages == [{"type": "CONFIGURATION_POLICY", "package_id": package_id, **latest}]

            # Retry can reach the socket while normal fanout is still waiting
            # on the admin broadcast. A Retry frame cannot replace that fanout.
            server.devices[DEVICE_ID] = entry
            server._save_package(package_id, {"name": "Office", "published": first})
            assert await server._send_configuration(DEVICE_ID, entry, retry=(package_id, first["revision"]))
            assert "retry_id" in entry["ws"].messages[-1]
            assert await server._send_configuration(DEVICE_ID, entry)
            assert entry["ws"].messages[-1] == {"type": "CONFIGURATION_POLICY", "package_id": package_id, **first}
        finally:
            server.devices.pop(DEVICE_ID, None)
    asyncio.run(body())

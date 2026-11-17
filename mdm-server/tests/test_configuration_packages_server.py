"""Configuration package lifecycle and policy delivery through the real routes."""

import asyncio
import json
import time
import uuid
from contextlib import asynccontextmanager
from urllib.parse import urlencode

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import configuration_packages, server
from server_test_helpers import reset_server_state


DEVICE_ID = "11111111-1111-4111-8111-111111111111"
OTHER_ID = "22222222-2222-4222-8222-222222222222"
OFFLINE_ID = "33333333-3333-4333-8333-333333333333"
REGISTRATION = {"type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
                "device_id": DEVICE_ID, "capabilities": ["configuration_packages_v1"]}
NO_POLICY = {"type": "CONFIGURATION_POLICY", "package_id": None, "revision": None, "settings": {}}


def _package_profile(name, settings, *, published=True):
    revision = str(uuid.uuid4())
    return {
        "name": name,
        "settings": settings,
        "edit_revision": revision,
        "published_revision": revision if published else None,
        "deployed_at_ms": None,
    }


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


def _deployment_path(group, package_id):
    return "/api/group-package/deploy?" + urlencode({"name": group, "package_id": package_id or ""})


async def _assign(session, base, group, profile, **kwargs):
    package_id = None if profile is None else profile["package_id"]
    path = _deployment_path(group, package_id)
    preview = await _request(session, "GET", base, path)
    return await _request(session, "POST", base, path,
                          {"expected_impact": preview["impact"]["token"]}, **kwargs)


async def _deploy(session, base, profile, *, settings=None, **kwargs):
    if settings is None:
        settings = json.loads(json.dumps(profile["settings"]))
        wifi_value = settings.get("wifi", {}).get("value", {})
        for network in wifi_value.get("networks", []):
            network.pop("password_present", None)
    return await _request(session, "POST", base, _path(profile) + "/save-and-deploy", {
        "expected_revision": profile["edit_revision"], "expected_impact": profile["impact"]["token"],
        "settings": settings,
    }, **kwargs)


async def _deploy_to(session, base, profile, settings, *groups):
    """Save & deploy the settings, then deploy the current version to each group."""
    deployed = await _deploy(session, base, profile, settings=settings)
    for group in groups:
        await _assign(session, base, group, deployed)
    return await _request(session, "GET", base, _path(deployed))


def _brightness(value, *, managed=True):
    return {"brightness": {"managed": managed, "value": value}}


def _wifi_value(*, auto_return=False, hidden=False, networks=None):
    if networks is None:
        networks = [
            {"ssid": "Hidden Office", "security": "WPA2_WPA3", "password": "test-password", "hidden": hidden},
            {"ssid": "Guest", "security": "OPEN", "password": "", "hidden": False},
        ]
    return {"networks": networks, "auto_return": auto_return}


def _wifi_setting(*, managed=True, auto_return=False, hidden=False, networks=None):
    return {"wifi": {"managed": managed, "value": _wifi_value(
        auto_return=auto_return, hidden=hidden, networks=networks,
    )}}


def _group_revision(group):
    return json.loads(server.build_group_list_msg())["deployments"][group]["revision"]


def _policy(profile, settings, *, group=None):
    revision = profile["published_revision"] if group is None else _group_revision(group)
    return {"type": "CONFIGURATION_POLICY", "package_id": profile["package_id"],
            "revision": revision, "settings": settings}


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


def test_device_summary_tracks_group_and_deployment_without_sending_settings_to_summary_only_clients(tmp_path):
    async def body():
        package_id = OFFLINE_ID
        profile = _package_profile("Office", _brightness(45))
        groups = {"Studio": {"members": [DEVICE_ID, OTHER_ID], "package_id": package_id}}
        catalog = {"version": configuration_packages.VERSION, "packages": {package_id: profile}}
        summary = {"type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
                   "device_id": OTHER_ID, "capabilities": [configuration_packages.SUMMARY_CAPABILITY]}
        supported = {**REGISTRATION, "capabilities": [*REGISTRATION["capabilities"],
                                                     configuration_packages.SUMMARY_CAPABILITY]}
        async with _running_server(tmp_path, groups, packages=catalog) as (session, base):
            async with (session.ws_connect(base + "/ws/device") as ws,
                        session.ws_connect(base + "/ws/device") as summary_ws,
                        session.ws_connect(base + "/ws/admin") as admin):
                await _register(ws, supported)
                await _register(summary_ws, summary)
                old = await _receive_type(ws, "CONFIGURATION_POLICY")
                assert old == {"type": "CONFIGURATION_POLICY", "package_id": package_id,
                               "revision": profile["published_revision"], "settings": {"brightness": 45},
                               "group_name": "Studio", "deployed_at_ms": None}
                assert await _receive_type(summary_ws, "CONFIGURATION_POLICY") == {**old, "settings": {}}

                draft = await _request(session, "GET", base, "/api/packages/" + package_id)
                started = time.time_ns() // 1_000_000
                deployed = await _deploy(session, base, draft)
                delivered = await _receive_type(ws, "CONFIGURATION_POLICY")
                deployed_at = delivered["deployed_at_ms"]
                assert started <= deployed_at <= time.time_ns() // 1_000_000
                assert delivered == {**_policy(deployed, {"brightness": 45}),
                                     "group_name": "Studio", "deployed_at_ms": deployed_at}
                assert await _receive_type(summary_ws, "CONFIGURATION_POLICY") == {**delivered, "settings": {}}

                await admin.send_json({"type": "RENAME_GROUP", "name": "Studio", "new_name": "Gallery"})
                await _receive_type(admin, "GROUP_RENAMED")
                renamed = {**delivered, "group_name": "Gallery"}
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == renamed
                assert await _receive_type(summary_ws, "CONFIGURATION_POLICY") == {**renamed, "settings": {}}

        async with _running_server(tmp_path) as (session, base):
            async with (session.ws_connect(base + "/ws/device") as ws,
                        session.ws_connect(base + "/ws/admin") as admin):
                await _register(ws, supported)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == renamed
                await _assign(session, base, "Gallery", None)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == {
                    **NO_POLICY, "group_name": "Gallery", "deployed_at_ms": None,
                }
                await admin.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "Gallery",
                                       "add": {}, "remove": [DEVICE_ID]})
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == {
                    **NO_POLICY, "group_name": None, "deployed_at_ms": None,
                }

    asyncio.run(body())


def test_summary_only_client_gets_unified_wifi_revision_without_settings(tmp_path):
    async def body():
        package_id = OFFLINE_ID
        settings = _wifi_setting(auto_return=False, hidden=True)
        groups = {"Studio": {"members": [OTHER_ID], "package_id": package_id}}
        profile = _package_profile("Office", settings)
        catalog = {"version": configuration_packages.VERSION, "packages": {package_id: profile}}
        summary_client = {
            "type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
            "device_id": OTHER_ID, "capabilities": [configuration_packages.SUMMARY_CAPABILITY],
        }

        async with _running_server(tmp_path, groups, packages=catalog) as (session, base):
            async with session.ws_connect(base + "/ws/device", autoping=False) as ws:
                await _register(ws, summary_client)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == {
                    "type": "CONFIGURATION_POLICY",
                    "package_id": package_id,
                    "revision": profile["published_revision"],
                    "settings": {},
                    "group_name": "Studio",
                    "deployed_at_ms": None,
                }
                assert not server._supports_configuration(server.devices[OTHER_ID])
                await _assert_no_policy(ws)

    asyncio.run(body())


def test_shared_publish_and_offline_reconnect_use_latest_published_snapshot(tmp_path):
    async def body():
        secret = "office-secret"
        network = {"ssid": "Office", "security": "WPA2_WPA3", "password": secret, "hidden": False}
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID], "Gallery": [OFFLINE_ID, OTHER_ID]}) as (session, base):
            empty = await _create(session, base, "Office")
            async with (session.ws_connect(base + "/ws/device", autoping=False) as studio,
                        session.ws_connect(base + "/ws/device", autoping=False) as gallery):
                await _register(studio)
                await _register(gallery, {**REGISTRATION, "device_id": OTHER_ID})
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == NO_POLICY
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == NO_POLICY
                first = await _deploy_to(session, base, empty, {
                    **_brightness(10),
                    "usbCopy": {"managed": True, "value": True},
                    "developerMenu": {"managed": True, "value": False},
                }, "Studio", "Gallery")
                assert first["impact"]["groups"] == 2 and first["impact"]["devices"] == 3
                first_settings = {"brightness": 10, "usbCopy": True, "developerMenu": False}
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == _policy(first, first_settings, group="Studio")
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == _policy(first, first_settings, group="Gallery")

                latest = await _deploy(session, base, first, settings={
                    **_brightness(25),
                    "usbCopy": {"managed": True, "value": False},
                    "developerMenu": {"managed": True, "value": True},
                    "wifi": {"managed": True, "value": _wifi_value(networks=[network])},
                })
                assert latest["published_revision"] != first["published_revision"]
                assert set(latest) == {"package_id", "name", "edit_revision", "published_revision",
                                       "settings", "sections", "impact"}
                wanted = _policy(latest, {
                    "brightness": 25, "usbCopy": False, "developerMenu": True,
                    "wifi": _wifi_value(networks=[network]),
                })
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == wanted
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == wanted
                assert secret not in json.dumps(latest)

        async with _running_server(tmp_path) as (session, base):
            assert await _request(session, "GET", base, _path(latest)) == latest
            async with session.ws_connect(base + "/ws/device") as ws:
                await _register(ws, {**REGISTRATION, "device_id": OFFLINE_ID})
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == wanted
                # Unmanaged settings keep their values, including the saved Wi-Fi password.
                cleared = await _deploy(session, base, latest, settings={
                    **_brightness(90, managed=False),
                    "usbCopy": {"managed": False, "value": False},
                    "developerMenu": {"managed": False, "value": False},
                    "wifi": {"managed": False, "value": _wifi_value(
                        networks=[{k: v for k, v in network.items() if k != "password"}],
                    )},
                })
                assert cleared["settings"]["wifi"]["value"]["networks"][0]["password_present"] is True
                assert secret not in json.dumps(cleared)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(cleared, {})
    asyncio.run(body())


def test_mutations_enforce_origin_draft_cas_impact_and_validation(tmp_path):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID], "Gallery": [OTHER_ID]}) as (session, base):
            await _create(session, base, "Forbidden", origin="http://attacker.invalid", status=403)
            initial = await _create(session, base, " Studio ")
            assert initial["name"] == "Studio"
            assert initial["settings"] == {} and initial["edit_revision"] is None
            assert [section["id"] for section in initial["sections"]] == ["basic", "network", "input", "safety", "advanced"]
            first = await _deploy(session, base, initial, settings=_brightness(40))
            await _assign(session, base, "Studio", first, origin="http://attacker.invalid", status=403)
            await _assign(session, base, "Studio", first)
            first = await _request(session, "GET", base, _path(first))
            await _deploy(session, base, first, settings=_brightness(50), origin="http://attacker.invalid", status=403)
            await _deploy(session, base, {**first, "edit_revision": None}, settings=_brightness(50), status=409)

            # Both roster changes and moves within a shared package invalidate confirmation.
            async with session.ws_connect(base + "/ws/admin") as admin:
                await admin.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "Studio",
                                       "add": {OFFLINE_ID: None}, "remove": []})
                await _receive_type(admin, "GROUP_MEMBERS_SET")
                await _deploy(session, base, first, status=409)
                await _assign(session, base, "Gallery", first)
                current = await _request(session, "GET", base, _path(first))
                assert current["impact"]["groups"] == 2 and current["impact"]["devices"] == 3
                await admin.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "Gallery",
                                       "add": {OFFLINE_ID: "Studio"}, "remove": []})
                await _receive_type(admin, "GROUP_MEMBERS_SET")
                await _deploy(session, base, current, status=409)

            current = await _request(session, "GET", base, _path(first))
            published = await _deploy(session, base, current, settings={
                "screenOff": {"managed": False, "value": -1},
                "sleepDelay": {"managed": True, "value": 300},
                "wifi": {"managed": False, "value": _wifi_value(networks=[])},
            })
            await _deploy(session, base, current, status=409)
            republished = await _deploy(session, base, published)
            assert republished["published_revision"] != published["published_revision"]

            for invalid in (
                _brightness(False),
                {"usbCopy": {"managed": True, "value": "on"}},
                {"developerMenu": {"managed": False, "value": None}},
                {"wifi": {"managed": True, "value": _wifi_value(networks=[])}},
                {"screenOff": {"managed": True, "value": -1}, "sleepDelay": {"managed": True, "value": 300}},
                {"shutdown": {"managed": True, "value": {"enabled": True, "hour": 23, "minute": 0, "repeat": 0}}},
            ):
                await _deploy(session, base, republished, settings=invalid, status=400)
            async with session.post(base + _path(first) + "/save-and-deploy",
                                    data=b" " * (server.PACKAGE_REQUEST_MAX_BYTES + 1)) as response:
                assert response.status == 413
            assert await _request(session, "GET", base, _path(first)) == republished
            for method, path, status in (
                ("PUT", _path(first), 405),
                ("POST", _path(first) + "/publish", 404),
                ("PUT", "/api/group-package?name=Studio", 404),
                ("GET", "/api/wifi/groups/Studio", 404),
                ("GET", "/api/group-settings?name=Studio", 404),
            ):
                async with session.request(method, base + path) as response:
                    assert response.status == status
    asyncio.run(body())


def test_save_and_deploy_commits_draft_and_published_settings_together(tmp_path, monkeypatch):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}) as (session, base):
            empty = await _create(session, base, "Studio")
            package = await _deploy_to(session, base, empty, _brightness(20), "Studio")
            path = _path(package) + "/save-and-deploy"
            request = {
                "expected_revision": package["edit_revision"], "expected_impact": package["impact"]["token"],
                "settings": _brightness(40), "name": "Bright studio",
            }
            async with session.ws_connect(base + "/ws/device", autoping=False) as ws:
                await _register(ws)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(package, {"brightness": 20}, group="Studio")
                await _request(session, "POST", base, path, request, status=403, origin="http://other.invalid")
                await _request(session, "POST", base, path, {**request, "expected_impact": "stale"}, status=409)
                committed = server.REGISTRY_PATH.read_bytes()
                with monkeypatch.context() as patch:
                    patch.setattr(server, "_write_registry", lambda *args: False)
                    await _request(session, "POST", base, path, request, status=500)
                assert await _request(session, "GET", base, _path(package)) == package
                assert server.REGISTRY_PATH.read_bytes() == committed
                await _assert_no_policy(ws)

                deployed = await _request(session, "POST", base, path, request)
                assert deployed["name"] == "Bright studio"
                assert deployed["edit_revision"] != package["edit_revision"]
                assert deployed["published_revision"] != package["published_revision"]
                assert deployed["settings"] == _brightness(40)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(deployed, {"brightness": 40})
                await _request(session, "POST", base, path, request, status=409)
                await _assert_no_policy(ws)

    asyncio.run(body())


def test_group_deploy_assigns_atomically_redeploys_and_reuses_the_current_version(tmp_path, monkeypatch):
    async def body():
        groups = {"Studio": [DEVICE_ID], "Gallery": [OTHER_ID], "Later": [OFFLINE_ID]}
        async with _running_server(tmp_path, groups) as (session, base):
            unpublished = await _create(session, base, "New")
            response = await _request(session, "GET", base, _deployment_path("Studio", unpublished["package_id"]),
                                      status=409)
            assert response == {"error": "Edit this package and select Save & deploy to deploy it first."}
            empty = await _create(session, base, "Shared")
            package = await _deploy_to(session, base, empty, _brightness(30), "Gallery")
            package_id = package["package_id"]

            def path(group):
                return _deployment_path(group, package_id)

            async with (session.ws_connect(base + "/ws/device", autoping=False) as studio,
                        session.ws_connect(base + "/ws/device", autoping=False) as gallery):
                await _register(studio)
                await _register(gallery, {**REGISTRATION, "device_id": OTHER_ID})
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == NO_POLICY
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == _policy(package, {"brightness": 30}, group="Gallery")
                preview = await _request(session, "GET", base, path("Studio"))
                assert preview["use_current"] is True
                assert (preview["impact"]["groups"], preview["impact"]["devices"]) == (1, 1)
                # A newer version invalidates a preview bound to the previous one.
                package = await _deploy(session, base, package, settings=_brightness(40))
                policy = _policy(package, {"brightness": 40})
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == policy
                await _request(session, "POST", base, path("Studio"),
                               {"expected_impact": preview["impact"]["token"]}, status=409)
                preview = await _request(session, "GET", base, path("Studio"))
                request = {"expected_impact": preview["impact"]["token"]}
                committed = server.REGISTRY_PATH.read_bytes()
                with monkeypatch.context() as patch:
                    patch.setattr(server, "_write_registry", lambda *args: False)
                    await _request(session, "POST", base, path("Studio"), request, status=500)
                assert server.REGISTRY_PATH.read_bytes() == committed
                assert "Studio" not in json.loads(server.build_group_list_msg())["assignments"]
                assert await _request(session, "GET", base, _path(package)) == package
                await _assert_no_policy(studio)
                await _assert_no_policy(gallery)

                assert await _request(session, "POST", base, path("Studio"), request) == {"package_id": package_id}
                scoped_policy = _policy(package, {"brightness": 40}, group="Studio")
                assert scoped_policy["revision"] != package["published_revision"]
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == scoped_policy
                assert _group_revision("Gallery") == package["published_revision"]
                await _assert_no_policy(gallery)

                # Deploying the group's current package republishes the same settings
                # as a new revision to every group that uses it.
                preview = await _request(session, "GET", base, path("Studio"))
                assert preview["use_current"] is False
                assert (preview["impact"]["groups"], preview["impact"]["devices"]) == (2, 2)
                await _request(session, "POST", base, path("Studio"), {"expected_impact": preview["impact"]["token"]})
                redeployed = await _request(session, "GET", base, _path(package))
                assert redeployed["published_revision"] != package["published_revision"]
                assert redeployed["settings"] == package["settings"]
                policy = _policy(redeployed, {"brightness": 40})
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == policy
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == policy

                preview = await _request(session, "GET", base, path("Later"))
                assert preview["use_current"] is True
                assert (preview["impact"]["groups"], preview["impact"]["devices"]) == (1, 1)
                await _request(session, "POST", base, path("Later"), {"expected_impact": preview["impact"]["token"]})
                assert (await _request(session, "GET", base, _path(package)))["published_revision"] == redeployed["published_revision"]
                await _assert_no_policy(studio)
                await _assert_no_policy(gallery)
                async with session.ws_connect(base + "/ws/device") as later:
                    await _register(later, {**REGISTRATION, "device_id": OFFLINE_ID})
                    assert await _receive_type(later, "CONFIGURATION_POLICY") == _policy(redeployed, {"brightness": 40}, group="Later")

    asyncio.run(body())


def test_deploy_no_package_clears_management_for_only_the_selected_group(tmp_path):
    async def body():
        groups = {"Studio": [DEVICE_ID, OFFLINE_ID], "Gallery": [OTHER_ID]}
        async with _running_server(tmp_path, groups) as (session, base):
            package = await _create(session, base, "Shared")
            package = await _deploy_to(session, base, package, _brightness(40), "Studio", "Gallery")
            path = "/api/group-package/deploy?name=Studio&package_id="
            await _request(session, "GET", base, "/api/group-package/deploy?name=Studio", status=400)

            async with (session.ws_connect(base + "/ws/device", autoping=False) as studio,
                        session.ws_connect(base + "/ws/device", autoping=False) as gallery):
                await _register(studio)
                await _register(gallery, {**REGISTRATION, "device_id": OTHER_ID})
                gallery_policy = _policy(package, {"brightness": 40}, group="Gallery")
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == _policy(package, {"brightness": 40}, group="Studio")
                assert await _receive_type(gallery, "CONFIGURATION_POLICY") == gallery_policy
                preview = await _request(session, "GET", base, path)
                assert preview["clear"] is True
                assert (preview["impact"]["groups"], preview["impact"]["devices"]) == (1, 2)
                request = {"expected_impact": preview["impact"]["token"]}
                assert await _request(session, "POST", base, path, request) == {"package_id": None}
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == NO_POLICY
                await _assert_no_policy(gallery)
                snapshot = json.loads(server.build_group_list_msg())
                assert set(snapshot["groups"]["Studio"]) == set(groups["Studio"])
                assert snapshot["assignments"] == {"Gallery": package["package_id"]}
                assert snapshot["deployments"]["Gallery"]["revision"] == gallery_policy["revision"]

                await _request(session, "POST", base, path, request, status=409)
                preview = await _request(session, "GET", base, path)
                await _request(session, "POST", base, path, {"expected_impact": preview["impact"]["token"]})
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == NO_POLICY
                async with session.ws_connect(base + "/ws/device") as later:
                    await _register(later, {**REGISTRATION, "device_id": OFFLINE_ID})
                    assert await _receive_type(later, "CONFIGURATION_POLICY") == NO_POLICY

    asyncio.run(body())


def test_reassigning_after_no_package_issues_a_fresh_revision_for_an_offline_device(tmp_path):
    async def body():
        groups = {"Studio": [DEVICE_ID], "Gallery": [OTHER_ID]}
        async with _running_server(tmp_path, groups) as (session, base):
            empty = await _create(session, base, "Shared")
            package = await _deploy_to(session, base, empty, _brightness(40), "Studio", "Gallery")
            settings = {"brightness": 40}
            original = _policy(package, settings, group="Studio")
            async with session.ws_connect(base + "/ws/device") as studio:
                await _register(studio)
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == original

            async with session.ws_connect(base + "/ws/device", autoping=False) as gallery:
                await _register(gallery, {**REGISTRATION, "device_id": OTHER_ID})
                gallery_policy = await _receive_type(gallery, "CONFIGURATION_POLICY")
                await _assign(session, base, "Studio", None)
                await _assign(session, base, "Studio", package)
                replacement = _policy(package, settings, group="Studio")
                assert replacement["revision"] != original["revision"]
                assert _group_revision("Gallery") == gallery_policy["revision"]
                assert await _request(session, "GET", base, _path(package)) == package
                await _assert_no_policy(gallery)

        async with _running_server(tmp_path) as (session, base):
            async with session.ws_connect(base + "/ws/device") as studio:
                await _register(studio)
                assert _group_revision("Studio") == replacement["revision"]
                assert await _receive_type(studio, "CONFIGURATION_POLICY") == replacement
                await _request(session, "POST", base, "/api/package-retry", {
                    "device_id": DEVICE_ID, "package_id": package["package_id"], "revision": original["revision"],
                }, status=409)

    asyncio.run(body())


def test_group_assignment_and_membership_release_and_referenced_delete(tmp_path):
    async def body():
        group = "A / B? # 日本語"
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID], group: []}) as (session, base):
            first = await _create(session, base, "Office")
            first = await _deploy(session, base, first, settings=_brightness(30))
            other = await _create(session, base, "Gallery")
            other = await _deploy(session, base, other, settings=_brightness(75))
            assert await _assign(session, base, group, other) == {"package_id": other["package_id"]}
            async with (session.ws_connect(base + "/ws/device", autoping=False) as ws,
                        session.ws_connect(base + "/ws/admin") as admin):
                await _register(ws)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == NO_POLICY
                await _assign(session, base, "Studio", first)
                studio_policy = _policy(first, {"brightness": 30}, group="Studio")
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == studio_policy
                await _request(session, "DELETE", base, _path(first), status=409)
                # A move must name the current owner, so a device never lands in two groups.
                await admin.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": group,
                                       "add": {DEVICE_ID: None}, "remove": []})
                assert "Reload the console" in (await _receive_type(admin, "ERROR"))["message"]
                await _assert_no_policy(ws)

                await admin.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": group,
                                       "add": {DEVICE_ID: "Studio"}, "remove": []})
                updated = await _receive_type(admin, "GROUP_MEMBERS_SET")
                assert updated["operation"] == "UPDATE_GROUP_MEMBERS"
                assert updated["name"] == group
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(other, {"brightness": 75}, group=group)
                assert json.loads(server.build_group_list_msg())["groups"] == {"Studio": [], group: [DEVICE_ID]}

                await _assign(session, base, group, None)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == NO_POLICY
                await _assign(session, base, group, first)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(first, {"brightness": 30}, group=group)

                # The move receives the destination group's existing deployment.
                await admin.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "Studio",
                                       "add": {DEVICE_ID: group}, "remove": []})
                await _receive_type(admin, "GROUP_MEMBERS_SET")
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == studio_policy

                await admin.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "Studio",
                                       "add": {}, "remove": [DEVICE_ID]})
                await _receive_type(admin, "GROUP_MEMBERS_SET")
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == NO_POLICY
                await _request(session, "DELETE", base, _path(first), status=409)
                assert await _request(session, "DELETE", base, _path(other)) == {"deleted": True}
                await _request(session, "GET", base, _path(other), status=404)
    asyncio.run(body())


@pytest.mark.parametrize("disconnect_on_ack", [False, True], ids=["saved", "console-disconnected"])
def test_membership_save_precedes_policy_delivery(tmp_path, monkeypatch, disconnect_on_ack):
    """The durable save precedes delivery; console replies and delivery do not wait on each other."""
    async def body():
        release_delivery = asyncio.Event()
        delivery_started = asyncio.Event()
        ack_failed = asyncio.Event()
        publish_configuration = server._publish_configuration

        async def hold_delivery(device_ids):
            assert device_ids == [DEVICE_ID]
            delivery_started.set()
            await release_delivery.wait()
            await publish_configuration(device_ids)

        async with _running_server(tmp_path, {"Gallery": []}) as (session, base):
            package = await _create(session, base, "Gallery")
            package = await _deploy(session, base, package, settings=_brightness(75))
            await _assign(session, base, "Gallery", package)

            async with (session.ws_connect(base + "/ws/device") as device,
                        session.ws_connect(base + "/ws/admin") as admin):
                await _register(device)
                assert await _receive_type(device, "CONFIGURATION_POLICY") == NO_POLICY
                assert (await _receive_type(admin, "GROUP_LIST"))["groups"] == {"Gallery": []}
                monkeypatch.setattr(server, "_publish_configuration", hold_delivery)
                if disconnect_on_ack:
                    send_str = server.web.WebSocketResponse.send_str

                    async def fail_save_ack(ws, data, compress=None):
                        if json.loads(data)["type"] == "GROUP_MEMBERS_SET":
                            ack_failed.set()
                            raise ConnectionResetError("Console disconnected before receiving the save acknowledgment")
                        await send_str(ws, data, compress=compress)

                    monkeypatch.setattr(server.web.WebSocketResponse, "send_str", fail_save_ack)

                try:
                    await admin.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "Gallery",
                                           "add": {DEVICE_ID: None}, "remove": []})
                    assert (await _receive_type(admin, "GROUP_LIST"))["groups"] == {"Gallery": [DEVICE_ID]}
                    if not disconnect_on_ack:
                        ack = await _receive_type(admin, "GROUP_MEMBERS_SET")
                        assert ack["operation"] == "UPDATE_GROUP_MEMBERS"
                        assert ack["members"] == [DEVICE_ID]
                    await asyncio.wait_for(delivery_started.wait(), 2)
                    if disconnect_on_ack:
                        await asyncio.wait_for(ack_failed.wait(), 2)
                    stored_groups = json.loads(server.REGISTRY_PATH.read_text())["groups"]
                    assert stored_groups["Gallery"]["members"] == [DEVICE_ID]
                finally:
                    release_delivery.set()
                assert await _receive_type(device, "CONFIGURATION_POLICY") == _policy(package, {"brightness": 75}, group="Gallery")

    asyncio.run(body())


def test_slow_admin_fan_out_does_not_delay_policy_delivery(tmp_path, monkeypatch):
    async def body():
        release_admins = asyncio.Event()
        broadcast_group_list = server.broadcast_group_list

        async def slow_group_list():
            await release_admins.wait()
            await broadcast_group_list()

        async with _running_server(tmp_path, {"Gallery": [DEVICE_ID]}) as (session, base):
            package = await _create(session, base, "Gallery")
            package = await _deploy(session, base, package, settings=_brightness(75))
            async with session.ws_connect(base + "/ws/device") as device:
                await _register(device)
                assert await _receive_type(device, "CONFIGURATION_POLICY") == NO_POLICY
                monkeypatch.setattr(server, "broadcast_group_list", slow_group_list)
                try:
                    await _assign(session, base, "Gallery", package)
                    assert await _receive_type(device, "CONFIGURATION_POLICY") == _policy(package, {"brightness": 75}, group="Gallery")
                finally:
                    release_admins.set()

    asyncio.run(body())


def test_device_label_reports_a_registry_save_failure(tmp_path, monkeypatch):
    async def body():
        async with _running_server(tmp_path, {}) as (session, base):
            monkeypatch.setattr(server, "_write_registry", lambda configuration, records: False)
            async with session.ws_connect(base + "/ws/admin") as admin:
                await admin.send_json({"type": "SET_DEVICE_LABEL", "device_id": OTHER_ID, "label": "Spare"})
                error = await _receive_type(admin, "ERROR")
            assert error["message"] == "Could not persist the device label"
            assert OTHER_ID not in server.device_registry

    asyncio.run(body())


def test_telemetry_coalescer_runs_once_with_the_latest_state_and_flushes():
    async def body():
        state = {"value": 0}
        runs = []
        coalescer = server._Coalescer(0.05, lambda: runs.append(state["value"]), "test")
        for value in (1, 2, 3):
            state["value"] = value
            coalescer.schedule()
        await asyncio.sleep(0.1)
        assert runs == [3]

        state["value"] = 4
        coalescer.schedule()
        await coalescer.flush(run=True)
        assert runs == [3, 4]
        await coalescer.flush(run=True)
        assert runs == [3, 4]

        coalescer.schedule()
        await coalescer.flush(run=False)
        await asyncio.sleep(0.1)
        assert runs == [3, 4]

    asyncio.run(body())


def test_update_group_members_uses_expected_owners_from_each_console_snapshot(tmp_path, monkeypatch):
    async def body():
        legacy_id = "SERIAL-LEGACY"
        added_offline_id = "SERIAL-NEW"
        policy_targets = asyncio.Queue()

        async def capture_policy_targets(device_ids):
            policy_targets.put_nowait(sorted(device_ids))

        monkeypatch.setattr(server, "_publish_configuration", capture_policy_targets)
        async with _running_server(tmp_path, {"A": [DEVICE_ID, OTHER_ID, legacy_id], "B": []}) as (session, base):
            async with (session.ws_connect(base + "/ws/admin") as first,
                        session.ws_connect(base + "/ws/admin") as stale):
                first_snapshot = await _receive_type(first, "GROUP_LIST")
                stale_snapshot = await _receive_type(stale, "GROUP_LIST")
                assert stale_snapshot["groups"] == first_snapshot["groups"] == {
                    "A": [DEVICE_ID, OTHER_ID, legacy_id], "B": [],
                }

                # Another console moves one device. The stale console then sends
                # only the IDs it edited; membership it never touched is omitted.
                await first.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "B",
                                       "add": {OTHER_ID: "A"}, "remove": []})
                first_group_list = await _receive_type(first, "GROUP_LIST")
                success = await _receive_type(first, "GROUP_MEMBERS_SET")
                assert success["operation"] == "UPDATE_GROUP_MEMBERS"
                assert success["name"] == "B"
                assert first_group_list["groups"] == {
                    "A": [DEVICE_ID, legacy_id], "B": [OTHER_ID],
                }
                assert (await _receive_type(stale, "GROUP_LIST"))["groups"] == {
                    "A": [DEVICE_ID, legacy_id], "B": [OTHER_ID],
                }
                assert await asyncio.wait_for(policy_targets.get(), 2) == [OTHER_ID]

                # One stale owner rejects every edit in the command, including
                # the otherwise valid move of DEVICE_ID. The error refreshes both
                # admin snapshots and does not publish any device policy.
                await stale.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "B",
                                      "add": {DEVICE_ID: "A", OTHER_ID: "A"}, "remove": []})
                error = await _receive_type(stale, "ERROR")
                assert error["operation"] == "UPDATE_GROUP_MEMBERS"
                assert error["name"] == "B"
                assert "Reload the console" in error["message"]
                current = {"A": [DEVICE_ID, legacy_id], "B": [OTHER_ID]}
                assert (await _receive_type(first, "GROUP_LIST"))["groups"] == current
                assert (await _receive_type(stale, "GROUP_LIST"))["groups"] == current
                assert policy_targets.empty()

                # The current owner is accepted, and the legacy full-list edit
                # remains compatible with offline or unregistered identifiers.
                await stale.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "B",
                                      "add": {DEVICE_ID: "A", OTHER_ID: "B"}, "remove": []})
                assert (await _receive_type(first, "GROUP_LIST"))["groups"] == {
                    "A": [legacy_id], "B": [OTHER_ID, DEVICE_ID],
                }
                assert (await _receive_type(stale, "GROUP_LIST"))["groups"] == {
                    "A": [legacy_id], "B": [OTHER_ID, DEVICE_ID],
                }
                accepted = await _receive_type(stale, "GROUP_MEMBERS_SET")
                assert accepted["operation"] == "UPDATE_GROUP_MEMBERS"
                assert await asyncio.wait_for(policy_targets.get(), 2) == [DEVICE_ID]

                # An expected null owner is valid for a device not yet in the
                # registry, matching the existing group membership contract.
                await first.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "A",
                                       "add": {added_offline_id: None}, "remove": []})
                assert (await _receive_type(stale, "GROUP_LIST"))["groups"]["A"] == [legacy_id, added_offline_id]
                assert (await _receive_type(first, "GROUP_LIST"))["groups"]["A"] == [legacy_id, added_offline_id]
                accepted_offline = await _receive_type(first, "GROUP_MEMBERS_SET")
                assert accepted_offline["operation"] == "UPDATE_GROUP_MEMBERS"
                assert await asyncio.wait_for(policy_targets.get(), 2) == [added_offline_id]

                # Removing from a stale group snapshot is also an owner conflict;
                # removal succeeds only from the group's current membership.
                await stale.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "A",
                                      "add": {}, "remove": [OTHER_ID]})
                error = await _receive_type(stale, "ERROR")
                assert error["operation"] == "UPDATE_GROUP_MEMBERS"
                assert error["name"] == "A"
                assert (await _receive_type(first, "GROUP_LIST"))["groups"]["B"] == [OTHER_ID, DEVICE_ID]
                assert (await _receive_type(stale, "GROUP_LIST"))["groups"]["B"] == [OTHER_ID, DEVICE_ID]
                assert policy_targets.empty()

                await stale.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "B",
                                      "add": {}, "remove": [OTHER_ID]})
                assert (await _receive_type(first, "GROUP_LIST"))["groups"]["B"] == [DEVICE_ID]
                assert (await _receive_type(stale, "GROUP_LIST"))["groups"]["B"] == [DEVICE_ID]
                removed = await _receive_type(stale, "GROUP_MEMBERS_SET")
                assert removed["operation"] == "UPDATE_GROUP_MEMBERS"
                assert await asyncio.wait_for(policy_targets.get(), 2) == [OTHER_ID]

                # A current-owner no-op is acknowledged without a write,
                # snapshot broadcast, or duplicate policy delivery.
                await first.send_json({"type": "UPDATE_GROUP_MEMBERS", "name": "B",
                                       "add": {DEVICE_ID: "B"}, "remove": []})
                no_op = await _receive_type(first, "GROUP_MEMBERS_SET")
                assert no_op["operation"] == "UPDATE_GROUP_MEMBERS"
                assert policy_targets.empty()
                for console in (first, stale):
                    with pytest.raises(asyncio.TimeoutError):
                        await asyncio.wait_for(console.receive(), 0.05)

    asyncio.run(body())


@pytest.mark.parametrize("deployment", ["package", "group"])
def test_deploy_response_and_admin_metadata_do_not_wait_for_device_delivery(tmp_path, monkeypatch, deployment):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}) as (session, base):
            secret = "office-secret"
            empty = await _create(session, base, "Office")
            settings = _wifi_setting(networks=[{
                "ssid": "Office", "security": "WPA2_WPA3", "password": secret, "hidden": False,
            }])
            saved = (await _deploy_to(session, base, empty, settings, "Studio") if deployment == "package"
                     else await _deploy(session, base, empty, settings=settings))
            released = asyncio.Event()
            entered = asyncio.Event()
            holding = False
            publish_configuration = server._publish_configuration

            async def hold_device_delivery(members):
                if not holding:
                    return await publish_configuration(members)
                assert members == [DEVICE_ID]
                entered.set()
                await released.wait()

            monkeypatch.setattr(server, "_publish_configuration", hold_device_delivery)
            async with session.ws_connect(base + "/ws/admin") as other_console:
                before = (await _receive_type(other_console, "GROUP_LIST"))["deployments"]
                assert before == ({} if deployment == "group" else {"Studio": {
                    "package_id": saved["package_id"], "revision": _group_revision("Studio"),
                    "keys": ["wifi"], "wifi_mode": "priority",
                }})
                holding = True
                request = asyncio.create_task(_deploy(session, base, saved) if deployment == "package"
                                              else _assign(session, base, "Studio", saved))
                try:
                    await asyncio.wait_for(entered.wait(), 2)
                    result = await asyncio.wait_for(asyncio.shield(request), 1)
                    assert not released.is_set()
                    # A second HTTP request is also usable while delivery is blocked.
                    published = await asyncio.wait_for(_request(session, "GET", base, _path(saved)), 1)
                    assert result == (published if deployment == "package" else {"package_id": saved["package_id"]})
                    group_list = await _receive_type(other_console, "GROUP_LIST")
                    assert group_list["groups"] == {"Studio": [DEVICE_ID]}
                    assert group_list["assignments"] == {"Studio": saved["package_id"]}
                    assert secret not in json.dumps(group_list)
                    revision = published["published_revision"]
                    if deployment == "group":
                        registry = json.loads(server.REGISTRY_PATH.read_bytes())
                        revision = registry["groups"]["Studio"]["deployment_revision"]
                        assert revision != published["published_revision"]
                    assert group_list["deployments"] == {"Studio": {
                        "package_id": saved["package_id"], "revision": revision,
                        "keys": ["wifi"], "wifi_mode": "priority",
                    }}
                    assert await _request(session, "GET", base, "/api/packages") == {"packages": group_list["packages"]}
                finally:
                    released.set()
                    await request
    asyncio.run(body())


def test_failed_publish_and_cleanup_keep_saved_and_delivered_policy(tmp_path, monkeypatch):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}) as (session, base):
            package = await _create(session, base, "Office")
            first = await _deploy_to(session, base, package, _brightness(30), "Studio")
            committed = server.REGISTRY_PATH.read_bytes()
            original_replace = server.Path.replace
            original_unlink = server.Path.unlink
            failed_temp = None
            cleanup_attempted = False

            def deny_replace(path, target):
                nonlocal failed_temp
                if target == server.REGISTRY_PATH:
                    failed_temp = path
                    raise PermissionError("registry replace denied")
                return original_replace(path, target)

            def deny_cleanup(path, *, missing_ok=False):
                nonlocal cleanup_attempted
                if path == failed_temp:
                    cleanup_attempted = True
                    raise PermissionError("temporary registry cleanup denied")
                return original_unlink(path, missing_ok=missing_ok)

            with monkeypatch.context() as patch:
                patch.setattr(server.Path, "replace", deny_replace)
                patch.setattr(server.Path, "unlink", deny_cleanup)
                async with session.post(base + _path(first) + "/save-and-deploy", json={
                    "expected_revision": first["edit_revision"],
                    "settings": _brightness(80),
                    "expected_impact": first["impact"]["token"],
                }) as response:
                    status = response.status
                    error = await response.text()

            assert cleanup_attempted
            assert server.REGISTRY_PATH.read_bytes() == committed
            async with session.ws_connect(base + "/ws/admin") as admin:
                await admin.send_json({"type": "SET_DEVICE_LABEL", "device_id": OTHER_ID, "label": "Spare"})
                await _receive_type(admin, "DEVICE_LABEL_SET")
            saved = json.loads(server.REGISTRY_PATH.read_text())
            assert saved["devices"][OTHER_ID]["label"] == "Spare"

            async with session.ws_connect(base + "/ws/device") as ws:
                await _register(ws)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(first, {"brightness": 30}, group="Studio")

            saved_package = saved["packages"]["packages"][first["package_id"]]
            committed_package = json.loads(committed)["packages"]["packages"][first["package_id"]]
            assert saved_package["published_revision"] == committed_package["published_revision"]
            assert saved_package["settings"] == committed_package["settings"]
            assert status == 500
            assert json.loads(error) == {"error": "Configuration package could not be saved"}

    asyncio.run(body())


def test_configuration_state_is_sanitized_and_has_no_effect_on_delivery(tmp_path):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID, OTHER_ID, OFFLINE_ID]}) as (session, base):
            empty = await _create(session, base, "Office")
            published = await _deploy_to(session, base, empty, _brightness(45), "Studio")
            ids = {"package_id": published["package_id"], "revision": _group_revision("Studio")}
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
                "wifi": {"status": "failed", "reason": "VERIFY_FAILED"},
                "bluetooth": {"status": "unsupported"},
                "developerMenu": {"status": "failed", "reason": "PERMISSION_DENIED"},
            }}
            async with (session.ws_connect(base + "/ws/device", autoping=False) as ws,
                        session.ws_connect(base + "/ws/device", autoping=False) as legacy,
                        session.ws_connect(base + "/ws/device", autoping=False) as old,
                        session.ws_connect(base + "/ws/device", autoping=False) as provisional):
                await _register(ws, {**REGISTRATION, "configuration_state": reported})
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(published, {"brightness": 45}, group="Studio")
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

            async with session.ws_connect(base + "/ws/device") as reconnected:
                await _register(reconnected)
                await _receive_type(reconnected, "CONFIGURATION_POLICY")
                row = next(row for row in json.loads(server.build_device_list_msg())["devices"]
                           if row["device_id"] == DEVICE_ID)
                assert row["configuration_state"] == interrupted

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
            published = await _deploy_to(session, base, empty, _brightness(45), "Studio")
            retry = {"device_id": device_id, "package_id": published["package_id"],
                     "revision": _group_revision("Studio")}
            await _request(session, "POST", base, "/api/package-retry", retry, status=409)
            async with session.ws_connect(base + "/ws/device", autoping=False) as ws:
                await _register(ws, {**REGISTRATION, "device_id": device_id})
                wanted = _policy(published, {"brightness": 45}, group="Studio")
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
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]},
                                    packages={"version": configuration_packages.VERSION, "packages": []}) as (session, base):
            await _request(session, "GET", base, "/api/packages", status=503)
            async with (session.ws_connect(base + "/ws/device", autoping=False) as ws,
                        session.ws_connect(base + "/ws/admin") as admin):
                snapshot = await _receive_type(admin, "GROUP_LIST")
                assert snapshot["registry_writable"] is True
                assert snapshot["configuration_available"] is False
                await _register(ws)
                await _assert_no_policy(ws)
    asyncio.run(body())


def test_transport_send_gate_rechecks_retry_revision_and_socket_owner(tmp_path, monkeypatch):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}) as (session, base):
            initial = await _create(session, base, "Office")
            old = await _deploy(session, base, initial, settings=_brightness(20))
            await _assign(session, base, "Studio", old)
            old = await _request(session, "GET", base, _path(old))
            async with session.ws_connect(base + "/ws/device", autoping=False) as device:
                await _register(device)
                assert await _receive_type(device, "CONFIGURATION_POLICY") == _policy(old, {"brightness": 20}, group="Studio")
                entry = server.devices[DEVICE_ID]
                transport = entry["ws"]
                lock = transport._push_send_lock
                entered = asyncio.Event()
                send_current = transport.send_current_text

                async def observe_gate(resolve, **kwargs):
                    entered.set()
                    return await send_current(resolve, **kwargs)

                monkeypatch.setattr(transport, "send_current_text", observe_gate)
                retry = {"device_id": DEVICE_ID, "package_id": old["package_id"],
                         "revision": _group_revision("Studio")}
                async with lock:
                    stale = asyncio.create_task(_request(session, "POST", base, "/api/package-retry", retry, status=409))
                    await asyncio.wait_for(entered.wait(), 2)
                    latest = await asyncio.wait_for(_deploy(session, base, old, settings=_brightness(95)), 1)
                    assert not stale.done()
                await stale
                assert await _receive_type(device, "CONFIGURATION_POLICY") == _policy(latest, {"brightness": 95})
                await _assert_no_policy(device)

                # A queued sender must also reject a socket that lost ownership.
                entered.clear()
                async with lock:
                    replaced = asyncio.create_task(server._send_configuration(DEVICE_ID, entry))
                    await asyncio.wait_for(entered.wait(), 2)
                    server.devices[DEVICE_ID] = {**entry, "ws": object()}
                try:
                    assert await replaced is False
                finally:
                    server.devices[DEVICE_ID] = entry
                await _assert_no_policy(device)

                # Explicit Retry never suppresses a later normal publication.
                retry["revision"] = latest["published_revision"]
                await _request(session, "POST", base, "/api/package-retry", retry)
                retried = await _receive_type(device, "CONFIGURATION_POLICY")
                assert retried.pop("retry_id")
                assert retried == _policy(latest, {"brightness": 95})
                assert await server._send_configuration(DEVICE_ID, entry)
                assert await _receive_type(device, "CONFIGURATION_POLICY") == retried
    asyncio.run(body())


def test_unified_wifi_policy_is_sent_with_configuration_package_capability(tmp_path):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}) as (session, base):
            profile = await _create(session, base, "Office")
            wifi_value = _wifi_value(auto_return=True)
            published = await _deploy_to(session, base, profile, {
                **_brightness(45), "wifi": {"managed": True, "value": wifi_value},
            }, "Studio")
            client_without_separate_capability = {
                **REGISTRATION, "capabilities": [configuration_packages.CAPABILITY],
            }

            async with session.ws_connect(base + "/ws/device", autoping=False) as ws:
                await _register(ws, client_without_separate_capability)
                expected = _policy(published, {
                    "brightness": 45,
                    "wifi": wifi_value,
                }, group="Studio")
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == expected

                state = {
                    "package_id": published["package_id"], "revision": expected["revision"],
                    "status": "complete", "results": {
                        "brightness": {"status": "applied", "value": 45},
                        "wifi": {"status": "applied"},
                    },
                }
                await ws.send_json({"type": "CONFIGURATION_STATE", "configuration_state": state})
                await _assert_no_policy(ws)
                assert server.devices[DEVICE_ID]["configuration_state"] == state

                rows = {row["device_id"]: row for row in json.loads(server.build_device_list_msg())["devices"]}
                assert rows[DEVICE_ID]["configuration_state"] == state

            offline = next(row for row in json.loads(server.build_device_list_msg())["devices"]
                           if row["device_id"] == DEVICE_ID)
            assert offline["status"] == "offline"

            async with session.ws_connect(base + "/ws/device", autoping=False) as reconnected:
                await _register(reconnected, client_without_separate_capability)
                assert await _receive_type(reconnected, "CONFIGURATION_POLICY") == expected

    asyncio.run(body())


def test_unified_wifi_policy_publishes_networks_and_auto_return_together(tmp_path):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}) as (session, base):
            profile = await _create(session, base, "Office")
            value = _wifi_value(auto_return=False, hidden=True)
            published = await _deploy_to(session, base, profile, {"wifi": {"managed": True, "value": value}}, "Studio")
            capable_client = {**REGISTRATION, "capabilities": [configuration_packages.CAPABILITY]}

            async with session.ws_connect(base + "/ws/device", autoping=False) as ws:
                await _register(ws, capable_client)
                assert await _receive_type(ws, "CONFIGURATION_POLICY") == _policy(published, {"wifi": value}, group="Studio")
                row = next(row for row in json.loads(server.build_device_list_msg())["devices"]
                           if row["device_id"] == DEVICE_ID)
                assert published["settings"]["wifi"] == {
                    "managed": True,
                    "value": {
                        "networks": [
                            {"ssid": network["ssid"], "security": network["security"],
                             "password_present": network["security"] == "WPA2_WPA3",
                             "hidden": network["hidden"]}
                            for network in value["networks"]
                        ],
                        "auto_return": value["auto_return"],
                    },
                }
                assert "wifiPriority" not in published["settings"]

    asyncio.run(body())


def test_android_wifi_selection_delivers_all_settings_and_reports_mode_for_no_package(tmp_path):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}) as (session, base):
            profile = await _create(session, base, "Office")
            wifi_value = _wifi_value(auto_return=False)
            wifi_value["prioritize_networks"] = False
            published = await _deploy_to(session, base, profile, {
                **_brightness(45), "wifi": {"managed": True, "value": wifi_value},
            }, "Studio")
            package_client = {**REGISTRATION, "capabilities": [configuration_packages.CAPABILITY]}

            group = json.loads(server.build_group_list_msg())
            assert group["deployments"]["Studio"]["wifi_mode"] == "android_selection"
            assert group["deployments"]["Studio"]["keys"] == ["brightness", "wifi"]

            async with session.ws_connect(base + "/ws/device", autoping=False) as device:
                await _register(device, package_client)
                delivered = await _receive_type(device, "CONFIGURATION_POLICY")
                assert delivered == _policy(published, {
                    "brightness": 45,
                    "wifi": wifi_value,
                }, group="Studio")
                row = next(row for row in json.loads(server.build_device_list_msg())["devices"]
                           if row["device_id"] == DEVICE_ID)
                assert row["configuration_supported"] is True
                assert "android_wifi_selection_supported" not in row

                path = _deployment_path("Studio", None)
                preview = await _request(session, "GET", base, path)
                assert preview["wifi_mode"] == "android_selection"

                # Republish the shared package in another mode after previewing No package.
                # The old confirmation must become stale along with its Wi-Fi warning.
                priority_wifi_value = _wifi_value(auto_return=False)
                updated = await _deploy(session, base, published, settings={
                    "brightness": {"managed": True, "value": 45},
                    "wifi": {"managed": True, "value": priority_wifi_value},
                })
                updated_policy = await _receive_type(device, "CONFIGURATION_POLICY")
                assert updated_policy["package_id"] == updated["package_id"]
                assert updated_policy["settings"] == {
                    "brightness": 45,
                    "wifi": priority_wifi_value,
                }
                assert json.loads(server.build_group_list_msg())["deployments"]["Studio"]["wifi_mode"] == "priority"
                await _request(session, "POST", base, path,
                               {"expected_impact": preview["impact"]["token"]}, status=409)

                refreshed = await _request(session, "GET", base, path)
                assert refreshed["wifi_mode"] == "priority"
                await _request(session, "POST", base, path,
                               {"expected_impact": refreshed["impact"]["token"]})
                assert await _receive_type(device, "CONFIGURATION_POLICY") == NO_POLICY

    asyncio.run(body())


def test_hidden_wifi_can_be_saved_without_auto_return_but_api_rejects_auto_return(tmp_path):
    async def body():
        async with _running_server(tmp_path, {"Studio": [DEVICE_ID]}) as (session, base):
            profile = await _create(session, base, "Office")
            hidden_wifi = _wifi_setting(auto_return=True, hidden=True, networks=[{
                "ssid": "Hidden Office", "security": "WPA2_WPA3", "password": "test-password", "hidden": True,
            }])

            await _deploy(session, base, profile, settings=hidden_wifi, status=400)
            unchanged = await _request(session, "GET", base, _path(profile))
            assert unchanged["edit_revision"] is None

            hidden_wifi["wifi"]["value"]["auto_return"] = False
            published = await _deploy(session, base, unchanged, settings=hidden_wifi)
            assert published["settings"]["wifi"]["value"]["networks"][0]["hidden"] is True
            assert published["settings"]["wifi"]["value"]["auto_return"] is False
            await _assign(session, base, "Studio", published)
            assigned = await _request(session, "GET", base, _path(profile))

            hidden_wifi["wifi"]["value"]["auto_return"] = True
            await _deploy(session, base, assigned, settings=hidden_wifi, status=400)
            still_published = await _request(session, "GET", base, _path(profile))
            assert still_published["published_revision"] == published["published_revision"]
            assert still_published["edit_revision"] == published["edit_revision"]

    asyncio.run(body())


def test_unmanaged_hidden_wifi_auto_return_can_be_saved_but_managed_value_is_rejected(tmp_path):
    async def body():
        async with _running_server(tmp_path) as (session, base):
            profile = await _create(session, base, "Unmanaged hidden Wi-Fi")
            settings = _wifi_setting(managed=False, auto_return=True, hidden=True, networks=[{
                "ssid": "Hidden Office", "security": "WPA2_WPA3",
                "password": "test-password", "hidden": True,
            }])
            saved = await _deploy(session, base, profile, settings=settings)
            assert saved["settings"]["wifi"]["managed"] is False
            assert saved["settings"]["wifi"]["value"]["auto_return"] is True
            assert saved["settings"]["wifi"]["value"]["networks"] == [{
                "ssid": "Hidden Office", "security": "WPA2_WPA3",
                "password_present": True, "hidden": True,
            }]
            assert "test-password" not in json.dumps(saved)

            current = await _request(session, "GET", base, _path(saved))
            managed = _wifi_setting(managed=True, auto_return=True, hidden=True, networks=[{
                "ssid": "Hidden Office", "security": "WPA2_WPA3",
                "password": "test-password", "hidden": True,
            }])
            await _deploy(session, base, current, settings=managed, status=400)
            unchanged = await _request(session, "GET", base, _path(saved))
            assert unchanged["edit_revision"] == saved["edit_revision"]
            assert unchanged["published_revision"] == saved["published_revision"]

    asyncio.run(body())


def test_api_rejects_more_than_four_networks_with_auto_return_on_or_off(tmp_path):
    async def body():
        networks = [
            {"ssid": f"Network {index}", "security": "OPEN", "password": "", "hidden": False}
            for index in range(1, 6)
        ]
        async with _running_server(tmp_path) as (session, base):
            profile = await _create(session, base, "Five networks")
            for auto_return in (False, True):
                await _deploy(session, base, profile, settings={
                    "wifi": {"managed": True, "value": _wifi_value(
                        networks=networks, auto_return=auto_return,
                    )},
                }, status=400)

            current = await _request(session, "GET", base, _path(profile))
            assert current["published_revision"] is None
            assert current["edit_revision"] is None

    asyncio.run(body())


@pytest.mark.parametrize("ack_stalls", [False, True])
def test_group_edits_order_requester_ack_and_publish_to_other_connections(tmp_path, monkeypatch, ack_stalls):
    monkeypatch.setattr(server, "_admin_send_locks", {})

    async def body():
        package = _package_profile("Office", _brightness(45))
        groups = {"Studio": {"members": [DEVICE_ID], "package_id": OFFLINE_ID}}
        registration = {**REGISTRATION, "capabilities": [
            configuration_packages.CAPABILITY,
            configuration_packages.SUMMARY_CAPABILITY,
        ]}
        release_ack = asyncio.Event()
        ack_started = asyncio.Event()

        class UnreachableAdmin:
            closed = False

            async def send_str(self, _message):
                if ack_stalls:
                    await asyncio.Future()
                raise ConnectionResetError("admin disconnected after group commit")

            async def close(self, **_kwargs):
                self.closed = True

        class RecordingAdmin:
            def __init__(self):
                self.messages = []
                self.received = asyncio.Event()

            async def send_str(self, raw):
                self.messages.append(json.loads(raw))
                self.received.set()

        class SlowAdmin(RecordingAdmin):
            async def send_str(self, raw):
                if json.loads(raw)["type"] == "GROUP_RENAMED":
                    ack_started.set()
                    await release_ack.wait()
                await super().send_str(raw)

        async with _running_server(
            tmp_path, groups,
            packages={"version": configuration_packages.VERSION, "packages": {OFFLINE_ID: package}},
        ) as (session, base):
            async with session.ws_connect(base + "/ws/device") as device:
                await _register(device, registration)
                current = await _receive_type(device, "CONFIGURATION_POLICY")
                assert current["group_name"] == "Studio"

                requester = SlowAdmin()
                peer = RecordingAdmin()
                server.admin_connections.update((requester, peer))
                rename = asyncio.create_task(server.handle_rename_group(
                    requester, {"name": "Studio", "new_name": "Gallery"},
                ))
                # This broadcast starts after the commit and before the handler resumes.
                concurrent_list = asyncio.create_task(server.broadcast_group_list())
                try:
                    await asyncio.wait_for(ack_started.wait(), 2)
                    renamed = await _receive_type(device, "CONFIGURATION_POLICY")
                    assert renamed["group_name"] == "Gallery"
                    await asyncio.wait_for(peer.received.wait(), 2)
                    assert peer.messages[0]["groups"] == {"Gallery": [DEVICE_ID]}
                    assert not rename.done()
                    assert requester.messages == []
                finally:
                    release_ack.set()
                    await asyncio.gather(rename, concurrent_list)
                assert [msg["type"] for msg in requester.messages] == [
                    "GROUP_RENAMED", "GROUP_LIST", "GROUP_LIST",
                ]
                assert "Studio" not in server._configuration.groups
                assert "Gallery" in server._configuration.groups

                unreachable = UnreachableAdmin()
                server.admin_connections.add(unreachable)
                monkeypatch.setattr(server, "ADMIN_SEND_TIMEOUT", 0.05)
                await asyncio.wait_for(server.handle_delete_group(unreachable, {"name": "Gallery"}), 2)
                released = await _receive_type(device, "CONFIGURATION_POLICY")
                assert released == {**NO_POLICY, "group_name": None, "deployed_at_ms": None}
                assert unreachable.closed
                assert unreachable not in server.admin_connections
                assert unreachable not in server._admin_send_locks
                assert peer.messages[-1]["groups"] == {}
                assert "Gallery" not in server._configuration.groups

    asyncio.run(body())

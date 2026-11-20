"""Group deployment times follow the effective revision across persistence."""

import asyncio
import uuid

import pytest

from styly_mdm import configuration_packages, server
from test_configuration_packages_server import (
    DEVICE_ID, OFFLINE_ID, OTHER_ID, REGISTRATION, _assign, _brightness, _create, _deploy,
    _package_profile, _path, _receive_type, _register, _request, _running_server,
)


def test_scoped_deployment_time_survives_restart_and_shared_redeploy(tmp_path, monkeypatch):
    async def body():
        clock = [1_000]
        monkeypatch.setattr(server.time, "time_ns", lambda: clock[0] * 1_000_000)
        groups = {"Studio": [DEVICE_ID], "Gallery": [OTHER_ID], "Later": [OFFLINE_ID]}
        async with _running_server(tmp_path, groups) as (session, base):
            package = await _create(session, base, "Shared")
            package = await _deploy(session, base, package, settings=_brightness(30))
            assert server._configuration.packages[package["package_id"]]["deployed_at_ms"] == 1_000
            clock[0] = 2_000
            await _assign(session, base, "Gallery", package)

            capabilities = [configuration_packages.CAPABILITY, configuration_packages.SUMMARY_CAPABILITY]
            async with (session.ws_connect(base + "/ws/device") as studio,
                        session.ws_connect(base + "/ws/device") as gallery,
                        session.ws_connect(base + "/ws/admin") as admin):
                await _register(studio, {**REGISTRATION, "capabilities": capabilities})
                await _register(gallery, {**REGISTRATION, "device_id": OTHER_ID, "capabilities": capabilities})
                assert (await _receive_type(studio, "CONFIGURATION_POLICY"))["deployed_at_ms"] is None
                assert (await _receive_type(gallery, "CONFIGURATION_POLICY"))["deployed_at_ms"] == 2_000

                clock[0] = 3_000
                assigned = await _assign(session, base, "Studio", package)
                studio_policy = await _receive_type(studio, "CONFIGURATION_POLICY")
                assert studio_policy["revision"] == assigned["deployments"]["Studio"]["revision"]
                assert studio_policy["deployed_at_ms"] == 3_000
                assert server._desired_configuration(OTHER_ID, include_summary=True)["deployed_at_ms"] == 2_000

                clock[0] = 3_500
                await _assign(session, base, "Later", package)
                before = server.REGISTRY_PATH.read_bytes()
                backups = set(tmp_path.glob("device_registry.json.*.bak"))
                server.load_registry()
                assert server.REGISTRY_PATH.read_bytes() == before
                assert set(tmp_path.glob("device_registry.json.*.bak")) == backups
                assert server._desired_configuration(DEVICE_ID, include_summary=True)["deployed_at_ms"] == 3_000
                async with session.ws_connect(base + "/ws/device") as later:
                    await _register(later, {**REGISTRATION, "device_id": OFFLINE_ID, "capabilities": capabilities})
                    assert (await _receive_type(later, "CONFIGURATION_POLICY"))["deployed_at_ms"] == 3_500

                await admin.send_json({"type": "RENAME_GROUP", "name": "Studio", "new_name": "Renamed"})
                await _receive_type(admin, "GROUP_RENAMED")
                renamed = await _receive_type(studio, "CONFIGURATION_POLICY")
                assert renamed["group_name"] == "Renamed"
                assert renamed["deployed_at_ms"] == 3_000

                clock[0] = 4_000
                package = await _request(session, "GET", base, _path(package))
                package = await _deploy(session, base, package)
                for ws in (studio, gallery):
                    policy = await _receive_type(ws, "CONFIGURATION_POLICY")
                    assert policy["revision"] == package["published_revision"]
                    assert policy["deployed_at_ms"] == 4_000
                assert server._configuration.groups["Renamed"].deployment_at_ms is None

                await _assign(session, base, "Renamed", None)
                released = await _receive_type(studio, "CONFIGURATION_POLICY")
                assert released["package_id"] is None
                assert released["deployed_at_ms"] is None

    asyncio.run(body())


@pytest.mark.parametrize("timestamp", [0, -1, True, "1000", 253402300800000])
def test_invalid_saved_group_deployment_time_is_rejected(timestamp):
    package_id = str(uuid.uuid4())
    profile = _package_profile("Shared", _brightness(30))
    raw = {"groups": {"Studio": {"members": [], "package_id": package_id,
                                  "deployment_revision": str(uuid.uuid4()), "deployment_at_ms": timestamp}},
           "packages": {"version": configuration_packages.VERSION, "packages": {package_id: profile}}}
    with pytest.raises(configuration_packages.PackageError, match="deployment time"):
        server._read_configuration(raw)

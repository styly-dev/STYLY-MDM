"""Registration and transport failures preserve Push ownership and control sockets."""
import asyncio
import json
import uuid

import pytest

from styly_mdm import server
from styly_mdm.push_jobs import DeviceState, ProtocolMode, canonicalize_create_request
from styly_mdm.push_job_store import now_ms
from test_push_job_admission import reset_server_state, _registered, _server_session
from test_push_review_connection import Ws
from ws_test_helpers import receive_type

CAPS = ["push_job_id_v1", "push_resume_v1"]


async def wait_for_state(runtime, job_id, device_id, state):
    async def wait():
        while True:
            row = await runtime.manager.assignment(job_id, device_id)
            if row["state"] == state:
                return row
            await asyncio.sleep(0.001)
    return await asyncio.wait_for(wait(), 2)


async def active_job(runtime, device_id, phase="downloading"):
    store, manager = runtime.store, runtime.manager
    _, job = await store.create_job(canonicalize_create_request({
        "client_request_id": str(uuid.uuid4()), "target_devices": [device_id],
        "mode": "push", "dest_path": "/sdcard/STYLY/content",
        "source": {"display_name": "content", "declared_file_count": 1,
                   "declared_total_bytes": 1},
    }), {device_id: (ProtocolMode.JOB_V1, CAPS)}, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": "content.zip",
        "display_filename": "content.zip", "byte_size": 1,
        "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    assert await manager.claim_next([device_id]) is not None
    await manager.prepare_dispatch(job_id, device_id, accept_deadline=now_ms() + 60_000)
    await manager.store.transition_device(job_id, device_id, expected={DeviceState.DISPATCHING},
        target=DeviceState.DOWNLOADING, fields={"accepted_at": now_ms(), "accept_deadline": None})
    if phase in {"validating", "applying"}:
        await manager.store.transition_device(job_id, device_id, expected={DeviceState.DOWNLOADING},
            target=DeviceState.VALIDATING)
    if phase == "applying":
        await manager.store.transition_device(job_id, device_id, expected={DeviceState.VALIDATING},
            target=DeviceState.APPLYING)
    return await store.get_snapshot(job_id)


def registration(device_id, process_id, active=None, available=True):
    return {
        "type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
        "device_id": device_id, "model": "M", "ip": "1.1.1.2",
        "process_instance_id": process_id, "capabilities": CAPS if available else [],
        "push_state": {"status": "available" if available else "unavailable"},
        "push_runtime": {"active": active},
    }


def interrupted_report(job, device_id):
    return {
        "job_id": job["job_id"], "attempt": 1,
        "artifact_id": job["artifact"]["artifact_id"],
        "revision": job["devices"][device_id]["dispatch_revision"],
        "phase": "downloading", "status": "interrupted",
        "validated_offset": 1, "reason": "client_restarted",
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("known", [False, True])
async def test_registration_ack_precedes_exact_resume_rejection(known):
    async with _server_session() as (test_server, client):
        runtime = test_server.app["push_runtime"]
        await runtime.scheduler.stop()
        device_id = str(uuid.uuid4())
        if known:
            job = await active_job(runtime, device_id)
            await runtime.store.transition_device(job["job_id"], device_id,
                expected={DeviceState.DOWNLOADING}, target=DeviceState.FAILED,
                fields={"failure_code": "artifact_identity_mismatch"})
            report = interrupted_report(job, device_id)
        else:
            report = {"job_id": str(uuid.uuid4()), "attempt": 1,
                      "artifact_id": str(uuid.uuid4()), "revision": 7,
                      "phase": "downloading", "status": "interrupted", "validated_offset": 1}
        device = await client.ws_connect(test_server.make_url("/ws/device"))
        await device.send_json(registration(device_id, str(uuid.uuid4()), report))
        ack = await device.receive_json(timeout=2)
        rejected = await device.receive_json(timeout=2)
        assert ack["type"] == "REGISTERED"
        assert rejected["type"] == "PUSH_RESUME_REJECTED"
        for field in ("job_id", "attempt", "artifact_id", "revision"):
            assert rejected[field] == report[field]
        await device.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("phase", ["validating", "applying"])
async def test_refresh_send_failure_runs_disconnect_cleanup(monkeypatch, phase):
    async with _server_session() as (test_server, client):
        runtime = test_server.app["push_runtime"]
        device_id, device = await _registered(client, str(test_server.make_url("")), CAPS)
        await runtime.scheduler.stop()
        job = await active_job(runtime, device_id, phase)
        live = runtime.sessions[device_id]
        original_send = live.ws.send_str

        async def fail_ack(text, compress=None):
            if json.loads(text)["type"] == "REGISTERED":
                raise ConnectionResetError("device transport failed")
            await original_send(text, compress=compress)

        monkeypatch.setattr(live.ws, "send_str", fail_ack)
        await device.send_json(registration(device_id, live.process_instance_id))
        row = await wait_for_state(runtime, job["job_id"], device_id, "reconciling")
        assert device_id not in runtime.sessions
        assert device_id not in server.devices
        assert live.ws._push_disconnect_notified
        assert row["reconciliation_deadline"] is not None
        await device.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("error", [ConnectionResetError, asyncio.TimeoutError])
async def test_device_reconcile_send_failure_keeps_admin_socket_open(monkeypatch, error):
    async with _server_session() as (test_server, client):
        runtime = test_server.app["push_runtime"]
        device_id, device = await _registered(client, str(test_server.make_url("")), CAPS)
        await runtime.scheduler.stop()
        job = await active_job(runtime, device_id)
        await runtime.store.mark_reconciling(job["job_id"], device_id,
            expected={DeviceState.DOWNLOADING}, reason="device_disconnect", deadline=now_ms() + 60_000)
        live = runtime.sessions[device_id]
        original_send = live.ws.send_str

        async def fail_reconcile(text, compress=None):
            if json.loads(text)["type"] == "PUSH_RECONCILE_REQUEST":
                raise error("device transport failed")
            await original_send(text, compress=compress)

        monkeypatch.setattr(live.ws, "send_str", fail_reconcile)
        admin = await client.ws_connect(test_server.make_url("/ws/admin"))
        assert await receive_type(admin, "PUSH_JOBS_SNAPSHOT")
        for _ in range(2):
            await admin.send_json({"type": "RECONCILE_PUSH_DEVICE", "device_id": device_id})
            reply = await receive_type(admin, "ERROR")
            assert reply and "reconciliation" in reply["message"]
            assert not admin.closed
        await admin.close()
        await device.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("timing", ["before_resume", "slot_wait", "before_send"])
async def test_unavailable_push_state_preserves_interrupted_work(monkeypatch, timing):
    async with _server_session() as (test_server, client):
        runtime = test_server.app["push_runtime"]
        device_id, device = await _registered(client, str(test_server.make_url("")), CAPS)
        await runtime.scheduler.stop()
        job = await active_job(runtime, device_id)
        report = interrupted_report(job, device_id)
        await runtime.manager.resume_interrupted(job["job_id"], device_id, attempt=1,
            artifact_id=report["artifact_id"], dispatch_revision=report["revision"],
            validated_offset=1, reason="download_interrupted")
        live = runtime.sessions[device_id]
        slots = asyncio.Semaphore(0 if timing == "slot_wait" else 1)
        runtime.scheduler.transfer_slots = lambda: slots

        async def refresh(available):
            await device.send_json(registration(device_id, live.process_instance_id, report, available))
            assert await receive_type(device, "REGISTERED")

        if timing == "before_resume":
            await refresh(False)
        unavailable_refreshed = asyncio.Event()
        if timing == "before_send":
            original_publish = runtime.scheduler.publish

            async def publish(snapshot):
                if snapshot["devices"][device_id]["state"] == "dispatching":
                    await refresh(False)
                    unavailable_refreshed.set()
                await original_publish(snapshot)

            monkeypatch.setattr(runtime.scheduler, "publish", publish)
        admin = Ws()
        await runtime.handle_admin_message(admin, {
            "type": "PUSH_FILES", "job_id": job["job_id"], "target_devices": [device_id],
        })
        if timing == "before_resume":
            assert admin.messages[-1]["type"] == "ERROR"
        elif timing == "slot_wait":
            await wait_for_state(runtime, job["job_id"], device_id, "waiting_transfer")
            await refresh(False)
            slots.release()
        elif timing == "before_send":
            await asyncio.wait_for(unavailable_refreshed.wait(), 2)
        row = await wait_for_state(runtime, job["job_id"], device_id, "queued")
        assert row["queue_reason"] in {"download_interrupted", "dispatch_paused"}
        assert row["dispatch_revision"] == report["revision"]
        assert row["validated_offset"] == 1
        await refresh(True)
        current = await runtime.store.get_snapshot(job["job_id"])
        assert current["devices"][device_id]["resume_required"]
        assert not await receive_type(device, "PUSH_RESUME_REJECTED", timeout=0.02)
        assert await runtime.manager.claim_next([device_id]) is None
        await device.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("route", ["registration", "reconcile"])
async def test_timeout_interruption_recovers_over_real_socket_without_auto_dispatch(route):
    async with _server_session() as (test_server, client):
        runtime = test_server.app["push_runtime"]
        await runtime.scheduler.stop()
        device_id = str(uuid.uuid4())
        job = await active_job(runtime, device_id)
        await runtime.store.mark_reconciling(job["job_id"], device_id,
            expected={DeviceState.DOWNLOADING}, reason="device_disconnect", deadline=1)
        await runtime.manager.mark_unconfirmed(job["job_id"], device_id, None,
            "offline timeout", expected_deadline=1, observed_now=2)
        report = interrupted_report(job, device_id)
        device = await client.ws_connect(test_server.make_url("/ws/device"))
        await device.send_json(registration(device_id, str(uuid.uuid4()),
            report if route == "registration" else {**report, "status": "active"}))
        assert (await device.receive_json(timeout=2))["type"] == "REGISTERED"
        if route == "reconcile":
            await device.send_json({"type": "PUSH_RECONCILE_REPORT", **report})
        row = await wait_for_state(runtime, job["job_id"], device_id, "queued")
        assert row["dispatch_revision"] == report["revision"]
        assert await runtime.manager.fenced_assignment_for_device(device_id) is None
        assert await runtime.manager.claim_next([device_id]) is None
        assert not await receive_type(device, "PUSH_RESUME_REJECTED", timeout=0.05)
        assert not await receive_type(device, "EXECUTE_PUSH_FILES", timeout=0.05)
        await device.close()


@pytest.mark.asyncio
@pytest.mark.parametrize('phase', ['validating', 'applying'])
async def test_initial_registration_ack_failure_settles_adopted_state(monkeypatch, phase):
    from styly_mdm.push_runtime import RuntimeWebSocketResponse
    async with _server_session() as (test_server, client):
        runtime = test_server.app['push_runtime']
        await runtime.scheduler.stop()
        device_id = str(uuid.uuid4())
        job = await active_job(runtime, device_id, phase)
        await runtime.store.mark_reconciling(job['job_id'], device_id,
            expected={DeviceState(phase)}, reason='device_disconnect', deadline=now_ms()+60000)
        original_send = RuntimeWebSocketResponse.send_str
        failed = asyncio.Event()
        async def fail_ack(ws, text, **kwargs):
            if json.loads(text)['type'] == 'REGISTERED':
                failed.set()
                raise ConnectionResetError('initial registration ACK failed')
            await original_send(ws, text, **kwargs)
        monkeypatch.setattr(RuntimeWebSocketResponse, 'send_str', fail_ack)
        device = await client.ws_connect(test_server.make_url('/ws/device'))
        report = {**interrupted_report(job, device_id), 'status': 'active', 'phase': phase}
        await device.send_json(registration(device_id, str(uuid.uuid4()), report))
        await asyncio.wait_for(failed.wait(), 2)
        row = await wait_for_state(runtime, job['job_id'], device_id, 'reconciling')
        assert row['reconciliation_deadline'] is not None
        assert device_id not in runtime.sessions
        assert device_id not in runtime.pending_disconnects
        await device.close()

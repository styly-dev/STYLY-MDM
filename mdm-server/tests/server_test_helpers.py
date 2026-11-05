"""Server isolation fixtures explicitly imported by integration test modules."""

import pytest

from styly_mdm import server


@pytest.fixture(autouse=True)
def reset_server_state(tmp_path):
    saved_paths = (server.DATA_DIR, server.APK_DIR, server.BUNDLE_DIR, server.REGISTRY_PATH)
    collections = (
        server.devices,
        server.admin_connections,
        server.pending_transfers,
        server._transfer_tasks,
        server.pending_self_updates,
        server._apk_hash_cache,
        server.device_registry,
    )
    for collection in collections:
        collection.clear()
    server.reset_transfer_slots()
    server._apply_data_dir(str(tmp_path))
    yield
    for collection in collections:
        collection.clear()
    server.reset_transfer_slots()
    server.DATA_DIR, server.APK_DIR, server.BUNDLE_DIR, server.REGISTRY_PATH = saved_paths

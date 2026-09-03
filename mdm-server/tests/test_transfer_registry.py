import asyncio

import pytest

from styly_mdm.transfer_registry import TransferKey, TransferRegistry


def test_push_key_requires_exact_identity():
    with pytest.raises(ValueError):
        TransferKey("push", "D")
    with pytest.raises(ValueError):
        TransferKey("push", "D", "job", 2)


@pytest.mark.asyncio
async def test_stale_job_cannot_release_current_waiter():
    registry = TransferRegistry()
    current = asyncio.get_running_loop().create_future()
    registry.register(TransferKey("push", "D", "new", 1), current)
    assert not registry.release_exact(TransferKey("push", "D", "old", 1), "late")
    assert not current.done()
    assert registry.release_exact(TransferKey("push", "D", "new", 1), "done")
    assert await current == "done"


@pytest.mark.asyncio
async def test_disconnect_releases_install_and_push_without_tuple_unpacking():
    registry = TransferRegistry()
    install = asyncio.get_running_loop().create_future()
    push = asyncio.get_running_loop().create_future()
    registry.register(TransferKey("install", "D"), install)
    registry.register(TransferKey("push", "D", "job", 1), push)
    released = registry.release_all_for_device("D", "disconnect")
    assert set(released) == {TransferKey("install", "D"), TransferKey("push", "D", "job", 1)}
    assert await install == "disconnect"
    assert await push == "disconnect"

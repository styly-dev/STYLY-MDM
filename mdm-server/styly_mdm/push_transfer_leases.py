"""Short-lived HTTP permissions for one Push assignment and transfer slot."""

from __future__ import annotations

import asyncio
import secrets
import time
from dataclasses import dataclass, field
from typing import Any, Literal

from .transfer_registry import TransferKey

TRANSFER_IDLE_SECONDS = 60.0


@dataclass(slots=True)
class _Lease:
    key: TransferKey
    artifact_id: str
    token: str
    last_progress: float
    task: asyncio.Task[Any] | None = None
    transport: Any = None
    claim_lock: asyncio.Lock = field(default_factory=asyncio.Lock)


class PushTransferLeases:
    """An in-memory lease dies with the server process or its transfer slot."""

    def __init__(self) -> None:
        self._by_key: dict[TransferKey, _Lease] = {}
        self._by_token: dict[str, _Lease] = {}

    def issue(self, key: TransferKey, artifact_id: str) -> str:
        self.revoke_now(key)
        token = secrets.token_urlsafe(32)
        lease = _Lease(key, artifact_id, token, time.monotonic())
        self._by_key[key] = lease
        self._by_token[token] = lease
        return token

    def last_progress(self, key: TransferKey) -> float | None:
        lease = self._by_key.get(key)
        return lease.last_progress if lease is not None else None

    def token(self, key: TransferKey) -> str | None:
        lease = self._by_key.get(key)
        return lease.token if lease is not None else None

    def valid(self, artifact_id: str, token: str) -> bool:
        lease = self._by_token.get(token)
        return lease is not None and lease.artifact_id == artifact_id

    async def claim(
        self,
        artifact_id: str,
        token: str,
        task: asyncio.Task[Any],
        transport: Any,
    ) -> Literal["claimed", "revoked"]:
        lease = self._by_token.get(token)
        if lease is None or lease.artifact_id != artifact_id or transport is None:
            return "revoked"
        # Serialize reconnects so a later request cannot race an older handler's exit.
        async with lease.claim_lock:
            if self._by_token.get(token) is not lease:
                return "revoked"
            previous_task = lease.task
            if previous_task is not None and previous_task is not task:
                if lease.transport is not None:
                    lease.transport.abort()
                previous_task.cancel()
                await asyncio.gather(previous_task, return_exceptions=True)
                if self._by_token.get(token) is not lease:
                    return "revoked"
            lease.task = task
            lease.transport = transport
            return "claimed"

    def progress(self, token: str, byte_count: int) -> None:
        if byte_count > 0:
            lease = self._by_token.get(token)
            if lease is not None:
                lease.last_progress = time.monotonic()

    def unclaim(self, token: str, task: asyncio.Task[Any]) -> None:
        lease = self._by_token.get(token)
        if lease is not None and lease.task is task:
            lease.task = None
            lease.transport = None

    def revoke_now(self, key: TransferKey) -> asyncio.Task[Any] | None:
        lease = self._by_key.pop(key, None)
        if lease is None:
            return None
        self._by_token.pop(lease.token, None)
        if lease.transport is not None:
            lease.transport.abort()
        if lease.task is not None:
            lease.task.cancel()
        return lease.task

    async def expire(self, key: TransferKey, token: str) -> bool:
        lease = self._by_key.get(key)
        if lease is None or lease.token != token:
            return False
        task = self.revoke_now(key)
        if task is not None and task is not asyncio.current_task():
            await asyncio.gather(task, return_exceptions=True)
        return True

"""Typed transfer-slot waiters shared by install and push jobs."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Literal


@dataclass(frozen=True, slots=True)
class TransferKey:
    kind: Literal["install", "push"]
    device_id: str
    job_id: str | None = None
    attempt: int | None = None

    def __post_init__(self) -> None:
        if self.kind == "push":
            if not self.job_id or self.attempt != 1:
                raise ValueError("push transfer keys require job_id and attempt=1")
        elif self.job_id is not None or self.attempt is not None:
            raise ValueError("install transfer keys do not carry push identity")


class TransferRegistry:
    def __init__(self) -> None:
        self._futures: dict[TransferKey, asyncio.Future[str]] = {}

    def register(self, key: TransferKey, future: asyncio.Future[str]) -> None:
        current = self._futures.get(key)
        if current is not None and not current.done():
            raise RuntimeError(f"transfer waiter already registered: {key}")
        self._futures[key] = future

    def release_exact(self, key: TransferKey, reason: str) -> bool:
        future = self._futures.get(key)
        if future is None or future.done():
            return False
        future.set_result(reason)
        return True

    def release_all_for_device(self, device_id: str, reason: str) -> list[TransferKey]:
        released: list[TransferKey] = []
        for key, future in tuple(self._futures.items()):
            if key.device_id == device_id and not future.done():
                future.set_result(reason)
                released.append(key)
        return released

    def remove_if_same(self, key: TransferKey, future: asyncio.Future[str]) -> bool:
        if self._futures.get(key) is not future:
            return False
        del self._futures[key]
        return True

    def get(self, key: TransferKey) -> asyncio.Future[str] | None:
        return self._futures.get(key)

    def __len__(self) -> int:
        return len(self._futures)

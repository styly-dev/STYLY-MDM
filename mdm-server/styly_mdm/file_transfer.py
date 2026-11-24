"""Shared HTTP streaming for immutable server-side files."""

from __future__ import annotations

import asyncio
import re
from collections.abc import Callable
from pathlib import Path

from aiohttp import web

from .push_artifacts import strong_etag


def if_match_satisfied(value: str | None, etag: str) -> bool:
    if value is None:
        return True
    return value.strip() == etag


def parse_open_ended_range(value: str, total: int) -> tuple[int, int] | None:
    """Parse the Push client's supported `bytes=start-` resume range."""
    if not isinstance(value, str):
        raise ValueError("invalid Range syntax")
    match = re.fullmatch(r"bytes=(\d+)-", value.strip())
    if match is None:
        raise ValueError("unsupported Range syntax")
    start = int(match.group(1))
    if total <= 0 or start >= total:
        return None
    return start, total - 1


async def stream_file(
    request: web.Request,
    path: Path,
    total: int,
    sha256: str,
    *,
    cache_control: str,
    on_progress: Callable[[int], None] | None = None,
    write_timeout_seconds: float | None = None,
) -> web.StreamResponse:
    """Serve a file with one shared validator, range, and streaming implementation."""
    etag = strong_etag(sha256)
    headers = {
        "ETag": etag,
        "Accept-Ranges": "bytes",
        "Cache-Control": cache_control,
        "Content-Type": "application/zip",
    }
    if not if_match_satisfied(request.headers.get("If-Match"), etag):
        headers["Content-Length"] = "0"
        response = web.StreamResponse(status=412, headers=headers)
        await response.prepare(request)
        await response.write_eof()
        return response

    start, end = 0, total - 1
    status = 200
    range_header = request.headers.get("Range")
    if range_header is not None:
        try:
            parsed = parse_open_ended_range(range_header, total)
        except ValueError:
            # Ignore malformed or unsupported ranges and serve the full file.
            pass
        else:
            if parsed is None:
                headers.update({"Content-Range": f"bytes */{total}", "Content-Length": "0"})
                response = web.StreamResponse(status=416, headers=headers)
                await response.prepare(request)
                await response.write_eof()
                return response
            start, end = parsed
            status = 206
            headers["Content-Range"] = f"bytes {start}-{end}/{total}"

    body_length = max(0, end - start + 1)
    headers["Content-Length"] = str(body_length)
    response = web.StreamResponse(status=status, headers=headers)
    await response.prepare(request)
    try:
        if request.method != "HEAD":
            handle = await asyncio.to_thread(path.open, "rb")
            try:
                await asyncio.to_thread(handle.seek, start)
                remaining = body_length
                while remaining > 0:
                    chunk = await asyncio.to_thread(handle.read, min(1024 * 1024, remaining))
                    if not chunk:
                        break
                    write = response.write(chunk)
                    if write_timeout_seconds is None:
                        await write
                    else:
                        await asyncio.wait_for(write, write_timeout_seconds)
                    if on_progress is not None:
                        on_progress(len(chunk))
                    remaining -= len(chunk)
            finally:
                await asyncio.to_thread(handle.close)
        write_eof = response.write_eof()
        if write_timeout_seconds is None:
            await write_eof
        else:
            await asyncio.wait_for(write_eof, write_timeout_seconds)
    except asyncio.TimeoutError:
        if request.transport is not None:
            request.transport.close()
        raise
    return response

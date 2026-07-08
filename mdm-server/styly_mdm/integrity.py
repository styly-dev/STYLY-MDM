"""Content-integrity helpers: APK Central-Directory digest and directory tree hashing.

Used by the ``styly-mdm hash`` CLI subcommand to compute a *local reference* for the
console's on-demand integrity check (issue #37). The control server itself never hashes
anything for verification — it only relays the ``VERIFY_*`` messages. These helpers are
kept dependency-free and pure so they can be unit-tested and, crucially, cross-checked
byte-for-byte against the browser (pure JS) and device (Kotlin) implementations of the
exact same algorithms. If any implementation disagrees on a single byte of the hashed
range, every device would report a mismatch, so the algorithm here is the canonical spec.
"""

from __future__ import annotations

import hashlib
import os
import struct

# End Of Central Directory record signature ("PK\x05\x06").
EOCD_SIGNATURE = b"\x50\x4b\x05\x06"
# The ZIP comment length field is a uint16, so the EOCD starts at most this many bytes
# (plus the 22-byte fixed record) from EOF.
MAX_EOCD_COMMENT = 0xFFFF
EOCD_MIN_SIZE = 22
# A CD offset of 0xFFFFFFFF in the classic EOCD means the real value lives in a ZIP64
# record. We do not parse ZIP64 (standard APKs are not ZIP64); we surface a clear error
# rather than hash a wrong byte range.
ZIP64_SENTINEL = 0xFFFFFFFF

_CHUNK = 1024 * 1024


class Zip64UnsupportedError(ValueError):
    """Raised when an archive uses ZIP64 (CD offset sentinel), which we do not hash."""


def _find_eocd(tail: bytes, file_len: int, tail_start: int) -> int:
    """Return the absolute offset of the EOCD record within the file.

    ``tail`` is the final ``len(tail)`` bytes of the file, beginning at absolute offset
    ``tail_start``. We scan backwards and accept the *last* EOCD signature whose declared
    comment length makes the record end exactly at EOF — the length check stops a stray
    "PK\\x05\\x06" byte sequence inside the archive or comment from being mistaken for the
    real record. Raises ``ValueError`` if no valid EOCD is found.
    """
    for i in range(len(tail) - EOCD_MIN_SIZE, -1, -1):
        if tail[i:i + 4] != EOCD_SIGNATURE:
            continue
        comment_len = struct.unpack_from("<H", tail, i + 20)[0]
        eocd_abs = tail_start + i
        if eocd_abs + EOCD_MIN_SIZE + comment_len == file_len:
            return eocd_abs
    raise ValueError("End Of Central Directory record not found")


def apk_cd_digest(path: str) -> tuple[int, str]:
    """Return ``(size, cd_sha256_hex)`` for an APK/ZIP file.

    ``cd_sha256`` is the SHA-256 of ``file[CD_offset .. EOF]``, where ``CD_offset`` is
    read (little-endian uint32) from the parsed EOCD record. This region covers the
    Central Directory — every entry's CRC-32 and compressed/uncompressed sizes — plus the
    EOCD itself, so any real change to the archive perturbs it, while reading only a few
    hundred KB regardless of the APK size.
    """
    file_len = os.path.getsize(path)
    with open(path, "rb") as f:
        read_len = min(file_len, EOCD_MIN_SIZE + MAX_EOCD_COMMENT)
        tail_start = file_len - read_len
        f.seek(tail_start)
        tail = f.read(read_len)
        eocd_off = _find_eocd(tail, file_len, tail_start) - tail_start
        cd_offset = struct.unpack_from("<I", tail, eocd_off + 16)[0]
        if cd_offset == ZIP64_SENTINEL:
            raise Zip64UnsupportedError("zip64 not supported")
        f.seek(cd_offset)
        h = hashlib.sha256()
        while True:
            chunk = f.read(_CHUNK)
            if not chunk:
                break
            h.update(chunk)
    return file_len, h.hexdigest()


def _file_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(_CHUNK)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _iter_files(root: str):
    """Yield ``(relative_path, absolute_path)`` for every regular file under ``root``.

    Policy (must match the JS and Kotlin implementations): symlinks are not followed and
    are excluded; directories (including empty ones) are not represented; ``relative_path``
    uses forward slashes with no leading slash.
    """
    root = os.path.abspath(root)
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        # os.walk(followlinks=False) already will not descend into symlinked dirs, but
        # prune them explicitly so they are never even listed.
        dirnames[:] = [d for d in dirnames
                       if not os.path.islink(os.path.join(dirpath, d))]
        for name in filenames:
            abspath = os.path.join(dirpath, name)
            if os.path.islink(abspath):
                continue
            rel = os.path.relpath(abspath, root).replace(os.sep, "/")
            yield rel, abspath


def dir_manifest(path: str, manifest_entry_cap: int | None = None) -> dict:
    """Return ``{tree_hash, file_count, total_size, manifest?}`` for a directory tree.

    ``manifest`` is a list of ``{relative_path, size, sha256}`` sorted by the UTF-8 byte
    order of ``relative_path``. ``tree_hash`` is the SHA-256 over, for each entry in that
    order, the UTF-8 bytes of ``f"{relative_path}\\n{size}\\n{sha256}\\n"``. When
    ``manifest_entry_cap`` is given and there are more files than the cap, the ``manifest``
    key is omitted (``tree_hash`` alone still distinguishes same/different).
    """
    entries = []
    for rel, abspath in _iter_files(path):
        entries.append({
            "relative_path": rel,
            "size": os.path.getsize(abspath),
            "sha256": _file_sha256(abspath),
        })
    # Sort by the UTF-8 byte sequence, not Python's default str ordering, so JS
    # (UTF-16 code units) and Kotlin can reproduce the exact same order for non-ASCII.
    entries.sort(key=lambda e: e["relative_path"].encode("utf-8"))

    h = hashlib.sha256()
    total_size = 0
    for e in entries:
        total_size += e["size"]
        line = "{}\n{}\n{}\n".format(e["relative_path"], e["size"], e["sha256"])
        h.update(line.encode("utf-8"))

    result = {
        "tree_hash": h.hexdigest(),
        "file_count": len(entries),
        "total_size": total_size,
    }
    if manifest_entry_cap is None or len(entries) <= manifest_entry_cap:
        result["manifest"] = entries
    return result

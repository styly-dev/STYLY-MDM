"""Tests for the integrity helpers (issue #37).

These pin the canonical hashing spec that the browser (pure JS) and device (Kotlin)
implementations must reproduce byte-for-byte, so a change here that would break
cross-implementation agreement fails a test rather than silently reporting every
device as a mismatch.
"""

import hashlib
import os
import struct
import zipfile

import pytest

from styly_mdm import integrity


# --------------------------------------------------------------------------- #
# APK / ZIP Central-Directory digest
# --------------------------------------------------------------------------- #

def _make_zip(path, entries, comment=b""):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
        if comment:
            zf.comment = comment


def _independent_cd_digest(path):
    """Re-derive size + CD digest without using integrity.py, to catch a spec drift."""
    file_len = os.path.getsize(path)
    with open(path, "rb") as f:
        blob = f.read()
    # No ZIP64, comment length known from EOCD; scan for the last EOCD.
    for i in range(len(blob) - 22, -1, -1):
        if blob[i:i + 4] == b"\x50\x4b\x05\x06":
            comment_len = struct.unpack_from("<H", blob, i + 20)[0]
            if i + 22 + comment_len == file_len:
                cd_off = struct.unpack_from("<I", blob, i + 16)[0]
                return file_len, hashlib.sha256(blob[cd_off:]).hexdigest()
    raise AssertionError("no EOCD in test fixture")


def test_apk_cd_digest_matches_independent(tmp_path):
    apk = tmp_path / "a.apk"
    _make_zip(apk, {"AndroidManifest.xml": b"x" * 100, "classes.dex": b"y" * 500})
    size, cd = integrity.apk_cd_digest(str(apk))
    exp_size, exp_cd = _independent_cd_digest(str(apk))
    assert (size, cd) == (exp_size, exp_cd)
    assert len(cd) == 64


def test_apk_cd_digest_is_deterministic(tmp_path):
    apk = tmp_path / "a.apk"
    _make_zip(apk, {"a": b"a", "b": b"b"})
    assert integrity.apk_cd_digest(str(apk)) == integrity.apk_cd_digest(str(apk))


def test_apk_cd_digest_changes_when_contents_change(tmp_path):
    a, b = tmp_path / "a.apk", tmp_path / "b.apk"
    _make_zip(a, {"f": b"hello"})
    _make_zip(b, {"f": b"hello!"})  # different size/CRC perturbs the Central Directory
    assert integrity.apk_cd_digest(str(a))[1] != integrity.apk_cd_digest(str(b))[1]


def test_apk_cd_digest_handles_zip_comment(tmp_path):
    """A non-empty EOCD comment must not defeat the EOCD scan."""
    apk = tmp_path / "c.apk"
    _make_zip(apk, {"f": b"data"}, comment=b"this is a trailing comment PK\x05\x06 lookalike")
    size, cd = integrity.apk_cd_digest(str(apk))
    assert (size, cd) == _independent_cd_digest(str(apk))


def test_apk_cd_digest_rejects_zip64_sentinel(tmp_path):
    apk = tmp_path / "z.apk"
    _make_zip(apk, {"f": b"data"})
    blob = bytearray(apk.read_bytes())
    # Find the EOCD (no comment) and force the CD-offset field to the ZIP64 sentinel.
    idx = blob.rfind(b"\x50\x4b\x05\x06")
    struct.pack_into("<I", blob, idx + 16, 0xFFFFFFFF)
    apk.write_bytes(blob)
    with pytest.raises(integrity.Zip64UnsupportedError):
        integrity.apk_cd_digest(str(apk))


def test_apk_cd_digest_rejects_non_zip(tmp_path):
    f = tmp_path / "not.apk"
    f.write_bytes(b"definitely not a zip archive")
    with pytest.raises(ValueError):
        integrity.apk_cd_digest(str(f))


# --------------------------------------------------------------------------- #
# Directory manifest + tree hash
# --------------------------------------------------------------------------- #

def _write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def _expected_tree_hash(files):
    """Independent tree-hash of {relative_path: bytes}, matching the documented spec."""
    entries = sorted(files.items(), key=lambda kv: kv[0].encode("utf-8"))
    h = hashlib.sha256()
    for rel, data in entries:
        line = "{}\n{}\n{}\n".format(rel, len(data), hashlib.sha256(data).hexdigest())
        h.update(line.encode("utf-8"))
    return h.hexdigest()


def test_dir_manifest_matches_independent_spec(tmp_path):
    files = {
        "a.txt": b"hello world\n",
        "sub/b.bin": b"nested content",
        "sub/deep/c.dat": b"deeper",
        "sub/deep/empty-file": b"",
        "zsub/z.txt": b"z",
        "日本語.txt": b"unicode name content",  # non-ASCII sorts by UTF-8 bytes
    }
    for rel, data in files.items():
        _write(tmp_path / rel, data)
    (tmp_path / "emptydir").mkdir()  # empty dir must not appear in the manifest

    result = integrity.dir_manifest(str(tmp_path))
    assert result["tree_hash"] == _expected_tree_hash(files)
    assert result["file_count"] == len(files)
    assert result["total_size"] == sum(len(d) for d in files.values())
    paths = [e["relative_path"] for e in result["manifest"]]
    assert paths == sorted(files.keys(), key=lambda p: p.encode("utf-8"))
    assert all("/" in p or "\\" not in p for p in paths)  # forward slashes only


def test_dir_manifest_is_deterministic(tmp_path):
    _write(tmp_path / "x", b"1")
    _write(tmp_path / "y", b"2")
    assert integrity.dir_manifest(str(tmp_path)) == integrity.dir_manifest(str(tmp_path))


def test_dir_manifest_skips_symlinks(tmp_path):
    _write(tmp_path / "real.txt", b"data")
    try:
        os.symlink(tmp_path / "real.txt", tmp_path / "link.txt")
    except (OSError, NotImplementedError):
        pytest.skip("symlinks not supported on this platform")
    result = integrity.dir_manifest(str(tmp_path))
    assert [e["relative_path"] for e in result["manifest"]] == ["real.txt"]


def test_dir_manifest_omits_manifest_above_cap(tmp_path):
    for i in range(5):
        _write(tmp_path / f"f{i}", b"x")
    capped = integrity.dir_manifest(str(tmp_path), manifest_entry_cap=3)
    assert "manifest" not in capped
    assert capped["file_count"] == 5
    assert capped["tree_hash"]  # tree hash still distinguishes same/different
    uncapped = integrity.dir_manifest(str(tmp_path), manifest_entry_cap=10)
    assert "manifest" in uncapped
    assert capped["tree_hash"] == uncapped["tree_hash"]


def test_dir_manifest_excludes_os_metadata(tmp_path):
    _write(tmp_path / "Movies" / "clip.mp4", b"movie")
    _write(tmp_path / ".DS_Store", b"junk")
    _write(tmp_path / "Movies" / ".DS_Store", b"junk")
    _write(tmp_path / "Movies" / "._clip.mp4", b"junk")
    _write(tmp_path / "__MACOSX" / "._clip.mp4", b"junk")
    result = integrity.dir_manifest(str(tmp_path))
    assert [e["relative_path"] for e in result["manifest"]] == ["Movies/clip.mp4"]
    assert result["excluded_count"] == 4


def test_dir_manifest_keeps_authored_dotfiles(tmp_path):
    # The exclusion list is deliberately narrow: anything a user could have written stays.
    _write(tmp_path / ".gitignore", b"x")
    _write(tmp_path / "scene.unity.meta", b"x")
    _write(tmp_path / ".git" / "config", b"x")
    result = integrity.dir_manifest(str(tmp_path))
    assert [e["relative_path"] for e in result["manifest"]] == [
        ".git/config", ".gitignore", "scene.unity.meta"]
    assert result["excluded_count"] == 0


def test_reference_matches_the_tree_that_push_delivers(tmp_path):
    """A folder picked as a reference must hash to what `push files` actually ships.

    `upload_bundle_handler` drops OS metadata on the way into the bundle, so a device never
    receives it. If the reference kept those files the console would report a permanent
    `missing N` against a device that is in fact identical.
    """
    source, delivered = tmp_path / "source", tmp_path / "delivered"
    for rel in ["Movies/clip.mp4", "Textures/a.png"]:
        _write(source / rel, rel.encode())
        _write(delivered / rel, rel.encode())          # what survives the bundle
    for junk in [".DS_Store", "Movies/.DS_Store", "Movies/._clip.mp4"]:
        _write(source / junk, b"junk")                 # stripped by the bundle upload
        assert integrity.is_os_metadata(junk) is True

    reference = integrity.dir_manifest(str(source))
    device = integrity.dir_manifest(str(delivered))
    assert reference["tree_hash"] == device["tree_hash"]
    assert reference["file_count"] == device["file_count"] == 2
    assert reference["excluded_count"] == 3


def test_dir_manifest_empty_tree(tmp_path):
    result = integrity.dir_manifest(str(tmp_path))
    assert result["file_count"] == 0
    assert result["total_size"] == 0
    assert result["manifest"] == []
    assert result["tree_hash"] == hashlib.sha256(b"").hexdigest()

import hashlib
from pathlib import Path

from styly_mdm.push_artifacts import ArtifactStore


def test_publication_is_immutable_and_sha_identified(tmp_path):
    store = ArtifactStore(tmp_path)
    work = store.work_dir("11111111-1111-4111-8111-111111111111")
    part = work / "artifact.part"
    part.write_bytes(b"payload")
    published = store.publish(
        "11111111-1111-4111-8111-111111111111", part, "content.zip", 1
    )
    path = store.path_for_record(published)
    assert path.read_bytes() == b"payload"
    assert published["sha256"] == hashlib.sha256(b"payload").hexdigest()
    assert not part.exists()

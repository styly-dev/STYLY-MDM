"""Run the recording console behavior tests when Node is installed."""
import shutil
import subprocess
from pathlib import Path

import pytest


def test_recording_console():
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is unavailable")
    subprocess.run([node, "--test", str(Path(__file__).with_suffix(".js"))], check=True)

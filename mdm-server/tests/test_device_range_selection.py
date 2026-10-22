import shutil
import subprocess
from pathlib import Path

import pytest


def test_device_range_selection_behavior() -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is not available")
    script = Path(__file__).with_suffix(".js")
    subprocess.run([node, "--test", str(script)], check=True, capture_output=True, text=True)

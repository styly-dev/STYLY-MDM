import shutil
import subprocess
from pathlib import Path

import pytest


def test_browser_console_behavior():
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is not available")
    subprocess.run([node, "--test", str(Path(__file__).with_suffix(".js"))],
                   check=True, capture_output=True, text=True)

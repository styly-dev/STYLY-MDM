"""STYLY-MDM control server package.

Exposes the aiohttp application factory and console entrypoint.  Issue #91's
push-job subsystem is installed before the application registers routes, while
established non-push handlers remain in :mod:`styly_mdm.server`.
"""

from importlib.metadata import PackageNotFoundError, version

from . import server as server
from .push_runtime import install as _install_push_runtime

_install_push_runtime(server)

create_app = server.create_app
run_server = server.run_server
main = server.main

try:
    __version__ = version("styly-mdm")
except PackageNotFoundError:  # running from a source tree that isn't installed
    __version__ = "0.0.0"

__all__ = ["create_app", "main", "run_server", "server", "__version__"]

"""STYLY-MDM control server package.

Exposes the aiohttp application factory and the console entrypoint. The server is
started with the ``styly-mdm`` console script (or ``python -m styly_mdm``); it is
not an ASGI app, because the LAN UDP discovery responder is started in the same
asyncio loop as the HTTP server (see server.run_server).
"""

from importlib.metadata import PackageNotFoundError, version

from .server import create_app, main, run_server

try:
    __version__ = version("styly-mdm")
except PackageNotFoundError:  # running from a source tree that isn't installed
    __version__ = "0.0.0"

__all__ = ["create_app", "main", "run_server", "__version__"]

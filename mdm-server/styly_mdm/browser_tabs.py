"""Data-preserving browser tab control through authorized, identity-checked ADB.

The WebLayer tab snapshot/close/verify flow follows IMMENSIVA-MDM PR #4.
"""
import asyncio
from ipaddress import ip_address
import json
import os
import re
import shlex
import shutil

import aiohttp

GUID = r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"


class BrowserTabs:
    def __init__(self, adb=None):
        self.adb = shutil.which(adb or os.environ.get("STYLY_MDM_ADB") or "adb")

    @property
    def available(self):
        return self.adb is not None

    async def command(self, *args, timeout=15):
        try:
            process = await asyncio.create_subprocess_exec(
                self.adb, *args, stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
                creationflags=0x08000000 if os.name == "nt" else 0)
            try:
                output, _ = await asyncio.wait_for(process.communicate(), timeout)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                if process.returncode is None:
                    process.kill()
                await process.wait()
                raise
        except (OSError, asyncio.TimeoutError) as error:
            raise RuntimeError("ADB did not respond. Check the server's ADB installation and headset authorization.") from error
        if process.returncode:
            # ADB and browser diagnostics can echo private URLs or device data.
            raise RuntimeError("ADB could not access the headset. Check network debugging and authorization.")
        return output.decode(errors="replace").strip()

    async def connect(self, device):
        device_id = device.get("device_id")
        if (device.get("identity_kind") != "canonical" or not isinstance(device_id, str)
                or not re.fullmatch(GUID, device_id)):
            raise ValueError("Select a headset with a ready canonical identity.")
        try:
            address = str(ip_address(device.get("ip")))
        except ValueError:
            raise ValueError("The headset has no valid network address.") from None
        address = (f"[{address}]" if ":" in address else address) + ":5555"
        await self.command("connect", address)
        # Read the same published MediaStore namespace as the pinned ID Provider.
        # Never create an ID, substitute a serial, or trust the IP address alone.
        output = await self.command("-s", address, "shell", shlex.join([
            "content", "query", "--uri", "content://media/external/images/media",
            "--projection", "_id:_display_name:date_added", "--where",
            "relative_path LIKE 'Pictures/Device-ID-Provider/%' AND _display_name LIKE '%.png' AND is_pending=0",
            "--sort", "date_added ASC, _id ASC",
        ]))
        candidates = []
        for line in output.splitlines():
            row = re.fullmatch(r"Row: \d+ _id=\d+, _display_name=([^\r\n]+), date_added=\d+", line.strip())
            if row is None:
                raise RuntimeError("Could not verify the headset's published identity. No tabs were closed.")
            filename = row.group(1)
            if re.fullmatch(GUID + r"\.png", filename):
                candidates.append(filename[:-4])
        if candidates != [device_id]:
            raise RuntimeError("ADB identity is missing, ambiguous, or does not match the selected headset. No tabs were closed.")
        return address

    async def close_all_tabs(self, device):
        if device.get("status") != "online":
            raise ValueError("Select an online headset.")
        if not self.available:
            raise ValueError("Close All Tabs needs Android platform-tools on the server. Set STYLY_MDM_ADB or add adb to PATH.")
        try:
            return await asyncio.wait_for(self._close_all_tabs(device), timeout=90)
        except asyncio.TimeoutError as error:
            raise RuntimeError("Closing browser tabs timed out. Check the headset before retrying.") from error

    async def _close_all_tabs(self, device):
        address = await self.connect(device)
        package = await self.resolve_browser(address)
        return await self.close_browser_tabs(address, package)

    async def browser_socket(self, address, package):
        async def find():
            pids = (await self.command("-s", address, "shell", "pidof " + shlex.quote(package) + " || true")).split()
            if not pids:
                return None
            if len(pids) != 1 or not pids[0].isdigit():
                raise RuntimeError("Could not identify the headset browser process.")
            # Only use the socket belonging to the resolved browser's main process.
            # Other apps' WebViews may also expose debugging sockets on this headset.
            socket = "weblayer_devtools_remote_" + pids[0]
            sockets = (await self.command("-s", address, "shell", "cat", "/proc/net/unix")).split()
            return socket if "@" + socket in sockets else None

        socket = await find()
        if socket:
            return socket
        # Restore saved tabs if the browser is not running, without opening a URL.
        output = await self.command("-s", address, "shell", shlex.join([
            "am", "start", "-W", "--user", "current", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.APP_BROWSER", "-p", package,
        ]), timeout=30)
        if not re.search(r"(?im)^Status:\s*ok\s*$", output):
            raise RuntimeError("Could not open the browser to close its saved tabs.")
        # PICO can return Status: ok while retaining its current system activity
        # and never starting the browser. Do not misreport that as unsupported.
        if re.search(r"Activity not started because.*current activity.*kept", output):
            socket = await find()
            if socket:
                return socket
            raise RuntimeError("The headset did not allow its browser to open. Wake the headset and open its browser once, then retry. No browser data was cleared.")
        for _ in range(12):
            socket = await find()
            if socket:
                return socket
            await asyncio.sleep(.25)
        raise RuntimeError("This headset browser does not expose tab controls. No browser data was cleared.")

    async def devtools_request(self, session, port, path):
        try:
            async with session.get(f"http://127.0.0.1:{port}{path}", allow_redirects=False) as response:
                if response.status != 200:
                    raise RuntimeError("The headset browser rejected a tab-control request.")
                data = bytearray()
                async for chunk in response.content.iter_chunked(65536):
                    data.extend(chunk)
                    if len(data) > 1024 * 1024:
                        raise RuntimeError("The headset browser returned too much tab information.")
                return data.decode("utf-8")
        except (aiohttp.ClientError, asyncio.TimeoutError, UnicodeError) as error:
            raise RuntimeError("Browser tab controls did not respond. Check the headset before retrying.") from error

    async def browser_tabs(self, session, port):
        try:
            targets = json.loads(await self.devtools_request(session, port, "/json/list"))
            if not isinstance(targets, list) or any(not isinstance(target, dict) for target in targets):
                raise ValueError()
            tabs = {}
            for target in targets:
                if target.get("type") != "page":
                    continue
                key = target.get("id")
                if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", key) or key in tabs:
                    raise ValueError()
                # Keep only whether a page is empty; never return/log browsing URLs.
                tabs[key] = target.get("url") in {"about:blank", "chrome://newtab/", "chrome-native://newtab/"}
            return tabs
        except (ValueError, TypeError) as error:
            raise RuntimeError("Could not read the headset browser's tab list.") from error

    async def close_browser_tabs(self, address, package):
        socket = await self.browser_socket(address, package)
        # Once ADB allocates a port, retain ownership until its number is returned.
        # Cancellation must not kill that response and lose the cleanup address.
        allocation = asyncio.create_task(self.command(
            "-s", address, "forward", "tcp:0", "localabstract:" + socket))
        cancelled = False
        try:
            port = await asyncio.shield(allocation)
        except asyncio.CancelledError:
            cancelled = True
            try:
                port = await allocation
            except Exception:
                raise asyncio.CancelledError() from None
        if not port.isdigit() or not 1 <= int(port) <= 65535:
            raise RuntimeError("Could not create the temporary browser connection.")
        try:
            if cancelled:
                raise asyncio.CancelledError()
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5), trust_env=False) as session:
                original = await self.browser_tabs(session, port)
                # Snapshot the tabs. Do not chase tabs opened by a person during the operation.
                for key in original:
                    try:
                        await self.devtools_request(session, port, "/json/close/" + key)
                    except RuntimeError:
                        # A lost response is ambiguous: verify the resulting list, never retry closing.
                        pass
                remaining = await self.browser_tabs(session, port)
                for _ in range(8):
                    if not original.keys() & remaining.keys():
                        break
                    await asyncio.sleep(.25)
                    remaining = await self.browser_tabs(session, port)
                closed = len(original.keys() - remaining.keys())
                empty_replacement = (len(remaining) == 1 and all(remaining.values())
                                     and not original.keys() & remaining.keys())
                complete = not remaining or empty_replacement
                if empty_replacement:
                    message = f"Closed {closed} browser tab(s). The browser has one new empty tab."
                elif complete:
                    message = f"Closed all {closed} browser tab(s)." if closed else "No open browser tabs."
                else:
                    message = f"Closed {closed} browser tab(s); {len(remaining)} remain. Check the headset before retrying."
                return {"status": "confirmed" if complete else "partial", "closed_tabs": closed,
                        "remaining_tabs": len(remaining), "message": message}
        finally:
            # Remove only this request's dynamically allocated forward, including on cancellation.
            await self.command("-s", address, "forward", "--remove", "tcp:" + port, timeout=5)

    async def resolve_browser(self, address):
        # Resolve without a URL: `am`'s --selector parsing can attach the URL
        # to MAIN/APP_BROWSER, which PICO's launcher activity cannot handle.
        # Use the discovered package with VIEW so Android chooses its URL entry
        # point (IntentDispatcher on PICO), not its launcher activity (Main).
        try:
            output = await self.command("-s", address, "shell", shlex.join([
                "cmd", "package", "resolve-activity", "--brief", "--user", "current",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.APP_BROWSER",
            ]))
        except (OSError, RuntimeError, asyncio.TimeoutError) as error:
            raise RuntimeError("Could not find the headset browser. Check its ADB connection.") from error
        if "ResolverActivity" in output or "ChooserActivity" in output:
            raise RuntimeError("Choose a default browser inside the headset, then try again.")
        components = re.findall(r"(?m)^\s*([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+\s*$", output)
        if len(components) != 1:
            raise RuntimeError("The headset could not find a browser. Check that a browser is enabled and allowed by headset policy.")
        return components[0]



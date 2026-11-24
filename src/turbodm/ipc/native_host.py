"""Chrome & Firefox Native Messaging Host for TurboDM.

Reads 4-byte little-endian length-prefixed JSON messages from stdin,
ensures TurboDM desktop app is running (auto-launching it if needed),
forwards the command securely with the local IPC token, and writes a
length-prefixed JSON response to stdout.
"""

from __future__ import annotations

import json
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, BinaryIO

from turbodm.constants import IPC_DEFAULT_PORT, IPC_HOST, get_config_dir
from turbodm.ipc.server import get_or_create_ipc_token


def read_native_message(stream: BinaryIO) -> dict[str, Any] | None:
    raw_len = stream.read(4)
    if not raw_len or len(raw_len) < 4:
        return None
    msg_len = struct.unpack("<I", raw_len)[0]
    if msg_len == 0 or msg_len > 4 * 1024 * 1024:
        return None
    raw_msg = stream.read(msg_len)
    if len(raw_msg) < msg_len:
        return None
    return json.loads(raw_msg.decode("utf-8"))


def write_native_message(stream: BinaryIO, payload: dict[str, Any]) -> None:
    encoded = json.dumps(payload).encode("utf-8")
    stream.write(struct.pack("<I", len(encoded)))
    stream.write(encoded)
    stream.flush()


def _ipc_request(
    method: str,
    path: str,
    port: int = IPC_DEFAULT_PORT,
    token: str = "",
    body: dict[str, Any] | None = None,
    timeout: float = 3.0,
) -> dict[str, Any]:
    url = f"http://{IPC_HOST}:{port}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-TurboDM-Token"] = token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _is_app_running(port: int = IPC_DEFAULT_PORT) -> bool:
    try:
        res = _ipc_request("GET", "/ping", port=port, timeout=1.0)
        return bool(res.get("ok"))
    except Exception:
        return False


def _launch_turbodm_app() -> bool:
    """Spawn TurboDM desktop app in background so browser downloads work seamlessly."""
    candidates = [
        str(Path.home() / ".local" / "bin" / "turbodm"),
        shutil.which("turbodm") or "",
    ]
    exe = next((c for c in candidates if c and Path(c).exists()), None)
    if not exe:
        return False
    try:
        subprocess.Popen(
            [exe, "--background"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        return True
    except OSError:
        return False


def ensure_app_running(port: int = IPC_DEFAULT_PORT, wait_seconds: float = 6.0) -> bool:
    if _is_app_running(port):
        return True
    if not _launch_turbodm_app():
        return False
    deadline = time.monotonic() + wait_seconds
    while time.monotonic() < deadline:
        time.sleep(0.25)
        if _is_app_running(port):
            return True
    return False


def handle_message(msg: dict[str, Any], port: int | None = None) -> dict[str, Any]:
    """Process one Native Messaging command from Chrome/Firefox extension."""
    target_port = int(port or msg.get("port") or IPC_DEFAULT_PORT)
    action = str(msg.get("action", "ping"))
    token = get_or_create_ipc_token(get_config_dir())

    if action == "ping":
        running = _is_app_running(target_port)
        return {"ok": running, "running": running, "port": target_port}

    if action == "launch":
        started = ensure_app_running(target_port)
        if started:
            try:
                _ipc_request("POST", "/focus", port=target_port, token=token, body={})
            except Exception:
                pass
        return {"ok": started, "running": started}

    # For add / add_batch / focus / downloads, auto-start app if closed
    if not ensure_app_running(target_port):
        return {
            "ok": False,
            "error": "TurboDM desktop app is not running and could not be launched automatically.",
        }

    try:
        if action == "add":
            return _ipc_request("POST", "/add", port=target_port, token=token, body=msg)
        if action == "add_batch":
            return _ipc_request("POST", "/add_batch", port=target_port, token=token, body=msg)
        if action == "focus":
            return _ipc_request("POST", "/focus", port=target_port, token=token, body={})
        if action == "downloads":
            return _ipc_request("GET", "/downloads", port=target_port, token=token)
        return {"ok": False, "error": f"Unknown action: {action}"}
    except urllib.error.HTTPError as exc:
        return {"ok": False, "error": f"IPC HTTP {exc.code}: {exc.reason}"}
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def main() -> None:
    stdin_bin = sys.stdin.buffer
    stdout_bin = sys.stdout.buffer
    while True:
        msg = read_native_message(stdin_bin)
        if msg is None:
            break
        response = handle_message(msg)
        write_native_message(stdout_bin, response)


if __name__ == "__main__":
    main()

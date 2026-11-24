"""Lightweight async localhost HTTP/JSON IPC server for browser & CLI integration."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import secrets
from collections.abc import Callable
from pathlib import Path
from typing import Any

from turbodm.constants import APP_NAME, APP_VERSION, IPC_DEFAULT_PORT, IPC_HOST, get_config_dir
from turbodm.core.manager import DownloadManager

logger = logging.getLogger(__name__)


def get_or_create_ipc_token(config_dir: Path | None = None) -> str:
    """Load or generate a per-user secret token (chmod 0600) protecting local IPC."""
    cfg = config_dir or get_config_dir()
    cfg.mkdir(parents=True, exist_ok=True)
    token_path = cfg / "ipc_token"
    if token_path.exists():
        token = token_path.read_text(encoding="utf-8").strip()
        if len(token) >= 24:
            return token
    token = secrets.token_urlsafe(32)
    token_path.write_text(token, encoding="utf-8")
    try:
        os.chmod(token_path, 0o600)
    except OSError:
        pass
    return token


class LocalIPCServer:
    """Async TCP HTTP/1.1 JSON server bound strictly to 127.0.0.1."""

    def __init__(
        self,
        manager: DownloadManager,
        port: int = IPC_DEFAULT_PORT,
        token: str | None = None,
        on_incoming_download: Callable[[dict[str, Any]], None] | None = None,
        on_incoming_batch: Callable[[list[str], dict[str, Any]], None] | None = None,
        on_focus_window: Callable[[], None] | None = None,
    ) -> None:
        self.manager = manager
        self.port = port
        self.token = token or get_or_create_ipc_token()
        self.on_incoming_download = on_incoming_download
        self.on_incoming_batch = on_incoming_batch
        self.on_focus_window = on_focus_window
        self._server: asyncio.AbstractServer | None = None

    @property
    def bound_port(self) -> int:
        if self._server and self._server.sockets:
            return int(self._server.sockets[0].getsockname()[1])
        return self.port

    async def start(self) -> None:
        self._server = await asyncio.start_server(
            self._handle_client,
            host=IPC_HOST,
            port=self.port,
        )
        logger.info("TurboDM IPC server listening on http://%s:%d", IPC_HOST, self.bound_port)

    async def stop(self) -> None:
        if self._server:
            self._server.close()
            await self._server.wait_closed()
            self._server = None

    async def _handle_client(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        try:
            raw_head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5.0)
        except Exception:
            writer.close()
            return

        try:
            head_text = raw_head.decode("utf-8", errors="replace")
            lines = head_text.split("\r\n")
            if not lines:
                await self._send_json(writer, 400, {"ok": False, "error": "Malformed request"})
                return

            parts = lines[0].split(" ")
            if len(parts) < 2:
                await self._send_json(writer, 400, {"ok": False, "error": "Malformed request line"})
                return
            method, path = parts[0].upper(), parts[1].split("?")[0]

            headers: dict[str, str] = {}
            for line in lines[1:]:
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()

            content_length = int(headers.get("content-length", "0") or "0")
            if content_length > 2 * 1024 * 1024:
                await self._send_json(writer, 413, {"ok": False, "error": "Payload too large"})
                return

            body_bytes = b""
            if content_length > 0:
                body_bytes = await asyncio.wait_for(
                    reader.readexactly(content_length), timeout=5.0
                )

            if method == "OPTIONS":
                await self._send_json(writer, 200, {"ok": True})
                return

            # Public healthcheck (read-only minimal info)
            if method == "GET" and path == "/ping":
                await self._send_json(
                    writer,
                    200,
                    {
                        "ok": True,
                        "app": APP_NAME,
                        "version": APP_VERSION,
                        "active_downloads": self.manager.get_active_count(),
                    },
                )
                return

            # Authenticate all other endpoints with per-user token
            provided_token = headers.get("x-turbodm-token", "")
            if not secrets.compare_digest(provided_token, self.token):
                await self._send_json(writer, 401, {"ok": False, "error": "Unauthorized"})
                return

            payload: dict[str, Any] = {}
            if body_bytes:
                try:
                    payload = json.loads(body_bytes.decode("utf-8"))
                except ValueError:
                    await self._send_json(writer, 400, {"ok": False, "error": "Invalid JSON"})
                    return

            status, resp_data = self._dispatch(method, path, payload)
            await self._send_json(writer, status, resp_data)
        except Exception as exc:
            logger.exception("IPC request handler error")
            try:
                await self._send_json(writer, 500, {"ok": False, "error": str(exc)})
            except Exception:
                pass
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

    def _dispatch(
        self, method: str, path: str, payload: dict[str, Any]
    ) -> tuple[int, dict[str, Any]]:
        if method == "GET" and path == "/downloads":
            items = [i.to_summary_dict() for i in self.manager.list_downloads()]
            return 200, {"ok": True, "downloads": items}

        if method == "POST" and path == "/focus":
            if self.on_focus_window:
                self.on_focus_window()
            return 200, {"ok": True}

        if method == "POST" and path == "/add":
            url = str(payload.get("url", "")).strip()
            if not url:
                return 400, {"ok": False, "error": "Missing url"}

            show_dialog = bool(payload.get("show_dialog", True))
            if show_dialog and self.on_incoming_download:
                self.on_incoming_download(payload)
                return 200, {"ok": True, "queued_ui": True}

            item = self.manager.add_download(
                url=url,
                filename=str(payload.get("filename", "")),
                save_dir=str(payload.get("save_dir", "")),
                category=str(payload.get("category", "")),
                referer=str(payload.get("referer", "")),
                cookies=str(payload.get("cookies", "")),
                user_agent=str(payload.get("user_agent", "")),
                is_media=payload.get("is_media"),
                start_immediately=bool(payload.get("start_immediately", True)),
            )
            return 200, {"ok": True, "download": item.to_summary_dict()}

        if method == "POST" and path == "/add_batch":
            raw_urls = payload.get("urls") or []
            urls = [str(u).strip() for u in raw_urls if str(u).strip()]
            if not urls:
                return 400, {"ok": False, "error": "Missing urls list"}
            if self.on_incoming_batch:
                self.on_incoming_batch(urls, payload)
                return 200, {"ok": True, "count": len(urls)}
            added = []
            for u in urls:
                item = self.manager.add_download(
                    url=u,
                    referer=str(payload.get("referer", "")),
                    cookies=str(payload.get("cookies", "")),
                    user_agent=str(payload.get("user_agent", "")),
                    start_immediately=False,
                )
                added.append(item.id)
            return 200, {"ok": True, "count": len(added), "ids": added}

        return 404, {"ok": False, "error": f"Unknown route: {method} {path}"}

    @staticmethod
    async def _send_json(
        writer: asyncio.StreamWriter, status_code: int, data: dict[str, Any]
    ) -> None:
        reasons = {
            200: "OK",
            400: "Bad Request",
            401: "Unauthorized",
            404: "Not Found",
            413: "Payload Too Large",
            500: "Internal Server Error",
        }
        body = json.dumps(data).encode("utf-8")
        reason = reasons.get(status_code, "OK")
        response = (
            f"HTTP/1.1 {status_code} {reason}\r\n"
            f"Content-Type: application/json; charset=utf-8\r\n"
            f"Content-Length: {len(body)}\r\n"
            f"Access-Control-Allow-Origin: *\r\n"
            f"Access-Control-Allow-Headers: Content-Type, X-TurboDM-Token\r\n"
            f"Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
            f"Connection: close\r\n"
            f"\r\n"
        ).encode("ascii") + body
        writer.write(response)
        await writer.drain()

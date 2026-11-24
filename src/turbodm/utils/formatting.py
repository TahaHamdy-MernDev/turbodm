"""Formatting, filename sanitization, and HTTP header parsing helpers."""

from __future__ import annotations

import hashlib
import os
import re
from pathlib import Path
from urllib.parse import unquote, urlparse

from turbodm.constants import DEFAULT_CATEGORIES, get_downloads_root


def format_bytes(num_bytes: int | float | None) -> str:
    if num_bytes is None or num_bytes < 0:
        return "Unknown"
    value = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024.0 or unit == "TB":
            if unit == "B":
                return f"{int(value)} B"
            return f"{value:.2f} {unit}"
        value /= 1024.0
    return f"{value:.2f} TB"


def format_speed(bytes_per_sec: float | None) -> str:
    if not bytes_per_sec or bytes_per_sec <= 0:
        return "0 B/s"
    return f"{format_bytes(bytes_per_sec)}/s"


def format_eta(seconds: float | None) -> str:
    if seconds is None or seconds < 0 or seconds > 86400 * 30:
        return "--"
    secs = int(round(seconds))
    if secs < 60:
        return f"{secs} sec"
    mins, secs = divmod(secs, 60)
    if mins < 60:
        return f"{mins} min {secs} sec"
    hours, mins = divmod(mins, 60)
    if hours < 24:
        return f"{hours} hr {mins} min"
    days, hours = divmod(hours, 24)
    return f"{days} d {hours} hr"


def sanitize_filename(name: str, fallback: str = "download") -> str:
    """Sanitize filename preserving Unicode/Arabic while removing unsafe path chars."""
    if not name:
        return fallback
    name = unquote(name).strip()
    # Keep basename only if path separators snuck in
    name = name.replace("\\", "/").split("/")[-1]
    # Remove control chars and forbidden filesystem chars
    name = re.sub(r'[\x00-\x1f\x7f<>:"|?*]', "_", name)
    name = name.strip(" .")
    if not name or name in {".", ".."}:
        return fallback
    # Limit byte length to 240 for ext4/btrfs safety (preserves UTF-8 boundary)
    encoded = name.encode("utf-8")
    if len(encoded) > 240:
        stem, dot, ext = name.rpartition(".")
        if dot and len(ext.encode("utf-8")) < 20:
            max_stem = 240 - len(ext.encode("utf-8")) - 1
            trimmed = stem.encode("utf-8")[:max_stem].decode("utf-8", errors="ignore")
            name = f"{trimmed}.{ext}"
        else:
            name = encoded[:240].decode("utf-8", errors="ignore")
    return name or fallback


def parse_content_disposition(header_value: str | None) -> str | None:
    """Parse RFC 5987 filename* and standard filename from Content-Disposition."""
    if not header_value:
        return None
    # RFC 5987: filename*=UTF-8''%D9%85%D9%84%D9%81.zip
    m_ext = re.search(r"filename\*\s*=\s*([^'\s]+)'[^']*'?([^;\r\n]+)", header_value, re.IGNORECASE)
    if m_ext:
        charset = m_ext.group(1) or "utf-8"
        raw = m_ext.group(2).strip().strip('"').strip("'")
        try:
            return sanitize_filename(unquote(raw, encoding=charset, errors="replace"))
        except LookupError:
            return sanitize_filename(unquote(raw))

    # Standard filename="..." or filename=...
    m_std = re.search(r'filename\s*=\s*"([^"]+)"', header_value, re.IGNORECASE)
    if m_std:
        return sanitize_filename(m_std.group(1))
    m_bare = re.search(r"filename\s*=\s*([^;\r\n]+)", header_value, re.IGNORECASE)
    if m_bare:
        return sanitize_filename(m_bare.group(1).strip().strip('"').strip("'"))
    return None


def filename_from_url(url: str, fallback: str = "download") -> str:
    parsed = urlparse(url)
    path_part = unquote(parsed.path or "").rstrip("/")
    if path_part:
        candidate = path_part.split("/")[-1]
        if candidate and candidate not in {".", ".."}:
            return sanitize_filename(candidate, fallback=fallback)
    if parsed.netloc:
        return sanitize_filename(parsed.netloc.replace(":", "_"), fallback=fallback)
    return fallback


def detect_category(filename: str, custom_rules: dict[str, list[str]] | None = None) -> str:
    """Detect IDM-style category from filename extension."""
    ext = Path(filename).suffix.lstrip(".").lower()
    if not ext:
        return "General"
    if custom_rules:
        for cat, exts in custom_rules.items():
            if ext in [e.lower().lstrip(".") for e in exts]:
                return cat
    for cat, meta in DEFAULT_CATEGORIES.items():
        if ext in meta["extensions"]:  # type: ignore[operator]
            return cat
    return "General"


def get_category_folder(
    category: str,
    custom_folders: dict[str, str] | None = None,
    downloads_root: Path | None = None,
) -> Path:
    if custom_folders and category in custom_folders and custom_folders[category]:
        return Path(os.path.expanduser(custom_folders[category]))
    root = downloads_root or get_downloads_root()
    meta = DEFAULT_CATEGORIES.get(category)
    sub = str(meta["folder"]) if meta else ""
    return (root / sub) if sub else root


def unique_filepath(directory: Path, filename: str) -> Path:
    """Return a non-colliding file path like file.zip -> file (1).zip."""
    directory.mkdir(parents=True, exist_ok=True)
    candidate = directory / filename
    if not candidate.exists():
        return candidate
    stem = candidate.stem
    suffix = candidate.suffix
    counter = 1
    while True:
        alt = directory / f"{stem} ({counter}){suffix}"
        if not alt.exists():
            return alt
        counter += 1


def compute_file_hash(filepath: Path, algorithm: str = "sha256") -> str:
    h = hashlib.new(algorithm.lower())
    with open(filepath, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()

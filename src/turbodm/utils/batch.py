"""Batch URL expansion (IDM wildcard syntax like [01-10] and [a-z]) and list parsing."""

from __future__ import annotations

import itertools
import re

WILDCARD_PATTERN = re.compile(r"\[([0-9]+)-([0-9]+)(?::([0-9]+))?\]|\[([a-zA-Z])-([a-zA-Z])\]")


def expand_wildcard_url(pattern: str, max_urls: int = 5000) -> list[str]:
    """Expand IDM-style batch wildcard patterns.

    Examples:
        https://example.com/file[01-05].zip -> file01.zip .. file05.zip
        https://example.com/img_[1-10:2].jpg -> img_1.jpg, img_3.jpg, ..., img_9.jpg
        https://example.com/part_[a-d].rar -> part_a.rar .. part_d.rar
    """
    pattern = pattern.strip()
    if not pattern:
        return []

    matches = list(WILDCARD_PATTERN.finditer(pattern))
    if not matches:
        return [pattern]

    parts: list[str] = []
    ranges: list[list[str]] = []
    last_idx = 0

    for match in matches:
        parts.append(pattern[last_idx : match.start()])
        if match.group(1) is not None:
            start_str, end_str, step_str = match.group(1), match.group(2), match.group(3)
            start_num, end_num = int(start_str), int(end_str)
            step = max(1, int(step_str)) if step_str else 1
            width = len(start_str) if start_str.startswith("0") else 0
            if start_num <= end_num:
                nums = range(start_num, end_num + 1, step)
            else:
                nums = range(start_num, end_num - 1, -step)
            expanded = [str(n).zfill(width) if width else str(n) for n in nums]
            ranges.append(expanded)
        else:
            c1, c2 = match.group(4), match.group(5)
            o1, o2 = ord(c1), ord(c2)
            step = 1 if o1 <= o2 else -1
            expanded = [chr(c) for c in range(o1, o2 + step, step)]
            ranges.append(expanded)
        last_idx = match.end()

    parts.append(pattern[last_idx:])

    results: list[str] = []
    for combo in itertools.product(*ranges):
        url_pieces: list[str] = []
        for idx, value in enumerate(combo):
            url_pieces.append(parts[idx])
            url_pieces.append(value)
        url_pieces.append(parts[-1])
        results.append("".join(url_pieces))
        if len(results) >= max_urls:
            break
    return results


def parse_url_list(text: str) -> list[str]:
    """Extract valid HTTP(S)/FTP URLs from multi-line text, expanding wildcards."""
    urls: list[str] = []
    seen: set[str] = set()
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if not re.match(r"^(https?|ftp)://", line, re.IGNORECASE):
            continue
        for url in expand_wildcard_url(line):
            if url not in seen:
                seen.add(url)
                urls.append(url)
    return urls

"""Tests for formatting, Arabic/UTF-8 filenames, category routing, and batch wildcard expansion."""

from __future__ import annotations

from pathlib import Path

from turbodm.utils.batch import expand_wildcard_url, parse_url_list
from turbodm.utils.formatting import (
    detect_category,
    filename_from_url,
    format_bytes,
    format_eta,
    format_speed,
    get_category_folder,
    parse_content_disposition,
    sanitize_filename,
    unique_filepath,
)


def test_format_helpers():
    assert format_bytes(512) == "512 B"
    assert format_bytes(1024 * 1024) == "1.00 MB"
    assert format_bytes(None) == "Unknown"
    assert format_speed(2048) == "2.00 KB/s"
    assert format_speed(0) == "0 B/s"
    assert format_eta(45) == "45 sec"
    assert format_eta(125) == "2 min 5 sec"
    assert format_eta(3665) == "1 hr 1 min"


def test_arabic_and_utf8_filename_handling():
    # RFC 5987 UTF-8 Arabic filename in Content-Disposition
    cd = "attachment; filename*=UTF-8''%D9%85%D9%84%D9%81_%D8%AA%D8%AC%D8%B1%D9%8A%D8%A8%D9%8A.pdf"
    assert parse_content_disposition(cd) == "ملف_تجريبي.pdf"
    assert parse_content_disposition('attachment; filename="setup.rpm"') == "setup.rpm"
    assert sanitize_filename("../../../etc/passwd") == "passwd"
    assert sanitize_filename("فيديو: تجربة*.mp4") == "فيديو_ تجربة_.mp4"
    assert filename_from_url("https://example.com/files/archive.tar.gz?token=1") == "archive.tar.gz"


def test_category_detection_and_folders(tmp_path: Path):
    assert detect_category("fedora-45.iso") == "Compressed"
    assert detect_category("report.pdf") == "Documents"
    assert detect_category("song.flac") == "Music"
    assert detect_category("installer.rpm") == "Programs"
    assert detect_category("movie.mkv") == "Video"
    assert detect_category("unknown.xyz") == "General"

    assert get_category_folder("Video", downloads_root=tmp_path) == tmp_path / "Video"
    assert get_category_folder("General", downloads_root=tmp_path) == tmp_path


def test_unique_filepath(tmp_path: Path):
    p1 = unique_filepath(tmp_path, "test.zip")
    assert p1.name == "test.zip"
    p1.write_bytes(b"a")
    p2 = unique_filepath(tmp_path, "test.zip")
    assert p2.name == "test (1).zip"
    p2.write_bytes(b"b")
    p3 = unique_filepath(tmp_path, "test.zip")
    assert p3.name == "test (2).zip"


def test_batch_wildcard_expansion():
    urls = expand_wildcard_url("https://example.com/ep[01-04].mp4")
    assert urls == [
        "https://example.com/ep01.mp4",
        "https://example.com/ep02.mp4",
        "https://example.com/ep03.mp4",
        "https://example.com/ep04.mp4",
    ]
    alpha = expand_wildcard_url("https://example.com/part_[a-c].rar")
    assert alpha == [
        "https://example.com/part_a.rar",
        "https://example.com/part_b.rar",
        "https://example.com/part_c.rar",
    ]
    stepped = expand_wildcard_url("https://example.com/img_[1-5:2].jpg")
    assert stepped == [
        "https://example.com/img_1.jpg",
        "https://example.com/img_3.jpg",
        "https://example.com/img_5.jpg",
    ]

    parsed = parse_url_list(
        "# comment\nhttps://a.com/f[1-2].zip\nnot-a-url\nhttps://a.com/f1.zip\n"
    )
    assert parsed == ["https://a.com/f1.zip", "https://a.com/f2.zip"]

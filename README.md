<div align="center">

<img src="assets/com.turbodm.app.svg" alt="TurboDM Logo" width="92" height="92" />

# TurboDM

**IDM-Class Download Speed. Built Natively for Fedora Linux (GNOME / Wayland).**

[![Live Website](https://img.shields.io/badge/Website-turbodm.pages.dev-3584E4?style=flat-square&logo=cloudflarepages&logoColor=white)](https://turbodm.pages.dev)
[![CI](https://github.com/TahaHamdy-MernDev/turbodm/actions/workflows/ci.yml/badge.svg)](https://github.com/TahaHamdy-MernDev/turbodm/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/tag/TahaHamdy-MernDev/turbodm?label=Release&color=2EC27E&style=flat-square)](https://github.com/TahaHamdy-MernDev/turbodm/releases)
[![Platform](https://img.shields.io/badge/Fedora-GTK4%20%2B%20Libadwaita-51A2DA?style=flat-square&logo=fedora&logoColor=white)](https://turbodm.pages.dev)
[![License: MIT](https://img.shields.io/badge/License-MIT-E5A50A?style=flat-square)](LICENSE)

[🌐 Official Website](https://turbodm.pages.dev) • [⚡ Quick Install](#quick-install-fedora-linux) • [🧩 Browser Extensions](#browser-extensions-chrome--firefox) • [📦 Releases](https://github.com/TahaHamdy-MernDev/turbodm/releases)

</div>

---

## Overview

**TurboDM** is a fast, lightweight, native Linux download manager built for **Fedora Workstation (GNOME / Wayland)** using **Python + GTK4 + Libadwaita**, designed around the classic Internet Download Manager (IDM) workflow with a dark-first Fedora Native visual identity (~40 MB RAM — zero Electron bloat).

## Key Features

- **High-Speed Multi-Part Engine with Dynamic Segmentation**:
  - Splits downloads across **16–32 parallel byte-range connections** using `256 KB` reads, `1 MB` per-segment memory write buffers, and atomic `os.pwrite` disk I/O (zero post-download copy overhead).
  - **IDM-style Work Stealing**: When a fast worker finishes early, it automatically splits the slowest remaining active segment in half to eliminate tail slowdown.
  - Shares pooled connections and skips redundant probe handshakes for instant download start.
- **Crash-Safe Pause & Resume**:
  - Persists exact byte offsets, segment ranges, and `ETag` / `Last-Modified` metadata in SQLite WAL (`~/.local/share/turbodm/turbodm.db`) so downloads survive pauses, disconnects, and restarts.
- **YouTube, HLS & DASH Video Grabber (`yt-dlp` + `ffmpeg`)**:
  - Auto-detects YouTube, Vimeo, `.m3u8` (HLS), `.mpd` (DASH), and 1000+ streaming sites with an interactive quality/resolution selector (4K/1080p/720p or Audio-Only MP3/M4A) and `10 MB` chunk anti-throttling.
- **Authentic IDM Desktop Experience (Native GTK4 + Libadwaita)**:
  - **Download File Info** dialog before starting (live size/resume probe, category auto-routing, video quality picker, Start Now vs Download Later).
  - Separate **Download Progress** window with a **3-column telemetry strip**, custom Cairo **rounded multi-segment byte map** (Emerald / Adwaita Blue / Cyan active tips), per-connection table, live **Speed Limiter**, and **Options on Completion**.
  - **Automatic Category Folders**: Sorts downloads into `~/Downloads/{Compressed,Documents,Music,Programs,Video}` with full UTF-8 / Arabic filename support.
  - **Batch Downloads & Scheduler**: IDM wildcard ranges (`ep[01-12].mp4`, `part_[a-z].rar`), `.txt` import, multi-queue concurrency limits, scheduled start/stop times, and post-queue actions (notify, command, suspend, shutdown).
  - **Background GNOME Mode**: Closing the main window keeps active downloads, queues, and browser integration alive in the background with native GNOME desktop notifications (`Ctrl+Q` to quit completely).
- **Zero-Annoyance Chrome & Firefox Integration**:
  - Right-click **"Download with TurboDM"** on any link, image, audio, or video + **"Download all links on page with TurboDM"**.
  - Floating **"↓ Download this video"** pill over web video players + extension popup listing all detected media streams.
  - **Auto-interception is OFF by default** (toggleable anytime in the popup) so you stay in control.
  - Authenticated Native Messaging Host forwards Cookies, Referer & User-Agent and auto-launches TurboDM when closed.

---

## Quick Install (Fedora Linux)

Zero `sudo` required — installs cleanly into your user prefix using `uv` (or `python3 -m venv`) with system GTK4/Libadwaita bindings:

```bash
git clone https://github.com/TahaHamdy-MernDev/turbodm.git
cd turbodm
./install.sh
```

What `./install.sh` sets up automatically:
- Isolated virtual environment with `httpx[http2,socks]` and `yt-dlp`
- CLI launcher at `~/.local/bin/turbodm`
- GNOME Activities icon & `.desktop` entry
- Native Messaging Host manifests for **Google Chrome**, **Chromium**, and **Mozilla Firefox**
- Default IDM category folders inside `~/Downloads/`

### Uninstall

```bash
./uninstall.sh
```
*(Preserves your downloaded files in `~/Downloads/` and SQLite history.)*

---

## Browser Extensions (Chrome & Firefox)

Native Messaging manifests are already registered by `./install.sh`:

- **Google Chrome / Chromium**:
  1. Open `chrome://extensions`
  2. Enable **Developer mode**
  3. Click **Load unpacked** → select `extensions/chrome`
- **Mozilla Firefox**:
  1. Open `about:debugging#/runtime/this-firefox`
  2. Click **Load Temporary Add-on...** → select `extensions/firefox/manifest.json`

---

## Development & Testing

```bash
# Run full automated test suite (18 tests: engine, dynamic split, resume, SQLite, IPC, UI)
.venv/bin/pytest -v

# Run the Astro + Tailwind v4 landing page locally (deployed on Cloudflare Pages)
cd website && npm install && npm run dev
```

## License

Licensed under the [MIT License](LICENSE).

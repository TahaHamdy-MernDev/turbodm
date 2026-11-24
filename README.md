# TurboDM — Native IDM-Style Download Manager for Fedora Linux

**TurboDM** is a fast, lightweight, native Linux download manager built for **Fedora Workstation (GNOME / Wayland)** using **Python + GTK4 + Libadwaita**, designed around the classic Internet Download Manager (IDM) workflow.

## Key Features
- **High-Speed Multi-Part Engine with Dynamic Segmentation**: Splits files into up to 32 parallel byte-range connections using atomic `os.pwrite` I/O (zero post-download copy overhead). When one segment finishes early, it dynamically splits the slowest remaining active segment in half—just like IDM.
- **Reliable Pause & Resume**: Persists byte-range segment state and ETags in SQLite (`~/.local/share/turbodm/turbodm.db`) so downloads survive pauses, disconnects, or app restarts.
- **Video & Streaming Grabber (`yt-dlp` + `ffmpeg`)**: Automatically detects YouTube, Vimeo, HLS (`.m3u8`), DASH (`.mpd`), and 1000+ media sites with an interactive quality/resolution & audio-only MP3/M4A picker.
- **Authentic IDM Desktop Experience**:
  - **Download File Info** dialog before starting (size/resume probe, category auto-routing, video quality picker, Start Download vs Download Later).
  - Separate **Download Progress** popup window with a custom Cairo **live multi-segment progress bar**, per-connection status table, live Speed Limiter tab, and Options on Completion tab.
  - **IDM Categories & Auto-Folders**: Automatically routes files into `~/Downloads/{Compressed,Documents,Music,Programs,Video}`.
  - **Batch Downloads**: Supports IDM wildcard ranges (`[01-10]`, `[a-z]`) and `.txt` URL list import.
  - **Queues & Scheduler**: Main & Synchronization queues + custom queues, concurrency caps, scheduled start/stop times, and post-queue actions (notify, command, suspend, shutdown).
  - **Background GNOME Mode**: Closing the main window hides it cleanly while active downloads, schedules, and browser integration keep running with native GNOME notifications.
- **Chrome & Firefox Integration**:
  - Right-click **"Download with TurboDM"** on any link, image, audio, or video.
  - Floating **"⬇ Download this video"** pill over web video players.
  - Extension popup listing all sniffed media streams on the active tab.
  - Auto-interception is **OFF by default** (can be enabled anytime from the extension popup).
  - Native Messaging Host automatically launches TurboDM if it isn't running yet.

## Quick Install (Fedora Linux)
```bash
./install.sh
```

## Uninstall
```bash
./uninstall.sh
```

## Loading Browser Extensions
- **Google Chrome**: Go to `chrome://extensions` → enable **Developer mode** → **Load unpacked** → select `extensions/chrome`.
- **Mozilla Firefox**: Go to `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on...** → select `extensions/firefox/manifest.json`.

## Running Tests
```bash
.venv/bin/pytest -v
```

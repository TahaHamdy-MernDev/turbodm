# TurboDM for Android

Native **Kotlin + Jetpack Compose** Android download manager sharing TurboDM's dark Fedora Native design system.

## Features
- **Universal Share Sheet (`Share → TurboDM`)**: Share links directly from **YouTube** (single videos & full **YouTube Playlists** with multi-select!), **TikTok**, **Facebook**, **Instagram**, **X / Twitter**, **Reddit**, **Vimeo**, **Chrome**, or **Telegram**.
- **Dual Download Engine**:
  - **Multi-Part HTTP/HTTPS Engine** (`OkHttp` + Coroutines + atomic `FileChannel` positional writes + **Dynamic Segmentation** up to 32 parallel connections + crash-safe Room SQLite pause/resume).
  - **Social & Streaming Media Engine** (`youtubedl-android` bundling `yt-dlp` + `ffmpeg` + in-app **Update yt-dlp** button).
- **Background Foreground Service**: Downloads continue with screen off, live speed/progress notification + Pause All action, and automatic `MediaStore` indexing into `Downloads/TurboDM/{Video,Music,Compressed,Documents,Programs}`.
- **TurboDM Dark UI**: Custom Compose Canvas **16-Segment Byte Map** (Emerald / Adwaita Blue / Cyan tips), 3-column telemetry strip, and compact `[ − | N | + ]` steppers.

## Build & Install

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

Install the generated APK onto a connected Android phone:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or copy `app/build/outputs/apk/debug/app-debug.apk` directly to your Android phone and open it to install.

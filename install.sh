#!/usr/bin/env bash
set -euo pipefail

# TurboDM Installer for Fedora Linux (User-level, zero sudo required)

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$PROJECT_DIR/.venv"
BIN_DIR="$HOME/.local/bin"
APPS_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor/scalable/apps"
CHROME_NM_DIR="$HOME/.config/google-chrome/NativeMessagingHosts"
CHROMIUM_NM_DIR="$HOME/.config/chromium/NativeMessagingHosts"
FIREFOX_NM_DIR="$HOME/.mozilla/native-messaging-hosts"

CHROME_EXT_ID="pbeoliagfckkldoheimnbenijkmgagpm"
FIREFOX_EXT_ID="turbodm@turbodm.app"
HOST_NAME="com.turbodm.host"

echo "==> Installing TurboDM from: $PROJECT_DIR"

# 1. Verify GTK4 + Libadwaita bindings on Fedora
if ! /usr/bin/python3 -c "import gi; gi.require_version('Gtk','4.0'); gi.require_version('Adw','1'); from gi.repository import Gtk, Adw" 2>/dev/null; then
    echo "ERROR: Python GTK4 / Libadwaita bindings not found."
    echo "On Fedora, install them with:"
    echo "  sudo dnf install python3-gobject gtk4 libadwaita ffmpeg"
    exit 1
fi

# 2. Set up isolated Python environment with system GTK4 site-packages
export UV_LINK_MODE=copy
if command -v uv >/dev/null 2>&1; then
    echo "==> Setting up uv virtual environment..."
    if [ ! -d "$VENV_DIR" ]; then
        uv venv --python /usr/bin/python3 --system-site-packages "$VENV_DIR"
    fi
    uv pip install --python "$VENV_DIR/bin/python" -e "$PROJECT_DIR"
else
    echo "==> Setting up python3 venv..."
    if [ ! -d "$VENV_DIR" ]; then
        /usr/bin/python3 -m venv --system-site-packages "$VENV_DIR"
    fi
    "$VENV_DIR/bin/pip" install --upgrade pip
    "$VENV_DIR/bin/pip" install -e "$PROJECT_DIR"
fi

# 3. Install CLI & Native Messaging Host executables into ~/.local/bin
mkdir -p "$BIN_DIR"

cat > "$BIN_DIR/turbodm" <<EOF
#!/usr/bin/env bash
exec "$VENV_DIR/bin/turbodm" "\$@"
EOF
chmod +x "$BIN_DIR/turbodm"

cat > "$BIN_DIR/turbodm-native-host" <<EOF
#!/usr/bin/env bash
exec "$VENV_DIR/bin/turbodm-native-host" "\$@"
EOF
chmod +x "$BIN_DIR/turbodm-native-host"

# 4. Install SVG App Icon & GNOME .desktop Launcher
mkdir -p "$ICON_DIR" "$APPS_DIR"
cp "$PROJECT_DIR/assets/com.turbodm.app.svg" "$ICON_DIR/com.turbodm.app.svg"

sed "s|@BIN_PATH@|$BIN_DIR/turbodm|g" \
    "$PROJECT_DIR/assets/com.turbodm.app.desktop" \
    > "$APPS_DIR/com.turbodm.app.desktop"
chmod +x "$APPS_DIR/com.turbodm.app.desktop"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APPS_DIR" 2>/dev/null || true
fi
if command -v gtk4-update-icon-cache >/dev/null 2>&1; then
    gtk4-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
elif command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
fi

# 5. Register Native Messaging Hosts for Google Chrome, Chromium, and Mozilla Firefox
mkdir -p "$CHROME_NM_DIR" "$CHROMIUM_NM_DIR" "$FIREFOX_NM_DIR"

cat > "$CHROME_NM_DIR/$HOST_NAME.json" <<EOF
{
  "name": "$HOST_NAME",
  "description": "TurboDM Native Messaging Host for Google Chrome",
  "path": "$BIN_DIR/turbodm-native-host",
  "type": "stdio",
  "allowed_origins": [
    "chrome-extension://$CHROME_EXT_ID/"
  ]
}
EOF
cp "$CHROME_NM_DIR/$HOST_NAME.json" "$CHROMIUM_NM_DIR/$HOST_NAME.json"

cat > "$FIREFOX_NM_DIR/$HOST_NAME.json" <<EOF
{
  "name": "$HOST_NAME",
  "description": "TurboDM Native Messaging Host for Mozilla Firefox",
  "path": "$BIN_DIR/turbodm-native-host",
  "type": "stdio",
  "allowed_extensions": [
    "$FIREFOX_EXT_ID"
  ]
}
EOF

# 6. Create IDM-style default category folders inside ~/Downloads
mkdir -p "$HOME/Downloads/Compressed" \
         "$HOME/Downloads/Documents" \
         "$HOME/Downloads/Music" \
         "$HOME/Downloads/Programs" \
         "$HOME/Downloads/Video"

echo ""
echo "============================================================"
echo "  TurboDM installed successfully!"
echo "============================================================"
echo "  App Launcher : GNOME Activities -> search 'TurboDM'"
echo "  CLI Command  : $BIN_DIR/turbodm"
echo "  Native Host  : Registered for Chrome, Chromium & Firefox"
echo ""
echo "  Load Browser Extensions:"
echo "  - Google Chrome:"
echo "      1. Open chrome://extensions"
echo "      2. Enable 'Developer mode'"
echo "      3. Click 'Load unpacked' and select:"
echo "         $PROJECT_DIR/extensions/chrome"
echo "  - Mozilla Firefox:"
echo "      1. Open about:debugging#/runtime/this-firefox"
echo "      2. Click 'Load Temporary Add-on...' and select:"
echo "         $PROJECT_DIR/extensions/firefox/manifest.json"
echo "============================================================"

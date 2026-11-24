#!/usr/bin/env bash
set -euo pipefail

echo "==> Uninstalling TurboDM user integration..."

rm -f "$HOME/.local/bin/turbodm"
rm -f "$HOME/.local/bin/turbodm-native-host"
rm -f "$HOME/.local/share/applications/com.turbodm.app.desktop"
rm -f "$HOME/.local/share/icons/hicolor/scalable/apps/com.turbodm.app.svg"
rm -f "$HOME/.config/autostart/com.turbodm.app.desktop"
rm -f "$HOME/.config/google-chrome/NativeMessagingHosts/com.turbodm.host.json"
rm -f "$HOME/.config/chromium/NativeMessagingHosts/com.turbodm.host.json"
rm -f "$HOME/.mozilla/native-messaging-hosts/com.turbodm.host.json"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
fi

echo "==> TurboDM desktop launcher and browser native hosts removed."
echo "    (Your downloaded files and ~/.local/share/turbodm/turbodm.db were preserved.)"

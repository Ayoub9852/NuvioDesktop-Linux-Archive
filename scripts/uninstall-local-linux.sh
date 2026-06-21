#!/usr/bin/env bash
set -euo pipefail

install_dir="$HOME/.local/opt/nuvio-linux"
wrapper="$HOME/.local/bin/nuvio-linux"
desktop_file="$HOME/.local/share/applications/nuvio-linux.desktop"
icon_file="$HOME/.local/share/icons/hicolor/256x256/apps/nuvio-linux.png"

rm -rf "$install_dir"
rm -f "$wrapper" "$desktop_file" "$icon_file"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$HOME/.local/share/applications" || true
else
    printf 'update-desktop-database not found; skipped desktop database refresh.\n'
fi

if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache "$HOME/.local/share/icons/hicolor" || true
else
    printf 'gtk-update-icon-cache not found; skipped icon cache refresh.\n'
fi

printf 'Removed local Nuvio Linux launcher files.\n'

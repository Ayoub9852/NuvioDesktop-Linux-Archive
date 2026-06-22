#!/usr/bin/env bash
set -euo pipefail

repo_root="$(pwd)"
app_image="$repo_root/composeApp/build/compose/binaries/main-release/app/Nuvio"
install_dir="$HOME/.local/opt/nuvio-linux"
wrapper="$HOME/.local/bin/nuvio-linux"
desktop_file="$HOME/.local/share/applications/nuvio-linux.desktop"
icon_dir="$HOME/.local/share/icons/hicolor/256x256/apps"
icon_file="$icon_dir/nuvio-linux.png"
noipv6_source="$repo_root/packaging/linux/noipv6/nuvio_noipv6.c"
noipv6_build="$repo_root/packaging/linux/noipv6/libnuvio-noipv6.so"
noipv6_install_dir="$install_dir/lib/nuvio"
noipv6_install="$noipv6_install_dir/libnuvio-noipv6.so"

build_command="./gradlew --no-configuration-cache -Pnuvio.desktopOnly=true :composeApp:createReleaseDistributable --stacktrace"

if [[ ! -d "$app_image" || ! -x "$app_image/bin/Nuvio" ]]; then
    printf 'Release app image not found at:\n  %s\n\n' "$app_image" >&2
    printf 'Build it first with:\n  %s\n' "$build_command" >&2
    exit 1
fi

mkdir -p "$HOME/.local/opt" "$HOME/.local/bin" "$(dirname "$desktop_file")" "$icon_dir"

if [[ "$(uname -s)" == "Linux" && -f "$noipv6_source" ]]; then
    if [[ ! -f "$noipv6_build" || "$noipv6_source" -nt "$noipv6_build" ]]; then
        if ! bash "$repo_root/scripts/build-linux-noipv6.sh"; then
            printf 'Warning: failed to build no-IPv6 preload library; launcher will fall back to Firejail or JVM-only mode.\n' >&2
        fi
    fi
fi

rm -rf "$install_dir"
cp -a "$app_image" "$install_dir"

if [[ -f "$noipv6_build" ]]; then
    mkdir -p "$noipv6_install_dir"
    cp "$noipv6_build" "$noipv6_install"
fi

cat > "$wrapper" <<EOF
#!/usr/bin/env bash
set -euo pipefail

app_bin="$install_dir/bin/Nuvio"
noipv6_lib="$noipv6_install"

export LC_ALL=C.UTF-8
export LC_NUMERIC=C
export LANG=C.UTF-8
export NUVIO_DESKTOP_PLAYER_BACKEND="\${NUVIO_DESKTOP_PLAYER_BACKEND:-mpv}"
export NUVIO_MPV_NETWORK_MODE="\${NUVIO_MPV_NETWORK_MODE:-direct-ipv4}"
export NUVIO_IPV4_ONLY="\${NUVIO_IPV4_ONLY:-1}"
export JAVA_TOOL_OPTIONS="\${JAVA_TOOL_OPTIONS:+\$JAVA_TOOL_OPTIONS }-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false"

firejail_found=false
firejail_path=""
if firejail_path="\$(command -v firejail 2>/dev/null)"; then
    firejail_found=true
fi
noipv6_found=false
if [[ -f "\$noipv6_lib" ]]; then
    noipv6_found=true
fi

ipv4_method="\${NUVIO_IPV4_METHOD:-auto}"
selected_ipv4_method="none"
case "\$ipv4_method" in
    auto|"")
        if [[ "\$NUVIO_IPV4_ONLY" == "0" ]]; then
            selected_ipv4_method="none"
        elif [[ "\${NUVIO_FIREJAIL_ACTIVE:-0}" == "1" ]]; then
            selected_ipv4_method="firejail"
        elif [[ "\$noipv6_found" == "true" ]]; then
            selected_ipv4_method="ldpreload"
        elif [[ "\$firejail_found" == "true" ]]; then
            selected_ipv4_method="firejail"
        else
            selected_ipv4_method="jvm-only"
        fi
        ;;
    firejail)
        if [[ "\$NUVIO_IPV4_ONLY" == "0" ]]; then
            selected_ipv4_method="none"
        elif [[ "\${NUVIO_FIREJAIL_ACTIVE:-0}" == "1" || "\$firejail_found" == "true" ]]; then
            selected_ipv4_method="firejail"
        elif [[ "\$noipv6_found" == "true" ]]; then
            selected_ipv4_method="ldpreload"
        else
            selected_ipv4_method="jvm-only"
        fi
        ;;
    ldpreload)
        if [[ "\$NUVIO_IPV4_ONLY" == "0" ]]; then
            selected_ipv4_method="none"
        elif [[ "\$noipv6_found" == "true" ]]; then
            selected_ipv4_method="ldpreload"
        else
            selected_ipv4_method="jvm-only"
        fi
        ;;
    jvm-only|none)
        selected_ipv4_method="\$ipv4_method"
        ;;
    *)
        printf 'Nuvio launcher warning: unknown NUVIO_IPV4_METHOD=%s; using auto.\n' "\$ipv4_method" >&2
        if [[ "\$NUVIO_IPV4_ONLY" == "0" ]]; then
            selected_ipv4_method="none"
        elif [[ "\${NUVIO_FIREJAIL_ACTIVE:-0}" == "1" ]]; then
            selected_ipv4_method="firejail"
        elif [[ "\$noipv6_found" == "true" ]]; then
            selected_ipv4_method="ldpreload"
        elif [[ "\$firejail_found" == "true" ]]; then
            selected_ipv4_method="firejail"
        else
            selected_ipv4_method="jvm-only"
        fi
        ;;
esac

debug_launcher() {
    if [[ "\${NUVIO_DEBUG_LAUNCHER:-0}" != "1" ]]; then
        return
    fi
    printf 'Nuvio launcher debug: NUVIO_IPV4_ONLY=%s\n' "\$NUVIO_IPV4_ONLY" >&2
    printf 'Nuvio launcher debug: selected_ipv4_method=%s\n' "\$selected_ipv4_method" >&2
    printf 'Nuvio launcher debug: NUVIO_FIREJAIL_ACTIVE=%s\n' "\${NUVIO_FIREJAIL_ACTIVE:-0}" >&2
    printf 'Nuvio launcher debug: firejail_found=%s\n' "\$firejail_found" >&2
    printf 'Nuvio launcher debug: firejail_path=%s\n' "\${firejail_path:-missing}" >&2
    printf 'Nuvio launcher debug: noipv6_lib=%s\n' "\$noipv6_lib" >&2
    printf 'Nuvio launcher debug: noipv6_found=%s\n' "\$noipv6_found" >&2
    printf 'Nuvio launcher debug: LD_PRELOAD=%s\n' "\${LD_PRELOAD:-}" >&2
    printf 'Nuvio launcher debug: LC_ALL=%s\n' "\$LC_ALL" >&2
    printf 'Nuvio launcher debug: LC_NUMERIC=%s\n' "\$LC_NUMERIC" >&2
    printf 'Nuvio launcher debug: LANG=%s\n' "\$LANG" >&2
    printf 'Nuvio launcher debug: JAVA_TOOL_OPTIONS=%s\n' "\$JAVA_TOOL_OPTIONS" >&2
    printf 'Nuvio launcher debug: NUVIO_DESKTOP_PLAYER_BACKEND=%s\n' "\$NUVIO_DESKTOP_PLAYER_BACKEND" >&2
    printf 'Nuvio launcher debug: NUVIO_MPV_NETWORK_MODE=%s\n' "\$NUVIO_MPV_NETWORK_MODE" >&2
    printf 'Nuvio launcher debug: finalCommand=%s\n' "\$1" >&2
}

if [[ "\$NUVIO_IPV4_ONLY" == "1" && "\${NUVIO_FIREJAIL_ACTIVE:-0}" != "1" ]]; then
    if [[ "\$selected_ipv4_method" == "firejail" ]]; then
        export NUVIO_FIREJAIL_ACTIVE=1
        debug_launcher "firejail --noprofile --protocol=unix,inet env ... $wrapper"
        exec firejail --noprofile --protocol=unix,inet env \\
            LC_ALL="\$LC_ALL" \\
            LC_NUMERIC="\$LC_NUMERIC" \\
            LANG="\$LANG" \\
            NUVIO_DESKTOP_PLAYER_BACKEND="\$NUVIO_DESKTOP_PLAYER_BACKEND" \\
            NUVIO_MPV_NETWORK_MODE="\$NUVIO_MPV_NETWORK_MODE" \\
            NUVIO_IPV4_ONLY="\$NUVIO_IPV4_ONLY" \\
            NUVIO_IPV4_METHOD="\$ipv4_method" \\
            NUVIO_FIREJAIL_ACTIVE="\$NUVIO_FIREJAIL_ACTIVE" \\
            NUVIO_DEBUG_LAUNCHER="\${NUVIO_DEBUG_LAUNCHER:-0}" \\
            JAVA_TOOL_OPTIONS="\$JAVA_TOOL_OPTIONS" \\
            "\$0" "\$@"
    elif [[ "\$selected_ipv4_method" == "ldpreload" ]]; then
        if [[ -n "\${LD_PRELOAD:-}" ]]; then
            export LD_PRELOAD="\$noipv6_lib:\$LD_PRELOAD"
        else
            export LD_PRELOAD="\$noipv6_lib"
        fi
        debug_launcher "LD_PRELOAD=\$LD_PRELOAD \$app_bin"
        exec "\$app_bin" "\$@"
    else
        printf 'Nuvio launcher warning: no process IPv4-only sandbox available; continuing with JVM IPv4 flags and direct-ipv4 mode only.\n' >&2
    fi
fi

debug_launcher "\$app_bin"
exec "\$app_bin" "\$@"
EOF
chmod +x "$wrapper"

icon_source=""
for candidate in \
    "$install_dir/lib/Nuvio.png" \
    "$repo_root/composeApp/src/commonMain/composeResources/drawable/nuvio_window_icon.png" \
    "$repo_root/composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark.png"
do
    if [[ -f "$candidate" ]]; then
        icon_source="$candidate"
        break
    fi
done

if [[ -n "$icon_source" ]]; then
    cp "$icon_source" "$icon_file"
fi

cat > "$desktop_file" <<EOF
[Desktop Entry]
Name=Nuvio Linux
Comment=Unofficial Linux desktop port of Nuvio
Exec=$wrapper %u
Terminal=false
Type=Application
Categories=AudioVideo;Video;Player;
StartupNotify=true
StartupWMClass=com-nuvio-app-DesktopAppKt
MimeType=x-scheme-handler/nuvio;
EOF

if [[ -n "$icon_source" ]]; then
    printf 'Icon=nuvio-linux\n' >> "$desktop_file"
fi

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$HOME/.local/share/applications" || true
else
    printf 'update-desktop-database not found; skipped desktop database refresh.\n'
fi

if command -v xdg-mime >/dev/null 2>&1; then
    xdg-mime default nuvio-linux.desktop x-scheme-handler/nuvio || true
else
    printf 'xdg-mime not found; skipped nuvio:// protocol registration.\n'
fi

if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache "$HOME/.local/share/icons/hicolor" || true
else
    printf 'gtk-update-icon-cache not found; skipped icon cache refresh.\n'
fi

printf 'Installed Nuvio Linux app image to: %s\n' "$install_dir"
printf 'Installed launcher wrapper to: %s\n' "$wrapper"
printf 'Installed desktop entry to: %s\n' "$desktop_file"
if [[ -n "$icon_source" ]]; then
    printf 'Installed icon from: %s\n' "$icon_source"
else
    printf 'No icon found; desktop entry was installed without Icon.\n'
fi
if [[ -f "$noipv6_install" ]]; then
    printf 'Installed no-IPv6 preload library to: %s\n' "$noipv6_install"
else
    printf 'No no-IPv6 preload library installed; Firejail or JVM-only mode will be used.\n'
fi

Then you run:

cd /home/ayoub/Documents/GitHub/NuvioDesktop

./gradlew --no-configuration-cache -Pnuvio.desktopOnly=true \
:composeApp:createReleaseDistributable --stacktrace

bash scripts/install-local-linux.sh

Then test:

nuvio-linux

The installed launcher defaults to embedded MPV with direct IPv4 playback:

NUVIO_MPV_NETWORK_MODE=direct-ipv4
NUVIO_DESKTOP_PLAYER_BACKEND=mpv

The launcher normally uses the bundled no-IPv6 LD_PRELOAD library. If that library is missing and firejail is installed, the launcher falls back to:

firejail --noprofile --protocol=unix,inet

Both methods disable IPv6 only for Nuvio, so system IPv6 can stay enabled. To bypass the IPv4-only process wrapper:

NUVIO_IPV4_ONLY=0 nuvio-linux

To explicitly test the local proxy fallback:

NUVIO_MPV_NETWORK_MODE=proxy nuvio-linux

If Vicinae does not show it immediately:

update-desktop-database ~/.local/share/applications 2>/dev/null || true
systemctl --user restart vicinae.service

This is the right setup because your launcher points to ~/.local/bin/nuvio-linux, while the actual app lives in ~/.local/opt/nuvio-linux. Rebuilding the repo won’t randomly break your launcher unless you reinstall the local copy.

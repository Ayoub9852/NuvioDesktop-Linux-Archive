#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_file="$repo_root/packaging/linux/noipv6/nuvio_noipv6.c"
output_file="$repo_root/packaging/linux/noipv6/libnuvio-noipv6.so"

if [[ "$(uname -s)" != "Linux" ]]; then
    printf 'nuvio-noipv6 can only be built on Linux hosts.\n' >&2
    exit 1
fi

machine="$(uname -m)"
if [[ "$machine" != "x86_64" && "$machine" != "amd64" ]]; then
    printf 'nuvio-noipv6 only supports Linux x86_64 right now; host is %s.\n' "$machine" >&2
    exit 1
fi

compiler="${CC:-}"
if [[ -z "$compiler" ]]; then
    if command -v cc >/dev/null 2>&1; then
        compiler="$(command -v cc)"
    elif command -v clang >/dev/null 2>&1; then
        compiler="$(command -v clang)"
    elif command -v gcc >/dev/null 2>&1; then
        compiler="$(command -v gcc)"
    else
        printf 'No C compiler found. Install gcc or clang to build %s.\n' "$output_file" >&2
        exit 1
    fi
fi

mkdir -p "$(dirname "$output_file")"
"$compiler" -shared -fPIC -O2 -Wall -Wextra -o "$output_file" "$source_file" -ldl
printf 'Built no-IPv6 preload library: %s\n' "$output_file"

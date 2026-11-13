#!/usr/bin/env bash
set -euo pipefail

BIN="${1:-}"
if [[ -z "$BIN" || ! -f "$BIN" ]]; then
  echo "Usage: $0 path/to/sldns-client" >&2
  exit 2
fi

echo "== file =="
file "$BIN"

if ! command -v readelf >/dev/null 2>&1; then
  echo "readelf is required (install binutils)" >&2
  exit 2
fi

MACHINE="$(readelf -h "$BIN" 2>/dev/null | awk -F: '/Machine:/ {gsub(/^[[:space:]]+/, "", $2); print $2}')"
echo "ELF machine: ${MACHINE:-unknown}"

if [[ "$MACHINE" != *AArch64* ]]; then
  echo "REJECTED: expected AArch64/ARM64 executable; refusing to package incompatible binary." >&2
  exit 1
fi

echo "Architecture check passed. Android/Bionic runtime compatibility still needs an on-device smoke test."

#!/usr/bin/env bash
set -euo pipefail

BIN="${1:-}"
if [[ -z "$BIN" || ! -f "$BIN" ]]; then
  echo "Usage: $0 path/to/sldns-client" >&2
  exit 2
fi

command -v file >/dev/null 2>&1 || { echo "file is required" >&2; exit 2; }
command -v readelf >/dev/null 2>&1 || { echo "readelf is required (install binutils)" >&2; exit 2; }

echo "== file =="
file "$BIN"

HEADER="$(readelf -h "$BIN")"
CLASS="$(awk -F: '/Class:/ {gsub(/^[[:space:]]+/, "", $2); print $2}' <<<"$HEADER")"
MACHINE="$(awk -F: '/Machine:/ {gsub(/^[[:space:]]+/, "", $2); print $2}' <<<"$HEADER")"
echo "ELF class: ${CLASS:-unknown}"
echo "ELF machine: ${MACHINE:-unknown}"

if [[ "$CLASS" != "ELF64" || "$MACHINE" != *AArch64* ]]; then
  echo "REJECTED: expected 64-bit AArch64/ARM64 executable." >&2
  exit 1
fi

echo "== Interpreter =="
INTERP="$(readelf -l "$BIN" | sed -n 's/.*Requesting program interpreter: \(.*\)]/\1/p')"
if [[ -z "$INTERP" ]]; then
  echo "No ELF interpreter recorded (likely static); this alone does not prove Android compatibility."
else
  echo "ELF interpreter: $INTERP"
  if [[ "$INTERP" == *"ld-linux"* ]]; then
    echo "WARNING: glibc loader detected; not directly Android/Bionic compatible."
  fi
fi

echo "== Dynamic dependencies =="
readelf -d "$BIN" 2>/dev/null | grep NEEDED || echo "No DT_NEEDED entries detected."

echo "Architecture gate passed only. Android/Bionic runtime, DNS behavior, and local forwarding still require device testing."

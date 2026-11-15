#!/usr/bin/env bash
# Whisper reference transcripts for every take M1 downloaded.
# Uses the project venv (dev/adaware/.venv) because it has faster-whisper + the CUDA wheels.
set -euo pipefail
cd "$(dirname "$0")"
PY=../../.venv/bin/python
mkdir -p ref
for ep in .audio/*/; do
  name=$(basename "$ep")
  for f in "$ep"*.mp3; do
    base=$(basename "$f" .mp3)
    out="ref/$name/$base.json"
    [ -f "$out" ] && { echo "skip $out"; continue; }
    echo "== whisper $name/$base"
    $PY whisper_reference.py --audio "$f" --out "$out"
  done
done

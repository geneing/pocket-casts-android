#!/usr/bin/env bash
# Moonshine tiny-streaming transcription of every English episode take0 we downloaded.
# Batch mode: one call per file, word timestamps on (default in transcribe.py).
set -euo pipefail
cd "$(dirname "$0")"
PY=.venv/bin/python
for ep in .audio/*/; do
  name=$(basename "$ep")
  case "$name" in _test|_spanish) continue ;; esac
  f="$ep/take0.mp3"
  out="out/$name/take0.json"
  [ -f "$out" ] && { echo "skip $out"; continue; }
  echo "== moonshine $name/take0"
  $PY transcribe.py --audio "$f" --model-dir models/en/quantized_26_11_02 --mode batch --out "$out"
done

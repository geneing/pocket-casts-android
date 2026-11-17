#!/usr/bin/env bash
# Score every English episode: Moonshine vs Whisper (same audio) vs re-derived ad labels.
set -euo pipefail
cd "$(dirname "$0")"
for ep in .audio/*/; do
  name=$(basename "$ep")
  case "$name" in _test|_spanish) continue ;; esac
  .venv/bin/python score.py \
    --moonshine "out/$name/take0.json" --whisper "ref/$name/take0.json" \
    --labels "labels/$name.json" --out "results/$name.json"
done
.venv/bin/python score.py --moonshine out/_spanish/speech_900_300.json --whisper ref/_spanish/speech_900_300.json \
  --labels labels/_none.json --out results/_spanish.json

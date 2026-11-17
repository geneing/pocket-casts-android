#!/usr/bin/env bash
# Re-derive ad labels for every freshly downloaded take pair (Whisper sibling diff).
set -euo pipefail
cd "$(dirname "$0")"
for ep in .audio/*/; do
  name=$(basename "$ep")
  case "$name" in _test|_spanish) continue ;; esac
  .venv/bin/python derive_labels.py \
    --take0 "ref/$name/take0.json" --take1 "ref/$name/take1.json" --out "labels/$name.json"
done

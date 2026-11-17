#!/usr/bin/env bash
# Wait for the running collector to finish, then retrain and publish the Android bundle.
# Safe to run repeatedly: collection and training are both resumable/idempotent.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/.."

while pgrep -f 'm0/collect.py' >/dev/null; do
  echo "collector still running; sleeping 5m"
  sleep 300
done

uv run python -u next/m0/merge_legacy.py
uv run python -u next/m0/publish.py

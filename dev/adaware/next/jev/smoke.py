#!/usr/bin/env python3
"""Entry point for the System One smoke test.

The smoke case, its diagnostics and the model fallback live in :mod:`jev_client` so that
the library and this script can never drift apart; this file only makes the obvious
command available:

    cd dev/adaware
    export OPENCODE_API_KEY=...
    uv run python next/jev/smoke.py

Exit codes: 0 ok, 1 request failed, 2 key missing or rejected, 3 both model ids rejected,
4 the API answered but the response shape was not recognised (raw body printed).
"""

from __future__ import annotations

from jev_client import main

if __name__ == "__main__":
    raise SystemExit(main())

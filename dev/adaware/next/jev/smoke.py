#!/usr/bin/env python3
"""Smoke-test the System One classifier with a five-sentence sample.

From ``dev/adaware``::

    OPENCODE_API_KEY=$(tr -d '\\r\\n' < ../../secrets/opencode.key) \\
        uv run python next/jev/smoke.py [--model ID] [--timeout S] [--raw]

Exit codes: 0 ok, 1 request failed, 2 key missing or rejected, 4 an answer was missing.
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Sequence

from jev_client import (
    DEFAULT_MODEL,
    ENDPOINT,
    MissingApiKey,
    SystemOneError,
    classify_batch,
)

PODCAST = "Technology Today"
SENTENCES = [
    {"id": 1, "text": "Today we discuss the latest AI developments."},
    {"id": 2, "text": "This episode is sponsored by Acme."},
    {"id": 3, "text": "Acme makes tools for developers."},
    {"id": 4, "text": "Use code TECH20 for 20% off."},
    {"id": 5, "text": "Now, back to our discussion of AI."},
]

EXIT_OK, EXIT_FAILED, EXIT_NO_KEY, EXIT_MISSING = 0, 1, 2, 4


def _row(cells: Sequence[str], widths: Sequence[int]) -> str:
    return " | ".join(cell.ljust(width) for cell, width in zip(cells, widths))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", default=DEFAULT_MODEL, help=f"model id (default {DEFAULT_MODEL})")
    parser.add_argument("--timeout", type=float, default=120.0, help="HTTP timeout in seconds")
    parser.add_argument("--raw", action="store_true", help="print the raw JSON response")
    args = parser.parse_args(argv)

    print(f"System One smoke test\n  endpoint : {ENDPOINT}\n  model    : {args.model}")
    print(f"  podcast  : {PODCAST}\n  sentences: {len(SENTENCES)}\n")

    try:
        result = classify_batch(PODCAST, SENTENCES, model=args.model, timeout=args.timeout)
    except MissingApiKey as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return EXIT_NO_KEY
    except SystemOneError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        if exc.body:
            print(f"  body: {exc.body}", file=sys.stderr)
        return EXIT_FAILED
    except (TypeError, ValueError) as exc:
        print(f"ERROR: could not build the request: {exc}", file=sys.stderr)
        return EXIT_FAILED

    widths = (3, 13, 6, 60)
    print(_row(("id", "label", "p(ad)", "sentence"), widths))
    print("-+-".join("-" * width for width in widths))
    for row in result.per_sentence:
        label = row.label if row.label is not None else f"MISSING ({row.missing})"
        score = "   -  " if row.ad_score is None else f"{row.ad_score:.3f}"
        print(_row((str(row.id), label, score, row.text), widths))

    usage = result.usage
    print(
        f"\nlatency: {result.elapsed_ms:.0f} ms | model: {result.model} | "
        f"tokens: {usage.get('input_tokens', '?')} in / {usage.get('output_tokens', '?')} out | "
        f"cost: {usage.get('cost', '?')} ({usage.get('opencode', {}).get('billing_source', '?')})"
    )

    missing = [row.id for row in result.per_sentence if row.missing]
    if missing:
        print(f"WARNING: no answer for sentence id(s) {missing}", file=sys.stderr)

    if args.raw:
        print("\nraw response:\n" + json.dumps(result.raw, indent=2))

    return EXIT_MISSING if missing else EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())

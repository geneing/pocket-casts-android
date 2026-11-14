#!/usr/bin/env python3
"""Time and score one K=10 and one K=40 batch on synthetic sentences.

From ``dev/adaware``::

    OPENCODE_API_KEY=$(tr -d '\\r\\n' < ../../secrets/opencode.key) \\
        uv run python next/jev/bench.py [--model ID] [--timeout S]

Two live requests. Prints latency, token usage and label accuracy against the expected
labels below, so a later run can re-check the behaviour recorded in the README.
"""

from __future__ import annotations

import argparse
import sys
from typing import Sequence

from jev_client import DEFAULT_MODEL, SystemOneError, classify_batch

PODCAST = "Technology Today"

# (expected label, sentence) -- 20 content, 14 advertisement, 6 transition, ordered as a
# real episode: content, break, ad pod, return, content, ...
SENTENCES: list[tuple[str, str]] = [
    ("content", "Today we're looking at why electric car batteries lose range in the cold."),
    ("content", "The chemistry is straightforward: cold electrolyte moves ions more slowly."),
    ("content", "That is why a car that manages three hundred miles in summer may manage two hundred in winter."),
    ("content", "Our guest has tested more than forty models in a climate chamber."),
    ("content", "She says the biggest surprise was how much the tyres mattered."),
    ("content", "We will get to her winter results in a moment."),
    ("transition", "We'll be right back after a short break."),
    ("advertisement", "This episode is sponsored by Acme."),
    ("advertisement", "Acme makes tools for developers."),
    ("advertisement", "Use code TECH20 for twenty percent off your first order."),
    ("advertisement", "Visit acme dot com slash podcast to learn more."),
    ("advertisement", "Support for this show also comes from Northwind Insurance."),
    ("transition", "Welcome back to Technology Today."),
    ("content", "The test chamber can hold minus twenty degrees for eight hours."),
    ("content", "Each car was charged to full and then driven until it stopped."),
    ("content", "Only two of the forty models kept more than seventy percent of their summer range."),
    ("content", "The rest lost range fastest in the first hour of driving."),
    ("content", "Heating the cabin turned out to cost more than heating the battery."),
    ("content", "A heat pump made a measurable difference in every model that had one."),
    ("transition", "Let's pause for a word from our sponsors."),
    ("advertisement", "Northwind has been insuring small businesses for forty years."),
    ("advertisement", "Go to northwind dot com to get a quote today."),
    ("advertisement", "This episode is brought to you by Blue Bottle Coffee."),
    ("advertisement", "Blue Bottle delivers fresh beans to your door every month."),
    ("advertisement", "New customers get thirty percent off their first bag."),
    ("transition", "And we're back."),
    ("content", "Manufacturers are now advertising heat pumps as a winter feature."),
    ("content", "Our guest is sceptical of the marketing language around them."),
    ("content", "She points out that a heat pump helps most on short trips."),
    ("content", "On a long motorway run the battery warms itself and the advantage shrinks."),
    ("content", "The next round of tests will look at charging speed in freezing weather."),
    ("content", "We will publish those results when the chamber work is finished."),
    ("transition", "Time for a quick break."),
    ("advertisement", "Our final sponsor is Summit Outdoor Gear."),
    ("advertisement", "Summit makes jackets that pack down smaller than a water bottle."),
    ("advertisement", "Order before Friday and shipping is free."),
    ("advertisement", "That is summit outdoor dot com, code WINTER."),
    ("transition", "That brings us back to the conversation."),
    ("content", "So the practical advice is simple: preheat while plugged in."),
    ("content", "And do not trust a summer range figure to tell you anything about January."),
]


def run(k: int, model: str, timeout: float) -> int:
    subset = SENTENCES[:k]
    sentences = [{"id": index, "text": text} for index, (_, text) in enumerate(subset, start=1)]
    result = classify_batch(PODCAST, sentences, model=model, timeout=timeout)

    hits = 0
    per_label: dict[str, list[int]] = {}
    misses = []
    for row, (expected, _) in zip(result.per_sentence, subset):
        ok = row.label == expected
        hits += ok
        per_label.setdefault(expected, [0, 0])
        per_label[expected][0] += ok
        per_label[expected][1] += 1
        if not ok:
            misses.append(f"  id {row.id}: expected {expected}, got {row.label} ({row.ad_score})")

    breakdown = ", ".join(f"{label} {hit}/{total}" for label, (hit, total) in per_label.items())
    usage = result.usage
    print(
        f"K={k:<2} {result.elapsed_ms:6.0f} ms  {hits}/{k} labels  "
        f"({breakdown})  tokens {usage.get('input_tokens', '?')} in / "
        f"{usage.get('output_tokens', '?')} out"
    )
    for miss in misses:
        print(miss)
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--timeout", type=float, default=120.0)
    args = parser.parse_args(argv)
    try:
        for k in (10, 40):
            run(k, args.model, args.timeout)
    except SystemOneError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        if exc.body:
            print(f"  body: {exc.body}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

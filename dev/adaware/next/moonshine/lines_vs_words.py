"""How much would line-only cues be off, compared with cues cut at the first word?

Spanish has no attention decoder, so the Android plan falls back to line `startTime`/`duration`
for the cue (RESEARCH.md D4). A line is a VAD-delimited utterance: it starts where the VAD
opened, which is at or before the first spoken word. This measures that gap on the audio we
have, using Whisper's word times inside each Moonshine line as the reference.

Metric: for every Moonshine line, the earliest Whisper word whose midpoint falls inside the
line, minus the line start. Positive = the line starts before the speech.

Run:  .venv/bin/python lines_vs_words.py --moonshine out/_spanish/speech_900_300.json \
        --whisper ref/_spanish/speech_900_300.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np


def whisper_words(doc: dict) -> list[tuple[float, float, str]]:
    out = []
    if doc.get("words"):
        return [(w["s"], w["e"], w["w"]) for w in doc["words"]]
    for seg in doc.get("segments", []):
        for w in seg.get("words") or []:
            out.append((w["s"], w["e"], w["w"]))
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--moonshine", required=True)
    ap.add_argument("--whisper", required=True)
    args = ap.parse_args()

    m = json.loads(Path(args.moonshine).read_text())
    w = json.loads(Path(args.whisper).read_text())
    ww = whisper_words(w)

    deltas, missing = [], 0
    for line in m["segments"]:
        s, e = line["start"], line["end"]
        inside = [(ws, we, t) for ws, we, t in ww if s <= (ws + we) / 2 <= e]
        if not inside:
            missing += 1
            continue
        first = min(inside, key=lambda x: x[0])
        deltas.append(first[0] - s)

    a = np.abs(np.array(deltas, dtype=float))
    signed = np.array(deltas, dtype=float)
    print(
        f"{Path(args.moonshine).parent.name}: {len(m['segments'])} lines, {len(a)} matched, {missing} unmatched"
    )
    print(
        f"  line.start - first word: median {np.median(signed):+.2f}s, mean {signed.mean():+.2f}s, "
        f"|d| median {np.median(a):.2f}s, p90 {np.percentile(a, 90):.2f}s, max {a.max():.2f}s"
    )
    print(
        f"  share |d| <= 0.5 s {(a <= 0.5).mean() * 100:.0f}%, <= 1 s {(a <= 1).mean() * 100:.0f}%, "
        f"<= 2 s {(a <= 2).mean() * 100:.0f}%"
    )


if __name__ == "__main__":
    main()

"""Re-derive ad spans for a freshly downloaded take pair.

This is the sibling-take method from `dev/adaware/scripts/03_ads_from_transcripts.py`
(4-word shingle diff between the two takes; the complement of the shared anchors is the ad
pod), reduced to what M1 needs: one pair, both directions, for the take we transcribe.

It has to be re-run because dynamic ad insertion stitches a different ad pod on every
request: the committed `data/labels/ad_spans.json` describes audio that is no longer on
disk. Labels are therefore Whisper-derived by construction, which is exactly why the
Whisper column in the boundary metric is a floor, not an independent reference.

Run:  .venv/bin/python derive_labels.py --take0 ref/EP/take0.json --take1 ref/EP/take1.json \
        --out labels/EP.json
"""

from __future__ import annotations

import argparse
import json
import re
from difflib import SequenceMatcher
from pathlib import Path

from rapidfuzz import fuzz

WORD_RE = re.compile(r"[a-z0-9$%]+")

AD_PATTERNS = [
    (r"brought to you by", 4),
    (r"sponsored by", 4),
    (r"support(ed)? (comes|for this|us|our)", 3),
    (r"this (episode|show) is (sponsored|supported|brought)", 5),
    (r"promo code", 5),
    (r"use code|enter code|code [a-z]{3,}", 4),
    (r"dot com slash|\.com/|dot com", 3),
    (r"free trial|try it free|start your free", 3),
    (r"terms (and conditions )?apply|subject to credit approval", 4),
    (r"limited time offer|\boffer\b.*\b(visit|go to)\b", 3),
    (r"discount|save \d+%|\d+% off", 3),
    (r"sign up (today|now)|subscribe (today|now)", 2),
    (r"call \d{3}|1-800|800-|855-", 3),
    (r"visit .* (dot com|\.com)", 4),
    (r"available (now|wherever)", 2),
    (r"our (partner|sponsor)", 3),
    (r"ad[- ]free", 2),
    (r"thanks to (our|the) sponsor", 5),
    (r"message from|a word from", 4),
    (r"promotion(al)? (code|offer)", 3),
]
AD_PATTERNS_C = [(re.compile(p), w) for p, w in AD_PATTERNS]

AD_VOCAB = {
    "sponsor", "sponsored", "promo", "promotion", "code", "offer", "discount", "trial",
    "subscription", "subscribe", "terms", "conditions", "visit", "dot", "com", "app",
    "free", "save", "percent", "deal", "shop", "buy", "order", "today", "call",
    "insurance", "mortgage", "credit", "loan", "sale", "annual", "plan", "policy",
    "customer", "customers", "rewards", "shipping", "delivery", "brand", "partner",
}


def ad_score(text: str) -> float:
    t = text.lower()
    score = 0.0
    for pat, weight in AD_PATTERNS_C:
        if pat.search(t):
            score += weight
    words = WORD_RE.findall(t)
    if words:
        score += 2.5 * sum(w in AD_VOCAB for w in words) / max(1, len(words))
    return score


def norm_words(segments: list[dict]) -> list[tuple[str, float, float]]:
    out = []
    for seg in segments:
        for w in seg.get("words") or []:
            token = (w["w"] or "").lower().replace("\u2019", "'")
            for piece in WORD_RE.findall(token):
                out.append((piece, w["s"], w["e"]))
    return out


def anchors(wa, wb, which: str, k: int = 4) -> list[tuple[float, float]]:
    if len(wa) < k or len(wb) < k:
        return []
    sa = [" ".join(w[0] for w in wa[i : i + k]) for i in range(len(wa) - k + 1)]
    sb = [" ".join(w[0] for w in wb[i : i + k]) for i in range(len(wb) - k + 1)]
    words = wa if which == "a" else wb
    sm = SequenceMatcher(None, sa, sb, autojunk=False)
    out = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag != "equal" or i2 <= i1 or j2 <= j1:
            continue
        lo = i1 if which == "a" else j1
        hi = i2 + k - 2 if which == "a" else j2 + k - 2
        out.append((words[lo][1], words[hi][2]))
    return out


def similar_nearby(other, text: str, s: float, e: float) -> bool:
    window = " ".join(w[0] for w in other if w[1] >= s - 20 and w[2] <= e + 20)
    if not window:
        return False
    return fuzz.token_set_ratio(text, window) >= 55


def ad_regions(segments_a: list[dict], segments_b: list[dict], min_len: float = 8.0) -> list[dict]:
    """Parts of b not covered by words shared with a."""
    wa, wb = norm_words(segments_a), norm_words(segments_b)
    if not wa or not wb:
        return []
    found = sorted(anchors(wa, wb, "b"))
    if not found:
        return []
    end_of_media = wb[-1][2]
    regions = []
    prev = wa[0][1]
    for s, e in found + [(end_of_media + 1e9, end_of_media + 1e9)]:
        if s - prev >= min_len:
            regions.append((prev, s))
        prev = max(prev, e)
    out = []
    for s, e in regions:
        s, e = max(0.0, s - 0.3), min(end_of_media, e + 0.3)
        if e - s < min_len:
            continue
        words = [w for w in wb if w[1] >= s - 0.5 and w[2] <= e + 0.5]
        if not words:
            continue
        text = " ".join(w[0] for w in words)
        dur = e - s
        if dur < 20.0:
            if ad_score(text) < 2.0:
                continue
            if similar_nearby(wa, text, s, e):
                continue
        out.append({"start": round(s, 2), "end": round(e, 2), "text": text})
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--take0", required=True)
    ap.add_argument("--take1", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--min-len", type=float, default=8.0)
    args = ap.parse_args()

    a = json.loads(Path(args.take0).read_text())["segments"]
    b = json.loads(Path(args.take1).read_text())["segments"]
    spans = ad_regions(b, a, args.min_len)
    spans.sort(key=lambda s: s["start"])
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(spans, indent=1))
    print(f"{out}: {len(spans)} ad spans")
    for s in spans:
        print(f"  {s['start']:7.2f}-{s['end']:7.2f} ({s['end'] - s['start']:5.1f}s) {s['text'][:70]}")


if __name__ == "__main__":
    main()

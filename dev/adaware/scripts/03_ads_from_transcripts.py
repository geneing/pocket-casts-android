"""Derive ad ground truth from transcripts.

Two sources of labels, in order of trust:

1. Dynamic ad slots. Every take of an episode shares the show body but has its own
   stitched ads. Aligning the word streams of two takes with difflib shows exactly
   which spans exist in one take and not the other, including the ones that shift
   the body when a pre-roll appears. Those spans are ads (or station promos).

2. Baked-in ads. Host-read or pre-produced ads that every take shares never show up
   in the diff, so we score segments with ad vocabulary ("brought to you by",
   "promo code", "terms apply", ...) plus cross-episode clustering of repeated copy.

Output: data/labels/ads.json  - per episode, per take, ad intervals with text.
"""

from __future__ import annotations

import argparse
import re
from collections import Counter, defaultdict
from difflib import SequenceMatcher

import numpy as np

from common import DATA, LABELS, TRANSCRIPTS, read_json, write_json

# ---------------------------------------------------------------- text helpers

WORD_RE = re.compile(r"[a-z0-9$%]+")


def norm_words(segments: list[dict]) -> list[tuple[str, float, float, str]]:
    out = []
    for seg in segments:
        for w in seg.get("words") or []:
            token = w["w"].lower().replace("’", "'")
            for piece in WORD_RE.findall(token):
                out.append((piece, w["s"], w["e"], seg["text"]))
    return out


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


def tokenize(text: str) -> list[str]:
    return WORD_RE.findall(text.lower())


# ------------------------------------------------------------ dynamic slots


def _anchors(wa, wb, which: str, k: int = 4) -> list[tuple[float, float]]:
    """Time intervals of runs of words that line up between the two takes.

    Matching is done on k-word shingles: a coincidental single word inside two different
    ads ("in all states") would otherwise punch a hole in an ad region.

    Returns spans in the timeline of `which` ("a" or "b").
    """
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


def ad_regions(segments_a: list[dict], segments_b: list[dict], min_len: float = 8.0) -> list[dict]:
    """Parts of b not covered by words shared with a.

    An ad pod has no exact word correspondence in the other take, so the complement of
    the matching anchors is the ad slot: no drift heuristics, no opcode taxonomy, and
    transcription jitter only ever shortens a region by a word.
    """
    wa, wb = norm_words(segments_a), norm_words(segments_b)
    if not wa or not wb:
        return []
    anchors = sorted(_anchors(wa, wb, "b"))
    if not anchors:
        return []
    end_of_media = wb[-1][2]
    regions = []
    prev = wa[0][1] if wa else 0.0
    for s, e in anchors + [(end_of_media + 1e9, end_of_media + 1e9)]:
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
        # Short regions are usually ASR disagreement about the same words (credits,
        # names) rather than inserted copy: require ad evidence for those, and drop
        # them when the other take says almost the same thing nearby.
        if dur < 20.0:
            if ad_score(text) < 2.0:
                continue
            if _similar_nearby(wa, text, s, e):
                continue
        out.append({"start": round(s, 2), "end": round(e, 2), "text": text})
    return out


def _similar_nearby(other: list[tuple[str, float, float, str]], text: str, s: float, e: float) -> bool:
    from rapidfuzz import fuzz

    window = " ".join(w[0] for w in other if w[1] >= s - 20 and w[2] <= e + 20)
    if not window:
        return False
    return fuzz.token_set_ratio(text, window) >= 55


def dynamic_spans(segments_a: list[dict], segments_b: list[dict], min_len: float = 8.0) -> list[dict]:
    """Ad regions on both sides of a take pair."""
    spans = []
    for side, segs, other in (("a", segments_a, segments_b), ("b", segments_b, segments_a)):
        for r in ad_regions(other, segs, min_len):
            spans.append({"take": side, **r})
    return spans




def dedupe(spans: list[dict], iou: float = 0.5) -> list[dict]:
    """Drop the same slot found by more than one take pair."""
    out = []
    for s in sorted(spans, key=lambda x: (x["start"], -x["end"])):
        dup = False
        for o in out:
            inter = max(0.0, min(s["end"], o["end"]) - max(s["start"], o["start"]))
            union = max(s["end"], o["end"]) - min(s["start"], o["start"])
            if union > 0 and inter / union >= iou:
                dup = True
                break
        if not dup:
            out.append(s)
    return out


def merge_spans(spans: list[dict], gap: float = 2.0) -> list[dict]:
    if not spans:
        return []
    spans = sorted(spans, key=lambda s: s["start"])
    out = [dict(spans[0])]
    for s in spans[1:]:
        if s["start"] - out[-1]["end"] <= gap:
            out[-1]["end"] = max(out[-1]["end"], s["end"])
            out[-1]["text"] += " " + s["text"]
        else:
            out.append(dict(s))
    return out


# --------------------------------------------------------------- baked-in ads


def baked_candidates(segments: list[dict], duration: float) -> list[dict]:
    """Contiguous stretches of segments with high ad vocabulary, epsilon-smoothed."""
    scored = []
    for seg in segments:
        scored.append((seg["start"], seg["end"], seg["text"], ad_score(seg["text"])))
    # expand scores by neighbour smoothing (ads usually run over 3+ segments)
    wins = []
    for i, (s, e, t, sc) in enumerate(scored):
        lo, hi = max(0, i - 1), min(len(scored), i + 2)
        ctx = sum(x[3] for x in scored[lo:hi]) / (hi - lo)
        wins.append((s, e, t, sc + 0.5 * ctx))
    out = []
    cur = None
    for s, e, t, sc in wins:
        hit = sc >= 6.0
        if hit and cur is None:
            cur = {"start": s, "end": e, "text": t, "score": sc}
        elif hit and cur is not None:
            cur["end"] = e
            cur["text"] += " " + t
            cur["score"] = max(cur["score"], sc)
        elif not hit and cur is not None:
            if cur["end"] - cur["start"] >= 8:
                out.append(cur)
            cur = None
    if cur and cur["end"] - cur["start"] >= 8:
        out.append(cur)
    for c in out:
        c["start"], c["end"] = round(c["start"], 2), round(c["end"], 2)
    return out


# --------------------------------------------------------------------- main


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-len", type=float, default=8.0)
    args = ap.parse_args()

    LABELS.mkdir(parents=True, exist_ok=True)
    episodes = {}
    for ep_dir in sorted(TRANSCRIPTS.glob("*")):
        takes = sorted(ep_dir.glob("*.json"))
        if len(takes) < 2:
            continue
        tr = [read_json(t) for t in takes]
        key = ep_dir.name
        dynamic = []
        for i in range(len(tr)):
            for j in range(i + 1, len(tr)):
                spans = dynamic_spans(tr[i]["segments"], tr[j]["segments"], args.min_len)
                for s in spans:
                    s["take"] = j if s["take"] == "b" else i
                    dynamic.append(s)
        per_take = defaultdict(list)
        for s in dynamic:
            per_take[s["take"]].append(s)
        slots = {str(k): merge_spans(dedupe(v)) for k, v in per_take.items()}
        baked = baked_candidates(tr[0]["segments"], tr[0]["duration"])
        episodes[key] = {
            "duration": tr[0]["duration"],
            "takes": [t["file"] for t in tr],
            "dynamic_slots": slots,
            "baked_candidates": baked,
        }
        n = sum(len(v) for v in slots.values())
        print(f"{key:55s} dynamic slots {n:3d}  baked {len(baked):2d}")

    write_json(LABELS / "ads.json", episodes)

    # corpus of ad copy, for the phrase report and for the text model
    corpus = []
    for key, ep in episodes.items():
        for take, slots in ep["dynamic_slots"].items():
            for s in slots:
                corpus.append({"episode": key, "take": take, **s})
    write_json(LABELS / "ad_spans.json", corpus)

    print(f"\n{len(corpus)} dynamic ad spans over {len(episodes)} episodes")
    vocab = Counter(w for c in corpus for w in tokenize(c["text"]))
    print("top dynamic-ad words:", ", ".join(f"{w}:{n}" for w, n in vocab.most_common(30)))

    clusters = cluster_corpus(corpus)
    print(f"{len(clusters)} distinct creatives")
    for c in clusters[:10]:
        print(f"  x{c['count']:2d} {c['duration']:5.1f}s {c['text'][:90]}")
    write_report(episodes, corpus, clusters)


def cluster_corpus(corpus: list[dict], threshold: float = 0.6) -> list[dict]:
    """Group ad spans that read the same, i.e. the same creative served repeatedly."""
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.metrics.pairwise import cosine_similarity

    texts = [c["text"] for c in corpus]
    if not texts:
        return []
    vec = TfidfVectorizer(ngram_range=(1, 2), min_df=1, sublinear_tf=True)
    X = vec.fit_transform(texts)
    sim = cosine_similarity(X)
    clusters, taken = [], np.zeros(len(texts), bool)
    for i in range(len(texts)):
        if taken[i]:
            continue
        members = np.where((sim[i] >= threshold) & ~taken)[0]
        taken[members] = True
        clusters.append(
            {
                "count": int(len(members)),
                "episodes": sorted({corpus[j]["episode"] for j in members}),
                "duration": round(
                    float(np.median([corpus[j]["end"] - corpus[j]["start"] for j in members])), 1
                ),
                "text": max((corpus[j]["text"] for j in members), key=len)[:400],
            }
        )
    return sorted(clusters, key=lambda c: -c["count"])


def write_report(episodes: dict, corpus: list[dict], clusters: list[dict]) -> None:
    total_dur = sum(e["duration"] for e in episodes.values()) / 60
    lines = [
        "# Ad inventory extracted from transcripts",
        "",
        f"- episodes: {len(episodes)}",
        f"- audio transcribed: {total_dur:.0f} min",
        f"- dynamic ad spans: {len(corpus)}",
        f"- distinct creatives (cosine >= 0.6): {len(clusters)}",
        "",
        "## Recurring creatives",
        "",
        "| x | len s | episodes | first words |",
        "|---|-------|----------|-------------|",
    ]
    for c in clusters[:40]:
        text = c["text"].replace("|", "/")[:120]
        lines.append(f"| {c['count']} | {c['duration']} | {len(c['episodes'])} | {text} |")
    lines += ["", "## Per-episode dynamic slots", ""]
    for key, ep in episodes.items():
        lines.append(f"### {key}")
        for take, slots in sorted(ep["dynamic_slots"].items()):
            for s in slots:
                lines.append(
                    f"- take {take}: {s['start']:7.1f} - {s['end']:7.1f} "
                    f"({s['end'] - s['start']:5.1f}s) {s['text'][:180]}"
                )
        lines.append("")
    (LABELS / "ads_report.md").write_text("\n".join(lines), encoding="utf-8")
    write_json(LABELS / "ad_creatives.json", clusters)
    print(f"wrote {LABELS / 'ads_report.md'}")


if __name__ == "__main__":
    main()

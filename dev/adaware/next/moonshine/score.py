"""Score a Moonshine transcript against the Whisper reference on the same audio.

Three things are measured, all against `ref/<episode>/take0.json` (faster-whisper small,
word timestamps) and the ad labels derived from a sibling take:

  text       word error rate of the Moonshine word stream, Whisper as the reference
             (Whisper is not ground truth; this is agreement, not accuracy)
  timestamps for words the two transcripts both contain (difflib alignment on normalized
             tokens): |delta start| / |delta end| distributions + signed bias
  boundaries for every labelled ad boundary: distance to the nearest word boundary
             (start or end of any word) in each transcript

Run:  .venv/bin/python score.py --moonshine out/EP/take0.json \
        --whisper ref/EP/take0.json --labels labels/EP.json --out results/EP.json
"""

from __future__ import annotations

import argparse
import json
import re
import unicodedata
from pathlib import Path

import numpy as np
from rapidfuzz.distance import Levenshtein

WORD_RE = re.compile(r"[a-z0-9$%]+")


def norm_text(text: str) -> str:
    """Lowercase, fold accents (NFKD) and drop apostrophes before tokenizing.

    Accent folding matters for the Spanish run: one model writes "Bárbara", the other
    "Barbara", and without folding the two tokenize differently and every name shows up as
    a substitution.
    """
    s = unicodedata.normalize("NFKD", text or "")
    s = "".join(c for c in s if not unicodedata.combining(c))
    return s.lower().replace("\u2019", "'")


def tokens_of(text: str) -> list[str]:
    return WORD_RE.findall(norm_text(text))


def norm_tokens(words: list[dict], key_w: str = "w") -> list[str]:
    out = []
    for w in words:
        out.extend(tokens_of(w.get(key_w) or ""))
    return out


def words_from(doc: dict, s_key: str, e_key: str) -> list[dict]:
    """Flat word list with normalized keys, working for both JSON schemas."""
    if "words" in doc and doc["words"]:
        return [
            {"w": w["w"], "s": float(w[s_key]), "e": float(w[e_key])}
            for w in doc["words"]
            if (w.get("w") or "").strip()
        ]
    out = []
    for seg in doc.get("segments", []):
        for w in seg.get("words") or []:
            if (w.get("w") or "").strip():
                out.append({"w": w["w"], "s": float(w[s_key]), "e": float(w[e_key])})
    return out


def onset_series(words: list[dict]) -> list[str]:
    """One token per word, splitting hyphenated/punctuated tokens so alignment is 1:1.

    A word that splits into several tokens keeps its own timing for each piece: the first
    piece starts at the word start, the last ends at the word end, middles are distributed.
    """
    toks, owners = [], []
    for i, w in enumerate(words):
        pieces = tokens_of(w["w"] or "")
        if not pieces:
            continue
        for p in pieces:
            toks.append(p)
            owners.append(i)
    return toks, owners


def distribute(words: list[dict], owners: list[int], n_tokens: int) -> tuple[list[float], list[float]]:
    """Token-level start/end times derived from word times (equal split inside a word)."""
    counts = np.bincount(np.array(owners), minlength=len(words)) if owners else []
    starts, ends = [], []
    for tok_i, wi in enumerate(owners):
        w = words[wi]
        c = counts[wi]
        k = sum(1 for o in owners[:tok_i] if o == wi)
        span = (w["e"] - w["s"]) / c
        starts.append(round(w["s"] + k * span, 3))
        ends.append(round(w["s"] + (k + 1) * span, 3))
    return starts, ends


def wer(ref: list[str], hyp: list[str]) -> dict:
    """WER from an optimal Levenshtein alignment (rapidfuzz, C-speed).

    difflib.SequenceMatcher is a greedy LCS, not a minimum edit path: on long streams it
    drifts after the first disagreement and reports far more errors than there are (it
    turned a 0.35 Spanish WER into 0.85 before this was swapped out).
    """
    sub = dele = ins = 0
    for op in Levenshtein.opcodes(ref, hyp):
        if op.tag == "equal":
            continue
        r, h = op.src_end - op.src_start, op.dest_end - op.dest_start
        if op.tag == "replace":
            m = min(r, h)
            sub += m
            dele += r - m
            ins += h - m
        elif op.tag == "delete":
            dele += r
        else:
            ins += h
    n = max(1, len(ref))
    return {
        "ref_words": len(ref),
        "hyp_words": len(hyp),
        "sub": sub,
        "del": dele,
        "ins": ins,
        "wer": round((sub + dele + ins) / n, 4),
    }


def describe(deltas: list[float]) -> dict:
    if not deltas:
        return {"n": 0}
    a = np.abs(np.array(deltas, dtype=float))
    return {
        "n": int(len(a)),
        "median": round(float(np.median(a)), 3),
        "p90": round(float(np.percentile(a, 90)), 3),
        "p99": round(float(np.percentile(a, 99)), 3),
        "max": round(float(a.max()), 3),
        "mean": round(float(a.mean()), 3),
        "within_0.2": round(float((a <= 0.2).mean()), 4),
        "within_0.5": round(float((a <= 0.5).mean()), 4),
        "within_1.0": round(float((a <= 1.0).mean()), 4),
        "within_2.0": round(float((a <= 2.0).mean()), 4),
        "bias_median_signed": round(float(np.median(deltas)), 3),
        "bias_mean_signed": round(float(np.mean(deltas)), 3),
    }


def nearest_boundary(t: float, times: np.ndarray) -> tuple[float, float]:
    """Signed distance from t to the closest word boundary in `times`."""
    if len(times) == 0:
        return float("nan"), float("nan")
    idx = int(np.argmin(np.abs(times - t)))
    return float(times[idx] - t), float(times[idx])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--moonshine", required=True)
    ap.add_argument("--whisper", required=True)
    ap.add_argument("--labels", required=True, help="JSON list of {start,end} ad spans for this take")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    m_doc = json.loads(Path(args.moonshine).read_text())
    w_doc = json.loads(Path(args.whisper).read_text())
    labels = json.loads(Path(args.labels).read_text())

    m_words = words_from(m_doc, "s", "e")
    w_words = words_from(w_doc, "s", "e")

    # ---- text agreement
    if m_words:
        m_tok, m_owner = onset_series(m_words)
        m_s, m_e = distribute(m_words, m_owner, len(m_tok))
        text_source = "words"
    else:
        # no word timings (e.g. Spanish: no attention decoder) -> score the line text only
        m_tok = []
        for seg in m_doc.get("segments", []):
            m_tok.extend(tokens_of(seg.get("text") or ""))
        m_s, m_e = [], []
        text_source = "segments"
    w_tok, w_owner = onset_series(w_words)
    text = wer(w_tok, m_tok)
    w_s, w_e = distribute(w_words, w_owner, len(w_tok))

    # character error rate on the space-less string: separates real lexical errors from
    # tokenization differences ("ChatGBT" vs "Chat Gbt", "25" vs "twenty five")
    ref_chars, hyp_chars = "".join(w_tok), "".join(m_tok)
    text["cer_nospace"] = round(Levenshtein.distance(ref_chars, hyp_chars) / max(1, len(ref_chars)), 4)

    # ---- timestamp agreement on matched tokens
    ds, de, pairs = [], [], 0
    worst = []
    matched = []  # (ref_start, ref_end, |d| pair deltas) for the body/ad split below
    if m_s:
        for op in Levenshtein.opcodes(w_tok, m_tok):
            if op.tag != "equal":
                continue
            for k in range(op.src_end - op.src_start):
                wi, mi = op.src_start + k, op.dest_start + k
                d1, d2 = m_s[mi] - w_s[wi], m_e[mi] - w_e[wi]
                ds.append(d1)
                de.append(d2)
                pairs += 1
                matched.append((w_s[wi], w_e[wi], d1, d2))
                mag = max(abs(d1), abs(d2))
                worst.append(
                    (round(mag, 2), w_tok[wi], round(w_s[wi], 2), round(m_s[mi], 2), round(w_e[wi], 2), round(m_e[mi], 2))
                )
    worst.sort(reverse=True)

    # words inside a labelled ad pod are excluded from the "body" stats: there the two
    # transcripts genuinely differ (different creative), so their pairing is arbitrary and
    # contributes the long tail of |delta| without being a timing error.
    def inside_ad(t: float) -> bool:
        return any(span["start"] <= t <= span["end"] for span in labels)

    body = [p for p in matched if not inside_ad((p[0] + p[1]) / 2)]

    # ---- ad boundary distance
    w_times = np.array(sorted(set(w_s) | set(w_e)), dtype=float)
    m_times = np.array(sorted(set(m_s) | set(m_e)), dtype=float)
    boundary_rows = []
    for span in labels:
        for edge, t in (("start", span["start"]), ("end", span["end"])):
            wd, wt = nearest_boundary(t, w_times)
            row = {
                "edge": edge,
                "label_time": round(t, 2),
                "whisper_nearest": round(wt, 2),
                "whisper_signed": round(wd, 2),
                "whisper_abs": round(abs(wd), 2),
                "moonshine_nearest": None,
                "moonshine_signed": None,
                "moonshine_abs": None,
            }
            if len(m_times):
                md, mt = nearest_boundary(t, m_times)
                row.update(
                    moonshine_nearest=round(mt, 2),
                    moonshine_signed=round(md, 2),
                    moonshine_abs=round(abs(md), 2),
                )
            boundary_rows.append(row)

    result = {
        "episode": Path(args.moonshine).parent.name,
        "take": Path(args.moonshine).stem,
        "audio_seconds": m_doc.get("audio_seconds") or m_doc.get("duration"),
        "whisper_seconds": w_doc.get("seconds"),
        "moonshine": {
            "mode": m_doc.get("mode"),
            "seconds": m_doc.get("seconds"),
            "rtf": m_doc.get("rtf"),
            "load_seconds": m_doc.get("load_seconds"),
            "model_dir": m_doc.get("model_dir"),
            "attention_decoder_present": m_doc.get("attention_decoder_present"),
            "n_words": len(m_words),
        },
        "text": text,
        "text_source": text_source,
        "tokens_matched": pairs,
        "word_timestamps": {
            "start": describe(ds),
            "end": describe(de),
            "worst_5": worst[:5],
        },
        "word_timestamps_body": {
            "n_matched": len(body),
            "start": describe([p[2] for p in body]),
            "end": describe([p[3] for p in body]),
        },
        # raw per-matched-word deltas (seconds) so episodes can be pooled exactly
        "word_deltas": {
            "start": [round(d, 3) for d in ds],
            "end": [round(d, 3) for d in de],
        },
        "word_deltas_body": {
            "start": [round(p[2], 3) for p in body],
            "end": [round(p[3], 3) for p in body],
        },
        "ad_boundaries": {
            "n_labels": len(labels),
            "n_boundaries": len(boundary_rows),
            "whisper": describe([r["whisper_signed"] for r in boundary_rows]),
            "moonshine": describe([r["moonshine_signed"] for r in boundary_rows]),
            "rows": boundary_rows,
        },
    }

    out = Path(args.out) if args.out else Path("results") / f"{result['episode']}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=1))

    md, mdd = result["word_timestamps"]["start"], result["word_timestamps"]["end"]
    bm, bw = result["ad_boundaries"]["moonshine"], result["ad_boundaries"]["whisper"]
    print(
        f"{result['episode']}/{result['take']}: WER {text['wer']:.3f} ({text['sub']}/{text['del']}/{text['ins']} "
        f"of {text['ref_words']}), text from {text_source}, words matched {pairs}"
    )
    if md.get("n"):
        print(
            f"  start |d| median {md['median']:.2f}s p90 {md['p90']:.2f}s max {md['max']:.2f}s "
            f"| end |d| median {mdd['median']:.2f}s p90 {mdd['p90']:.2f}s max {mdd['max']:.2f}s"
        )
    if bm.get("n"):
        print(
            f"  ad boundaries (n={result['ad_boundaries']['n_boundaries']}): "
            f"moonshine median {bm['median']:.2f}s p90 {bm['p90']:.2f}s max {bm['max']:.2f}s | "
            f"whisper median {bw['median']:.2f}s p90 {bw['p90']:.2f}s max {bw['max']:.2f}s"
        )
    else:
        print(f"  no Moonshine word boundaries to compare ({result['ad_boundaries']['n_boundaries']} labels)")
    print(f"  -> {out}")


if __name__ == "__main__":
    main()

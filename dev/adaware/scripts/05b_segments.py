"""Segment-then-classify detector: acoustic change points, pooled features, logistic model.

Frames give a noisy boundary (a moving average smears the edge by seconds). Ads, however,
are stitched at acoustic discontinuities: music to speech, silence, a different mix. So
detect change points first, classify whole segments, then merge adjacent ad segments.
Boundaries then land on a real discontinuity, which is what the 5 s criterion needs.

Runs on the same 0.25 s feature grid as 04_features.py, so a phone can do it too.
"""

from __future__ import annotations

import argparse
import os
from itertools import product

for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_v, "4")

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from common import DATA, FEATURES, LABELS, read_json, write_json

GRID = 0.25


# ------------------------------------------------------------- change points


def change_points(feats: np.ndarray, names: list[str], grid: float = GRID) -> tuple[np.ndarray, np.ndarray]:
    """Indices where the local spectral mean jumps (3 s before vs 3 s after)."""
    cols = [i for i, n in enumerate(names) if n.endswith("_mean") and "_rel" not in n and "_std" not in n]
    x = feats[:, cols].astype(np.float64)
    x = (x - np.median(x, 0)) / (np.median(np.abs(x - np.median(x, 0)), 0) + 1e-6)
    n = len(x)
    w = max(2, int(round(3.0 / grid)))
    cs = np.vstack([np.zeros((1, x.shape[1])), np.cumsum(x, 0)])  # cs[i] = sum of x[:i]
    idx = np.arange(n)
    lo = np.clip(idx + 1 - w, 0, n)
    hi = np.clip(idx + 1, 0, n)
    a2 = np.clip(idx + 1 + w, 0, n)
    before = (cs[hi] - cs[lo]) / np.maximum(hi - lo, 1)[:, None]
    after = (cs[a2] - cs[hi]) / np.maximum(a2 - hi, 1)[:, None]
    dist = np.abs(after - before).mean(1)

    floor = np.median(dist) * 1.8
    peaks = []
    gap = int(4.0 / grid)
    for i in range(1, n - 1):
        if dist[i] >= floor and dist[i] >= dist[i - 1] and dist[i] >= dist[i + 1]:
            if not peaks or i - peaks[-1] > gap:
                peaks.append(i)
    return np.array(peaks, dtype=int), dist


def segments_from(points: np.ndarray, n: int) -> list[tuple[int, int]]:
    bounds = [0, *points.tolist(), n]
    segs = [(a, b) for a, b in zip(bounds[:-1], bounds[1:]) if b - a >= int(3.0 / GRID)]
    return segs


def segment_features(feats: np.ndarray, times: np.ndarray, segs: list[tuple[int, int]], dur: float):
    rows = []
    for a, b in segs:
        block = feats[a:b]
        stats = [block.mean(0), block.std(0), block.max(0) - block.min(0)]
        seg_dur = (b - a) * GRID
        pos = times[a] / max(dur, 1.0)
        rows.append(np.concatenate([np.concatenate(stats), [np.log1p(seg_dur), pos, 1 - pos]]))
    return np.stack(rows)


def overlap_label(times, seg, gold, share=0.5):
    a, b = times[seg[0]], times[seg[1] - 1]
    dur = b - a
    if dur <= 0:
        return 0
    covered = 0.0
    for g in gold:
        covered += max(0.0, min(b, g["end"]) - max(a, g["start"]))
    return int(covered >= share * dur)


# ------------------------------------------------------------------- pipeline


def to_intervals(prob, segs, times, thr, min_dur):
    out, cur = [], None
    for p, (a, b) in zip(prob, segs):
        if p >= thr:
            cur = [times[a], times[b - 1]] if cur is None else [cur[0], times[b - 1]]
        elif cur is not None:
            out.append(tuple(cur))
            cur = None
    if cur is not None:
        out.append(tuple(cur))
    merged = []
    for s, e in out:
        if merged and s - merged[-1][1] <= 6.0:
            merged[-1] = (merged[-1][0], e)
        else:
            merged.append((s, e))
    return [(s, e) for s, e in merged if e - s >= min_dur]


def match(pred, gold, tol=5.0):
    used, tp = set(), 0
    for s, e in pred:
        for k, (gs, ge) in enumerate(gold):
            if k in used:
                continue
            if abs(s - gs) <= tol and abs(e - ge) <= tol:
                used.add(k)
                tp += 1
                break
    return tp, len(pred) - tp, len(gold) - tp


def prf(tp, fp, fn):
    p = tp / (tp + fp) if tp + fp else 1.0
    r = tp / (tp + fn) if tp + fn else 1.0
    return p, r, 2 * p * r / (p + r) if p + r else 0.0


def load_dataset():
    labels = read_json(LABELS / "ads.json", default={})
    eps = [p.name for p in FEATURES.glob("*") if p.is_dir() and labels.get(p.name, {}).get("dynamic_slots")]
    data = {}
    for ep in eps:
        takes = []
        for npz in sorted((FEATURES / ep).glob("*.npz")):
            take = npz.stem.replace("take", "")
            d = np.load(npz, allow_pickle=True)
            feats, times = d["feats"], d["times"]
            names = [str(n) for n in d["names"]]
            gold = labels[ep]["dynamic_slots"].get(take, [])
            pts, _ = change_points(feats, names)
            segs = segments_from(pts, len(times))
            X = segment_features(feats, times, segs, float(times[-1]))
            y = np.array([overlap_label(times, s, gold) for s in segs], dtype=np.int8)
            takes.append({"take": take, "times": times, "segs": segs, "X": X, "y": y, "gold": gold})
        data[ep] = takes
    return eps, data


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tol", type=float, default=5.0)
    args = ap.parse_args()

    eps, data = load_dataset()
    X = np.concatenate([t["X"] for ep in eps for t in data[ep]])
    Y = np.concatenate([t["y"] for ep in eps for t in data[ep]])
    print(f"{len(eps)} episodes, {len(Y)} segments, {Y.mean() * 100:.1f}% ad segments")

    per_take = {}
    for i, ep in enumerate(eps):
        Xtr = np.concatenate([data[e][k]["X"] for e in eps if e != ep for k in range(len(data[e]))])
        Ytr = np.concatenate([data[e][k]["y"] for e in eps if e != ep for k in range(len(data[e]))])
        model = make_pipeline(StandardScaler(), LogisticRegression(C=0.3, max_iter=1000, class_weight="balanced"))
        model.fit(Xtr, Ytr)
        per_take[ep] = [{**t, "prob": model.predict_proba(t["X"])[:, 1]} for t in data[ep]]

    best = None
    for thr, min_dur in product((0.3, 0.4, 0.5, 0.6, 0.7), (8.0, 12.0, 15.0, 20.0, 30.0)):
        tp = fp = fn = 0
        for ep, takes in per_take.items():
            for t in takes:
                pred = to_intervals(t["prob"], t["segs"], t["times"], thr, min_dur)
                gold = [(g["start"], g["end"]) for g in t["gold"]]
                a, b, c = match(pred, gold, args.tol)
                tp, fp, fn = tp + a, fp + b, fn + c
        p, r, f = prf(tp, fp, fn)
        if best is None or f > best[0]:
            best = (f, p, r, thr, min_dur, tp, fp, fn)
    f, p, r, thr, min_dur, tp, fp, fn = best
    print(f"chosen thr {thr} min_dur {min_dur}s -> P {p:.3f} R {r:.3f} F1 {f:.3f} (tp {tp} fp {fp} fn {fn})")

    rows = []
    for ep, takes in per_take.items():
        tp = fp = fn = 0
        for t in takes:
            pred = to_intervals(t["prob"], t["segs"], t["times"], thr, min_dur)
            gold = [(g["start"], g["end"]) for g in t["gold"]]
            a, b, c = match(pred, gold, args.tol)
            tp, fp, fn = tp + a, fp + b, fn + c
        p1, r1, _ = prf(tp, fp, fn)
        rows.append({"episode": ep, "tp": tp, "fp": fp, "fn": fn, "p": p1, "r": r1, "gold": tp + fn, "pred": tp + fp})
        print(f"{ep[:45]:47s} gold {tp + fn:2d} pred {tp + fp:2d} tp {tp} P {p1:.2f} R {r1:.2f}")

    metrics = {
        "precision": round(p, 4),
        "recall": round(r, 4),
        "f1": round(f, 4),
        "macro_precision": round(float(np.mean([x["p"] for x in rows])), 4),
        "macro_recall": round(float(np.mean([x["r"] for x in rows])), 4),
        "tolerance": args.tol,
        "params": {"threshold": thr, "min_duration": min_dur},
        "counts": {"tp": tp, "fp": fp, "fn": fn},
        "episodes": rows,
        "model": "segment logistic regression",
    }
    write_json(DATA / "model" / "metrics_segments.json", metrics)


if __name__ == "__main__":
    main()

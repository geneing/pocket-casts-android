"""Diagnostic: post-processing strategies on saved out-of-fold probabilities."""

from __future__ import annotations

import json
from itertools import product
from pathlib import Path

import numpy as np

from common import DATA

GRID = 0.25
OOF = sorted((DATA / "model" / "oof").glob("*.npz"))


def smooth(p, sec):
    w = max(1, int(round(sec / GRID)))
    w += (w + 1) % 2
    return np.convolve(np.pad(p, (w // 2, w // 2), mode="edge"), np.ones(w) / w, mode="valid")[: len(p)]


def runs(mask):
    idx = np.flatnonzero(mask)
    if idx.size == 0:
        return []
    sp = np.flatnonzero(np.diff(idx) > 1)
    return list(zip(np.r_[idx[0], idx[sp + 1]], np.r_[idx[sp], idx[-1]]))


def merge(iv, gap):
    out = []
    for s, e in iv:
        if out and s - out[-1][1] <= gap:
            out[-1] = (out[-1][0], e)
        else:
            out.append((s, e))
    return out


def strategy(prob, times, kind, hi, lo, min_dur, gap, topk=0):
    if kind == "hi":
        iv = [(times[s], times[e]) for s, e in runs(prob >= hi)]
    elif kind == "hyst":
        iv = []
        for s, e in runs(prob >= lo):
            if prob[s : e + 1].max() >= hi:
                iv.append((times[s], times[e]))
    elif kind == "peak":
        iv = []
        for s, e in runs(prob >= lo):
            seg = prob[s : e + 1]
            if seg.max() < hi:
                continue
            core = np.flatnonzero(seg >= hi)
            a, b = core[0], core[-1]
            while a > 0 and seg[a - 1] >= lo:
                a -= 1
            while b < len(seg) - 1 and seg[b + 1] >= lo:
                b += 1
            iv.append((times[s + a], times[s + b]))
    else:
        raise ValueError(kind)
    iv = merge(iv, gap)
    iv = [(s, e) for s, e in iv if e - s >= min_dur]
    if topk:
        scored = sorted(
            iv,
            key=lambda se: prob[int(se[0] / GRID) : int(se[1] / GRID) + 1].mean() if se[1] > se[0] else 0,
            reverse=True,
        )
        iv = sorted(scored[:topk])
    return iv


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


def main() -> None:
    data = [(np.load(p), p.stem) for p in OOF]
    best = None
    for kind, hi, lo, sm, min_dur, gap in product(
        ("hi", "hyst", "peak"), (0.6, 0.7, 0.8, 0.9), (0.2, 0.3, 0.4), (2.0, 4.0), (10.0, 20.0, 30.0), (4.0, 10.0)
    ):
        tp = fp = fn = 0
        for d, _ in data:
            prob = smooth(d["prob"], sm)
            gold = list(zip(d["gold_starts"], d["gold_ends"]))
            pred = strategy(prob, d["times"], kind, hi, lo, min_dur, gap)
            a, b, c = match(pred, gold)
            tp, fp, fn = tp + a, fp + b, fn + c
        p = tp / (tp + fp) if tp + fp else 0
        r = tp / (tp + fn) if tp + fn else 0
        f = 2 * p * r / (p + r) if p + r else 0
        if best is None or f > best[0]:
            best = (f, p, r, kind, hi, lo, sm, min_dur, gap, tp, fp, fn)
    f, p, r, kind, hi, lo, sm, min_dur, gap, tp, fp, fn = best
    print(f"best {kind} hi {hi} lo {lo} smooth {sm} min_dur {min_dur} gap {gap}")
    print(f"  P {p:.3f} R {r:.3f} F1 {f:.3f} (tp {tp} fp {fp} fn {fn})")


if __name__ == "__main__":
    main()

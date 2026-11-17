"""Diagnostic: how far off are predicted boundaries from the labelled ones?"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from common import DATA

metrics = json.loads((DATA / "model" / "metrics.json").read_text())
hi = metrics["params"]["threshold"]
lo = metrics["params"].get("threshold_low", 0.25)
GRID = 0.25


def smooth(p, sec):
    w = max(1, int(round(sec / GRID)))
    w += (w + 1) % 2
    return np.convolve(np.pad(p, (w // 2, w // 2), mode="edge"), np.ones(w) / w, mode="valid")[: len(p)]


def intervals(prob, times):
    m = prob >= lo
    idx = np.flatnonzero(m)
    if idx.size == 0:
        return []
    sp = np.flatnonzero(np.diff(idx) > 1)
    st = np.r_[idx[0], idx[sp + 1]]
    en = np.r_[idx[sp], idx[-1]]
    return [(times[s], times[e]) for s, e in zip(st, en) if prob[s : e + 1].max() >= hi]


deltas = []
for path in sorted((DATA / "model" / "oof").glob("*.npz")):
    d = np.load(path)
    pred = intervals(smooth(d["prob"], metrics["params"]["smooth"]), d["times"])
    for gs, ge in zip(d["gold_starts"], d["gold_ends"]):
        best = None
        for s, e in pred:
            inter = max(0, min(e, ge) - max(s, gs))
            union = max(e, ge) - min(s, gs)
            iou = inter / union if union else 0
            if best is None or iou > best[0]:
                best = (iou, s, e)
        if best and best[0] > 0.2:
            deltas.append((best[0], best[1] - gs, best[2] - ge, ge - gs))

print(f"matched {len(deltas)} gold intervals with iou>0.2")
if deltas:
    arr = np.array(deltas)
    print("start delta (s): median %.1f  p10 %.1f  p90 %.1f" % tuple(np.percentile(arr[:, 1], [50, 10, 90])))
    print("end   delta (s): median %.1f  p10 %.1f  p90 %.1f" % tuple(np.percentile(arr[:, 2], [50, 10, 90])))
    print("iou: median %.2f" % np.median(arr[:, 0]))
    inside = ((arr[:, 1] >= -5) & (arr[:, 1] <= 5) & (arr[:, 2] >= -5) & (arr[:, 2] <= 5)).mean()
    print(f"fraction with both boundaries within 5 s: {inside:.2f}")

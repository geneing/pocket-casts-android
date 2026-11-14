"""Frame- and coverage-level metrics, for context next to the strict interval score."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from common import DATA, write_json

GRID = 0.25
m = json.loads((DATA / "model" / "metrics.json").read_text())
hi, lo, sm = m["params"]["threshold"], m["params"].get("threshold_low", 0.25), m["params"]["smooth"]


def smooth(p, sec):
    w = max(1, int(round(sec / GRID)))
    w += (w + 1) % 2
    return np.convolve(np.pad(p, (w // 2, w // 2), mode="edge"), np.ones(w) / w, mode="valid")[: len(p)]


tp = fp = fn = 0
ad_sec = pred_sec = hit_sec = 0.0
for path in sorted((DATA / "model" / "oof").glob("*.npz")):
    d = np.load(path)
    gold = d["gold_mask"] > 0
    prob = smooth(d["prob"], sm)
    pred = prob >= hi
    tp += int((pred & gold).sum())
    fp += int((pred & ~gold).sum())
    fn += int((~pred & gold).sum())
    ad_sec += gold.sum() * GRID
    pred_sec += pred.sum() * GRID
    hit_sec += (pred & gold).sum() * GRID

p = tp / (tp + fp) if tp + fp else 0
r = tp / (tp + fn) if tp + fn else 0
summary = {
    "frame_precision": round(p, 4),
    "frame_recall": round(r, 4),
    "ad_minutes": round(ad_sec / 60, 1),
    "predicted_ad_minutes": round(pred_sec / 60, 1),
    "true_positive_minutes": round(hit_sec / 60, 1),
    "predicted_minutes_that_are_ads": round(hit_sec / pred_sec, 3) if pred_sec else 0,
    "ad_minutes_found": round(hit_sec / ad_sec, 3) if ad_sec else 0,
}
print(json.dumps(summary, indent=1))
write_json(DATA / "model" / "coverage_metrics.json", summary)

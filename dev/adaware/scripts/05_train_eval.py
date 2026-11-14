"""Train and evaluate the frame classifier for ad detection.

Small, robust and phone-friendly on purpose: a per-frame logistic regression on the
0.25 s feature grid, then a moving average, a threshold, a gap merge and a minimum
duration. The exported model is one mean/std vector, one weight vector and a handful of
smoothing constants.

Evaluation is leave-one-episode-out (no take of the test episode is seen in training),
interval matching with 5 s boundary tolerance, and it is done per take because every
take has its own ad pod.
"""

from __future__ import annotations

import argparse
import os
from itertools import product

# OpenBLAS spawns one thread per core and makes lbfgs thrash; 4 is much faster here.
for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_v, "4")

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from common import DATA, FEATURES, LABELS, read_json, write_json

GRID = 0.25


def load_labels() -> dict:
    return read_json(LABELS / "ads.json", default={})


def episode_keys() -> list[str]:
    return sorted(p.name for p in FEATURES.glob("*") if p.is_dir())


def build(ep: str, labels: dict) -> tuple[np.ndarray, np.ndarray, list[dict], list[str]]:
    """Features and labels for one episode, kept per take."""
    ep_labels = labels.get(ep, {})
    xs, ys, takes = [], [], []
    names: list[str] = []
    for npz in sorted((FEATURES / ep).glob("*.npz")):
        take = npz.stem.replace("take", "")
        data = np.load(npz, allow_pickle=True)
        feats, times = data["feats"], data["times"]
        names = [str(n) for n in data["names"]]
        y = np.zeros(len(times), dtype=np.int8)
        for slot in ep_labels.get("dynamic_slots", {}).get(take, []):
            m = (times >= max(0, slot["start"] - 0.5)) & (times <= slot["end"] + 0.5)
            y[m] = 1
        names = [str(n) for n in data["names"]]
        sil = np.flatnonzero(data["silence_dist"] <= 0.01) if "silence_dist" in data else np.array([], int)
        xs.append(feats)
        ys.append(y)
        takes.append(
            {
                "take": take,
                "times": times,
                "y": y,
                "silence": sil,
                "gold": ep_labels.get("dynamic_slots", {}).get(take, []),
            }
        )
    if not xs:
        return np.zeros((0, 0)), np.zeros(0, np.int8), [], []
    return np.concatenate(xs), np.concatenate(ys), takes, names


# --------------------------------------------------------------- post-process


def smooth(prob: np.ndarray, seconds: float = 2.0) -> np.ndarray:
    w = max(1, int(round(seconds / GRID)))
    if w % 2 == 0:
        w += 1
    pad = w // 2
    p = np.pad(prob, (pad, pad), mode="edge")
    return np.convolve(p, np.ones(w) / w, mode="valid")[: len(prob)]


def to_intervals(prob: np.ndarray, times: np.ndarray, hi: float, lo: float, min_dur: float, merge_gap: float, silence=None):
    """Hysteresis: an ad needs a confident core (>= hi) and grows while >= lo.

    Ads start with a jingle and end in music, so the confidence drops before the real
    boundary; a single threshold either clips the ad or floods the timeline. The final
    boundaries are nudged onto the nearest silence, which is where a stitcher cuts.
    """
    low_mask = prob >= lo
    if not low_mask.any():
        return []
    idx = np.flatnonzero(low_mask)
    splits = np.flatnonzero(np.diff(idx) > 1)
    starts = np.r_[idx[0], idx[splits + 1]]
    ends = np.r_[idx[splits], idx[-1]]
    out = []
    for s, e in zip(starts, ends):
        if prob[s : e + 1].max() < hi:
            continue
        st, en = float(times[s] - GRID / 2), float(times[e] + GRID / 2)
        if silence is not None and len(silence):
            sil_t = times[silence]
            j = int(np.argmin(np.abs(sil_t - st)))
            if abs(sil_t[j] - st) <= 15:
                st = float(sil_t[j])
            j = int(np.argmin(np.abs(sil_t - en)))
            if abs(sil_t[j] - en) <= 15:
                en = float(sil_t[j])
        if en - st >= 1.0:
            out.append([st, en])
    merged = []
    for s, e in out:
        if merged and s - merged[-1][1] <= merge_gap:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])
    return [(s, e) for s, e in merged if e - s >= min_dur]


def match(pred: list[tuple[float, float]], gold: list[dict], tol: float = 5.0) -> dict:
    """Interval matching: both boundaries within `tol` counts as a hit."""
    gold_iv = [(g["start"], g["end"]) for g in gold]
    used = set()
    tp = 0
    for s, e in pred:
        best, best_cost = None, None
        for k, (gs, ge) in enumerate(gold_iv):
            if k in used or abs(s - gs) > tol or abs(e - ge) > tol:
                continue
            cost = abs(s - gs) + abs(e - ge)
            if best_cost is None or cost < best_cost:
                best, best_cost = k, cost
        if best is not None:
            used.add(best)
            tp += 1
    return {"tp": tp, "fp": len(pred) - tp, "fn": len(gold_iv) - tp}


def prf(m: dict) -> tuple[float, float, float]:
    tp, fp, fn = m["tp"], m["fp"], m["fn"]
    p = tp / (tp + fp) if tp + fp else 1.0
    r = tp / (tp + fn) if tp + fn else 1.0
    f = 2 * p * r / (p + r) if p + r else 0.0
    return p, r, f


def evaluate(per_take: dict, params: tuple, tol: float, snap: bool = True) -> tuple[dict, list[dict]]:
    sm, hi, lo, min_dur = params
    total = {"tp": 0, "fp": 0, "fn": 0}
    rows = []
    for ep, takes in per_take.items():
        if not takes:
            continue
        ep_tot = {"tp": 0, "fp": 0, "fn": 0}
        for t in takes:
            prob = smooth(t["prob"], sm)
            pred = to_intervals(prob, t["times"], hi, lo, min_dur, 6.0, t.get("silence") if snap else None)
            gold = t["gold"]
            mm = match(pred, gold, tol) if gold else {"tp": 0, "fp": len(pred), "fn": 0}
            for k in ep_tot:
                ep_tot[k] += mm[k]
        for k in total:
            total[k] += ep_tot[k]
        p1, r1, _ = prf(ep_tot)
        rows.append({"episode": ep, "pred": ep_tot["tp"] + ep_tot["fp"], "gold": ep_tot["tp"] + ep_tot["fn"], **ep_tot, "p": p1, "r": r1})
    return total, rows


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tol", type=float, default=5.0)
    ap.add_argument("--out", default="model.json")
    args = ap.parse_args()

    labels = load_labels()
    eps = [ep for ep in episode_keys() if labels.get(ep, {}).get("dynamic_slots")]
    if not eps:
        raise SystemExit("no labelled episodes; run 03_ads_from_transcripts.py first")
    data = {ep: build(ep, labels) for ep in eps}
    for ep, (x, y, takes, names) in data.items():
        print(f"{ep[:45]:47s} frames {len(y):6d} ad-frames {y.mean() * 100:5.1f}%")

    groups = np.concatenate([np.full(len(data[ep][1]), i) for i, ep in enumerate(eps)])
    X = np.concatenate([data[ep][0] for ep in eps])
    Y = np.concatenate([data[ep][1] for ep in eps])

    # leave-one-episode-out predictions
    per_take: dict[str, list[dict]] = {}
    for i, ep in enumerate(eps):
        train, test = groups != i, groups == i
        model = make_pipeline(
            StandardScaler(),
            LogisticRegression(C=0.3, max_iter=400, tol=1e-3, class_weight="balanced"),
        )
        model.fit(X[train], Y[train])
        prob = model.predict_proba(X[test])[:, 1]
        ap_score = average_precision_score(Y[test], prob)
        offset, takes = 0, data[ep][2]
        per_take[ep] = []
        for t in takes:
            n = len(t["y"])
            per_take[ep].append({**t, "prob": prob[offset : offset + n]})
            offset += n
        print(f"LOEO {ep[:45]:47s} AP {ap_score:.3f}")

    # tune post-processing on out-of-fold predictions, favouring both P and R >= 0.9
    grid = product(
        (1.0, 2.0, 4.0, 6.0),
        (0.5, 0.6, 0.7, 0.8),
        (0.15, 0.25, 0.35, 0.45),
        (10.0, 15.0, 20.0, 30.0),
    )
    scored = []
    for snap, params in product((False, True), grid):
        total, _ = evaluate(per_take, params, args.tol, snap)
        p, r, f = prf(total)
        scored.append((params, p, r, f, snap))
    good = [s for s in scored if s[1] >= 0.9 and s[2] >= 0.9]
    pick = max(good, key=lambda s: s[3]) if good else max(scored, key=lambda s: s[3])
    (sm, hi, lo, min_dur), p, r, f, snap = pick
    print(
        f"\nchosen: smooth {sm}s hi {hi:.2f} lo {lo:.2f} min_dur {min_dur}s snap {snap} "
        f"-> P {p:.3f} R {r:.3f} F1 {f:.3f}"
    )

    total, rows = evaluate(per_take, (sm, hi, lo, min_dur), args.tol, snap)
    for row in rows:
        print(
            f"{row['episode'][:45]:47s} gold {row['gold']:2d} pred {row['pred']:2d} "
            f"tp {row['tp']} P {row['p']:.2f} R {row['r']:.2f}"
        )
    p_micro, r_micro, f_micro = prf(total)
    print(f"micro P {p_micro:.3f} R {r_micro:.3f} F1 {f_micro:.3f} {total}")
    print(f"macro P {np.mean([r['p'] for r in rows]):.3f} R {np.mean([r['r'] for r in rows]):.3f}")

    oof_dir = DATA / "model" / "oof"
    oof_dir.mkdir(parents=True, exist_ok=True)
    for ep, takes in per_take.items():
        for t in takes:
            np.savez_compressed(
                oof_dir / f"{ep}__take{t['take']}.npz",
                times=t["times"],
                prob=smooth(t["prob"], sm),
                gold_mask=t["y"],
                gold_starts=np.array([g["start"] for g in t["gold"]]),
                gold_ends=np.array([g["end"] for g in t["gold"]]),
            )

    metrics = {
        "precision": round(p_micro, 4),
        "recall": round(r_micro, 4),
        "f1": round(f_micro, 4),
        "macro_precision": round(float(np.mean([r["p"] for r in rows])), 4),
        "macro_recall": round(float(np.mean([r["r"] for r in rows])), 4),
        "tolerance": args.tol,
        "params": {"smooth": sm, "threshold": float(hi), "threshold_low": float(lo), "min_duration": min_dur},
        "counts": total,
        "episodes": rows,
    }
    write_json(DATA / "model" / "metrics.json", metrics)

    final = make_pipeline(
        StandardScaler(),
        LogisticRegression(C=0.3, max_iter=400, tol=1e-3, class_weight="balanced"),
    )
    final.fit(X, Y)
    scaler, clf = final[0], final[1]
    write_json(
        DATA / "model" / args.out,
        {
            "features": names,
            "mean": scaler.mean_.tolist(),
            "scale": scaler.scale_.tolist(),
            "weights": clf.coef_[0].tolist(),
            "bias": float(clf.intercept_[0]),
            "smooth_seconds": sm,
            "threshold": float(hi),
            "threshold_low": float(lo),
            "min_duration": min_dur,
            "merge_gap": 6.0,
            "grid_seconds": GRID,
            "tolerance": args.tol,
            "train_episodes": eps,
        },
    )
    print(f"wrote {DATA / 'model' / args.out}")


if __name__ == "__main__":
    main()

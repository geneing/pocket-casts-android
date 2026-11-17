"""Diagnostic: does per-episode normalisation make features transfer across shows?

Ad acoustics differ per show (studio, mic, mastering). A global scaler keeps those
differences and the model can learn show identity instead of ad-ness.
"""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path

os.environ.setdefault("OMP_NUM_THREADS", "4")
os.environ.setdefault("OPENBLAS_NUM_THREADS", "4")

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

spec = importlib.util.spec_from_file_location("t", Path("scripts/05_train_eval.py"))
t = importlib.util.module_from_spec(spec)
spec.loader.exec_module(t)

REL = ("rel", "mod_", "silence_", "pos_")


def variant(feats, names, kind):
    keep = [i for i, n in enumerate(names) if any(k in n for k in REL)]
    if kind == "raw":
        return feats
    if kind == "rel":
        return feats[:, keep]
    if kind == "z":
        return (feats - feats.mean(0)) / (feats.std(0) + 1e-6)
    if kind == "z+rel":
        z = (feats - feats.mean(0)) / (feats.std(0) + 1e-6)
        return np.concatenate([z, feats[:, keep]], 1)
    raise ValueError(kind)


def dataset(kind, labels, eps):
    data = {}
    for ep in eps:
        takes = []
        for npz in sorted((Path("data/features") / ep).glob("*.npz")):
            d = np.load(npz, allow_pickle=True)
            names = [str(n) for n in d["names"]]
            take = npz.stem.replace("take", "")
            gold = labels[ep]["dynamic_slots"].get(take, [])
            y = np.zeros(len(d["times"]), np.int8)
            for s in gold:
                m = (d["times"] >= s["start"] - 0.5) & (d["times"] <= s["end"] + 0.5)
                y[m] = 1
            takes.append({"X": variant(d["feats"], names, kind), "y": y})
        data[ep] = takes
    return data


def main() -> None:
    labels = t.load_labels()
    eps = [e for e in t.episode_keys() if labels.get(e, {}).get("dynamic_slots")]
    for kind in ("raw", "rel", "z", "z+rel"):
        data = dataset(kind, labels, eps)
        aps = []
        for ep in eps:
            Xtr = np.concatenate([tk["X"] for e in eps if e != ep for tk in data[e]])
            Ytr = np.concatenate([tk["y"] for e in eps if e != ep for tk in data[e]])
            model = make_pipeline(StandardScaler(), LogisticRegression(C=0.3, max_iter=400, class_weight="balanced"))
            model.fit(Xtr, Ytr)
            Xte = np.concatenate([tk["X"] for tk in data[ep]])
            Yte = np.concatenate([tk["y"] for tk in data[ep]])
            aps.append(average_precision_score(Yte, model.predict_proba(Xte)[:, 1]))
        print(f"{kind:6s} AP {np.mean(aps):.3f}  {[round(a, 2) for a in aps]}", flush=True)


if __name__ == "__main__":
    main()

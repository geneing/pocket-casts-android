"""Diagnostic: which model family can separate ad frames? (fast variant)

Leave-one-episode-out average precision and best interval P/R with a subsampled
negative class, so this runs in minutes on a laptop. Not part of the pipeline.
"""

from __future__ import annotations

import importlib.util
import pickle
import warnings
from itertools import product
from pathlib import Path

import numpy as np

spec = importlib.util.spec_from_file_location("tune", Path(__file__).with_name("05_train_eval.py"))
tune = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tune)

from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

warnings.filterwarnings("ignore")
CACHE = Path("data/model/dataset.pkl")


def models():
    yield "logreg C=0.1", make_pipeline(StandardScaler(), LogisticRegression(C=0.1, max_iter=1500, class_weight="balanced"))
    yield "logreg C=1", make_pipeline(StandardScaler(), LogisticRegression(C=1.0, max_iter=1500, class_weight="balanced"))
    yield "hgb", HistGradientBoostingClassifier(
        max_iter=250, learning_rate=0.08, max_leaf_nodes=31, class_weight="balanced", early_stopping=True
    )
    yield "rf60d8", RandomForestClassifier(
        n_estimators=60, max_depth=8, min_samples_leaf=40, class_weight="balanced_subsample", n_jobs=-1
    )


def load() -> tuple[list[str], dict]:
    if CACHE.exists():
        with CACHE.open("rb") as f:
            return pickle.load(f)
    labels = tune.load_labels()
    eps = [ep for ep in tune.episode_keys() if labels.get(ep, {}).get("dynamic_slots")]
    data = {ep: tune.build(ep, labels) for ep in eps}
    with CACHE.open("wb") as f:
        pickle.dump((eps, data), f)
    return eps, data


def main() -> None:
    eps, data = load()
    groups = np.concatenate([np.full(len(data[ep][1]), i) for i, ep in enumerate(eps)])
    X = np.concatenate([data[ep][0] for ep in eps])
    Y = np.concatenate([data[ep][1] for ep in eps])
    print(f"episodes {len(eps)} frames {len(Y)} positives {Y.mean() * 100:.1f}%", flush=True)

    rng = np.random.default_rng(0)
    for name, model in models():
        per_take, aps = {}, []
        try:
            for i, ep in enumerate(eps):
                train = np.where(groups != i)[0]
                keep = train[(Y[train] == 1) | (rng.random(len(train)) < 0.25)]
                test = groups == i
                model.fit(X[keep], Y[keep])
                prob = model.predict_proba(X[test])[:, 1]
                aps.append(average_precision_score(Y[test], prob))
                per_take[ep], offset = [], 0
                for t in data[ep][2]:
                    n = len(t["y"])
                    per_take[ep].append({**t, "prob": prob[offset : offset + n]})
                    offset += n
        except Exception as exc:  # noqa: BLE001
            print(f"{name:12s} failed {exc!r}"[:200], flush=True)
            continue
        best = (0, 0, 0, None)
        for params in product((2.0, 4.0), (0.6, 0.7, 0.8), (0.25, 0.35), (15.0, 20.0, 30.0)):
            total, _ = tune.evaluate(per_take, params, 5.0)
            p, r, f = tune.prf(total)
            if f > best[2]:
                best = (p, r, f, params)
        print(f"{name:12s} AP {np.mean(aps):.3f}  P {best[0]:.3f} R {best[1]:.3f} F1 {best[2]:.3f} {best[3]}", flush=True)


if __name__ == "__main__":
    main()

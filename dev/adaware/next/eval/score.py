"""One-to-one podcast ad interval scoring with a two-boundary tolerance.

Import `score_take` for model experiments. A hit requires the start *and* end
to be within the tolerance; broad audio overlap alone is insufficient.
"""

from __future__ import annotations

from collections import defaultdict
from typing import Mapping, Sequence


def _bounds(interval: Mapping[str, object]) -> tuple[float, float]:
    start, end = float(interval["start"]), float(interval["end"])
    if not (0 <= start < end):
        raise ValueError(f"Invalid interval [{start}, {end}]")
    return start, end


def score_take(
    predictions: Sequence[Mapping[str, object]],
    references: Sequence[Mapping[str, object]],
    tolerance_seconds: float = 10.0,
) -> dict[str, float | int]:
    """Return max-cardinality TP/FP/FN for a single take.

    Maximum matching avoids a prediction-order artifact when several nearby
    predictions can match the same reference interval.
    """
    if tolerance_seconds < 0:
        raise ValueError("tolerance_seconds must be nonnegative")
    pred, gold = list(map(_bounds, predictions)), list(map(_bounds, references))
    edges = [
        [
            j
            for j, (gs, ge) in enumerate(gold)
            if abs(ps - gs) <= tolerance_seconds and abs(pe - ge) <= tolerance_seconds
        ]
        for ps, pe in pred
    ]
    owner: dict[int, int] = {}

    def augment(i: int, visited: set[int]) -> bool:
        for j in edges[i]:
            if j in visited:
                continue
            visited.add(j)
            if j not in owner or augment(owner[j], visited):
                owner[j] = i
                return True
        return False

    tp = sum(augment(i, set()) for i in range(len(pred)))
    fp, fn = len(pred) - tp, len(gold) - tp
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": tp / len(pred) if pred else (1.0 if not gold else 0.0),
        "recall": tp / len(gold) if gold else 1.0,
    }


def score_corpus(
    predictions: Mapping[str, Mapping[str, Sequence[Mapping[str, object]]]],
    references: Mapping[str, Mapping[str, Sequence[Mapping[str, object]]]],
    tolerance_seconds: float = 10.0,
) -> dict[str, object]:
    """Score episode -> take -> interval lists, including ad-free takes."""
    totals = defaultdict(int)
    rows = []
    for episode in sorted(set(predictions) | set(references)):
        pred_takes, gold_takes = predictions.get(episode, {}), references.get(episode, {})
        for take in sorted(set(pred_takes) | set(gold_takes)):
            result = score_take(pred_takes.get(take, []), gold_takes.get(take, []), tolerance_seconds)
            rows.append({"episode": episode, "take": take, **result})
            for key in ("tp", "fp", "fn"):
                totals[key] += result[key]
    tp, fp, fn = (totals[key] for key in ("tp", "fp", "fn"))
    return {
        "tolerance_seconds": tolerance_seconds,
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": tp / (tp + fp) if tp + fp else (1.0 if not fn else 0.0),
        "recall": tp / (tp + fn) if tp + fn else 1.0,
        "takes": rows,
    }

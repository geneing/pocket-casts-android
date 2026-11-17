"""Nested leave-one-episode-out evaluation of a frame score plus ad-pod prior.

The base probabilities are the saved out-of-fold predictions from the existing
episode-held-out acoustic model. This script adds only inexpensive temporal
post-processing: candidate runs, a learned ad-duration prior, and an ad-count cap.
All post-processing choices for an outer test episode are selected from other
episodes' OOF predictions and labels.
"""

from __future__ import annotations

import json
import math
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data"
OOF = DATA / "model" / "oof"
TOLERANCE = 10.0
GRID = 0.25


@dataclass(frozen=True)
class Sample:
    episode: str
    take: str
    times: np.ndarray
    prob: np.ndarray
    gold: tuple[tuple[float, float], ...]
    gold_mask: np.ndarray
    words: tuple[tuple[float, float, str], ...]
    lexical: np.ndarray | None = None


def load_samples() -> list[Sample]:
    samples = []
    for path in sorted(OOF.glob("*.npz")):
        # filenames are <episode>__takeN.npz; episode slugs themselves contain underscores
        stem = path.stem
        episode, take = stem.rsplit("__take", 1)
        data = np.load(path, allow_pickle=True)
        starts = np.asarray(data["gold_starts"], dtype=float)
        ends = np.asarray(data["gold_ends"], dtype=float)
        gold = tuple((float(s), float(e)) for s, e in zip(starts, ends))
        transcript = json.loads((DATA / "transcripts" / episode / f"take{take}.json").read_text(encoding="utf-8"))
        words = tuple(
            (float(w["s"]), float(w["e"]), normalize_word(w.get("w", "")))
            for segment in transcript["segments"]
            for w in segment.get("words", [])
            if normalize_word(w.get("w", ""))
        )
        samples.append(
            Sample(
                episode=episode,
                take=take,
                times=np.asarray(data["times"], dtype=float),
                prob=np.asarray(data["prob"], dtype=float),
                gold=gold,
                gold_mask=np.asarray(data["gold_mask"], dtype=np.uint8),
                words=words,
            )
        )
    if not samples:
        raise SystemExit(f"No OOF predictions found at {OOF}")
    return samples


def normalize_word(word: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", word.lower())


def lexical_model(samples: list[Sample]):
    ad_counts: Counter[str] = Counter()
    content_counts: Counter[str] = Counter()
    ad_total = content_total = 0
    for sample in samples:
        for i, (start, end, token) in enumerate(sample.words):
            is_ad = any(gs <= (start + end) / 2 <= ge for gs, ge in sample.gold)
            features = [token]
            if i:
                features.append(f"{sample.words[i - 1][2]}_{token}")
            target = ad_counts if is_ad else content_counts
            target.update(features)
            if is_ad:
                ad_total += len(features)
            else:
                content_total += len(features)
    vocabulary = set(ad_counts) | set(content_counts)
    alpha = 0.25
    n = max(1, len(vocabulary))
    return {
        word: math.log((ad_counts[word] + alpha) / (ad_total + alpha * n))
        - math.log((content_counts[word] + alpha) / (content_total + alpha * n))
        for word in vocabulary
    }


def lexical_scores(sample: Sample, model: dict[str, float]) -> np.ndarray:
    score = np.zeros(len(sample.times), dtype=float)
    weight = np.zeros(len(sample.times), dtype=float)
    for i, (start, end, token) in enumerate(sample.words):
        features = [token]
        if i:
            features.append(f"{sample.words[i - 1][2]}_{token}")
        value = float(np.mean([model.get(f, 0.0) for f in features]))
        left = max(0, int(math.floor((start - sample.times[0]) / GRID)))
        right = min(len(score), int(math.ceil((end - sample.times[0]) / GRID)) + 1)
        if right > left:
            score[left:right] += value
            weight[left:right] += 1
    have_words = weight > 0
    score[have_words] /= weight[have_words]
    return np.clip(score, -4.0, 4.0)


def crossfit_lexical(samples: list[Sample]) -> list[Sample]:
    """Attach lexical scores, cross-fitting training samples by episode."""
    scored = []
    episodes = sorted({s.episode for s in samples})
    for episode in episodes:
        model = lexical_model([s for s in samples if s.episode != episode])
        for sample in samples:
            if sample.episode == episode:
                scored.append(Sample(**{**sample.__dict__, "lexical": lexical_scores(sample, model)}))
    return scored


def smooth(prob: np.ndarray, seconds: float) -> np.ndarray:
    width = max(1, int(round(seconds / GRID)))
    if width % 2 == 0:
        width += 1
    pad = width // 2
    return np.convolve(np.pad(prob, (pad, pad), mode="edge"), np.ones(width) / width, mode="valid")[: len(prob)]


def runs(mask: np.ndarray) -> list[tuple[int, int]]:
    indexes = np.flatnonzero(mask)
    if not indexes.size:
        return []
    split = np.flatnonzero(np.diff(indexes) > 1)
    return list(zip(np.r_[indexes[0], indexes[split + 1]], np.r_[indexes[split], indexes[-1]]))


def candidates(sample: Sample, smooth_s: float, low: float, high: float, gap_s: float, min_s: float, prob=None):
    p = smooth(sample.prob if prob is None else prob, smooth_s)
    result = []
    for a, b in runs(p >= low):
        if p[a : b + 1].max() < high:
            continue
        start = float(sample.times[a] - GRID / 2)
        end = float(sample.times[b] + GRID / 2)
        if result and start - result[-1]["end"] <= gap_s:
            result[-1]["end"] = end
            result[-1]["last"] = b
        elif end - start >= min_s:
            result.append({"start": start, "end": end, "first": int(a), "last": int(b)})
    # A merge can make an interval long enough to survive min duration.
    return [c for c in result if c["end"] - c["start"] >= min_s]


def match(pred: list[tuple[float, float]], gold: tuple[tuple[float, float], ...]) -> tuple[int, int, int]:
    unused = set(range(len(gold)))
    tp = 0
    for start, end in pred:
        choices = [
            (abs(start - gs) + abs(end - ge), i)
            for i, (gs, ge) in enumerate(gold)
            if i in unused and abs(start - gs) <= TOLERANCE and abs(end - ge) <= TOLERANCE
        ]
        if choices:
            _, best = min(choices)
            unused.remove(best)
            tp += 1
    return tp, len(pred) - tp, len(gold) - tp


def totals(samples: list[Sample], prediction_map: dict[tuple[str, str], list[tuple[float, float]]]):
    tp = fp = fn = 0
    for sample in samples:
        a, b, c = match(prediction_map[(sample.episode, sample.take)], sample.gold)
        tp += a
        fp += b
        fn += c
    precision = tp / (tp + fp) if tp + fp else 1.0
    recall = tp / (tp + fn) if tp + fn else 1.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {"tp": tp, "fp": fp, "fn": fn, "precision": precision, "recall": recall, "f1": f1}


def average_precision(labels: np.ndarray, scores: np.ndarray) -> float:
    positives = int(labels.sum())
    if not positives:
        return 0.0
    order = np.argsort(-scores, kind="stable")
    ranked = labels[order].astype(float)
    precision_at_rank = np.cumsum(ranked) / np.arange(1, len(ranked) + 1)
    return float(np.sum(precision_at_rank * ranked) / positives)


def combined_probability(sample: Sample, lexical_scale: float) -> np.ndarray:
    if not lexical_scale or sample.lexical is None:
        return sample.prob
    p = np.clip(sample.prob, 1e-5, 1 - 1e-5)
    logits = np.log(p / (1 - p)) + lexical_scale * sample.lexical
    return 1.0 / (1.0 + np.exp(-np.clip(logits, -20, 20)))


def config_grid():
    # Small enough for nested evaluation; spans cover the useful range found in the prior
    # post-processing experiments. top_k=None is the uncapped acoustic baseline.
    for smoothing in (1.0, 3.0):
        for low in (0.2, 0.4):
            for high in (0.55, 0.8):
                if high <= low:
                    continue
                for gap in (0.0, 6.0):
                    for min_duration in (8.0, 20.0):
                        for top_k in (None, 2, 4):
                            for duration_prior in (False, True):
                                for lexical_scale in (0.0, 0.5, 1.0, 2.0):
                                    yield {
                                        "smooth_s": smoothing,
                                        "low": low,
                                        "high": high,
                                        "gap_s": gap,
                                        "min_s": min_duration,
                                        "top_k": top_k,
                                        "duration_prior": duration_prior,
                                        "lexical_scale": lexical_scale,
                                    }


def duration_stats(samples: list[Sample]) -> tuple[float, float]:
    durations = [e - s for x in samples for s, e in x.gold if e > s]
    if not durations:
        return math.log(45.0), 0.8
    logs = np.log(np.asarray(durations))
    return float(np.median(logs)), max(float(np.std(logs)), 0.4)


def infer(sample: Sample, config: dict, duration_mu: float, duration_sigma: float, candidate_cache=None):
    cache_key = (
        id(sample), config["smooth_s"], config["low"], config["high"], config["gap_s"],
        config["min_s"], config["lexical_scale"],
    )
    if candidate_cache is not None and cache_key in candidate_cache:
        found, p = candidate_cache[cache_key]
    else:
        prob = combined_probability(sample, config["lexical_scale"])
        p = smooth(prob, config["smooth_s"])
        found = candidates(
            sample,
            **{k: config[k] for k in ("smooth_s", "low", "high", "gap_s", "min_s")},
            prob=p,
        )
        for c in found:
            c["mean"] = float(np.mean(p[c["first"] : c["last"] + 1]))
        if candidate_cache is not None:
            candidate_cache[cache_key] = (found, p)
    scored = []
    for c in found:
        duration = max(GRID, c["end"] - c["start"])
        # This is the only added learned prior: ad pods usually contain a small number
        # of spots, and training-fold ad durations provide a broad log-normal preference.
        score = c["mean"]
        if config["duration_prior"]:
            z = (math.log(duration) - duration_mu) / duration_sigma
            score *= math.exp(-0.5 * z * z)
        scored.append((score, (c["start"], c["end"])))
    if config["top_k"] is not None:
        scored = sorted(scored, reverse=True)[: config["top_k"]]
    return sorted(iv for _, iv in scored)


def evaluate_config(samples: list[Sample], config: dict, candidate_cache=None):
    mu, sigma = duration_stats(samples)
    predictions = {(s.episode, s.take): infer(s, config, mu, sigma, candidate_cache) for s in samples}
    return totals(samples, predictions)


def main():
    samples = load_samples()
    episodes = sorted({s.episode for s in samples})
    configs = list(config_grid())
    fold_results = []
    all_predictions = {}
    all_acoustic_predictions = {}
    for heldout in episodes:
        train = crossfit_lexical([s for s in samples if s.episode != heldout])
        test_original = [s for s in samples if s.episode == heldout]
        test_model = lexical_model(train)
        test = [Sample(**{**s.__dict__, "lexical": lexical_scores(s, test_model)}) for s in test_original]
        # Tune all post-processing on training-episode OOF predictions only.
        candidate_cache = {}
        scored = [(evaluate_config(train, cfg, candidate_cache), cfg) for cfg in configs]
        best_metrics, best_config = max(scored, key=lambda x: x[0]["f1"])
        acoustic_metrics, acoustic_config = max(
            (item for item in scored if item[1]["lexical_scale"] == 0.0),
            key=lambda x: x[0]["f1"],
        )
        mu, sigma = duration_stats(train)
        predictions = {(s.episode, s.take): infer(s, best_config, mu, sigma) for s in test}
        acoustic_predictions = {(s.episode, s.take): infer(s, acoustic_config, mu, sigma) for s in test}
        all_predictions.update(predictions)
        all_acoustic_predictions.update(acoustic_predictions)
        fold_metrics = totals(test, predictions)
        acoustic_test_metrics = totals(test, acoustic_predictions)
        acoustic_ap = average_precision(
            np.concatenate([s.gold_mask for s in test]),
            np.concatenate([s.prob for s in test]),
        )
        hybrid_ap = average_precision(
            np.concatenate([s.gold_mask for s in test]),
            np.concatenate([combined_probability(s, best_config["lexical_scale"]) for s in test]),
        )
        fold_results.append({
            "heldout_episode": heldout,
            "selected_config": best_config,
            "training_tuning_metrics": best_metrics,
            "test_metrics": fold_metrics,
            "acoustic_only_config": acoustic_config,
            "acoustic_only_training_tuning_metrics": acoustic_metrics,
            "acoustic_only_test_metrics": acoustic_test_metrics,
            "frame_ap_acoustic": acoustic_ap,
            "frame_ap_hybrid": hybrid_ap,
            "test_gold": sum(len(s.gold) for s in test),
            "test_predictions": sum(len(predictions[(s.episode, s.take)]) for s in test),
        })
        print(f"LOEO {heldout:55s} P={fold_metrics['precision']:.3f} R={fold_metrics['recall']:.3f} "
              f"F1={fold_metrics['f1']:.3f} config={best_config}")

    overall = totals(samples, all_predictions)
    acoustic_only = totals(samples, all_acoustic_predictions)
    mean_acoustic_ap = float(np.mean([f["frame_ap_acoustic"] for f in fold_results]))
    mean_hybrid_ap = float(np.mean([f["frame_ap_hybrid"] for f in fold_results]))
    # Show whether recurrence across episodes can support a fingerprint memory. The
    # provided transcript clusters are used only for this corpus diagnostic, never inference.
    creatives = json.loads((DATA / "labels" / "ad_creatives.json").read_text(encoding="utf-8"))
    cross_episode_creatives = [c for c in creatives if len(set(c.get("episodes", []))) > 1]
    output = {
        "protocol": "Nested LOEO. Each outer fold selects thresholds, cap, duration prior, and lexical weight from other episodes' saved OOF predictions/labels only. Lexical scores for tuning episodes are cross-fitted by episode. Matching requires both boundaries within 10 seconds.",
        "n_episodes": len(episodes),
        "n_takes": len(samples),
        "n_gold_intervals": sum(len(s.gold) for s in samples),
        "n_configurations": len(configs),
        "overall": overall,
        "acoustic_only_nested_loeo": acoustic_only,
        "mean_episode_frame_ap_acoustic": mean_acoustic_ap,
        "mean_episode_frame_ap_hybrid": mean_hybrid_ap,
        "folds": fold_results,
        "creative_recurrence_diagnostic": {
            "creative_clusters": len(creatives),
            "clusters_in_multiple_episodes": len(cross_episode_creatives),
            "note": "Transcript-derived cluster metadata is diagnostic only; it is not provided to the detector.",
        },
    }
    out = Path(__file__).resolve().parent / "results.json"
    out.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(f"\nNested LOEO P={overall['precision']:.3f} R={overall['recall']:.3f} "
          f"F1={overall['f1']:.3f} (TP={overall['tp']} FP={overall['fp']} FN={overall['fn']})")
    print(f"Acoustic-only nested LOEO P={acoustic_only['precision']:.3f} R={acoustic_only['recall']:.3f} "
          f"F1={acoustic_only['f1']:.3f} (TP={acoustic_only['tp']} FP={acoustic_only['fp']} FN={acoustic_only['fn']})")
    print(f"Mean held-out episode frame AP acoustic={mean_acoustic_ap:.3f}, hybrid={mean_hybrid_ap:.3f}")
    print(f"Cross-episode creative clusters: {len(cross_episode_creatives)}/{len(creatives)}")
    print(f"Saved {out}")


if __name__ == "__main__":
    main()

"""Fold-safe contextual entry/exit heads on top of the frozen TF-IDF model."""

from __future__ import annotations

import hashlib
import json
import math
import time
from collections import Counter, defaultdict
from dataclasses import asdict
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupKFold
from sklearn.preprocessing import StandardScaler

import detector
import diagnostics

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
LABEL_FILE = RESULTS / "ads_v3_input_2c600b4e.json"
LABEL_SHA256 = "2c600b4e798c07b3afd8fb66bc64427953c3c9db3e7d537e59a626ab62a78870"
BASELINE_FILE = RESULTS / "evaluation_v3.json"
OUTPUT = RESULTS / "boundary_head_v3.json"
REPORT = RESULTS / "boundary_head_v3_REPORT.md"
SCALAR_COUNT = 18
LAMBDAS = (0.0, 1.0, 2.0)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def json_safe(value):
    """Convert nested NumPy values into objects accepted by the JSON encoder."""
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    if isinstance(value, np.ndarray):
        return json_safe(value.tolist())
    if isinstance(value, np.generic):
        return value.item()
    return value


def grouped_episode_splits(episodes: list[str], folds: int = 3):
    names = np.asarray(episodes)
    for train_idx, val_idx in GroupKFold(n_splits=folds).split(np.zeros(len(names)), groups=names):
        train, val = set(names[train_idx]), set(names[val_idx])
        assert not train & val
        yield sorted(train), sorted(val)


def _speech_union(starts, ends, indices, low, high):
    spans = sorted((max(low, float(starts[i])), min(high, float(ends[i]))) for i in indices if min(high, float(ends[i])) > max(low, float(starts[i])))
    if not spans:
        return 0.0
    total, lo, hi = 0.0, *spans[0]
    for a, b in spans[1:]:
        if a <= hi:
            hi = max(hi, b)
        else:
            total += hi - lo
            lo, hi = a, b
    return min(high - low, total + hi - lo)


def _side_stats(starts, ends, mids, probs, embeddings, low, split, high, side):
    if side == "left":
        a, b = np.searchsorted(mids, [low, split], side="left")
    else:
        a = np.searchsorted(mids, split, side="left")
        b = np.searchsorted(mids, high, side="right")
    indices = np.arange(int(a), int(b), dtype=int)
    if not len(indices):
        return [0.0] * 4, 0.0, np.zeros(384, np.float32), True
    overlaps = np.maximum(0.0, np.minimum(ends[indices], high) - np.maximum(starts[indices], low))
    valid = overlaps > 0
    indices, overlaps = indices[valid], overlaps[valid]
    if not len(indices):
        return [0.0] * 4, 0.0, np.zeros(384, np.float32), True
    nearest = indices[int(np.argmin(np.abs(mids[indices] - split)))]
    mean = float(np.average(probs[indices], weights=overlaps))
    stats = [float(probs[nearest]), mean, float(np.min(probs[indices])), float(np.max(probs[indices]))]
    vector = np.average(embeddings[indices], axis=0, weights=overlaps).astype(np.float32)
    norm = float(np.linalg.norm(vector))
    if norm > 1e-12:
        vector /= norm
    return stats, _speech_union(starts, ends, indices, low, high), vector, False


def _feature_row(starts, ends, mids, probs, current_embeddings, split_index, edge_time):
    low, high = edge_time - 15.0, edge_time + 15.0
    left, left_speech, left_vec, left_missing = _side_stats(starts, ends, mids, probs, current_embeddings, low, edge_time, edge_time, "left")
    right, right_speech, right_vec, right_missing = _side_stats(starts, ends, mids, probs, current_embeddings, edge_time, edge_time, high, "right")
    li, ri = split_index - 1, split_index
    gap = min(30.0, max(0.0, float(starts[ri] - ends[li]))) if li >= 0 and ri < len(starts) else 0.0
    ld = min(30.0, max(0.0, float(ends[li] - starts[li]))) if li >= 0 else 0.0
    rd = min(30.0, max(0.0, float(ends[ri] - starts[ri]))) if ri < len(starts) else 0.0
    cosine = float(np.dot(left_vec, right_vec)) if not left_missing and not right_missing else 0.0
    scalar = np.asarray(
        left + right + [right[1] - left[1], right[0] - left[0], gap, ld, rd,
                        min(15.0, left_speech), min(15.0, right_speech), float(left_missing), float(right_missing), cosine],
        dtype=np.float32,
    )
    assert len(scalar) == SCALAR_COUNT
    return np.concatenate((scalar, left_vec, right_vec)).astype(np.float32)


def feature_tables(samples, episodes, probabilities, current_embeddings):
    wanted = set(episodes)
    grouped = defaultdict(list)
    for i, sample in enumerate(samples):
        if sample.episode in wanted:
            grouped[(sample.episode, sample.take)].append(i)
    tables = {edge: {"features": [], "keys": [], "times": []} for edge in ("start", "end")}
    for (episode, take), indices in sorted(grouped.items()):
        indices.sort(key=lambda i: samples[i].start)
        seq = [samples[i] for i in indices]
        raw_probs = probabilities.get(episode, {}).get(take, [])
        starts = np.asarray([s.start for s in seq], np.float64)
        ends = np.asarray([s.end for s in seq], np.float64)
        mids = (starts + ends) / 2
        probs = np.asarray([raw_probs[s.index] if s.index < len(raw_probs) else 0.0 for s in seq], np.float32)
        embeds = current_embeddings[indices]
        for pos, sample_i in enumerate(indices):
            for edge, split_i, edge_time in (("start", pos, seq[pos].start), ("end", pos + 1, seq[pos].end)):
                tables[edge]["features"].append(_feature_row(starts, ends, mids, probs, embeds, split_i, edge_time))
                tables[edge]["keys"].append((episode, take, samples[sample_i].index))
                tables[edge]["times"].append(float(edge_time))
    for edge in ("start", "end"):
        tables[edge]["features"] = np.asarray(tables[edge]["features"], np.float32)
        tables[edge]["times"] = np.asarray(tables[edge]["times"], np.float32)
    return tables


def _nearest_candidate(times, target_time):
    if not len(times):
        return None
    i = int(np.searchsorted(times, target_time, side="left"))
    candidates = ([i] if i < len(times) else []) + ([i - 1] if i > 0 else [])
    best = min(candidates, key=lambda k: (abs(float(times[k]) - target_time), float(times[k])))
    return best if abs(float(times[best]) - target_time) <= 10.0 else None


def build_targets(table, references, episodes, edge_type):
    keys, times = table[edge_type]["keys"], table[edge_type]["times"]
    y = np.full(len(keys), -1, np.int8)  # ignored edge alternatives are omitted from fitting
    pos_weights = np.zeros(len(keys), np.float32)
    take_rows = defaultdict(list)
    for row, (episode, take, _seg_i) in enumerate(keys):
        take_rows[(episode, take)].append(row)
    positive, ignored = set(), set()
    omitted = 0
    for episode in sorted(set(episodes)):
        for take, intervals in references.get(episode, {}).items():
            rows = take_rows.get((episode, take), [])
            candidate_times = times[rows]
            for interval in intervals:
                target_time = float(interval["start"] if edge_type == "start" else interval["end"])
                near = np.flatnonzero(np.abs(candidate_times - target_time) <= 10.0)
                ignored.update(rows[int(i)] for i in near)
                nearest = _nearest_candidate(candidate_times, target_time)
                if nearest is None:
                    omitted += 1
                    continue
                row = rows[nearest]
                positive.add(row)
                uncertainty = max(0.0, float(interval.get("uncertainty_seconds", 0.0)))
                pos_weights[row] = max(pos_weights[row], 1.0 / (1.0 + uncertainty / 10.0))
    for row, (episode, _take, _seg_i) in enumerate(keys):
        if episode not in set(episodes):
            continue
        if row in positive:
            y[row] = 1
        elif row not in ignored:
            y[row] = 0
    indices = np.flatnonzero(y >= 0)
    labels = y[indices]
    weights = np.where(labels == 1, pos_weights[indices], 1.0).astype(np.float32)
    sums = defaultdict(float)
    for local, row in enumerate(indices):
        sums[keys[row][0]] += float(weights[local])
    nonzero = [value for value in sums.values() if value > 0]
    target_total = float(np.mean(nonzero)) if nonzero else 1.0
    for local, row in enumerate(indices):
        weights[local] *= target_total / max(1e-9, sums[keys[row][0]])
    return {
        "indices": indices,
        "labels": labels,
        "weights": weights,
        "positive": int(np.count_nonzero(labels == 1)),
        "negative": int(np.count_nonzero(labels == 0)),
        "ignored": int(np.count_nonzero(y < 0)),
        "omitted_gold_edges": omitted,
        "episode_weight_totals_before_normalization": dict(sums),
    }


class EdgeHead:
    def __init__(self):
        self.scaler = StandardScaler()
        self.model = LogisticRegression(C=0.1, solver="liblinear", class_weight="balanced", max_iter=250, random_state=27)

    def transform(self, features, fit=False):
        scalar = features[:, :SCALAR_COUNT]
        embedding_features = features[:, SCALAR_COUNT:]
        scalar = self.scaler.fit_transform(scalar) if fit else self.scaler.transform(scalar)
        return np.concatenate((scalar, embedding_features), axis=1)

    def fit(self, features, labels, weights):
        if len(np.unique(labels)) != 2:
            raise ValueError("Boundary head training rows must contain positive and negative examples")
        self.model.fit(self.transform(features, fit=True), labels, sample_weight=weights)
        return self

    def evidence(self, features):
        if not len(features):
            return np.zeros(0, np.float32)
        return np.clip(self.model.decision_function(self.transform(features)), -4.0, 4.0).astype(np.float32)


def fit_heads(tables, references, episodes):
    heads, training = {}, {}
    for edge in ("start", "end"):
        target = build_targets(tables, references, episodes, edge)
        idx = target["indices"]
        heads[edge] = EdgeHead().fit(tables[edge]["features"][idx], target["labels"], target["weights"])
        training[edge] = {key: value for key, value in target.items() if key != "indices"}
    return heads, training


def evidence_map(tables, heads):
    out = {}
    for edge in ("start", "end"):
        scores = heads[edge].evidence(tables[edge]["features"])
        for (episode, take, seg_i), score in zip(tables[edge]["keys"], scores):
            out.setdefault(episode, {}).setdefault(take, {"start": {}, "end": {}})[edge][seg_i] = float(score)
    return out


class BaseScoreCache:
    """Memoize deterministic TF-IDF models by exact training episode set."""

    def __init__(self, samples, labels):
        self.samples = samples
        self.labels = labels
        self.models = {}
        self.predictions = {}
        self.fit_count = 0
        self.prediction_count = 0
        self.cache_hits = 0
        self.producers = []

    def predict(self, training_episodes, prediction_episodes):
        train = tuple(sorted(training_episodes))
        predict = tuple(sorted(prediction_episodes))
        assert not set(train) & set(predict), f"Leakage: train={train}, predict={predict}"
        key = (train, predict)
        if key in self.predictions:
            self.cache_hits += 1
            return self.predictions[key]
        if train not in self.models:
            train_idx = detector.sample_indices(self.samples, train)
            model, vectorizer = detector.fit_model("tfidf", self.samples, self.labels, train_idx, None)
            self.models[train] = (model, vectorizer)
            self.fit_count += 1
        model, vectorizer = self.models[train]
        pred_idx = detector.sample_indices(self.samples, predict)
        scores = detector.predict_model("tfidf", model, vectorizer, self.samples, pred_idx, None)
        result = detector.probabilities_by_take(self.samples, pred_idx, scores)
        self.predictions[key] = result
        self.prediction_count += 1
        self.producers.append({"training_episodes": list(train), "prediction_episodes": list(predict)})
        return result


def merge_episode_maps(*maps):
    merged = {}
    for item in maps:
        for episode, takes in item.items():
            if episode in merged:
                raise AssertionError(f"Duplicate OOF episode: {episode}")
            merged[episode] = takes
    return merged


def decode_with_evidence(samples, probability_map, evidence, params, lambda_edge):
    predictions = defaultdict(dict)
    for episode, takes in probability_map.items():
        for take, vector in takes.items():
            indices = detector.sequence_indices(samples, episode, take)
            probs = [float(vector[samples[i].index]) if samples[i].index < len(vector) else 0.0 for i in indices]
            take_evidence = evidence.get(episode, {}).get(take, {})
            starts = [float(take_evidence.get("start", {}).get(samples[i].index, 0.0)) for i in indices]
            ends = [float(take_evidence.get("end", {}).get(samples[i].index, 0.0)) for i in indices]
            predictions[episode][take] = detector.decode_intervals(
                samples, indices, probs, params, start_evidence=starts, end_evidence=ends, lambda_edge=lambda_edge,
            )
    return predictions


def score_lambda(samples, probabilities, edge_scores, references, episodes, params, lambda_edge):
    predictions = decode_with_evidence(samples, probabilities, edge_scores, params, lambda_edge)
    subset_references = {episode: references.get(episode, {}) for episode in episodes}
    score = detector.score_corpus(predictions, subset_references, tolerance_seconds=10.0)
    f1 = 2 * score["precision"] * score["recall"] / max(1e-12, score["precision"] + score["recall"])
    return f1, float(score["precision"]), predictions, score


def choose_lambda(samples, probabilities, edge_scores, references, episodes, params):
    rows = []
    for value in LAMBDAS:
        f1, precision, predictions, score = score_lambda(samples, probabilities, edge_scores, references, episodes, params, value)
        rows.append({"lambda_edge": value, "f1": f1, "precision": precision, "recall": float(score["recall"]), "tp": score["tp"], "fp": score["fp"], "fn": score["fn"]})
    selected = max(rows, key=lambda row: (row["f1"], row["precision"], -row["lambda_edge"]))
    return float(selected["lambda_edge"]), rows


def validation_group_predictions(cache, samples, references, current_embeddings, training_episodes, validation_episodes):
    """Fit heads on A-only OOF scores and predict a disjoint validation group V."""
    A = sorted(set(training_episodes) - set(validation_episodes))
    V = sorted(set(validation_episodes))
    if not A or not V or set(A) & set(V):
        raise AssertionError("Invalid inner stacked split")
    base_v = cache.predict(A, V)
    A_parts = []
    oof_producers = []
    for subtrain, subval in grouped_episode_splits(A, folds=3):
        assert set(subtrain) | set(subval) == set(A)
        assert not set(subtrain) & set(subval)
        A_parts.append(cache.predict(subtrain, subval))
        oof_producers.append({"training_episodes": subtrain, "prediction_episodes": subval})
    base_a_oof = merge_episode_maps(*A_parts)
    assert set(base_a_oof) == set(A)
    A_tables = feature_tables(samples, A, base_a_oof, current_embeddings)
    A_heads, A_train_info = fit_heads(A_tables, references, A)
    V_tables = feature_tables(samples, V, base_v, current_embeddings)
    edge_v = evidence_map(V_tables, A_heads)
    return base_v, edge_v, {
        "head_train_episodes": A,
        "validation_episodes": V,
        "a_oof_producers": oof_producers,
        "validation_base_producer": {"training_episodes": A, "prediction_episodes": V},
        "start_training": A_train_info["start"],
        "end_training": A_train_info["end"],
    }


def _same_intervals(left, right, tolerance=1e-6):
    if len(left) != len(right):
        return False
    return all(abs(float(a["start"]) - float(b["start"])) <= tolerance and abs(float(a["end"]) - float(b["end"])) <= tolerance for a, b in zip(left, right))


def run_outer_fold(test_episode, episodes, samples, references, current_embeddings, baseline_data, cache):
    outer_train = [ep for ep in episodes if ep != test_episode]
    if test_episode in outer_train:
        raise AssertionError("Held-out episode leaked into training")
    baseline_fold = next(row for row in baseline_data["models"]["tfidf"]["folds"] if row["held_out_episode"] == test_episode)
    params = detector.DecodeParams(**baseline_fold["params"])
    inner_base_parts, inner_edge_parts, inner_metadata = [], [], []
    for A, V in grouped_episode_splits(outer_train, folds=3):
        base_v, edge_v, meta = validation_group_predictions(cache, samples, references, current_embeddings, A, V)
        inner_base_parts.append(base_v)
        inner_edge_parts.append(edge_v)
        inner_metadata.append(meta)
    outer_train_oof = merge_episode_maps(*inner_base_parts)
    outer_train_edges = merge_episode_maps(*inner_edge_parts)
    assert set(outer_train_oof) == set(outer_train)
    assert set(outer_train_edges) == set(outer_train)
    selected_lambda, lambda_rows = choose_lambda(samples, outer_train_oof, outer_train_edges, references, outer_train, params)

    train_tables = feature_tables(samples, outer_train, outer_train_oof, current_embeddings)
    final_heads, final_train_info = fit_heads(train_tables, references, outer_train)
    test_probabilities = cache.predict(outer_train, [test_episode])
    test_tables = feature_tables(samples, [test_episode], test_probabilities, current_embeddings)
    test_edges = evidence_map(test_tables, final_heads)
    prediction_by_lambda = {}
    for value in LAMBDAS:
        decoded = decode_with_evidence(samples, test_probabilities, test_edges, params, value)
        prediction_by_lambda[str(value)] = decoded.get(test_episode, {})
    baseline_intervals = baseline_data["models"]["tfidf"]["predictions"].get(test_episode, {})
    for take in set(baseline_intervals) | set(prediction_by_lambda["0.0"]):
        if not _same_intervals(baseline_intervals.get(take, []), prediction_by_lambda["0.0"].get(take, [])):
            raise AssertionError(f"lambda=0 failed baseline interval equality for {test_episode}/{take}")
    selected_predictions = {test_episode: prediction_by_lambda[str(selected_lambda)]}
    fold_score = detector.score_corpus(selected_predictions, {test_episode: references.get(test_episode, {})}, tolerance_seconds=10.0)
    return {
        "held_out_episode": test_episode,
        "outer_train_episodes": outer_train,
        "baseline_decoder_params": asdict(params),
        "inner_folds": inner_metadata,
        "lambda_scores_on_outer_training_episodes": lambda_rows,
        "selected_lambda_edge": selected_lambda,
        "final_head_training": final_train_info,
        "baseline_lambda_zero_equal": True,
        "test_score": fold_score,
        "predictions": selected_predictions,
        "lambda_predictions": prediction_by_lambda,
    }


def episode_totals(score):
    result = defaultdict(Counter)
    for row in score.get("takes", []):
        for key in ("tp", "fp", "fn"):
            result[row["episode"]][key] += int(row[key])
    return result


def _transcript_hash():
    digest = hashlib.sha256()
    for path in sorted(detector.TRANSCRIPTS.rglob("*.json")):
        digest.update(path.relative_to(detector.TRANSCRIPTS).as_posix().encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def report_text(payload):
    baseline = payload["baseline"]
    head_container = payload["boundary_head"]
    head = head_container["interval"]
    complete = payload["protocol"]["complete_outer_folds"]
    status = "complete 10-episode LOEO" if complete else "incomplete feasibility run"
    lines = [
        "# Fold-safe entry/exit boundary heads",
        "",
        f"Status: {status}; v3 is prediction-triggered and exploratory.  ",
        f"V3 SHA256: `{payload['protocol']['label_sha256']}`  ",
        f"Outer folds: {len(head_container['folds'])}/{len(payload['protocol']['episodes'])}; all take variants remain in their episode fold.",
        "",
        "## Strict 10-second interval results",
        "",
        "| Method | TP/FP/FN | Precision/Recall | Frame Precision/Recall/AP |",
        "|---|---:|---:|---:|",
    ]
    for name, data in (("TF-IDF baseline", baseline), ("TF-IDF + boundary heads", head)):
        strict, frame = data, data["frame"]
        lines.append(
            f"| {name} | {strict['tp']}/{strict['fp']}/{strict['fn']} | {strict['precision']:.3f}/{strict['recall']:.3f} | "
            f"{frame['precision']:.3f}/{frame['recall']:.3f}/{frame['average_precision']:.3f} |"
        )
    lines.extend(["", "## Per-episode paired results", "", "| Episode | Baseline TP/FP/FN, P/R | Boundary head TP/FP/FN, P/R | ΔTP |", "|---|---:|---:|---:|"])
    base_rows = baseline["per_episode"]
    head_rows = head["per_episode"]
    for episode in payload["protocol"]["evaluated_outer_episodes"]:
        b, h = base_rows.get(episode, {}), head_rows.get(episode, {})
        lines.append(
            f"| {episode} | {b.get('tp', 0)}/{b.get('fp', 0)}/{b.get('fn', 0)}, {b.get('precision', 0):.3f}/{b.get('recall', 0):.3f} | "
            f"{h.get('tp', 0)}/{h.get('fp', 0)}/{h.get('fn', 0)}, {h.get('precision', 0):.3f}/{h.get('recall', 0):.3f} | "
            f"{h.get('tp', 0) - b.get('tp', 0):+d} |"
        )
    lines.extend(["", "## Overlap and grouping diagnostics", ""])
    for name, data in (("Baseline", baseline), ("Boundary head", head)):
        d = data["overlap_diagnostics"]
        lines.append(
            f"- {name}: any-overlap recall {d['any_overlap']['recall']:.3f}; IoU≥0.5 recall {d['iou_at_least_0_5']['recall']:.3f}; "
            f"overlap-matched edge misses {d['overlap_matched_but_edge_missed']}; gold without overlap {d['gold_without_any_overlap']}; "
            f"split gold pods {d['gold_pods_overlapped_by_multiple_predictions']}; merged predictions {d['predictions_overlapping_multiple_gold_pods']}."
        )
    lines.extend(["", "## Fold choices and compute", ""])
    for fold in head_container["folds"]:
        lines.append(
            f"- {fold['held_out_episode']}: λ={fold['selected_lambda_edge']:.0f}; "
            f"inner λ scores={[(x['lambda_edge'], round(x['precision'], 3), round(x['recall'], 3)) for x in fold['lambda_scores_on_outer_training_episodes']]}; "
            f"λ=0 baseline intervals reproduced: {fold['baseline_lambda_zero_equal']}."
        )
    lines.append(
        f"- Base model fits: {payload['compute']['base_model_fits']} (cache hits {payload['compute']['score_cache_hits']}); "
        f"wall time {payload['compute']['wall_seconds']:.1f}s; contextual feature width {payload['compute']['feature_width']} "
        f"({SCALAR_COUNT} standardized scalars plus 768 normalized MiniLM dimensions)."
    )
    lines.extend([
        "",
        "## Method and limits",
        "",
        "Two C=0.1 logistic heads score candidate starts and ends from 15-second left/right context. The base TF-IDF probabilities and decoder settings match the frozen v3 baseline; only λ∈{0,1,2} was tuned on nested grouped validation. Boundary heads train only on inner-OOF base scores. Scalar scaling, edge targets, heads, and λ selection never see the outer held-out episode. The λ=0 check must exactly reproduce baseline intervals. Boundary-head inner folds balance episode counts; the frozen TF-IDF decoder tuning used the baseline's own grouped split protocol, which may balance segment counts differently.",
        "",
        "V3 was created after reviewing predictions from an earlier detector. These results are prediction-triggered and exploratory, not independent audio-grounded acceptance metrics. No audio refinement was run.",
        "",
    ])
    return "\n".join(lines)


def main():
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--outer-limit", type=int, help="run only the first N outer folds as an explicitly incomplete feasibility check")
    parser.add_argument("--out", type=Path, default=OUTPUT)
    args = parser.parse_args()
    start_time = time.perf_counter()
    if sha256(LABEL_FILE) != LABEL_SHA256:
        raise RuntimeError("Frozen v3 label snapshot hash mismatch")
    labels = json.loads(LABEL_FILE.read_text(encoding="utf-8"))
    references, provenance, boundary_support, _ = detector.parse_references(labels, LABEL_FILE.name)
    samples, transcripts, loaded_refs, _, _, transcript_meta, label_source = detector.load_corpus("auto", LABEL_FILE)
    if loaded_refs != references:
        raise AssertionError("Boundary head and baseline label parsing differ")
    episodes = sorted(transcripts)
    y = detector.build_labels(samples, references)
    embeddings = detector.cache_embeddings(samples)[:, :384]
    baseline_data = json.loads(BASELINE_FILE.read_text(encoding="utf-8"))
    if baseline_data["protocol"].get("label_sha256") != LABEL_SHA256:
        raise RuntimeError("Frozen baseline was produced from a different label snapshot")
    cache = BaseScoreCache(samples, y)
    selected = episodes[: args.outer_limit] if args.outer_limit else episodes
    folds = [run_outer_fold(ep, episodes, samples, references, embeddings, baseline_data, cache) for ep in selected]
    boundary_predictions = merge_episode_maps(*(fold["predictions"] for fold in folds))
    fold_episodes = [fold["held_out_episode"] for fold in folds]
    refs = {ep: references.get(ep, {}) for ep in fold_episodes}
    baseline_predictions = {ep: baseline_data["models"]["tfidf"]["predictions"].get(ep, {}) for ep in fold_episodes}
    baseline_score = detector.score_corpus(baseline_predictions, refs, tolerance_seconds=10.0)
    boundary_score = detector.score_corpus(boundary_predictions, refs, tolerance_seconds=10.0)
    baseline_score["per_episode"] = detector.per_episode_intervals(baseline_score)
    boundary_score["per_episode"] = detector.per_episode_intervals(boundary_score)
    baseline_probabilities = {ep: baseline_data["models"]["tfidf"]["oof_probabilities"].get(ep, {}) for ep in fold_episodes} if "oof_probabilities" in baseline_data["models"]["tfidf"] else {}
    # Frame scores use the frozen base OOF probability outputs from the same baseline evaluation.
    if not baseline_probabilities:
        baseline_probabilities = {ep: {} for ep in fold_episodes}
    current_outer_probs = {}
    for fold in folds:
        ep = fold["held_out_episode"]
        current_outer_probs.update(cache.predict(fold["outer_train_episodes"], [ep]))
    baseline_score["frame"] = baseline_data["models"]["tfidf"]["frame"] if len(selected) == len(episodes) else None
    boundary_score["frame"] = detector.frame_metrics(samples, current_outer_probs, boundary_predictions, refs, fold_episodes, transcript_meta)
    baseline_score["overlap_diagnostics"] = diagnostics.interval_stats(baseline_predictions, refs)
    boundary_score["overlap_diagnostics"] = diagnostics.interval_stats(boundary_predictions, refs)
    if len(selected) != len(episodes):
        # Recompute baseline frame metrics for the same partial fold slice using cached base outputs.
        baseline_score["frame"] = detector.frame_metrics(samples, current_outer_probs, baseline_predictions, refs, fold_episodes, transcript_meta)
    paired = {}
    for ep in fold_episodes:
        b = baseline_score["per_episode"].get(ep, {})
        h = boundary_score["per_episode"].get(ep, {})
        paired[ep] = {"baseline": b, "boundary_head": h, "tp_delta": int(h.get("tp", 0) - b.get("tp", 0))}
    payload = {
        "protocol": {
            "label_source": label_source,
            "label_sha256": LABEL_SHA256,
            "transcript_sha256": _transcript_hash(),
            "code_sha256": {name: sha256(HERE / name) for name in ("detector.py", "boundary_head.py", "diagnostics.py")},
            "split": "nested grouped LOEO; all takes for an episode remain together",
            "episodes": episodes,
            "evaluated_outer_episodes": fold_episodes,
            "complete_outer_folds": len(selected) == len(episodes),
            "v3_label_note": "prediction-triggered audit labels; exploratory, not independent ground truth",
            "boundary_targets": "nearest candidate edge within 10s; other candidates within 10s ignored; omitted references stay in evaluation",
            "audio_refinement": "not run",
        },
        "baseline": baseline_score,
        "boundary_head": {
            "interval": boundary_score,
            "paired_episode_outcomes": paired,
            "folds": folds,
            "provenance": detector.provenance_breakdown(boundary_predictions, refs, provenance, fold_episodes),
            "boundary_support": detector.boundary_support_breakdown(boundary_predictions, refs, boundary_support, fold_episodes),
        },
        "compute": {
            "base_model_fits": cache.fit_count,
            "score_cache_hits": cache.cache_hits,
            "prediction_sets": cache.prediction_count,
            "feature_width": SCALAR_COUNT + 768,
            "wall_seconds": round(time.perf_counter() - start_time, 3),
            "base_score_producers": cache.producers,
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(json_safe(payload), indent=2), encoding="utf-8")
    report_path = args.out.with_name(args.out.stem + "_REPORT.md")
    report_path.write_text(report_text(payload), encoding="utf-8")
    print(f"Saved {args.out} and {report_path}; outer folds {len(selected)}/{len(episodes)}; fits={cache.fit_count}; wall={payload['compute']['wall_seconds']:.1f}s")


if __name__ == "__main__":
    main()

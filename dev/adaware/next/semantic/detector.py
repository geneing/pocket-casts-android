"""Transcript-context ad-pod tagging experiment.

Inference receives timestamped transcript segments only. It never reads sibling
takes or uses reference-difference candidates to propose intervals.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import time
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score
from sklearn.model_selection import GroupKFold
from sklearn.pipeline import FeatureUnion

HERE = Path(__file__).resolve().parent
ADWARE = HERE.parents[1]
NEXT = HERE.parent
DATA = ADWARE / "data"
TRANSCRIPTS = DATA / "transcripts"
LABELS = DATA / "labels"
CACHE = HERE / ".cache"
OUTPUT = HERE / "results"
sys.path.insert(0, str(NEXT / "eval"))
from score import score_corpus  # noqa: E402

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
MIN_POD_SECONDS = 8.0
MAX_POD_SECONDS = 300.0


@dataclass(frozen=True)
class Sample:
    episode: str
    take: str
    index: int
    start: float
    end: float
    text: str
    document: str
    current_text: str
    context_text: str


@dataclass(frozen=True)
class DecodeParams:
    threshold: float = 0.5
    boundary_penalty: float = 3.0
    duration_weight: float = 0.35
    merge_gap: float = 5.0


def normalized_text(text: str) -> str:
    return " ".join(text.casefold().split())


def transcript_document(segments: list[dict[str, Any]], i: int) -> tuple[str, str, str]:
    current = normalized_text(str(segments[i].get("text", "")))
    previous = normalized_text(str(segments[i - 1].get("text", ""))) if i else ""
    following = normalized_text(str(segments[i + 1].get("text", ""))) if i + 1 < len(segments) else ""
    context = f"previous: {previous} current: {current} next: {following}"
    document = f"{current} {current} {previous} {following}"
    return current, context, document


def parse_references(payload: dict[str, Any], filename: str):
    references: dict[str, dict[str, list[dict[str, Any]]]] = {}
    provenance: dict[str, dict[str, list[str]]] = {}
    boundary_support: dict[str, dict[str, list[str]]] = {}
    source = filename if filename.startswith("ads_v") else "ads.json (legacy transcript-difference labels)"
    is_v2 = filename.startswith("ads_v")
    for episode, episode_data in payload.items():
        if not isinstance(episode_data, dict):
            continue
        takes: dict[str, list[dict[str, Any]]] = defaultdict(list)
        prov: dict[str, list[str]] = defaultdict(list)
        bounds: dict[str, list[str]] = defaultdict(list)
        for section in ("dynamic_slots", "baked_candidates"):
            section_data = episode_data.get(section, {})
            if isinstance(section_data, dict):
                values = [(str(take), items) for take, items in section_data.items()]
            elif isinstance(section_data, list):
                values = [(str(item["take"]), [item]) for item in section_data if isinstance(item, dict) and "take" in item]
            else:
                values = []
            for take, items in values:
                if isinstance(items, dict):
                    items = [items]
                for item in items or []:
                    if not isinstance(item, dict) or "start" not in item or "end" not in item:
                        continue
                    start, end = float(item["start"]), float(item["end"])
                    if not (0 <= start < end):
                        continue
                    record = {"start": start, "end": end, "text": str(item.get("text", ""))}
                    if "uncertainty_seconds" in item:
                        record["uncertainty_seconds"] = float(item["uncertainty_seconds"])
                    takes[take].append(record)
                    prov[take].append(provenance_for(item, section, is_v2))
                    bounds[take].append(boundary_support_for(item))
        for take in takes:
            ordered = sorted(zip(takes[take], prov[take], bounds[take]), key=lambda pair: pair[0]["start"])
            takes[take] = [x[0] for x in ordered]
            prov[take] = [x[1] for x in ordered]
            bounds[take] = [x[2] for x in ordered]
        references[episode] = dict(takes)
        provenance[episode] = dict(prov)
        boundary_support[episode] = dict(bounds)
    return references, provenance, boundary_support, source


def provenance_for(item: dict[str, Any], section: str, is_v2: bool) -> str:
    raw = " ".join(str(item.get(key, "")) for key in ("provenance", "source", "label_source", "method", "kind")).casefold()
    classification = str(item.get("classification", "")).casefold()
    if any(term in raw for term in ("audio_difference", "audio-difference", "audio_alignment", "waveform", "cross-correlation")):
        return "audio-difference"
    if any(term in raw for term in ("semantic", "baked", "candidate", "transcript", "cross_take")) or any(
        term in classification for term in ("semantic", "candidate")
    ):
        return "semantic-only"
    if section == "baked_candidates":
        return "semantic-only"
    return "unknown-v2-provenance" if is_v2 else "legacy-transcript-difference"


def boundary_support_for(item: dict[str, Any]) -> str:
    basis = str(item.get("boundary_basis", "")).casefold()
    if any(term in basis for term in ("audio", "align", "waveform", "byte")):
        return "localized-audio-supported"
    if basis:
        return "transcript-boundary"
    return "unspecified"


def load_corpus(label_mode: str = "auto", label_file: str | Path | None = None):
    samples: list[Sample] = []
    transcripts: dict[str, dict[str, list[dict[str, Any]]]] = {}
    transcript_meta: dict[str, dict[str, dict[str, Any]]] = {}
    for episode_dir in sorted(TRANSCRIPTS.iterdir()):
        if not episode_dir.is_dir():
            continue
        ep_takes: dict[str, list[dict[str, Any]]] = {}
        ep_meta: dict[str, dict[str, Any]] = {}
        for path in sorted(episode_dir.glob("take*.json")):
            payload = json.loads(path.read_text(encoding="utf-8"))
            segments = payload.get("segments", [])
            take = path.stem.removeprefix("take")
            ep_takes[take] = segments
            ep_meta[take] = {"file": str(payload.get("file", "")), "duration": float(payload.get("duration", 0.0))}
            for i, segment in enumerate(segments):
                start, end = float(segment.get("start", 0.0)), float(segment.get("end", 0.0))
                text = normalized_text(str(segment.get("text", "")))
                if end <= start or not text:
                    continue
                current, context, document = transcript_document(segments, i)
                samples.append(Sample(episode_dir.name, take, i, start, end, text, document, current, context))
        transcripts[episode_dir.name] = ep_takes
        transcript_meta[episode_dir.name] = ep_meta
    v2_path, legacy_path = NEXT / "labels" / "ads_v2.json", LABELS / "ads.json"
    if label_mode not in {"auto", "legacy", "v2"}:
        raise ValueError(f"Unknown label mode: {label_mode}")
    if label_file is not None:
        label_path = Path(label_file)
        if not label_path.is_absolute():
            label_path = Path.cwd() / label_path
    else:
        label_path = v2_path if label_mode == "v2" else legacy_path if label_mode == "legacy" else (v2_path if v2_path.exists() else legacy_path)
    if not label_path.exists():
        raise FileNotFoundError(f"No labels at {v2_path} or {legacy_path}")
    references, provenance, boundary_support, source = parse_references(json.loads(label_path.read_text(encoding="utf-8")), label_path.name)
    try:
        source = label_path.relative_to(ADWARE.parents[1]).as_posix()
    except ValueError:
        source = str(label_path)
    return samples, transcripts, references, provenance, boundary_support, transcript_meta, source


def build_labels(samples: list[Sample], references: dict[str, dict[str, list[dict[str, Any]]]]) -> np.ndarray:
    by_take: dict[tuple[str, str], list[tuple[float, float]]] = defaultdict(list)
    for ep, takes in references.items():
        for take, intervals in takes.items():
            by_take[(ep, take)] = [(float(x["start"]), float(x["end"])) for x in intervals]
    return np.asarray(
        [int(any(a <= (s.start + s.end) / 2 <= b for a, b in by_take[(s.episode, s.take)])) for s in samples],
        dtype=np.int8,
    )


def cache_embeddings(samples: list[Sample], force: bool = False) -> np.ndarray:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / "minilm_context_embeddings.npz"
    texts = [text for sample in samples for text in (sample.current_text, sample.context_text)]
    text_ids = {text: i for i, text in enumerate(dict.fromkeys(texts))}
    unique_texts = list(text_ids)
    signature_hash = hashlib.sha256("\0".join(texts).encode("utf-8")).hexdigest()
    signature = f"{MODEL_NAME}|{len(samples)}|{signature_hash}"
    if path.exists() and not force:
        cache = np.load(path, allow_pickle=False)
        if str(cache["signature"].item()) == signature and len(cache["vectors"]) == len(samples):
            return cache["vectors"].astype(np.float32, copy=False)
    from fastembed import TextEmbedding

    encoder = TextEmbedding(model_name=MODEL_NAME, cache_dir=str(CACHE / "models"), threads=4, providers=["CPUExecutionProvider"])
    began = time.perf_counter()
    unique_vectors = []
    for start in range(0, len(unique_texts), 512):
        batch = unique_texts[start : start + 512]
        unique_vectors.extend(encoder.embed(batch, batch_size=128))
        completed = min(start + len(batch), len(unique_texts))
        if completed % 2048 < 512 or completed == len(unique_texts):
            print(f"Embedded {completed:,}/{len(unique_texts):,} unique sentences", flush=True)
    encoded = np.asarray(unique_vectors, dtype=np.float32)
    sample_vectors = np.asarray([encoded[text_ids[text]] for text in texts], dtype=np.float32)
    vectors = np.concatenate((sample_vectors[0::2], sample_vectors[1::2]), axis=1)
    np.savez_compressed(path, signature=signature, vectors=vectors)
    print(f"Encoded {len(samples):,} transcript segments as {len(unique_texts):,} unique MiniLM embeddings in {time.perf_counter() - began:.1f}s")
    return vectors


def sample_indices(samples: list[Sample], episodes: Iterable[str]) -> np.ndarray:
    wanted = set(episodes)
    return np.asarray([i for i, sample in enumerate(samples) if sample.episode in wanted], dtype=int)


def fit_model(kind: str, samples: list[Sample], y: np.ndarray, train_idx: np.ndarray, embeddings: np.ndarray | None):
    if len(np.unique(y[train_idx])) < 2:
        raise ValueError("Training fold has only one class")
    model = LogisticRegression(C=0.5 if kind == "semantic" else 1.0, class_weight="balanced", max_iter=250, solver="liblinear", random_state=27)
    if kind == "semantic":
        assert embeddings is not None
        model.fit(embeddings[train_idx], y[train_idx])
        model.positive_prior_ = float(y[train_idx].mean())
        return model, None
    if kind != "tfidf":
        raise ValueError(f"Unknown model {kind}")
    vectorizer = FeatureUnion([
        ("word", TfidfVectorizer(analyzer="word", ngram_range=(1, 2), min_df=2, max_features=40_000, sublinear_tf=True, strip_accents="unicode")),
        ("char", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=2, max_features=20_000, sublinear_tf=True, strip_accents="unicode")),
    ])
    x_train = vectorizer.fit_transform([samples[i].document for i in train_idx])
    model.fit(x_train, y[train_idx])
    model.positive_prior_ = float(y[train_idx].mean())
    return model, vectorizer


def predict_model(kind: str, model, vectorizer, samples: list[Sample], indices: np.ndarray, embeddings: np.ndarray | None) -> np.ndarray:
    if kind == "semantic":
        assert embeddings is not None
        raw = model.predict_proba(embeddings[indices])[:, 1]
    else:
        raw = model.predict_proba(vectorizer.transform([samples[i].document for i in indices]))[:, 1]
    # Balanced class weights imply a 50/50 training prior; restore the observed
    # training-fold prevalence before applying a calibrated decoder threshold.
    prior = min(0.99, max(0.01, model.positive_prior_))
    clipped = np.clip(raw, 1e-6, 1 - 1e-6)
    logits = np.log(clipped / (1 - clipped)) + math.log(prior / (1.0 - prior))
    return 1.0 / (1.0 + np.exp(-logits))


def grouped_predictions(kind: str, samples: list[Sample], y: np.ndarray, episodes: list[str], embeddings: np.ndarray | None):
    if len(episodes) < 2:
        return {}
    all_idx = sample_indices(samples, episodes)
    groups = np.asarray([samples[i].episode for i in all_idx])
    out: dict[str, dict[str, list[float]]] = defaultdict(dict)
    splitter = GroupKFold(n_splits=min(3, len(episodes)))
    for train_rel, val_rel in splitter.split(all_idx, y[all_idx], groups=groups):
        train_idx, val_idx = all_idx[train_rel], all_idx[val_rel]
        model, vectorizer = fit_model(kind, samples, y, train_idx, embeddings)
        probs = predict_model(kind, model, vectorizer, samples, val_idx, embeddings)
        for idx, prob in zip(val_idx, probs):
            sample = samples[int(idx)]
            vector = out[sample.episode].setdefault(sample.take, [])
            if len(vector) <= sample.index:
                vector.extend([0.0] * (sample.index + 1 - len(vector)))
            vector[sample.index] = float(prob)
    return out


def sequence_indices(samples: list[Sample], episode: str, take: str) -> list[int]:
    return [i for i, sample in enumerate(samples) if sample.episode == episode and sample.take == take]


def merge_intervals(intervals: list[dict[str, float]], gap: float) -> list[dict[str, float]]:
    if not intervals:
        return []
    out = [dict(intervals[0])]
    for current in intervals[1:]:
        if current["start"] <= out[-1]["end"] + gap:
            out[-1]["end"] = max(out[-1]["end"], current["end"])
        else:
            out.append(dict(current))
    return out


def decode_intervals(
    samples: list[Sample],
    indices: list[int],
    probabilities: list[float],
    params: DecodeParams,
    start_evidence: list[float] | None = None,
    end_evidence: list[float] | None = None,
    lambda_edge: float = 0.0,
) -> list[dict[str, float]]:
    """Semi-Markov decoder selects non-overlapping, duration-scored ad pods."""
    if not indices:
        return []
    order = sorted(range(len(indices)), key=lambda k: samples[indices[k]].start)
    seq = [samples[indices[k]] for k in order]
    probs = np.asarray([probabilities[k] for k in order], dtype=np.float64)
    start_scores = np.zeros(len(order), dtype=np.float64) if start_evidence is None else np.asarray([start_evidence[k] for k in order], dtype=np.float64)
    end_scores = np.zeros(len(order), dtype=np.float64) if end_evidence is None else np.asarray([end_evidence[k] for k in order], dtype=np.float64)

    def logit(p):
        p = min(0.99999, max(0.00001, float(p)))
        return math.log(p / (1.0 - p))

    emissions = np.asarray([logit(p) - logit(params.threshold) for p in probs])
    durations = np.asarray([max(0.0, s.end - s.start) for s in seq])
    emissions *= np.clip(durations / 3.5, 0.5, 1.5)
    prefix = np.concatenate(([0.0], np.cumsum(emissions)))
    starts = np.asarray([s.start for s in seq])
    ends = np.asarray([s.end for s in seq])
    n = len(seq)
    hard_breaks = np.zeros(n, dtype=int)
    if n > 1:
        gap_starts = np.where(starts[1:] - ends[:-1] > 12.0, np.arange(1, n), 0)
        hard_breaks[1:] = np.maximum.accumulate(gap_starts)
    best = np.zeros(n + 1, dtype=np.float64)
    back: list[tuple[int, int] | None] = [None] * (n + 1)
    for end_i in range(1, n + 1):
        best[end_i] = best[end_i - 1]
        end_time = ends[end_i - 1]
        lo = int(np.searchsorted(starts[:end_i], end_time - MAX_POD_SECONDS, side="left"))
        hi = int(np.searchsorted(starts[:end_i], end_time - MIN_POD_SECONDS, side="right"))
        # A large transcript gap is a hard state boundary (music/silence).
        lo = max(lo, int(hard_breaks[end_i - 1]))
        if hi <= lo:
            continue
        run_starts = np.arange(lo, hi, dtype=int)
        pod_dur = end_time - starts[run_starts]
        valid = (pod_dur >= MIN_POD_SECONDS) & (pod_dur <= MAX_POD_SECONDS)
        run_starts, pod_dur = run_starts[valid], pod_dur[valid]
        if not len(run_starts):
            continue
        z = np.log(np.maximum(1.0, pod_dur) / 45.0)
        scores = (
            best[run_starts]
            + prefix[end_i]
            - prefix[run_starts]
            - params.boundary_penalty
            - params.duration_weight * 0.5 * z * z
            + lambda_edge * (start_scores[run_starts] + end_scores[end_i - 1])
        )
        winner = int(np.argmax(scores))
        if scores[winner] > best[end_i - 1] and scores[winner] > 0.0:
            best[end_i] = scores[winner]
            back[end_i] = (int(run_starts[winner]), end_i)
    chosen: list[tuple[int, int]] = []
    cursor = n
    while cursor:
        edge = back[cursor]
        if edge is None:
            cursor -= 1
        else:
            chosen.append(edge)
            cursor = edge[0]
    chosen.reverse()
    intervals = [{"start": float(starts[a]), "end": float(ends[b - 1])} for a, b in chosen]
    return merge_intervals(intervals, params.merge_gap)


def decode_corpus(samples: list[Sample], prob_map, params: DecodeParams):
    predictions: dict[str, dict[str, list[dict[str, float]]]] = defaultdict(dict)
    for ep, takes in prob_map.items():
        for take, vector in takes.items():
            indices = sequence_indices(samples, ep, take)
            probabilities = [float(vector[samples[i].index]) if samples[i].index < len(vector) else 0.0 for i in indices]
            predictions[ep][take] = decode_intervals(samples, indices, probabilities, params)
    return predictions


def tune_decoder(samples: list[Sample], val_probs, references, validation_episodes: list[str]):
    grid = [
        DecodeParams(threshold=t, boundary_penalty=b, duration_weight=d, merge_gap=m)
        for t in (0.05, 0.12, 0.25, 0.45)
        for b in (2.0, 5.0, 8.0)
        for d in (0.0, 0.75)
        for m in (0.0, 6.0)
    ]
    val_prob_subset = {ep: val_probs.get(ep, {}) for ep in validation_episodes}
    val_refs = {ep: references.get(ep, {}) for ep in validation_episodes}
    best_params, best_key = grid[0], (-1.0, -1.0)
    for params in grid:
        decoded = decode_corpus(samples, val_prob_subset, params)
        score = score_corpus(decoded, val_refs)
        f1 = 2 * score["precision"] * score["recall"] / max(1e-12, score["precision"] + score["recall"])
        key = (float(f1), float(score["precision"]))
        if key > best_key:
            best_params, best_key = params, key
    return best_params, {"inner_f1": best_key[0], "inner_precision": best_key[1]}


def probabilities_by_take(samples: list[Sample], indices: np.ndarray, probabilities: np.ndarray):
    result: dict[str, dict[str, list[float]]] = defaultdict(dict)
    for i, probability in zip(indices, probabilities):
        sample = samples[int(i)]
        vector = result[sample.episode].setdefault(sample.take, [])
        if len(vector) <= sample.index:
            vector.extend([0.0] * (sample.index + 1 - len(vector)))
        vector[sample.index] = float(probability)
    return result


def evaluate_model(kind: str, samples: list[Sample], y: np.ndarray, episodes: list[str], references, embeddings, transcript_meta):
    oof = np.zeros(len(samples), dtype=np.float32)
    folds = []
    for test_episode in episodes:
        train_episodes = [episode for episode in episodes if episode != test_episode]
        if len(train_episodes) < 2:
            continue
        inner = grouped_predictions(kind, samples, y, train_episodes, embeddings)
        params, tuned = tune_decoder(samples, inner, references, train_episodes)
        train_idx, test_idx = sample_indices(samples, train_episodes), sample_indices(samples, [test_episode])
        model, vectorizer = fit_model(kind, samples, y, train_idx, embeddings)
        probs = predict_model(kind, model, vectorizer, samples, test_idx, embeddings)
        oof[test_idx] = probs
        pred_map = probabilities_by_take(samples, test_idx, probs)
        decoded = decode_corpus(samples, pred_map, params)
        folds.append({"held_out_episode": test_episode, "params": asdict(params), **tuned})
        print(f"{kind}: held out {test_episode}; decoder={asdict(params)}")
    oof_map = probabilities_by_take(samples, np.arange(len(samples)), oof)
    selected_params = {fold["held_out_episode"]: DecodeParams(**fold["params"]) for fold in folds}
    predictions: dict[str, dict[str, list[dict[str, float]]]] = defaultdict(dict)
    for ep in episodes:
        if ep not in selected_params:
            continue
        for take, vector in oof_map.get(ep, {}).items():
            indices = sequence_indices(samples, ep, take)
            probs = [float(vector[samples[i].index]) if samples[i].index < len(vector) else 0.0 for i in indices]
            predictions[ep][take] = decode_intervals(samples, indices, probs, selected_params[ep])
    strict = score_corpus(predictions, references, tolerance_seconds=10.0)
    return {
        "interval": strict,
        "frame": frame_metrics(samples, oof_map, predictions, references, episodes, transcript_meta),
        "predictions": predictions,
        "oof_probabilities": oof_map,
        "folds": folds,
    }


def frame_metrics(samples, prob_map, predictions, references, episodes, transcript_meta):
    tp = fp = fn = tn = 0
    true_frames: list[bool] = []
    score_frames: list[float] = []
    for episode in episodes:
        takes = set(prob_map.get(episode, {})) | set(references.get(episode, {}))
        for take in takes:
            indices = sequence_indices(samples, episode, take)
            if not indices:
                continue
            media_duration = transcript_meta.get(episode, {}).get(take, {}).get("duration", 0.0)
            n_frames = int(math.ceil(max(float(media_duration), max(samples[i].end for i in indices))))
            gold_mask = np.zeros(n_frames, dtype=bool)
            prob_frame = np.zeros(n_frames, dtype=np.float32)
            pred_mask = np.zeros(n_frames, dtype=bool)
            for interval in references.get(episode, {}).get(take, []):
                lo = max(0, int(interval["start"]))
                hi = min(n_frames, int(math.ceil(interval["end"])))
                gold_mask[lo:hi] = True
            vector = prob_map.get(episode, {}).get(take, [])
            for i in indices:
                sample = samples[i]
                if sample.index >= len(vector):
                    continue
                lo = max(0, int(sample.start))
                hi = min(n_frames, int(math.ceil(sample.end)))
                prob_frame[lo:hi] = np.maximum(prob_frame[lo:hi], vector[sample.index])
            for interval in predictions.get(episode, {}).get(take, []):
                lo = max(0, int(interval["start"]))
                hi = min(n_frames, int(math.ceil(interval["end"])))
                pred_mask[lo:hi] = True
            tp += int(np.count_nonzero(pred_mask & gold_mask))
            fp += int(np.count_nonzero(pred_mask & ~gold_mask))
            fn += int(np.count_nonzero(~pred_mask & gold_mask))
            tn += int(np.count_nonzero(~pred_mask & ~gold_mask))
            true_frames.extend(gold_mask.tolist())
            score_frames.extend(prob_frame.tolist())
    precision = tp / (tp + fp) if tp + fp else (1.0 if not fn else 0.0)
    recall = tp / (tp + fn) if tp + fn else 1.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    ap = average_precision_score(true_frames, score_frames) if any(true_frames) else 0.0
    return {
        "frame_seconds": 1,
        "tp_seconds": tp,
        "fp_seconds": fp,
        "fn_seconds": fn,
        "tn_seconds": tn,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "average_precision": float(ap),
    }


def reference_group_breakdown(predictions, references, groups, episodes):
    """Attribute matched references by metadata; unmatched predictions stay separate."""
    rows: dict[str, Counter] = defaultdict(Counter)
    for ep in episodes:
        for take in sorted(set(predictions.get(ep, {})) | set(references.get(ep, {}))):
            predicted = predictions.get(ep, {}).get(take, [])
            gold = references.get(ep, {}).get(take, [])
            sources = groups.get(ep, {}).get(take, [])
            edges = [
                [j for j, interval in enumerate(gold)
                 if abs(p["start"] - interval["start"]) <= 10.0 and abs(p["end"] - interval["end"]) <= 10.0]
                for p in predicted
            ]
            owners: dict[int, int] = {}

            def augment(i: int, seen: set[int]) -> bool:
                for j in edges[i]:
                    if j in seen:
                        continue
                    seen.add(j)
                    if j not in owners or augment(owners[j], seen):
                        owners[j] = i
                        return True
                return False

            for i in range(len(predicted)):
                augment(i, set())
            matched_refs, matched_predictions = set(owners), set(owners.values())
            for j in range(len(gold)):
                key = sources[j] if j < len(sources) else "unknown"
                rows[key]["gold"] += 1
                rows[key]["tp" if j in matched_refs else "fn"] += 1
            rows["unmatched_predictions"]["fp"] += len(predicted) - len(matched_predictions)
    return {
        key: {
            "gold": counts["gold"],
            "tp": counts["tp"],
            "fp": counts["fp"],
            "fn": counts["fn"],
            "reference_recall": counts["tp"] / counts["gold"] if counts["gold"] else None,
        }
        for key, counts in sorted(rows.items())
    }


def provenance_breakdown(predictions, references, provenance, episodes):
    """Attribute correctly matched references by ad identity evidence."""
    return reference_group_breakdown(predictions, references, provenance, episodes)


def boundary_support_breakdown(predictions, references, boundary_support, episodes):
    """Report edge evidence separately from semantic ad identity provenance."""
    return reference_group_breakdown(predictions, references, boundary_support, episodes)


def per_episode_intervals(score: dict[str, Any]) -> dict[str, dict[str, float | int]]:
    totals: dict[str, Counter] = defaultdict(Counter)
    for row in score.get("takes", []):
        for key in ("tp", "fp", "fn"):
            totals[row["episode"]][key] += int(row[key])
    result = {}
    for episode, counts in sorted(totals.items()):
        tp, fp, fn = counts["tp"], counts["fp"], counts["fn"]
        result[episode] = {
            "tp": tp,
            "fp": fp,
            "fn": fn,
            "precision": tp / (tp + fp) if tp + fp else (1.0 if not fn else 0.0),
            "recall": tp / (tp + fn) if tp + fn else 1.0,
        }
    return result


def ffmpeg_binary(explicit: str | None = None) -> str:
    return explicit or os.environ.get("FFMPEG_PATH") or "ffmpeg"


def load_audio_window(audio_path: Path, start: float, end: float, ffmpeg_path: str | None = None) -> np.ndarray:
    duration = max(0.0, end - start)
    if not duration:
        return np.zeros(0, dtype=np.float32)
    result = subprocess.run(
        [ffmpeg_binary(ffmpeg_path), "-v", "error", "-ss", f"{start:.3f}", "-i", str(audio_path), "-t", f"{duration:.3f}", "-ac", "1", "-ar", "16000", "-f", "f32le", "-"],
        check=True,
        capture_output=True,
    )
    return np.frombuffer(result.stdout, dtype=np.float32)


def rms_envelope(audio: np.ndarray, window_seconds: float = 0.1, sample_rate: int = 16000, offset: float = 0.0):
    size = int(window_seconds * sample_rate)
    usable = len(audio) // size * size
    if usable == 0:
        return np.zeros(0), np.zeros(0)
    frames = audio[:usable].reshape(-1, size)
    rms = np.sqrt(np.mean(np.square(frames, dtype=np.float64), axis=1) + 1e-12)
    return offset + (np.arange(len(rms)) + 0.5) * window_seconds, rms


def refine_with_silence(intervals: list[dict[str, float]], audio_path: Path, max_shift: float = 8.0, ffmpeg_path: str | None = None):
    """Snap predicted edges to a nearby quiet window; never proposes an ad pod."""
    if not audio_path.exists():
        return intervals, {"audio_available": False, "snapped_edges": 0, "decoded_seconds": 0.0}
    requests = []
    for index, interval in enumerate(intervals):
        requests.append((max(0.0, interval["start"] - max_shift), interval["start"] + 1.5, index, "start"))
        requests.append((max(0.0, interval["end"] - 1.5), interval["end"] + max_shift, index, "end"))
    windows = []
    for low, high, index, side in sorted(requests):
        if windows and low <= windows[-1][1] + 1.0:
            windows[-1][1] = max(windows[-1][1], high)
            windows[-1][2].append((index, side, low, high))
        else:
            windows.append([low, high, [(index, side, low, high)]])
    time_parts, rms_parts = [], []
    decoded_seconds = 0.0
    for low, high, _members in windows:
        audio = load_audio_window(audio_path, low, high, ffmpeg_path)
        decoded_seconds += len(audio) / 16000.0
        times, rms = rms_envelope(audio, offset=low)
        if len(times):
            time_parts.append(times)
            rms_parts.append(rms)
    if not time_parts:
        return intervals, {"audio_available": True, "snapped_edges": 0, "decoded_seconds": decoded_seconds}
    times, rms = np.concatenate(time_parts), np.concatenate(rms_parts)
    ordering = np.argsort(times)
    times, rms = times[ordering], rms[ordering]
    normalized = rms / max(float(np.quantile(rms, 0.95)), 1e-6)
    quiet_cut = min(0.18, float(np.quantile(normalized, 0.12)) * 1.5)
    refined, snapped = [], 0
    for index, interval in enumerate(intervals):
        current = dict(interval)
        for side in ("start", "end"):
            low, high = (interval["start"] - max_shift, interval["start"] + 1.5) if side == "start" else (interval["end"] - 1.5, interval["end"] + max_shift)
            candidates = np.flatnonzero((times >= max(0.0, low)) & (times <= high) & (normalized <= quiet_cut))
            if len(candidates):
                best = min(candidates, key=lambda k: abs(times[k] - interval[side]) + 0.25 * normalized[k])
                if abs(float(times[best]) - interval[side]) >= 0.25:
                    current[side] = float(times[best])
                    snapped += 1
        if current["end"] > current["start"]:
            refined.append(current)
    return refined, {"audio_available": True, "snapped_edges": snapped, "decoded_seconds": round(decoded_seconds, 3)}


def enrich_predictions_with_audio(predictions, transcript_meta, ffmpeg_path: str | None = None):
    refined: dict[str, dict[str, list[dict[str, float]]]] = defaultdict(dict)
    diagnostics = Counter()
    for episode, takes in predictions.items():
        for take, intervals in takes.items():
            rel_path = transcript_meta.get(episode, {}).get(take, {}).get("file", "")
            audio_path = DATA / rel_path if rel_path else Path("__missing_audio__")
            rows, stats = refine_with_silence(intervals, audio_path, ffmpeg_path=ffmpeg_path)
            refined[episode][take] = rows
            diagnostics["takes_with_audio"] += int(stats["audio_available"])
            diagnostics["snapped_edges"] += int(stats["snapped_edges"])
            diagnostics["decoded_window_millis"] += int(stats["decoded_seconds"] * 1000)
    return refined, dict(diagnostics)


def jsonable(value):
    if isinstance(value, dict):
        return {str(key): jsonable(item) for key, item in value.items()}
    if isinstance(value, list):
        return [jsonable(item) for item in value]
    if isinstance(value, (np.floating, np.integer)):
        return value.item()
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", default="semantic,tfidf", help="comma-separated semantic,tfidf")
    parser.add_argument("--audio-refine", action="store_true", help="also snap decoded edges to nearby quiet windows")
    parser.add_argument("--ffmpeg-path", help="ffmpeg executable; defaults to FFMPEG_PATH or PATH lookup")
    parser.add_argument("--force-embeddings", action="store_true")
    parser.add_argument("--labels", choices=("auto", "legacy", "v2"), default="auto", help="auto prefers next/labels/ads_v2.json")
    parser.add_argument("--label-file", type=Path, help="explicit labels JSON path (for versioned follow-up sets)")
    parser.add_argument("--evaluation-note", default="", help="provenance note added to the result protocol")
    parser.add_argument("--out", type=Path, default=OUTPUT / "evaluation.json")
    args = parser.parse_args()
    started = time.perf_counter()
    samples, transcripts, references, provenance, boundary_support, transcript_meta, label_source = load_corpus(args.labels, args.label_file)
    episodes = sorted(transcripts)
    y = build_labels(samples, references)
    print(f"Input labels: {label_source}; {len(episodes)} episodes, {len(samples):,} segments, {int(y.sum()):,} positive segment targets")
    models = [item.strip() for item in args.models.split(",") if item.strip()]
    embeddings = cache_embeddings(samples, force=args.force_embeddings) if "semantic" in models else None
    results = {}
    for kind in models:
        began = time.perf_counter()
        evaluation = evaluate_model(kind, samples, y, episodes, references, embeddings, transcript_meta)
        predictions = evaluation["predictions"]
        evaluation["interval"]["per_episode"] = per_episode_intervals(evaluation["interval"])
        evaluation["provenance"] = provenance_breakdown(predictions, references, provenance, episodes)
        evaluation["boundary_support"] = boundary_support_breakdown(predictions, references, boundary_support, episodes)
        if args.audio_refine:
            refined, diagnostics = enrich_predictions_with_audio(predictions, transcript_meta, args.ffmpeg_path)
            evaluation["audio_refined"] = {
                "interval": score_corpus(refined, references, tolerance_seconds=10.0),
                "diagnostics": diagnostics,
            }
            evaluation["audio_refined"]["interval"]["per_episode"] = per_episode_intervals(evaluation["audio_refined"]["interval"])
        evaluation["predictions"] = dict(predictions)
        evaluation.pop("oof_probabilities", None)
        results[kind] = evaluation
        metrics = evaluation["interval"]
        frame = evaluation["frame"]
        print(f"{kind}: interval P/R {metrics['precision']:.3f}/{metrics['recall']:.3f} TP/FP/FN {metrics['tp']}/{metrics['fp']}/{metrics['fn']}; 1s frame P/R/AP {frame['precision']:.3f}/{frame['recall']:.3f}/{frame['average_precision']:.3f}; {time.perf_counter() - began:.1f}s")
        if "audio_refined" in evaluation:
            refined_score = evaluation["audio_refined"]["interval"]
            print(f"{kind}: audio-refined interval P/R {refined_score['precision']:.3f}/{refined_score['recall']:.3f}, diagnostics={evaluation['audio_refined']['diagnostics']}")
    payload = {
        "protocol": {
            "label_source": label_source,
            "split": "leave-one-episode-out; all takes kept together; one episode per show, so this is also show-held-out",
            "interval_tolerance_seconds": 10,
            "pod_definition": "maximal contiguous commercial or promotional pod, including house promos; editorial excluded",
            "stt_input": "existing Whisper-small-quality timestamped transcript; inference assumes equivalent on-device STT output",
            "audio_refinement": "optional quiet-window boundary snap after transcript-only pod proposal",
            "evaluation_note": args.evaluation_note,
            "evaluation_status": "exploratory; prediction-triggered labels" if "prediction-triggered" in args.evaluation_note.casefold() else "provisional",
        },
        "episodes": episodes,
        "samples": {
            "segments": len(samples),
            "positive_segments": int(y.sum()),
            "reference_intervals": sum(len(intervals) for takes in references.values() for intervals in takes.values()),
        },
        "provenance_counts": dict(Counter(source for takes in provenance.values() for values in takes.values() for source in values)),
        "boundary_support_counts": dict(Counter(source for takes in boundary_support.values() for values in takes.values() for source in values)),
        "models": results,
        "elapsed_seconds": round(time.perf_counter() - started, 3),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(jsonable(payload), indent=2), encoding="utf-8")
    print(f"Saved {args.out}")


if __name__ == "__main__":
    main()

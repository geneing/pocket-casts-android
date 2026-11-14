"""Train the transcript TF-IDF ad detector on the M0 corpus and export a phone bundle.

The feature definition, document construction and semi-Markov decoder are taken verbatim
from `next/semantic/detector.py` so the research result and the phone port stay identical.

Training episodes and test episodes are disjoint by *show* (see manifest/ads.json `split`).

Outputs:
  bundle/model.json      vocabulary, idf, logistic weights, decoder constants
  bundle/fixtures.json   parity fixtures for the Kotlin port
  eval/report.md, eval/metrics.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
import time
from collections import defaultdict
from dataclasses import asdict
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupKFold
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.pipeline import FeatureUnion

HERE = Path(__file__).resolve().parent
NEXT = HERE.parent
ADWARE = NEXT.parent
sys.path.insert(0, str(NEXT / "semantic"))
sys.path.insert(0, str(NEXT / "eval"))

import detector  # noqa: E402
from score import score_corpus  # noqa: E402

CORPUS = HERE / "corpus"
BUNDLE = HERE / "bundle"
EVAL = HERE / "eval"
SAMPLE_KEYS = ("episode", "take", "index", "start", "end", "text", "document", "current_text", "context_text")


def read_json(path: Path, default=None):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")


# ----------------------------------------------------------------------- corpus


def load_corpus(transcripts_dir: Path | None = None, labels_path: Path | None = None, default_split: bool = False):
    transcripts_dir = transcripts_dir or (CORPUS / "transcripts")
    labels = read_json(labels_path or (CORPUS / "labels" / "ads.json"), default={})
    samples = []
    tracks: dict[str, dict[str, list[dict]]] = {}
    meta: dict[str, dict] = {}
    for episode_dir in sorted(transcripts_dir.iterdir()):
        if not episode_dir.is_dir() or episode_dir.name not in labels:
            continue
        takes = {}
        durations = {}
        for path in sorted(episode_dir.glob("take*.json")):
            payload = read_json(path)
            segments = payload.get("segments", [])
            take = path.stem.removeprefix("take")
            takes[take] = segments
            durations[take] = float(payload.get("duration", 0.0))
            for i, segment in enumerate(segments):
                start, end = float(segment.get("start", 0.0)), float(segment.get("end", 0.0))
                text = detector.normalized_text(str(segment.get("text", "")))
                if end <= start or not text:
                    continue
                current, context, document = detector.transcript_document(segments, i)
                samples.append(
                    detector.Sample(
                        episode_dir.name,
                        take,
                        i,
                        start,
                        end,
                        text,
                        document,
                        current,
                        context,
                    )
                )
        tracks[episode_dir.name] = takes
        show = labels[episode_dir.name].get("show") or episode_dir.name.split("__")[0]
        split = labels[episode_dir.name].get("split")
        if split is None:
            split = "test" if default_split and (int(hashlib.sha256(show.encode()).hexdigest()[:8], 16) % 5 == 0) else "train"
        meta[episode_dir.name] = {
            "durations": durations,
            "show": show,
            "split": split,
            "episode": labels[episode_dir.name].get("episode", episode_dir.name),
        }
    references = {
        ep: {take: [{"start": float(s["start"]), "end": float(s["end"])} for s in slots] for take, slots in data.get("dynamic_slots", {}).items()}
        for ep, data in labels.items()
        if ep in tracks
    }
    return samples, tracks, references, meta


def build_y(samples: list, references: dict) -> np.ndarray:
    by_take = defaultdict(list)
    for ep, takes in references.items():
        for take, intervals in takes.items():
            by_take[(ep, take)] = [(x["start"], x["end"]) for x in intervals]
    return np.asarray(
        [int(any(a <= (s.start + s.end) / 2 <= b for a, b in by_take[(s.episode, s.take)])) for s in samples],
        dtype=np.int8,
    )


def episode_show(meta: dict) -> dict[str, str]:
    return {ep: m.get("show", ep) for ep, m in meta.items()}


# ------------------------------------------------------------------------ model


def fit_tfidf(samples: list, y: np.ndarray, idx: np.ndarray):
    model = LogisticRegression(C=1.0, class_weight="balanced", max_iter=250, solver="liblinear", random_state=27)
    vectorizer = FeatureUnion(
        [
            ("word", TfidfVectorizer(analyzer="word", ngram_range=(1, 2), min_df=2, max_features=40_000, sublinear_tf=True, strip_accents="unicode")),
            ("char", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=2, max_features=20_000, sublinear_tf=True, strip_accents="unicode")),
        ]
    )
    x = vectorizer.fit_transform([samples[i].document for i in idx])
    model.fit(x, y[idx])
    prior = float(y[idx].mean())
    return model, vectorizer, prior


def predict(model, vectorizer, prior: float, samples: list, idx: np.ndarray) -> np.ndarray:
    raw = model.predict_proba(vectorizer.transform([samples[i].document for i in idx]))[:, 1]
    clipped = np.clip(raw, 1e-6, 1 - 1e-6)
    logits = np.log(clipped / (1 - clipped)) + math.log(prior / (1 - prior))
    return 1.0 / (1.0 + np.exp(-logits))


def probs_by_take(samples: list, idx: np.ndarray, probs: np.ndarray):
    out = defaultdict(dict)
    for i, p in zip(idx, probs):
        s = samples[int(i)]
        vec = out[s.episode].setdefault(s.take, [])
        if len(vec) <= s.index:
            vec.extend([0.0] * (s.index + 1 - len(vec)))
        vec[s.index] = float(p)
    return out


def decode_map(samples: list, prob_map: dict, params: detector.DecodeParams) -> dict:
    return detector.decode_corpus(samples, prob_map, params)


def tune(samples: list, prob_map: dict, references: dict, episodes: list[str]) -> tuple[detector.DecodeParams, dict]:
    best, best_key = None, (-1.0, -1.0)
    for threshold in (0.05, 0.12, 0.25, 0.45):
        for penalty in (2.0, 5.0, 8.0):
            for duration in (0.0, 0.75):
                for gap in (0.0, 6.0):
                    params = detector.DecodeParams(threshold=threshold, boundary_penalty=penalty, duration_weight=duration, merge_gap=gap)
                    decoded = decode_map(samples, {ep: prob_map.get(ep, {}) for ep in episodes}, params)
                    score = score_corpus(decoded, {ep: references.get(ep, {}) for ep in episodes})
                    f1 = 2 * score["precision"] * score["recall"] / max(1e-9, score["precision"] + score["recall"])
                    key = (f1, score["precision"])
                    if key > best_key:
                        best, best_key = params, key
    return best, {"inner_f1": best_key[0], "inner_precision": best_key[1]}


def evaluate(samples, tracks, references, meta, episodes, params, prob_map) -> dict:
    decoded = decode_map(samples, {ep: prob_map.get(ep, {}) for ep in episodes}, params)
    strict = score_corpus(decoded, {ep: references.get(ep, {}) for ep in episodes}, tolerance_seconds=10.0)
    # frame metrics reuse the detector helper
    frame = detector.frame_metrics(
        samples,
        prob_map,
        decoded,
        references,
        episodes,
        {ep: {take: {"duration": dur} for take, dur in m["durations"].items()} for ep, m in meta.items()},
    )
    per_show = defaultdict(lambda: {"tp": 0, "fp": 0, "fn": 0})
    for row in strict.get("takes", []):
        show = meta.get(row["episode"], {}).get("show", row["episode"])
        for key in ("tp", "fp", "fn"):
            per_show[show][key] += int(row[key])
    shows = {
        show: {
            **counts,
            "precision": counts["tp"] / (counts["tp"] + counts["fp"]) if counts["tp"] + counts["fp"] else None,
            "recall": counts["tp"] / (counts["tp"] + counts["fn"]) if counts["tp"] + counts["fn"] else None,
        }
        for show, counts in per_show.items()
    }
    return {"strict": strict, "frame": frame, "per_show": shows, "predictions": decoded}


def export_bundle(model, vectorizer, prior: float, params: detector.DecodeParams, train_eps, y, path: Path):
    word: TfidfVectorizer = vectorizer.transformer_list[0][1]
    char: TfidfVectorizer = vectorizer.transformer_list[1][1]
    coef = model.coef_[0].tolist()
    vocab_hash = hashlib.sha256(
        "\0".join([*word.get_feature_names_out(), *char.get_feature_names_out()]).encode("utf-8")
    ).hexdigest()[:16]
    bundle = {
        "version": "tfidf-ad-v1",
        "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "vocab_hash": vocab_hash,
        "normalization": {"casefold": True, "collapse_whitespace": True, "strip_accents": "unicode"},
        "word": {
            "analyzer": "word",
            "ngram": [1, 2],
            "min_df": 2,
            "max_features": 40000,
            "sublinear_tf": True,
            "vocab": [str(t) for t in word.get_feature_names_out()],
            "idf": [round(float(v), 6) for v in word.idf_],
            "offset": 0,
        },
        "char": {
            "analyzer": "char_wb",
            "ngram": [3, 5],
            "min_df": 2,
            "max_features": 20000,
            "sublinear_tf": True,
            "vocab": [str(t) for t in char.get_feature_names_out()],
            "idf": [round(float(v), 6) for v in char.idf_],
            "offset": len(word.get_feature_names_out()),
        },
        "classifier": {
            "weights": [round(float(w), 7) for w in coef],
            "intercept": float(model.intercept_[0]),
            "positive_prior": float(prior),
        },
        "decoder": {
            "threshold": params.threshold,
            "boundary_penalty": params.boundary_penalty,
            "duration_weight": params.duration_weight,
            "merge_gap": params.merge_gap,
            "min_pod_seconds": detector.MIN_POD_SECONDS,
            "max_pod_seconds": detector.MAX_POD_SECONDS,
            "hard_gap_seconds": 12.0,
        },
        "training": {
            "episodes": len(train_eps),
            "segments": int(len(y)),
            "positive_segments": int(y.sum()),
            "positive_prior": float(prior),
        },
    }
    write_json(path, bundle)
    return bundle, str(path)


def fixtures(samples, model, vectorizer, prior, params, references, episodes) -> dict:
    """Small deterministic fixtures for the Kotlin parity test."""
    rng = np.random.default_rng(7)
    picks = rng.choice(len(samples), size=min(12, len(samples)), replace=False)
    texts = [samples[int(i)].document for i in picks]
    expected = predict(model, vectorizer, prior, samples, picks).tolist()

    episode = episodes[0]
    take = sorted(references[episode])[0]
    idx = np.asarray([i for i, s in enumerate(samples) if s.episode == episode and s.take == take], dtype=int)
    probs = predict(model, vectorizer, prior, samples, idx)
    prob_map = probs_by_take(samples, idx, probs)
    decoded = detector.decode_corpus(samples, prob_map, params)
    segments = [
        {"start": samples[int(i)].start, "end": samples[int(i)].end, "text": samples[int(i)].text}
        for i in idx
    ]
    return {
        "documents": texts,
        "probabilities": expected,
        "episode": {
            "segments": segments,
            "expected_probabilities": probs.tolist(),
            "expected_intervals": decoded.get(episode, {}).get(take, []),
        },
        "decoder": asdict(params),
    }


def main() -> None:
    global BUNDLE, EVAL

    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(BUNDLE / "model.json"))
    ap.add_argument("--transcripts", default="", help="override transcripts directory")
    ap.add_argument("--labels", default="", help="override labels json")
    ap.add_argument("--default-split", action="store_true", help="assign test shows by hash when labels have no split")
    ap.add_argument("--out-dir", default="", help="bundle/eval output directory")
    args = ap.parse_args()

    if args.out_dir:
        BUNDLE = Path(args.out_dir)
        EVAL = Path(args.out_dir).parent / "eval"
    out = Path(args.out) if args.out_dir == "" else BUNDLE / "model.json"

    samples, tracks, references, meta = load_corpus(
        Path(args.transcripts) if args.transcripts else None,
        Path(args.labels) if args.labels else None,
        args.default_split,
    )
    y = build_y(samples, references)
    shows = episode_show(meta)
    episodes = sorted(references)
    test_eps = [ep for ep in episodes if meta[ep].get("split") == "test"]
    train_eps = [ep for ep in episodes if meta[ep].get("split") != "test"]
    print(f"corpus: {len(episodes)} episodes, {len(train_eps)} train / {len(test_eps)} test, {len(samples)} segments, {y.mean() * 100:.1f}% ad")
    print(f"shows: {len({shows[e] for e in episodes})} total, {len({shows[e] for e in test_eps})} test")

    if not train_eps:
        raise SystemExit("no training episodes yet; run collect.py longer")

    train_idx = detector.sample_indices(samples, train_eps)
    # inner folds for decoder tuning (episode-grouped)
    inner_probs = defaultdict(dict)
    groups = np.asarray([samples[int(i)].episode for i in train_idx])
    folds = min(3, len(train_eps))
    for tr, va in GroupKFold(n_splits=folds).split(train_idx, y[train_idx], groups=groups):
        model, vec, prior = fit_tfidf(samples, y, train_idx[tr])
        probs = predict(model, vec, prior, samples, train_idx[va])
        for ep, takes in probs_by_take(samples, train_idx[va], probs).items():
            for take, p in takes.items():
                inner_probs[ep][take] = p
    params, tuned = tune(samples, inner_probs, references, train_eps)
    print(f"decoder tuned: {asdict(params)} {tuned}")

    model, vectorizer, prior = fit_tfidf(samples, y, train_idx)
    train_probs = predict(model, vectorizer, prior, samples, train_idx)
    train_map = probs_by_take(samples, train_idx, train_probs)
    train_score = evaluate(samples, tracks, references, meta, train_eps, params, train_map)
    print(
        f"train: strict P {train_score['strict']['precision']:.3f} R {train_score['strict']['recall']:.3f} "
        f"frame AP {train_score['frame']['average_precision']:.3f}"
    )

    test_score = None
    if test_eps:
        test_idx = detector.sample_indices(samples, test_eps)
        test_probs = predict(model, vectorizer, prior, samples, test_idx)
        test_map = probs_by_take(samples, test_idx, test_probs)
        test_score = evaluate(samples, tracks, references, meta, test_eps, params, test_map)
        print(
            f"test: strict P {test_score['strict']['precision']:.3f} R {test_score['strict']['recall']:.3f} "
            f"frame P {test_score['frame']['precision']:.3f} R {test_score['frame']['recall']:.3f} "
            f"AP {test_score['frame']['average_precision']:.3f}"
        )

    bundle, path = export_bundle(model, vectorizer, prior, params, train_eps, y[train_idx], out)
    write_json(BUNDLE / "fixtures.json", fixtures(samples, model, vectorizer, prior, params, references, test_eps or train_eps))
    metrics = {
        "corpus": {
            "episodes": len(episodes),
            "train_episodes": len(train_eps),
            "test_episodes": len(test_eps),
            "shows": len({shows[e] for e in episodes}),
            "test_shows": len({shows[e] for e in test_eps}),
            "segments": len(samples),
            "positive_segments": int(y.sum()),
            "adbundle": path,
            "vocab_hash": bundle["vocab_hash"],
        },
        "decoder": asdict(params),
        "train": {k: train_score[k] for k in ("strict", "frame", "per_show")},
        "test": {k: test_score[k] for k in ("strict", "frame", "per_show")} if test_score else None,
    }
    write_json(EVAL / "metrics.json", metrics)
    write_report(metrics, samples, y, bundle)
    print(f"bundle: {path} ({len(bundle['word']['vocab'])} word + {len(bundle['char']['vocab'])} char features)")


def write_report(metrics: dict, samples: list, y: np.ndarray, bundle: dict) -> None:
    c = metrics["corpus"]
    lines = [
        "# M0 TF-IDF ad detector",
        "",
        f"- corpus: {c['episodes']} episodes from {c['shows']} shows ({c['train_episodes']} train / {c['test_episodes']} test episodes)",
        f"- segments: {c['segments']:,} with {c['positive_segments']:,} positive ({y.mean() * 100:.1f}%)",
        f"- features: {len(bundle['word']['vocab']):,} word + {len(bundle['char']['vocab']):,} char n-grams",
        f"- decoder: {metrics['decoder']}",
        "",
        "Strict = one-to-one interval match with both boundaries within 10 s.",
        "",
        "## Train",
        "",
        f"- strict P {metrics['train']['strict']['precision']:.3f} / R {metrics['train']['strict']['recall']:.3f}",
        f"- frame P {metrics['train']['frame']['precision']:.3f} / R {metrics['train']['frame']['recall']:.3f} / AP {metrics['train']['frame']['average_precision']:.3f}",
        "",
    ]
    if metrics["test"]:
        lines += [
            "## Held-out shows",
            "",
            f"- strict P {metrics['test']['strict']['precision']:.3f} / R {metrics['test']['strict']['recall']:.3f}",
            f"- frame P {metrics['test']['frame']['precision']:.3f} / R {metrics['test']['frame']['recall']:.3f} / AP {metrics['test']['frame']['average_precision']:.3f}",
            "",
            "| show | tp | fp | fn | P | R |",
            "|---|---|---|---|---|---|",
        ]
        for show, row in sorted(metrics["test"]["per_show"].items()):
            lines.append(f"| {show} | {row['tp']} | {row['fp']} | {row['fn']} | {row['precision'] or 0:.2f} | {row['recall'] or 0:.2f} |")
        lines.append("")
    (EVAL / "report.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()

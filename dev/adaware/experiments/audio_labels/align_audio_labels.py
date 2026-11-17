"""Audio-derived ad labels from sibling-take feature alignment.

Run from the repository root:
    uv run --project dev/adaware python dev/adaware/experiments/audio_labels/align_audio_labels.py

Only this experiment directory is written. Existing labels and pipeline are read-only.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ADAWARE = HERE.parents[1]
DATA = ADAWARE / "data"
GRID = 0.25


def load_features(episode: str, take: int, bin_s: float) -> tuple[np.ndarray, float]:
    path = DATA / "features" / episode / f"take{take}.npz"
    with np.load(path) as z:
        names = z["names"].tolist()
        columns = [names.index(f"mfcc_{i}_mean") for i in range(1, 13)]
        x = z["feats"][:, columns].astype(np.float32)
    width = max(1, round(bin_s / GRID))
    n = len(x) // width
    return x[: n * width].reshape(n, width, -1).mean(axis=1), width * GRID


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    pooled = np.concatenate((a, b))
    med = np.median(pooled, axis=0)
    q25, q75 = np.percentile(pooled, (25, 75), axis=0)
    scale = np.maximum((q75 - q25) / 1.349, 1e-3)

    def normalize(x):
        x = np.clip((x - med) / scale, -5, 5)
        return x / np.maximum(np.linalg.norm(x, axis=1, keepdims=True), 1e-6)

    return normalize(a) @ normalize(b).T


def window_scores(sim: np.ndarray, width: int) -> np.ndarray:
    rows, cols = sim.shape[0] - width + 1, sim.shape[1] - width + 1
    if min(rows, cols) <= 0:
        return np.empty((0, 0), dtype=np.float32)
    out = np.zeros((rows, cols), dtype=np.float32)
    for k in range(width):
        out += sim[k : k + rows, k : k + cols]
    out /= width
    return out


def find_matches(sim, bin_s, window_s, stride_s, threshold, margin, frame_threshold):
    width = max(3, round(window_s / bin_s))
    stride = max(1, round(stride_s / bin_s))
    scores = window_scores(sim, width)
    if scores.size == 0:
        return np.zeros(sim.shape[0], bool), {"anchors": 0, "matched_fraction": 0.0}
    starts = np.arange(0, scores.shape[0], stride)
    best_j = scores[starts].argmax(axis=1)
    best_v = scores[starts, best_j]
    radius = round(30 / bin_s)
    second = np.full(len(starts), -1, np.float32)
    for k, j in enumerate(best_j):
        lo, hi = max(0, j - radius), min(scores.shape[1], j + radius + 1)
        candidates = [scores[starts[k], :lo].max(initial=-1), scores[starts[k], hi:].max(initial=-1)]
        second[k] = max(candidates)
    valid = (best_v >= threshold) & ((best_v - second >= margin) | (second < 0))

    # Retain an ordered path, then estimate the stable local time offset. This
    # prevents an ad window's unrelated global best match from making that ad look
    # shared. Missing anchors are interpolated only to choose a comparison time;
    # the frame still has to match strongly at that expected time to count as shared.
    anchor_indices, anchor_offsets, anchor_values = [], [], []
    last_j = -1
    for i, j, value, ok in zip(starts, best_j, best_v, valid):
        if ok and j > last_j:
            anchor_indices.append(int(i))
            anchor_offsets.append(float(j - i))
            anchor_values.append(float(value))
            last_j = int(j)

    if not anchor_indices:
        return np.zeros(sim.shape[0], bool), {"anchors": 0, "matched_fraction": 0.0}
    ix = np.asarray(anchor_indices)
    off = np.asarray(anchor_offsets)
    timeline = np.arange(sim.shape[0])
    offset = np.interp(timeline, ix, off)
    smooth_width = max(3, round(15 / bin_s) | 1)
    pad = smooth_width // 2
    padded = np.pad(offset, (pad, pad), mode="edge")
    offset = np.median(np.lib.stride_tricks.sliding_window_view(padded, smooth_width), axis=1)
    target = np.rint(timeline + offset).astype(int)
    in_bounds = (target >= 0) & (target < sim.shape[1])
    frame_similarity = np.full(len(timeline), -1.0, dtype=np.float32)
    frame_similarity[in_bounds] = sim[timeline[in_bounds], target[in_bounds]]
    smooth_width = max(3, round(5 / bin_s) | 1)
    pad = smooth_width // 2
    padded_similarity = np.pad(frame_similarity, (pad, pad), mode="edge")
    local_similarity = np.mean(
        np.lib.stride_tricks.sliding_window_view(padded_similarity, smooth_width), axis=1
    )
    matched = local_similarity >= frame_threshold
    return matched, {
        "anchors": len(anchor_indices),
        "valid_candidates": int(valid.sum()),
        "mean_anchor_similarity": round(float(np.mean(anchor_values)), 4),
        "matched_fraction": round(float(matched.mean()), 4),
        "median_aligned_frame_similarity": round(float(np.median(local_similarity[matched])), 4) if matched.any() else None,
    }


def gaps(matched: np.ndarray, bin_s: float, min_s: float) -> list[dict]:
    indices = np.flatnonzero(~matched)
    if not len(indices):
        return []
    breaks = np.flatnonzero(np.diff(indices) > 1)
    starts, ends = np.r_[indices[0], indices[breaks + 1]], np.r_[indices[breaks], indices[-1] + 1]
    groups = []
    for start, end in zip(starts, ends):
        if groups and (start - groups[-1][1]) * bin_s <= 2:
            groups[-1][1] = int(end)
        else:
            groups.append([int(start), int(end)])
    return [{"start": round(s * bin_s, 2), "end": round(e * bin_s, 2),
             "duration": round((e - s) * bin_s, 2)} for s, e in groups if (e - s) * bin_s >= min_s]


def align_pair(episode, source, target, args):
    a, bin_s = load_features(episode, source, args.bin_seconds)
    b, other_bin = load_features(episode, target, args.bin_seconds)
    if bin_s != other_bin:
        raise ValueError("Take feature grids differ")
    sim = cosine_similarity(a, b)
    matched, diag = find_matches(sim, bin_s, args.window_seconds, args.stride_seconds,
                                 args.similarity_threshold, args.uniqueness_margin, args.frame_threshold)
    intervals = gaps(matched, bin_s, args.min_interval_seconds)
    for item in intervals:
        item.update(take=source, compared_take=target)
    diag.update(episode=episode, source_take=source, target_take=target,
                source_seconds=round(len(a) * bin_s, 1), target_seconds=round(len(b) * bin_s, 1),
                intervals=len(intervals))
    return intervals, diag


def iou(a, b):
    overlap = max(0.0, min(a["end"], b["end"]) - max(a["start"], b["start"]))
    union = max(a["end"], b["end"]) - min(a["start"], b["start"])
    return overlap / union if union else 0.0


def dedupe(intervals):
    out = []
    for item in sorted(intervals, key=lambda x: x["start"]):
        duplicate = next((x for x in out if iou(x, item) >= 0.5 or
                          (abs(x["start"] - item["start"]) <= 10 and abs(x["end"] - item["end"]) <= 10)), None)
        if duplicate is None:
            out.append(item)
    return out


def score(pred, gold, tolerance):
    options = []
    for pi, p in enumerate(pred):
        for gi, g in enumerate(gold):
            ds, de = p["start"] - g["start"], p["end"] - g["end"]
            if abs(ds) <= tolerance and abs(de) <= tolerance:
                options.append((abs(ds) + abs(de), pi, gi, ds, de))
    used_p, used_g, matches = set(), set(), []
    for _, pi, gi, ds, de in sorted(options):
        if pi not in used_p and gi not in used_g:
            used_p.add(pi)
            used_g.add(gi)
            matches.append({"prediction": pi, "gold": gi, "start_delta_s": round(ds, 2), "end_delta_s": round(de, 2)})
    tp, fp, fn = len(matches), len(pred) - len(matches), len(gold) - len(matches)
    overlap_counts = {}
    for threshold in (0.1, 0.5):
        pairs = sorted(
            ((iou(p, g), pi, gi) for pi, p in enumerate(pred) for gi, g in enumerate(gold)
             if iou(p, g) >= threshold), reverse=True
        )
        used_p, used_g = set(), set()
        for _, pi, gi in pairs:
            if pi not in used_p and gi not in used_g:
                used_p.add(pi)
                used_g.add(gi)
        overlap_counts[f"iou_{threshold:g}_matches"] = len(used_p)
    return {"tp": tp, "fp": fp, "fn": fn, "precision": tp / (tp + fp) if tp + fp else 0,
            "recall": tp / (tp + fn) if tp + fn else 0, "matches": matches, **overlap_counts}


def write_report(result):
    counts = result["counts"]
    development = result["development"]
    evaluation_names = [name for name in result["episodes"] if not development or name != development["name"]]
    if not evaluation_names:
        evaluation_names = list(result["episodes"])
    lines = [
        "# Audio-derived ad interval labels", "",
        "This isolated experiment aligns sibling audio takes using existing MFCC features. It reads, but does not modify, the existing transcript labels and detector pipeline.", "",
        "## Method", "",
        "MFCC coefficients 1–12 from the 250 ms feature grid are averaged into the configured time bins. Each take pair is compared with cosine similarity; fixed-speed windows are matched, low-confidence and non-unique anchors are rejected, and the remaining anchors are required to stay in order. A smoothed local similarity check at the expected offset distinguishes shared content from unmatched audio. Long unsupported regions in each take are proposed as inserted audio intervals. Per-take proposals from sibling comparisons are deduplicated.", "",
        f"Transcript-derived `dynamic_slots` are the reference. A true positive requires both predicted boundaries to be within {result['tolerance_seconds']:.0f} seconds of one gold interval. Matching is one-to-one. Settings were selected on the development episode; held-out episodes are reported separately.", "",
        "## Held-out metrics", "",
        "| TP | FP | FN | Precision | Recall | Gold | Predicted |", "|---:|---:|---:|---:|---:|---:|---:|",
        f"| {counts['tp']} | {counts['fp']} | {counts['fn']} | {result['precision']:.3f} | {result['recall']:.3f} | {counts['gold']} | {counts['predicted']} |", "",
        "## Development episode", "",
    ]
    all_counts = result["all_episode_counts"]
    lines.append(f"All episodes including development: TP {all_counts['tp']}, FP {all_counts['fp']}, "
                 f"FN {all_counts['fn']}, P {all_counts['precision']:.3f}, R {all_counts['recall']:.3f}.")
    if development:
        m = development["metrics"]
        lines.append(f"`{development['name']}`: TP {m['tp']}, FP {m['fp']}, FN {m['fn']}, P {m['precision']:.3f}, R {m['recall']:.3f}.")
    else:
        lines.append("No configured development episode was present in this run.")
    broad_01 = sum(result["episodes"][name]["iou_0.1_matches"] for name in evaluation_names)
    broad_05 = sum(result["episodes"][name]["iou_0.5_matches"] for name in evaluation_names)
    lines.append(f"Held-out overlap-only context: {broad_01}/{counts['gold']} one-to-one overlaps at IoU ≥ 0.1; "
                 f"{broad_05}/{counts['gold']} at IoU ≥ 0.5. These do not meet the two-boundary criterion.")
    lines.extend(["", "## Per episode", "", "| Split | Episode | TP | FP | FN | P | R |", "|---|---|---:|---:|---:|---:|---:|"])
    for name, m in result["episodes"].items():
        split = "development" if development and name == development["name"] else "held-out"
        lines.append(f"| {split} | `{name}` | {m['tp']} | {m['fp']} | {m['fn']} | {m['precision']:.3f} | {m['recall']:.3f} |")
    ds = [m["start_delta_s"] for name in evaluation_names for m in result["episodes"][name]["matches"]]
    de = [m["end_delta_s"] for name in evaluation_names for m in result["episodes"][name]["matches"]]
    lines.extend(["", "## Boundary errors", ""])
    if ds:
        lines.extend([f"- Start delta (prediction minus label), median / p10 / p90: {np.percentile(ds, 50):.2f} / {np.percentile(ds, 10):.2f} / {np.percentile(ds, 90):.2f} s",
                       f"- End delta (prediction minus label), median / p10 / p90: {np.percentile(de, 50):.2f} / {np.percentile(de, 10):.2f} / {np.percentile(de, 90):.2f} s"])
    else:
        lines.append("No predictions met the two-boundary tolerance.")
    lines.extend(["", "### Example audio-to-transcript boundaries", "",
                  "| Episode | Take | Audio start–end | Transcript start–end | Start Δ | End Δ |",
                  "|---|---:|---:|---:|---:|---:|"])
    examples = []
    for name in evaluation_names:
        payload = result["data"][name]
        predicted = [p for group in payload["predictions"].values() for p in group]
        gold = [g for group in payload["transcript_labels"].values() for g in group]
        for match in result["episodes"][name]["matches"]:
            examples.append((name, predicted[match["prediction"]], gold[match["gold"]], match))
    if not examples and development:
        name = development["name"]
        payload = result["data"][name]
        predicted = [p for group in payload["predictions"].values() for p in group]
        gold = [g for group in payload["transcript_labels"].values() for g in group]
        for match in result["episodes"][name]["matches"]:
            examples.append((name, predicted[match["prediction"]], gold[match["gold"]], match))
    for name, pred, truth, match in examples[:8]:
        lines.append(f"| `{name}` | {pred['take']} | {pred['start']:.1f}–{pred['end']:.1f} s | "
                     f"{truth['start']:.1f}–{truth['end']:.1f} s | {match['start_delta_s']:+.1f} s | {match['end_delta_s']:+.1f} s |")
    if not examples:
        lines.append("| No strict boundary matches | | | | | |")
    lines.extend(["", "## Reproduce", "", "From `dev/adaware`:", "", "```powershell",
                  "uv run python experiments/audio_labels/align_audio_labels.py",
                  "```", "", "Defaults: 1 s bins, 8 s match windows, similarity ≥ 0.75, uniqueness margin 0.08, 5 s-smoothed frame similarity ≥ 0.50, minimum gap 8 s, and 10 s boundary tolerance. Detailed predictions and pair diagnostics are in `audio_labels_results.json`.", "",
                  "## Limitations", "",
                  "- The default matching bins are 1 second, using precomputed 250 ms features; boundaries are audio-feature-resolution estimates, not sample-accurate waveform splice points.",
                  "- The transcript labels are not independent physical stitch annotations. Because they come from word timestamps and matched transcript text, non-speech jingles are not represented; nearby show speech may also be included. For example, the 99% Invisible tail slot label begins at 2315.2 s and contains the outro through about 2328 s, while the detected ad audio begins at 2329 s.",
                  "- Repeated, quiet, or strongly re-encoded shared audio can reduce alignment confidence.",
                  "- Offline use requires a sibling take or reference audio for the same episode to be present on-device; a single downloaded take alone is insufficient.",
                  "- The evaluation measures label alignment only; it does not train or validate a detector at 0.9 precision/recall.", ""])
    (HERE / "REPORT.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--episode", action="append", help="episode key; repeat for multiple episodes")
    ap.add_argument("--bin-seconds", type=float, default=1.0)
    ap.add_argument("--window-seconds", type=float, default=8.0)
    ap.add_argument("--stride-seconds", type=float, default=1.0)
    ap.add_argument("--similarity-threshold", type=float, default=0.75)
    ap.add_argument("--uniqueness-margin", type=float, default=0.08)
    ap.add_argument("--frame-threshold", type=float, default=0.50)
    ap.add_argument("--min-interval-seconds", type=float, default=8.0)
    ap.add_argument("--boundary-tolerance", type=float, default=10.0)
    ap.add_argument("--development-episode", default="99-invisible__perfectly-leveraged-for-the-squat")
    args = ap.parse_args()
    labels = json.loads((DATA / "labels" / "ads.json").read_text(encoding="utf-8"))
    episodes = args.episode or sorted(labels)
    all_data, diagnostics, episode_metrics = {}, [], {}
    for episode in episodes:
        label_data = labels[episode]
        takes = {str(t): [] for t in range(len(label_data["takes"]))}
        for source in range(len(takes)):
            for target in range(len(takes)):
                if source == target:
                    continue
                proposals, diag = align_pair(episode, source, target, args)
                takes[str(source)].extend(proposals)
                diagnostics.append(diag)
        for take in takes:
            takes[take] = dedupe(takes[take])
        gold = {str(t): label_data["dynamic_slots"].get(str(t), []) for t in takes}
        predictions = [p for group in takes.values() for p in group]
        references = [g for group in gold.values() for g in group]
        episode_metrics[episode] = score(predictions, references, args.boundary_tolerance)
        all_data[episode] = {"predictions": takes, "transcript_labels": gold}
    development_name = args.development_episode if args.development_episode in episode_metrics else None
    held_out_metrics = {name: m for name, m in episode_metrics.items() if name != development_name}
    if not held_out_metrics:
        held_out_metrics = episode_metrics
        development_name = None
    tp = sum(m["tp"] for m in held_out_metrics.values())
    fp = sum(m["fp"] for m in held_out_metrics.values())
    fn = sum(m["fn"] for m in held_out_metrics.values())
    all_tp = sum(m["tp"] for m in episode_metrics.values())
    all_fp = sum(m["fp"] for m in episode_metrics.values())
    all_fn = sum(m["fn"] for m in episode_metrics.values())
    result = {"method": "audio MFCC sibling-take matching; long unsupported intervals become audio-derived labels",
              "parameters": vars(args), "tolerance_seconds": args.boundary_tolerance,
              "counts": {"tp": tp, "fp": fp, "fn": fn, "predicted": tp + fp, "gold": tp + fn},
              "precision": tp / (tp + fp) if tp + fp else 0,
              "recall": tp / (tp + fn) if tp + fn else 0,
              "all_episode_counts": {"tp": all_tp, "fp": all_fp, "fn": all_fn,
                                     "predicted": all_tp + all_fp, "gold": all_tp + all_fn,
                                     "precision": all_tp / (all_tp + all_fp) if all_tp + all_fp else 0,
                                     "recall": all_tp / (all_tp + all_fn) if all_tp + all_fn else 0},
              "development": {"name": development_name, "metrics": episode_metrics[development_name]} if development_name else None,
              "episodes": episode_metrics, "pair_diagnostics": diagnostics, "data": all_data}
    (HERE / "audio_labels_results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    write_report(result)
    print(f"episodes={len(episodes)} TP={tp} FP={fp} FN={fn} P={result['precision']:.3f} R={result['recall']:.3f}")
    print(f"Wrote {HERE / 'audio_labels_results.json'} and {HERE / 'REPORT.md'}")


if __name__ == "__main__":
    main()

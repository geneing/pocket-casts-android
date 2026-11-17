"""Attach descriptive overlap diagnostics and a concise report to evaluation.json."""

from __future__ import annotations

import hashlib
import argparse
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

import detector


RESULTS = detector.OUTPUT / "evaluation.json"
LABELS = RESULTS.parent / "ads_v2_input_d6a527.json"
EXPECTED_SHA256 = "d6a5279dbacea5d78e8735028943fb8940150486f27b125ad7144eb67ff1c2be"


def match_pairs(predicted, gold, criterion):
    edges = []
    for pred in predicted:
        candidates = []
        ps, pe = float(pred["start"]), float(pred["end"])
        for index, ref in enumerate(gold):
            gs, ge = float(ref["start"]), float(ref["end"])
            intersection = max(0.0, min(pe, ge) - max(ps, gs))
            union = max(pe, ge) - min(ps, gs)
            iou = intersection / union if union > 0 else 0.0
            if criterion == "overlap" and intersection > 0:
                candidates.append((index, iou))
            elif criterion == "iou50" and iou >= 0.5:
                candidates.append((index, iou))
            elif criterion == "strict10" and abs(ps - gs) <= 10 and abs(pe - ge) <= 10:
                candidates.append((index, -abs(ps - gs) - abs(pe - ge)))
        edges.append([index for index, _ in sorted(candidates, key=lambda item: -item[1])])
    owner: dict[int, int] = {}

    def augment(i, visited):
        for j in edges[i]:
            if j in visited:
                continue
            visited.add(j)
            if j not in owner or augment(owner[j], visited):
                owner[j] = i
                return True
        return False

    for i in range(len(predicted)):
        augment(i, set())
    return sorted((pred, gold_index) for gold_index, pred in owner.items())


def interval_stats(predictions, references):
    totals = defaultdict(int)
    predicted_durations, gold_durations = [], []
    overlap_edge_misses = 0
    gold_split_count = pred_merge_count = gold_no_overlap = pred_no_overlap = 0
    start_errors, end_errors = [], []
    for episode in sorted(set(predictions) | set(references)):
        pred_takes, gold_takes = predictions.get(episode, {}), references.get(episode, {})
        for take in sorted(set(pred_takes) | set(gold_takes)):
            pred, gold = pred_takes.get(take, []), gold_takes.get(take, [])
            predicted_durations.extend(float(x["end"]) - float(x["start"]) for x in pred)
            gold_durations.extend(float(x["end"]) - float(x["start"]) for x in gold)
            pred_degrees, gold_degrees = [0] * len(pred), [0] * len(gold)
            for pi, p in enumerate(pred):
                for gi, g in enumerate(gold):
                    if max(0.0, min(float(p["end"]), float(g["end"])) - max(float(p["start"]), float(g["start"]))) > 0:
                        pred_degrees[pi] += 1
                        gold_degrees[gi] += 1
            pred_no_overlap += sum(degree == 0 for degree in pred_degrees)
            gold_no_overlap += sum(degree == 0 for degree in gold_degrees)
            pred_merge_count += sum(degree > 1 for degree in pred_degrees)
            gold_split_count += sum(degree > 1 for degree in gold_degrees)
            for name, criterion in (("overlap", "overlap"), ("iou50", "iou50"), ("strict10", "strict10")):
                pairs = match_pairs(pred, gold, criterion)
                totals[f"{name}_tp"] += len(pairs)
                totals[f"{name}_fp"] += len(pred) - len(pairs)
                totals[f"{name}_fn"] += len(gold) - len(pairs)
            overlap_pairs = match_pairs(pred, gold, "overlap")
            for pred_index, gold_index in overlap_pairs:
                p, g = pred[pred_index], gold[gold_index]
                start_error = abs(float(p["start"]) - float(g["start"]))
                end_error = abs(float(p["end"]) - float(g["end"]))
                start_errors.append(start_error)
                end_errors.append(end_error)
                if start_error > 10 or end_error > 10:
                    overlap_edge_misses += 1

    def pr(prefix):
        tp, fp, fn = (totals[f"{prefix}_{key}"] for key in ("tp", "fp", "fn"))
        return {
            "tp": tp,
            "fp": fp,
            "fn": fn,
            "precision": tp / (tp + fp) if tp + fp else (1.0 if not fn else 0.0),
            "recall": tp / (tp + fn) if tp + fn else 1.0,
        }

    def distribution(values):
        if not values:
            return {"count": 0, "mean": None, "median": None, "p10": None, "p90": None}
        ordered = sorted(values)
        return {
            "count": len(values),
            "mean": statistics.fmean(values),
            "median": statistics.median(values),
            "p10": ordered[max(0, math.floor(0.10 * (len(ordered) - 1)))],
            "p90": ordered[min(len(ordered) - 1, math.ceil(0.90 * (len(ordered) - 1)))],
        }

    return {
        "any_overlap": pr("overlap"),
        "iou_at_least_0_5": pr("iou50"),
        "strict_10_seconds": pr("strict10"),
        "overlap_matched_but_edge_missed": overlap_edge_misses,
        "gold_without_any_overlap": gold_no_overlap,
        "predictions_without_any_overlap": pred_no_overlap,
        "gold_pods_overlapped_by_multiple_predictions": gold_split_count,
        "predictions_overlapping_multiple_gold_pods": pred_merge_count,
        "overlap_match_median_absolute_start_error_seconds": statistics.median(start_errors) if start_errors else None,
        "overlap_match_median_absolute_end_error_seconds": statistics.median(end_errors) if end_errors else None,
        "predicted_duration_seconds": distribution(predicted_durations),
        "gold_duration_seconds": distribution(gold_durations),
    }


def pct(value):
    return f"{value:.3f}"


def make_report(data):
    lines = [
        "# Transcript-context ad detector results",
        "",
        f"Label set: `{data['protocol']['label_source']}`  ",
        f"SHA256: `{data['protocol']['label_sha256']}`  ",
        f"References: {data['samples']['reference_intervals']} pods across {len(data['episodes'])} held-out episodes.",
        "",
        "## Primary results",
        "",
        "A hit requires both boundaries within 10 seconds. Every prediction is scored against all reference labels; overlap metrics below are diagnostic only.",
        "",
        "| Model | Strict TP/FP/FN | Strict P/R | 1s frame P/R/AP | Any-overlap P/R | IoU ≥ 0.5 P/R | Overlap match, edge missed |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for name, result in data["models"].items():
        strict, frame, diagnostic = result["interval"], result["frame"], result["overlap_diagnostics"]
        any_overlap, iou50 = diagnostic["any_overlap"], diagnostic["iou_at_least_0_5"]
        lines.append(
            f"| {name} | {strict['tp']}/{strict['fp']}/{strict['fn']} | {pct(strict['precision'])}/{pct(strict['recall'])} | "
            f"{pct(frame['precision'])}/{pct(frame['recall'])}/{pct(frame['average_precision'])} | "
            f"{pct(any_overlap['precision'])}/{pct(any_overlap['recall'])} | "
            f"{pct(iou50['precision'])}/{pct(iou50['recall'])} | {diagnostic['overlap_matched_but_edge_missed']} |"
        )
    lines.extend(["", "## Episode-held-out results", "", "| Model | Episode | TP/FP/FN | P/R |", "|---|---|---:|---:|"])
    for name, result in data["models"].items():
        for episode, row in result["interval"]["per_episode"].items():
            lines.append(f"| {name} | {episode} | {row['tp']}/{row['fp']}/{row['fn']} | {pct(row['precision'])}/{pct(row['recall'])} |")
    lines.extend(["", "## Label provenance and boundary support", ""])
    for name, result in data["models"].items():
        lines.append(f"### {name}")
        lines.append("")
        lines.append("Matched reference recall by ad identity source:")
        lines.append("")
        for source, row in result["provenance"].items():
            if source == "unmatched_predictions":
                lines.append(f"- Unmatched predictions (false positives): {row['fp']}")
            else:
                lines.append(f"- {source}: {row['tp']}/{row['gold']} matched (recall {pct(row['reference_recall']) if row['reference_recall'] is not None else 'n/a'})")
        lines.append("")
        lines.append("Matched reference recall by edge support (not ad identity):")
        lines.append("")
        for source, row in result["boundary_support"].items():
            if source == "unmatched_predictions":
                lines.append(f"- Unmatched predictions (false positives): {row['fp']}")
            else:
                lines.append(f"- {source}: {row['tp']}/{row['gold']} matched (recall {pct(row['reference_recall']) if row['reference_recall'] is not None else 'n/a'})")
        lines.append("")
    lines.extend(["## Boundary refinement ablation", ""])
    for name, result in data["models"].items():
        if "audio_refined" not in result:
            lines.append(f"- {name}: not run.")
        else:
            audio = result["audio_refined"]
            score = audio["interval"]
            lines.append(
                f"- {name}: P/R {pct(score['precision'])}/{pct(score['recall'])} "
                f"({score['tp']} TP, {score['fp']} FP, {score['fn']} FN); "
                f"{audio['diagnostics']['snapped_edges']} edges adjusted over "
                f"{audio['diagnostics']['decoded_window_millis'] / 1000:.1f}s of local audio."
            )
    lines.extend(["", "## Coverage, grouping, and duration diagnostics", ""])
    for name, result in data["models"].items():
        diag = result["overlap_diagnostics"]
        lines.append(
            f"- {name}: {diag['gold_without_any_overlap']} gold pods have no overlapping prediction; "
            f"{diag['gold_pods_overlapped_by_multiple_predictions']} gold pods overlap multiple predictions; "
            f"{diag['predictions_overlapping_multiple_gold_pods']} predictions span multiple gold pods. "
            f"Among maximum any-overlap matches, median absolute start/end errors are "
            f"{diag['overlap_match_median_absolute_start_error_seconds']:.1f}s/"
            f"{diag['overlap_match_median_absolute_end_error_seconds']:.1f}s."
        )
        pred, gold = diag["predicted_duration_seconds"], diag["gold_duration_seconds"]
        lines.append(
            f"- {name}: predicted median {pred['median']:.1f}s (P10–P90 {pred['p10']:.1f}–{pred['p90']:.1f}s); "
            f"gold median {gold['median']:.1f}s (P10–P90 {gold['p10']:.1f}–{gold['p90']:.1f}s)."
        )
    status = data["protocol"].get("evaluation_status", "provisional")
    note = data["protocol"].get("evaluation_note", "")
    if data["protocol"].get("label_sha256") == EXPECTED_SHA256:
        caveat = "The v2 references are same-ASR pseudo-gold/corpus-wide candidate labels, not an independently listened and annotated acceptance set. QA found that 99% Invisible take1's labeled 1497–1534s pod continues in the transcript to about 1627s, so this frozen reference is incomplete. Some apparent false positives and duration comparisons are unreliable."
    elif "prediction-triggered" in note.casefold():
        caveat = "EXPLORATORY: These labels were created after reviewing apparent false positives from the prior detector; they are prediction-triggered and are not independent held-out ground truth or an acceptance set."
    elif note:
        caveat = f"{status}: {note}. The labels are not an independent acceptance set."
    else:
        caveat = "The references are same-ASR pseudo-gold/corpus-wide candidate labels, not an independently listened and annotated acceptance set."
    lines.extend([
        "",
        "## Compute and limits",
        "",
        "The MiniLM ONNX model files occupy about 86.9 MiB. Encoding all 17,591 transcript segments took 173.4 seconds on the Windows CPU used here, with repeated text/context pairs deduplicated across takes. The 48 MiB embedding cache is not shipped. Android runtime, memory, and battery use remain unmeasured; the app would also need the ONNX runtime and tokenizer.",
        "",
        f"{caveat} The transcript-derived ad identity and localized audio boundary evidence are reported separately. One episode per show means leave-one-episode-out is show-held-out for this small corpus. Neither model approaches the 0.90/0.90 target, and these results do not justify a production success claim.",
        "",
    ])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", type=Path, default=RESULTS)
    parser.add_argument("--label-file", type=Path, default=LABELS)
    parser.add_argument("--expected-sha256", default=EXPECTED_SHA256)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    results_path = args.result
    labels_path = args.label_file
    if not labels_path.is_absolute():
        labels_path = Path.cwd() / labels_path
    data = json.loads(results_path.read_text(encoding="utf-8"))
    raw_labels = labels_path.read_bytes()
    observed = hashlib.sha256(raw_labels).hexdigest()
    if args.expected_sha256 and observed != args.expected_sha256.lower():
        raise RuntimeError(f"Label file does not match expected SHA256: {observed}")
    data["protocol"]["label_sha256"] = observed
    if "prediction-triggered" in str(data["protocol"].get("evaluation_note", "")).casefold():
        data["protocol"]["evaluation_status"] = "exploratory; prediction-triggered labels"
        data["protocol"]["label_caveat"] = "prediction-triggered labels; same-ASR pseudo-gold, not independent human acceptance labels"
    else:
        data["protocol"]["evaluation_status"] = "provisional; known label limitations"
        data["protocol"]["label_caveat"] = "same-ASR pseudo-gold generated from transcript/corpus evidence; not independent human acceptance labels"
    snapshot = json.loads(raw_labels)
    references, _, _, _ = detector.parse_references(snapshot, labels_path.name)
    for name, result in data["models"].items():
        result["overlap_diagnostics"] = interval_stats(result["predictions"], references)
    results_path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    report_path = args.report or (results_path.parent / ("REPORT.md" if results_path.name == "evaluation.json" else f"{results_path.stem}_REPORT.md"))
    report_path.write_text(make_report(data), encoding="utf-8")
    print(f"Updated {results_path} and wrote {report_path} for {labels_path.name} {observed}")


if __name__ == "__main__":
    main()

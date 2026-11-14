"""Descriptive failure audit; never tunes on these held-out predictions."""
import argparse
import json
import statistics
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("evaluation", type=Path)
    parser.add_argument("--labels", type=Path, default=Path(__file__).parents[1] / "labels/ads_v2.json")
    parser.add_argument("--out", type=Path, default=Path(__file__).with_name("prediction_diagnostics.json"))
    args = parser.parse_args()
    evaluation = json.loads(args.evaluation.read_text(encoding="utf-8"))
    labels = json.loads(args.labels.read_text(encoding="utf-8"))
    output = {}
    for model, result in evaluation["models"].items():
        rows, pred_rows = [], []
        for ep, data in labels.items():
            for take, gold in data["dynamic_slots"].items():
                predictions = result["predictions"].get(ep, {}).get(take, [])
                for g in gold:
                    overlaps = []
                    for p in predictions:
                        overlap = max(0., min(g["end"], p["end"]) - max(g["start"], p["start"]))
                        union = max(g["end"], p["end"]) - min(g["start"], p["start"])
                        overlaps.append((overlap / union, overlap, p))
                    best = max(overlaps, key=lambda x: x[0]) if overlaps else (0., 0., None)
                    p = best[2]
                    # Predictions are disjoint in the production decoder.
                    coverage = min(1., sum(x[1] for x in overlaps) / (g["end"] - g["start"]))
                    rows.append({"episode": ep, "take": take, "gold": [g["start"], g["end"]],
                                 "coverage": round(coverage, 4), "best_iou": round(best[0], 4),
                                 "overlapping_predictions": sum(x[1] >= 2 for x in overlaps),
                                 "best_prediction": [p["start"], p["end"]] if p else None,
                                 "start_error": round(p["start"] - g["start"], 2) if p and best[1] else None,
                                 "end_error": round(p["end"] - g["end"], 2) if p and best[1] else None})
                for p in predictions:
                    overlap = sum(max(0., min(g["end"], p["end"]) - max(g["start"], p["start"])) for g in gold)
                    pred_rows.append({"episode": ep, "take": take, "pred": [p["start"], p["end"]],
                                      "ad_fraction": round(overlap / (p["end"] - p["start"]), 4)})
        errors = [max(abs(r["start_error"]), abs(r["end_error"])) for r in rows if r["start_error"] is not None]
        output[model] = {"strict": {k: result["interval"][k] for k in ("tp", "fp", "fn", "precision", "recall")},
                         "gold_count": len(rows), "predicted_count": len(pred_rows),
                         "gold_zero_overlap": sum(r["coverage"] == 0 for r in rows),
                         "gold_below_20pct_coverage": sum(r["coverage"] < .2 for r in rows),
                         "gold_at_least_80pct_coverage": sum(r["coverage"] >= .8 for r in rows),
                         "gold_multiple_overlapping_predictions": sum(r["overlapping_predictions"] > 1 for r in rows),
                         "predictions_zero_ad_overlap": sum(r["ad_fraction"] == 0 for r in pred_rows),
                         "predictions_below_20pct_ad": sum(r["ad_fraction"] < .2 for r in pred_rows),
                         "median_worst_edge_error_for_overlapping_gold": statistics.median(errors) if errors else None,
                         "per_gold": rows, "per_prediction": pred_rows}
    args.out.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    for name, stats in output.items():
        print(name, json.dumps({k: v for k, v in stats.items() if k not in ("per_gold", "per_prediction")}))


if __name__ == "__main__":
    main()

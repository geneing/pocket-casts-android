"""Aggregate the per-episode score.py outputs into the tables REPORT.md quotes.

Run:  .venv/bin/python summarize.py [--results results] [--out results/summary.json]
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np


def describe(deltas) -> dict:
    a = np.abs(np.array(deltas, dtype=float))
    return {
        "n": int(len(a)),
        "median": round(float(np.median(a)), 3),
        "p90": round(float(np.percentile(a, 90)), 3),
        "p99": round(float(np.percentile(a, 99)), 3),
        "max": round(float(a.max()), 3),
        "within_0.2": round(float((a <= 0.2).mean()), 4),
        "within_0.5": round(float((a <= 0.5).mean()), 4),
        "within_1.0": round(float((a <= 1.0).mean()), 4),
        "within_2.0": round(float((a <= 2.0).mean()), 4),
        "bias_median_signed": round(float(np.median(deltas)), 3),
    }


def fmt(v: float, nd: int = 2) -> str:
    return f"{v:.{nd}f}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", default="results")
    ap.add_argument("--out", default="results/summary.json")
    args = ap.parse_args()

    files = sorted(Path(args.results).glob("*.json"))
    files = [f for f in files if not f.name.startswith(("summary", "_"))]
    runs = [json.loads(f.read_text()) for f in files]
    runs = [r for r in runs if isinstance(r, dict) and "word_deltas" in r]
    if not runs:
        raise SystemExit("no results yet")

    ds_all, de_all = [], []
    ds_body, de_body = [], []
    for r in runs:
        ds_all += r["word_deltas"]["start"]
        de_all += r["word_deltas"]["end"]
        ds_body += r["word_deltas_body"]["start"]
        de_body += r["word_deltas_body"]["end"]

    whisper_b, moonshine_b = [], []
    boundary_rows = []
    for r in runs:
        for row in r["ad_boundaries"]["rows"]:
            row = {"episode": r["episode"], **row}
            boundary_rows.append(row)
            whisper_b.append(row["whisper_signed"])
            moonshine_b.append(row["moonshine_signed"])

    total_audio = sum(r["audio_seconds"] for r in runs)
    total_wall = sum(r["moonshine"]["seconds"] for r in runs)
    wer_num = sum(r["text"]["sub"] + r["text"]["del"] + r["text"]["ins"] for r in runs)
    wer_den = sum(r["text"]["ref_words"] for r in runs)

    summary = {
        "episodes": [r["episode"] for r in runs],
        "total_audio_seconds": round(total_audio, 1),
        "total_wall_seconds": round(total_wall, 2),
        "rtf_overall": round(total_audio / total_wall, 3),
        "wer_pooled": round(wer_num / wer_den, 4),
        "wer_terms": {"errors": wer_num, "ref_words": wer_den},
        "word_start_all": describe(ds_all),
        "word_end_all": describe(de_all),
        "word_start_body": describe(ds_body),
        "word_end_body": describe(de_body),
        "boundary_whisper": describe(whisper_b),
        "boundary_moonshine": describe(moonshine_b),
        "boundaries": boundary_rows,
        "per_episode": [
            {
                "episode": r["episode"],
                "audio_minutes": round(r["audio_seconds"] / 60, 1),
                "wer": r["text"]["wer"],
                "matched": r["text"]["ref_words"] - r["text"]["sub"] - r["text"]["del"],
                "start_median": r["word_timestamps"]["start"]["median"],
                "start_p90": r["word_timestamps"]["start"]["p90"],
                "end_median": r["word_timestamps"]["end"]["median"],
                "end_p90": r["word_timestamps"]["end"]["p90"],
                "wall_seconds": r["moonshine"]["seconds"],
                "rtf": r["moonshine"]["rtf"],
                "n_boundaries": r["ad_boundaries"]["n_boundaries"],
                "boundary_median": r["ad_boundaries"]["moonshine"]["median"],
                "boundary_p90": r["ad_boundaries"]["moonshine"]["p90"],
                "boundary_max": r["ad_boundaries"]["moonshine"]["max"],
            }
            for r in runs
        ],
    }
    Path(args.out).write_text(json.dumps(summary, indent=1))

    print("## Per episode\n")
    print("| episode | audio min | WER | word start median / p90 | word end median / p90 | wall s | RTF | ad boundaries | bnd median / p90 / max |")
    print("|---|---|---|---|---|---|---|---|---|")
    for p in summary["per_episode"]:
        print(
            f"| {p['episode'][:46]} | {p['audio_minutes']} | {p['wer']:.3f} "
            f"| {p['start_median']:.2f} / {p['start_p90']:.2f} | {p['end_median']:.2f} / {p['end_p90']:.2f} "
            f"| {p['wall_seconds']:.1f} | {p['rtf']:.1f}x | {p['n_boundaries']} "
            f"| {p['boundary_median']:.2f} / {p['boundary_p90']:.2f} / {p['boundary_max']:.2f} |"
        )

    ws, we = summary["word_start_all"], summary["word_end_all"]
    print("\n## Word timestamps, pooled\n")
    print("| | n | median | p90 | p99 | max | <=0.2 s | <=0.5 s | <=1.0 s | <=2.0 s | signed median |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for name, d in (("start", ws), ("end", we)):
        print(
            f"| {name} | {d['n']} | {d['median']:.2f} | {d['p90']:.2f} | {d['p99']:.2f} | {d['max']:.2f} "
            f"| {d['within_0.2'] * 100:.1f}% | {d['within_0.5'] * 100:.1f}% | {d['within_1.0'] * 100:.1f}% "
            f"| {d['within_2.0'] * 100:.1f}% | {d['bias_median_signed']:+.2f} |"
        )
    print("\nBody only (words outside the labelled ad pods):\n")
    print("| | n | median | p90 | p99 | max | <=0.2 s | <=0.5 s | <=1.0 s | <=2.0 s | signed median |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for name, d in (("start", summary["word_start_body"]), ("end", summary["word_end_body"])):
        print(
            f"| {name} | {d['n']} | {d['median']:.2f} | {d['p90']:.2f} | {d['p99']:.2f} | {d['max']:.2f} "
            f"| {d['within_0.2'] * 100:.1f}% | {d['within_0.5'] * 100:.1f}% | {d['within_1.0'] * 100:.1f}% "
            f"| {d['within_2.0'] * 100:.1f}% | {d['bias_median_signed']:+.2f} |"
        )

    for name, d in (("Whisper", summary["boundary_whisper"]), ("Moonshine", summary["boundary_moonshine"])):
        print(
            f"\n{name} boundary error (n={d['n']}): median {d['median']:.2f} s, p90 {d['p90']:.2f} s, "
            f"max {d['max']:.2f} s, <=1 s {d['within_1.0'] * 100:.0f}%"
        )

    print("\n| episode | edge | label s | whisper abs | moonshine abs | moonshine signed |")
    print("|---|---|---|---|---|---|")
    for row in sorted(summary["boundaries"], key=lambda r: -r["moonshine_abs"]):
        print(
            f"| {row['episode'][:40]} | {row['edge']} | {row['label_time']:.2f} | {row['whisper_abs']:.2f} "
            f"| {row['moonshine_abs']:.2f} | {row['moonshine_signed']:+.2f} |"
        )
    print(f"\npooled WER {summary['wer_pooled']:.3f} ({wer_num}/{wer_den}), RTF {summary['rtf_overall']:.2f}x")
    print(f"-> {args.out}")


if __name__ == "__main__":
    main()

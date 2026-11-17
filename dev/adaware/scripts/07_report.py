"""Plots and a human-readable summary of the detector's out-of-fold performance."""

from __future__ import annotations

import numpy as np

from common import DATA, REPORTS, read_json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402


def main() -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    metrics = read_json(DATA / "model" / "metrics.json")
    rows = metrics["episodes"]
    files = sorted((DATA / "model" / "oof").glob("*.npz"))
    if not files:
        raise SystemExit("run 05_train_eval.py first")

    fig, axes = plt.subplots(len(files), 1, figsize=(12, 1.2 * len(files)), sharex=False)
    if len(files) == 1:
        axes = [axes]
    for ax, path in zip(axes, files):
        d = np.load(path)
        t, p = d["times"], d["prob"]
        ax.fill_between(t / 60, 0, 1, where=d["gold_mask"] > 0, color="#e8b4b8", step="mid", label="ad (label)")
        ax.plot(t / 60, p, lw=0.6, color="#2b6cb0", label="p(ad)")
        ax.axhline(metrics["params"]["threshold"], color="k", lw=0.4, ls="--")
        ax.set_ylim(0, 1)
        ax.set_ylabel(path.stem[:28], fontsize=7)
        ax.tick_params(labelsize=6)
    axes[0].legend(fontsize=6, loc="upper right")
    axes[-1].set_xlabel("minutes")
    fig.suptitle("Out-of-fold ad probability vs labels", fontsize=9)
    fig.tight_layout()
    fig.savefig(REPORTS / "detector_timeline.png", dpi=140)

    lines = [
        "# Ad detector evaluation",
        "",
        f"- leave-one-episode-out, {len(rows)} episodes",
        f"- tolerance: both boundaries within {metrics['tolerance']:.0f} s",
        f"- interval precision {metrics['precision']:.3f}, recall {metrics['recall']:.3f}, "
        f"F1 {metrics['f1']:.3f} (micro)",
        f"- macro precision {metrics['macro_precision']:.3f}, recall {metrics['macro_recall']:.3f}",
        f"- post-processing: smooth {metrics['params']['smooth']} s, "
        f"threshold {metrics['params']['threshold']:.2f}, min duration {metrics['params']['min_duration']} s",
        "",
        "| episode | gold | pred | tp | fp | fn | P | R |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append(
            f"| {r['episode'][:44]} | {r['gold']} | {r['pred']} | {r['tp']} | {r['fp']} | {r['fn']} "
            f"| {r['p']:.2f} | {r['r']:.2f} |"
        )
    lines += ["", "![timeline](detector_timeline.png)", ""]
    (REPORTS / "evaluation.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {REPORTS / 'evaluation.md'}")


if __name__ == "__main__":
    main()

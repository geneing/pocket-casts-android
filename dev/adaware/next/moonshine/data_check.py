"""Data check: does the audio we downloaded still match the committed corpus?

Every request gets a fresh dynamically inserted ad pod, so a re-download of `take0` is not
the file `dev/adaware/data/transcripts/` and `dev/adaware/data/labels/` describe. This
prints the size/duration delta and the label counts for the pair, which is why M1
re-transcribes with Whisper and re-derives labels instead of reusing them.

Run:  .venv/bin/python data_check.py
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
AUDIO = HERE / ".audio"


def duration(p: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(p)],
        capture_output=True,
        text=True,
        check=True,
    )
    return float(out.stdout.strip())


def main() -> None:
    man = json.loads((ROOT / "data" / "downloads.json").read_text())
    old_spans = json.loads((ROOT / "data" / "labels" / "ad_spans.json").read_text())
    rows = []
    for ep_dir in sorted(AUDIO.iterdir()):
        if not ep_dir.is_dir() or ep_dir.name.startswith("_"):
            continue
        ep = ep_dir.name
        f = ep_dir / "take0.mp3"
        if not f.exists():
            continue
        dur = duration(f)
        expect = man[ep]["takes"][0]["duration"]
        new_labels = HERE / "labels" / f"{ep}.json"
        rows.append(
            {
                "episode": ep,
                "manifest_bytes": man[ep]["takes"][0]["bytes"],
                "downloaded_bytes": f.stat().st_size,
                "manifest_seconds": expect,
                "downloaded_seconds": round(dur, 2),
                "delta_seconds": round(dur - expect, 2),
                "committed_spans_take0": sum(1 for s in old_spans if s["episode"] == ep and s["take"] == 0),
                "rederived_spans": len(json.loads(new_labels.read_text())) if new_labels.exists() else None,
            }
        )
    out = HERE / "results" / "data_check.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rows, indent=1))
    print("| episode | manifest s | downloaded s | delta | manifest B | downloaded B | committed spans | re-derived |")
    print("|---|---|---|---|---|---|---|---|")
    for r in rows:
        print(
            f"| {r['episode'][:44]} | {r['manifest_seconds']:.1f} | {r['downloaded_seconds']:.1f} "
            f"| {r['delta_seconds']:+.1f} | {r['manifest_bytes']} | {r['downloaded_bytes']} "
            f"| {r['committed_spans_take0']} | {r['rederived_spans']} |"
        )
    print(f"-> {out}")


if __name__ == "__main__":
    main()

"""Merge the original ten-episode transcript corpus into the M0 corpus as training data.

Legacy episodes whose show is reserved for the M0 test split are skipped, so the held-out
shows stay clean. Words are dropped from the copies: the detector only needs segment text and
timestamps.
"""

from __future__ import annotations

import json
import re
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
ADWARE = HERE.parents[1]
LEGACY_TRANSCRIPTS = ADWARE / "data" / "transcripts"
LEGACY_LABELS = HERE.parent / "labels" / "ads_v3.json"
CORPUS = HERE / "corpus"


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def main() -> None:
    plan = json.loads((CORPUS / "plan.json").read_text())
    test_shows = {slug(p["show"]) for p in plan if p["split"] == "test"}
    labels = json.loads((CORPUS / "labels" / "ads.json").read_text()) if (CORPUS / "labels" / "ads.json").exists() else {}
    legacy = json.loads(LEGACY_LABELS.read_text())
    merged = 0
    skipped = 0
    for episode, data in legacy.items():
        show = episode.split("__")[0]
        if show in test_shows:
            skipped += 1
            continue
        source = LEGACY_TRANSCRIPTS / episode
        if not source.is_dir():
            continue
        target = CORPUS / "transcripts" / episode
        target.mkdir(parents=True, exist_ok=True)
        for path in source.glob("take*.json"):
            payload = json.loads(path.read_text())
            payload["segments"] = [
                {"start": s["start"], "end": s["end"], "text": s["text"]}
                for s in payload.get("segments", [])
                if s.get("text")
            ]
            payload.pop("words", None)
            (target / path.name).write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        slots = {
            str(take): [
                {"start": float(s["start"]), "end": float(s["end"]), "text": str(s.get("text", ""))[:400]}
                for s in items
            ]
            for take, items in data.get("dynamic_slots", {}).items()
        }
        labels[episode] = {
            "duration": float(data.get("duration", 0.0)),
            "takes": sorted(slots),
            "dynamic_slots": slots,
            "baked_candidates": [],
            "show": show,
            "episode": episode,
            "split": "train",
            "source": "legacy-v3",
        }
        merged += 1
    (CORPUS / "labels" / "ads.json").write_text(json.dumps(labels, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"merged {merged} legacy episodes, skipped {skipped} on test shows")


if __name__ == "__main__":
    main()

"""Structural and change audit for versioned podcast ad annotations.

This checks completeness and interval geometry. It cannot certify that an
interval contains an ad; that requires independent content review.
"""

import argparse
import json
from collections import Counter
from pathlib import Path


def intervals_for(episode):
    return episode.get("dynamic_slots", {})


def audit(old, new, transcript_root=None):
    errors = []
    warnings = []
    changes = []
    provenance = Counter()
    old_count = new_count = 0
    old_seconds = new_seconds = 0.0
    for name in sorted(set(old) | set(new)):
        before, after = old.get(name, {}), new.get(name, {})
        old_takes, new_takes = intervals_for(before), intervals_for(after)
        if name not in new:
            errors.append(f"Missing episode: {name}")
        for take in sorted(set(old_takes) | set(new_takes)):
            if take not in new_takes:
                errors.append(f"Missing take: {name}/take{take}")
            originals = old_takes.get(take, [])
            current = new_takes.get(take, [])
            if len(originals) >= 3 and len(current) * 2 < len(originals):
                warnings.append(f"Large label drop: {name}/take{take}: {len(originals)} -> {len(current)}")
            old_count += len(originals)
            new_count += len(current)
            old_seconds += sum(float(x["end"]) - float(x["start"]) for x in originals)
            previous_end = -1.0
            duration = float(after.get("duration", before.get("duration", float("inf"))))
            if transcript_root is not None:
                transcript = transcript_root / name / f"take{take}.json"
                if transcript.exists():
                    duration = float(json.loads(transcript.read_text(encoding="utf-8"))["duration"])
            for i, interval in enumerate(current):
                try:
                    start, end = float(interval["start"]), float(interval["end"])
                except (KeyError, TypeError, ValueError) as exc:
                    errors.append(f"Bad bounds: {name}/take{take}/interval{i}: {exc}")
                    continue
                if not (0 <= start < end <= duration + 1):
                    errors.append(f"Out of range: {name}/take{take}/interval{i}: {start}-{end} / {duration}")
                if start < previous_end:
                    errors.append(f"Unsorted or overlapping: {name}/take{take}/interval{i}")
                uncertainty = interval.get("uncertainty_seconds")
                if uncertainty is not None and float(uncertainty) >= end - start:
                    warnings.append(f"Uncertainty >= duration: {name}/take{take}/interval{i}")
                previous_end = max(previous_end, end)
                new_seconds += end - start
                provenance[str(interval.get("provenance", interval.get("source", "unspecified")))] += 1
            changes.append({"episode": name, "take": take, "old": len(originals), "new": len(current),
                            "old_seconds": round(sum(float(x["end"]) - float(x["start"]) for x in originals), 2),
                            "new_seconds": round(sum(float(x["end"]) - float(x["start"]) for x in current), 2)})
    return {"old_intervals": old_count, "new_intervals": new_count,
            "old_minutes": round(old_seconds / 60, 2), "new_minutes": round(new_seconds / 60, 2),
            "episodes": len(new), "takes": len(changes), "provenance": dict(provenance),
            "errors": errors, "warnings": warnings, "per_take": changes}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("new_labels", type=Path)
    parser.add_argument("--old-labels", type=Path,
                        default=Path(__file__).resolve().parents[2] / "data" / "labels" / "ads.json")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    old = json.loads(args.old_labels.read_text(encoding="utf-8"))
    new = json.loads(args.new_labels.read_text(encoding="utf-8"))
    result = audit(old, new, Path(__file__).resolve().parents[2] / "data" / "transcripts")
    rendered = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "per_take"}, indent=2))
    if result["errors"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

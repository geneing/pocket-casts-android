#!/usr/bin/env python3
"""Create the v3 uncertainty-first review queue and compact boundary clips.

Reads the versioned ads_v3.json and source transcripts/audio. Writes only within
this labels directory. Run from the repository root with the bundled Python:

  python dev/adaware/next/labels/build_review_queue_v3.py

Clips are 20-second mono MP3 excerpts centered on each of the 10 widest-
uncertainty pods' start and end boundaries. They are machine-generated aids;
no listening or manual verification is implied.
"""
from __future__ import annotations

import csv
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
DATA = ROOT / "dev" / "adaware" / "data"
LABELS = HERE
ADS_PATH = LABELS / "ads_v3.json"
JSON_PATH = LABELS / "review_queue_v3.json"
CSV_PATH = LABELS / "review_queue_v3.csv"
REPORT_PATH = LABELS / "review_queue_v3.md"
CLIP_DIR = LABELS / "review_clips_v3"
CLIP_SECONDS = 20.0
TOP_CLIP_COUNT = 10
FFMPEG_CANDIDATES = [
    ROOT / "dev" / "adaware" / "next" / "audio_review" / ".uv-cache" /
    "archive-v0" / "1XPeA9RSkrbMYxJU" / "imageio_ffmpeg" / "binaries" /
    "ffmpeg-win-x86_64-v9.0.2.exe",
    Path(shutil.which("ffmpeg") or ""),
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_words(ep: str, take: int) -> list[dict[str, Any]]:
    path = DATA / "transcripts" / ep / f"take{take}.json"
    if not path.exists():
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    words: list[dict[str, Any]] = []
    for segment in data.get("segments", []):
        for word in segment.get("words", []):
            try:
                words.append({"start": float(word["s"]), "end": float(word["e"]),
                              "text": str(word.get("w", "")).strip()})
            except (KeyError, TypeError, ValueError):
                continue
    return words


def nearby(words: list[dict[str, Any]], at: float, radius: float = 10.0) -> str:
    selected = [w["text"] for w in words
                if w["end"] >= max(0.0, at - radius) and w["start"] <= at + radius and w["text"]]
    return re.sub(r"\s+", " ", " ".join(selected)).strip()


def safe_slug(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_-]+", "_", value).strip("_")[:72]


def find_ffmpeg() -> Path | None:
    return next((p for p in FFMPEG_CANDIDATES if p and p.is_file()), None)


def render_clip(ffmpeg: Path, source: Path, dest: Path, boundary: float,
                duration: float) -> dict[str, Any]:
    start = max(0.0, boundary - CLIP_SECONDS / 2.0)
    clip_length = min(CLIP_SECONDS, max(0.0, duration - start))
    if clip_length <= 0:
        return {"path": None, "error": "boundary is outside source duration"}
    dest.parent.mkdir(parents=True, exist_ok=True)
    cmd = [str(ffmpeg), "-hide_banner", "-loglevel", "error", "-y",
           "-ss", f"{start:.3f}", "-i", str(source), "-t", f"{clip_length:.3f}",
           "-vn", "-ac", "1", "-ar", "22050", "-b:a", "32k", str(dest)]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        return {"path": None, "error": result.stderr.strip()[-1000:]}
    return {"path": dest.relative_to(LABELS).as_posix(),
            "clip_start_seconds": round(start, 3),
            "clip_end_seconds": round(start + clip_length, 3),
            "boundary_offset_seconds": round(boundary - start, 3)}


def main() -> None:
    original_hash = sha256(ADS_PATH)
    ads = json.loads(ADS_PATH.read_text(encoding="utf-8"))
    rows: list[dict[str, Any]] = []

    for episode, entry in ads.items():
        take_paths = entry.get("takes", [])
        for slot_id, intervals in entry.get("dynamic_slots", {}).items():
            for item in intervals:
                uncertainty = float(item.get("uncertainty_seconds", 0.0))
                if uncertainty <= 10.0:
                    continue
                take = int(item["take"])
                words = load_words(episode, take)
                start = float(item["start"])
                end = float(item["end"])
                source_rel = (take_paths[take] if take < len(take_paths)
                              else f"audio/{episode}/take{take}.mp3")
                start_sources = item.get("boundary_sources", {}).get("start", {})
                end_sources = item.get("boundary_sources", {}).get("end", {})
                rows.append({
                    "episode": episode,
                    "take": take,
                    "slot_id": slot_id,
                    "label_id": item.get("label_id", ""),
                    "start_seconds": start,
                    "end_seconds": end,
                    "uncertainty_seconds": uncertainty,
                    "start_uncertainty_seconds": float(start_sources.get("uncertainty_seconds", uncertainty)),
                    "end_uncertainty_seconds": float(end_sources.get("uncertainty_seconds", uncertainty)),
                    "start_basis": start_sources.get("basis", item.get("boundary_basis", "unknown")),
                    "end_basis": end_sources.get("basis", item.get("boundary_basis", "unknown")),
                    "provenance": item.get("provenance", "unknown"),
                    "evaluation_role": item.get("evaluation_role", "reference_candidate"),
                    "classification": item.get("classification", "unknown"),
                    "confidence": item.get("confidence", "unknown"),
                    "start_nearby_transcript_text": nearby(words, start),
                    "end_nearby_transcript_text": nearby(words, end),
                    "audio_path": source_rel.replace("\\", "/"),
                    "start_clip": None,
                    "end_clip": None,
                })

    rows.sort(key=lambda row: (-row["uncertainty_seconds"], row["episode"], row["take"], row["start_seconds"]))
    for index, row in enumerate(rows, 1):
        row["review_rank"] = index

    ffmpeg = find_ffmpeg()
    clip_errors: list[dict[str, str]] = []
    if ffmpeg:
        CLIP_DIR.mkdir(exist_ok=True)
        for row in rows[:TOP_CLIP_COUNT]:
            source = DATA / row["audio_path"]
            if not source.is_file():
                clip_errors.append({"label_id": row["label_id"], "error": f"missing audio: {source}"})
                continue
            duration = float(json.loads((DATA / "transcripts" / row["episode"] /
                                         f"take{row['take']}.json").read_text(encoding="utf-8"))["duration"])
            stem = f"{row['review_rank']:02d}_{safe_slug(row['episode'])}_t{row['take']}_{row['label_id']}"
            for edge, boundary in (("start", row["start_seconds"]), ("end", row["end_seconds"])):
                clip_path = CLIP_DIR / f"{stem}_{edge}.mp3"
                result = render_clip(ffmpeg, source, clip_path, boundary, duration)
                row[f"{edge}_clip"] = result
                if result.get("error"):
                    clip_errors.append({"label_id": row["label_id"], "edge": edge, "error": result["error"]})

    output = {
        "source_labels": ADS_PATH.relative_to(ROOT).as_posix(),
        "source_labels_sha256": original_hash,
        "review_threshold_seconds_exclusive": 10.0,
        "interval_count": len(rows),
        "clip_policy": {
            "top_uncertainty_intervals": TOP_CLIP_COUNT if ffmpeg else 0,
            "clip_duration_seconds": CLIP_SECONDS,
            "format": "mono MP3 at 32 kbps",
            "ffmpeg_available": bool(ffmpeg),
            "clips_are_listening_verified": False,
            "errors": clip_errors,
        },
        "intervals": rows,
    }
    JSON_PATH.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    fields = ["review_rank", "episode", "take", "slot_id", "label_id", "start_seconds", "end_seconds",
              "uncertainty_seconds", "start_uncertainty_seconds", "end_uncertainty_seconds",
              "start_basis", "end_basis", "provenance", "evaluation_role", "classification", "confidence",
              "start_nearby_transcript_text", "end_nearby_transcript_text", "audio_path",
              "start_clip_path", "start_clip_window", "end_clip_path", "end_clip_window"]
    with CSV_PATH.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        csv_rows = []
        for row in rows:
            flat = dict(row)
            for edge in ("start", "end"):
                clip = row.get(f"{edge}_clip") or {}
                flat[f"{edge}_clip_path"] = clip.get("path", "")
                if clip.get("path"):
                    flat[f"{edge}_clip_window"] = (
                        f"{clip['clip_start_seconds']:.3f}-{clip['clip_end_seconds']:.3f}s"
                    )
                else:
                    flat[f"{edge}_clip_window"] = ""
            csv_rows.append(flat)
        writer.writerows(csv_rows)

    clip_count = sum(bool((r.get("start_clip") or {}).get("path")) + bool((r.get("end_clip") or {}).get("path")) for r in rows)
    clip_bytes = sum(path.stat().st_size for path in CLIP_DIR.glob("*.mp3")) if CLIP_DIR.exists() else 0
    report = [
        "# V3 boundary review queue", "",
        f"Source labels: `{ADS_PATH.name}` (SHA-256 `{original_hash}`).",
        f"The queue contains {len(rows)} v3 pods whose estimated overall boundary uncertainty exceeds 10 seconds.",
        "Each row includes episode/take, interval, overall and per-edge uncertainty, boundary basis, provenance, nearby transcript context, and source audio path.",
        f"The {TOP_CLIP_COUNT} widest-uncertainty intervals have 20-second start and end excerpts ({clip_count} clips, {clip_bytes} bytes total) under `review_clips_v3/`.",
        "Clips were machine-extracted with FFmpeg. They have not been listened to or manually verified.", "",
        "Regenerate the queue and clips from the frozen v3 labels with the bundled Python runtime:", "",
        "```powershell",
        "& 'C:\\Users\\genei\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe' `",
        "  dev/adaware/next/labels/build_review_queue_v3.py",
        "```", "",
        "The v2 queue remains available as the frozen checkpoint in `review_queue.json`, `review_queue.csv`, and `review_clips/`.",
    ]
    REPORT_PATH.write_text("\n".join(report) + "\n", encoding="utf-8")

    if sha256(ADS_PATH) != original_hash:
        raise RuntimeError("ads_v3.json changed while building the review queue")
    print(f"queued={len(rows)} clips={sum(bool((r.get('start_clip') or {}).get('path')) + bool((r.get('end_clip') or {}).get('path')) for r in rows)} "
          f"labels_sha256={original_hash}")
    print(f"wrote {JSON_PATH} and {CSV_PATH}")


if __name__ == "__main__":
    main()

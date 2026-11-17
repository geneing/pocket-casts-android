"""Transcribe every take with faster-whisper (small), keeping word timestamps.

Usage:
  uv run scripts/02_transcribe.py            # all takes
  uv run scripts/02_transcribe.py --only key --limit 2
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from common import DATA, TRANSCRIPTS, read_json, write_json


def setup_cuda_libs() -> None:
    """faster-whisper (ctranslate2 4.x) needs cuBLAS and cuDNN 9 from the nvidia wheels."""
    import site

    paths = []
    for sp in site.getsitepackages() + [site.getusersitepackages()]:
        nvidia = Path(sp) / "nvidia"
        if not nvidia.exists():
            continue
        for lib in ("cublas", "cudnn"):
            p = nvidia / lib / "lib"
            if p.exists():
                paths.append(str(p))
    if paths:
        os.environ["LD_LIBRARY_PATH"] = ":".join(paths + [os.environ.get("LD_LIBRARY_PATH", "")])
        # ctranslate2 dlopens these by exact soname, help it find the wheels' copies.
        os.environ.setdefault("CT2_FORCE_CPU_ISA", "")


def build_model(device: str, compute: str):
    from faster_whisper import WhisperModel

    root = Path(os.environ.get("WHISPER_CACHE", DATA / "models" / "whisper"))
    root.mkdir(parents=True, exist_ok=True)
    return WhisperModel(
        "small",
        device=device,
        compute_type=compute,
        download_root=str(root),
        cpu_threads=os.cpu_count() or 8,
    )


def load_model():
    attempts = [("cuda", "float16"), ("cpu", "int8")]
    errors = []
    for device, compute in attempts:
        try:
            model = build_model(device, compute)
            if device == "cuda":
                # a real inference is the only reliable probe
                import numpy as np

                list(model.transcribe(np.zeros(16000 * 2, dtype=np.float32), beam_size=1)[0])
            print(f"whisper: {device}/{compute}", flush=True)
            return model
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{device}/{compute}: {exc!r}"[:300])
    raise SystemExit("no whisper backend:\n" + "\n".join(errors))


def AUDIO_MP3S():
    from common import AUDIO

    yield from sorted(AUDIO.glob("*/*.mp3"))


def decode_audio(path: Path) -> "np.ndarray":
    """Decode with ffmpeg and hand whisper raw samples (av 19 dropped metadata_errors)."""
    import numpy as np

    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", "16000", "-f", "f32le", "-"],
        capture_output=True,
        check=True,
    )
    return np.frombuffer(out.stdout, dtype=np.float32).copy()


def transcribe(model, path: Path, out: Path) -> dict:
    t0 = time.time()
    segments, info = model.transcribe(
        decode_audio(path),
        language="en",
        beam_size=5,
        word_timestamps=True,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500},
        condition_on_previous_text=False,
    )
    rows = []
    for seg in segments:
        rows.append(
            {
                "start": round(seg.start, 2),
                "end": round(seg.end, 2),
                "text": seg.text.strip(),
                "words": [
                    {"w": w.word, "s": round(w.start, 2), "e": round(w.end, 2)} for w in (seg.words or [])
                ],
            }
        )
    result = {
        "file": str(path.relative_to(DATA)).replace("\\", "/"),
        "duration": round(info.duration, 2),
        "language": info.language,
        "language_probability": round(info.language_probability, 3),
        "model": "small",
        "seconds": round(time.time() - t0, 1),
        "segments": rows,
    }
    write_json(out, result)
    return result


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--device", default="", help="force cuda|cpu")
    args = ap.parse_args()

    setup_cuda_libs()
    if args.device == "cpu":
        model = build_model("cpu", "int8")
    else:
        model = load_model()

    done = 0
    for path in sorted((DATA / "audio").glob("*/*.mp3")):
        if args.only and args.only not in str(path):
            continue
        out = TRANSCRIPTS / path.parent.name / f"{path.stem}.json"
        if out.exists():
            continue
        print(f"transcribe {path.parent.name}/{path.name}", flush=True)
        try:
            res = transcribe(model, path, out)
        except Exception as exc:  # noqa: BLE001
            print("  FAILED", repr(exc)[:300], flush=True)
            continue
        print(
            f"  {res['duration'] / 60:.1f} min audio in {res['seconds'] / 60:.1f} min, "
            f"{len(res['segments'])} segments",
            flush=True,
        )
        done += 1
        if args.limit and done >= args.limit:
            break


if __name__ == "__main__":
    main()

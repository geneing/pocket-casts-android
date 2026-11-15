"""Reference transcript for M1: faster-whisper small (the same model and settings as
dev/adaware/scripts/02_transcribe.py), word timestamps on.

Why this exists: every download gets a fresh dynamically inserted ad pod, so the committed
`data/transcripts/` files describe audio that is no longer on disk. To compare Moonshine
and Whisper on *identical* samples this re-transcribes the audio M1 actually downloaded.

Run with the existing project venv (it has faster-whisper + CUDA wheels):
  ../../.venv/bin/python whisper_reference.py --audio .audio/EP/take0.mp3 --out ref/EP/take0.json
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
from pathlib import Path


def setup_cuda_libs() -> None:
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


def decode_audio(path: Path) -> "object":
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", "16000", "-f", "f32le", "-"],
        capture_output=True,
        check=True,
    )
    import numpy as np

    return np.frombuffer(out.stdout, dtype=np.float32).copy()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--audio", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--language", default="en")
    ap.add_argument("--device", default="")
    ap.add_argument("--no-vad", action="store_true", help="disable Whisper's VAD filter (sensitivity check)")
    args = ap.parse_args()

    setup_cuda_libs()
    from faster_whisper import WhisperModel

    root = Path(__file__).resolve().parents[2] / "data" / "models" / "whisper"
    root.mkdir(parents=True, exist_ok=True)
    attempts = [(args.device or "cuda", "float16"), ("cpu", "int8")]
    model = None
    for device, compute in attempts:
        try:
            m = WhisperModel("small", device=device, compute_type=compute, download_root=str(root))
            import numpy as np

            list(m.transcribe(np.zeros(16000 * 2, dtype=np.float32), beam_size=1)[0])
            model, used = m, f"{device}/{compute}"
            break
        except Exception as exc:  # noqa: BLE001
            print(f"whisper {device}/{compute} unavailable: {exc!r}"[:300])
    if model is None:
        raise SystemExit("no whisper backend")
    print(f"whisper backend: {used}", flush=True)

    audio = Path(args.audio)
    t0 = time.time()
    segments, info = model.transcribe(
        decode_audio(audio),
        language=args.language,
        beam_size=5,
        word_timestamps=True,
        vad_filter=not args.no_vad,
        vad_parameters={"min_silence_duration_ms": 500} if not args.no_vad else None,
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
        "file": str(audio),
        "duration": round(info.duration, 2),
        "language": info.language,
        "language_probability": round(info.language_probability, 3),
        "model": "small",
        "backend": used,
        "seconds": round(time.time() - t0, 1),
        "segments": rows,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=1))
    n_words = sum(len(s["words"]) for s in rows)
    print(
        f"{audio.name}: {result['duration'] / 60:.1f} min audio in {result['seconds'] / 60:.1f} min "
        f"({len(rows)} segments, {n_words} words) -> {out}",
        flush=True,
    )


if __name__ == "__main__":
    main()

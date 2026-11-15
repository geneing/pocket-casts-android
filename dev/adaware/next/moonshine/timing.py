"""Inference speed: batch vs streaming (and streaming chunk size) on the same clip.

The Android plan (RESEARCH.md D7) uses the streaming API, so its RTF — not the batch one —
is what a device has to pay. Both are measured here on one 120 s clip so the comparison is
apples to apples, plus a 0.5 s / 1.0 s / 2.0 s chunk sweep for the streaming path.

Run:  .venv/bin/python timing.py [--clip .audio/_test/slice120.mp3]
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import time
from pathlib import Path

import numpy as np

from moonshine_voice.moonshine_api import ModelArch
from moonshine_voice.transcriber import Transcriber
from transcribe import decode, run_batch, run_streaming, words_of

HERE = Path(__file__).resolve().parent


def cpu_model() -> str:
    for line in Path("/proc/cpuinfo").read_text().splitlines():
        if line.startswith("model name"):
            return line.split(":", 1)[1].strip()
    return platform.processor()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--clip", default=str(HERE / ".audio" / "_test" / "slice120.mp3"))
    ap.add_argument("--model-dir", default=str(HERE / "models" / "en" / "quantized_26_11_02"))
    ap.add_argument("--out", default=str(HERE / "out" / "_timing.json"))
    ap.add_argument("--modes", nargs="+", default=["batch", "streaming:0.5", "streaming:1.0", "streaming:2.0"])
    args = ap.parse_args()

    audio = decode(Path(args.clip))
    audio_s = len(audio) / 16000.0
    rows = []
    for spec in args.modes:
        mode, _, chunk = spec.partition(":")
        chunk = float(chunk) if chunk else 0.0
        tr = Transcriber(
            model_path=args.model_dir, model_arch=ModelArch.TINY_STREAMING, options={"word_timestamps": "true"}
        )
        t0 = time.time()
        lines = (
            run_batch(tr, audio, 16000)
            if mode == "batch"
            else run_streaming(tr, audio, 16000, chunk)
        )
        secs = time.time() - t0
        tr.close()
        words = words_of(lines)
        rows.append(
            {
                "mode": mode,
                "chunk_s": chunk or None,
                "audio_seconds": round(audio_s, 2),
                "wall_seconds": round(secs, 2),
                "rtf": round(audio_s / secs, 3),
                "n_words": len(words),
                "n_lines": len(lines),
            }
        )
        print(f"{mode:9s} chunk={chunk or '-'}  {audio_s:.0f}s audio in {secs:.1f}s -> RTF {audio_s / secs:.2f}x "
              f"({len(words)} words)")

    hw = {
        "cpu": cpu_model(),
        "threads": os.cpu_count(),
        "loadavg": os.getloadavg(),
        "ort_providers": "CPUExecutionProvider (only provider in the linux x86_64 libmoonshine.so)",
        "gpu_used": False,
        "python": platform.python_version(),
        "kernel": platform.release(),
    }
    Path(args.out).write_text(json.dumps({"hardware": hw, "runs": rows}, indent=1))
    print(f"-> {args.out}")


if __name__ == "__main__":
    main()

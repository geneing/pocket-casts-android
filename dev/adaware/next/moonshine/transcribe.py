"""Transcribe one audio file with Moonshine and emit segments + words as JSON.

Two paths, both requested with the attention decoder enabled by the `word_timestamps`
transcriber option:

  streaming  feed 0.5 s chunks through the same streaming API a phone would use, then
             take the finished `Transcript` (`--mode streaming`, default)
  batch      one call to `moonshine_transcribe_without_streaming`. The python wrapper
             builds its ctypes array from a python list, which is unusable for 40 min of
             audio, so this passes a numpy buffer straight to the C API.

Output schema (deliberately close to dev/adaware/data/transcripts/*.json):
  {file, duration, model, model_dir, arch, mode, seconds, audio_seconds, rtf,
   word_timestamps_requested, words:[{w,s,e,conf}], segments:[{start,end,text,words:[...]}]}

Run:  .venv/bin/python transcribe.py --audio .audio/EP/take0.mp3 \
        --model-dir models/en/quantized_26_11_02 --out out/EP/take0.json
"""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import subprocess
import time
from pathlib import Path

import numpy as np

from moonshine_voice.moonshine_api import ModelArch, model_arch_to_string
from moonshine_voice.transcriber import Transcriber

HERE = Path(__file__).resolve().parent


def decode(path: Path, sample_rate: int = 16000) -> np.ndarray:
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(sample_rate), "-f", "f32le", "-"],
        capture_output=True,
        check=True,
    )
    return np.frombuffer(out.stdout, dtype=np.float32).copy()


def words_of(lines) -> list[dict]:
    out = []
    for line in lines:
        for w in line.words or []:
            text = (w.word or "").strip()
            if not text:
                continue
            out.append(
                {"w": text, "s": round(w.start, 2), "e": round(w.end, 2), "conf": round(w.confidence, 3)}
            )
    return out


def run_streaming(tr: Transcriber, audio: np.ndarray, sample_rate: int, chunk_s: float):
    tr.start()
    step = int(chunk_s * sample_rate)
    for i in range(0, len(audio), step):
        tr.add_audio(audio[i : i + step], sample_rate)
    transcript = tr.stop()
    return transcript.lines if transcript else []


def run_batch(tr: Transcriber, audio: np.ndarray, sample_rate: int):
    from moonshine_voice.errors import check_error
    from moonshine_voice.moonshine_api import TranscriptC

    buf = np.ascontiguousarray(audio, dtype=np.float32)
    ptr = buf.ctypes.data_as(ctypes.POINTER(ctypes.c_float))
    transcript_ptr = ctypes.POINTER(TranscriptC)()
    err = tr._lib.moonshine_transcribe_without_streaming(
        tr._handle, ptr, len(buf), sample_rate, 0, ctypes.byref(transcript_ptr)
    )
    check_error(err)
    parsed = tr._parse_transcript(transcript_ptr)
    free = getattr(tr._lib, "moonshine_free_transcript", None)
    if free is not None:
        free(transcript_ptr)
    return parsed.lines


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--audio", required=True)
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--arch", default="tiny-streaming")
    ap.add_argument("--mode", choices=["streaming", "batch"], default="streaming")
    ap.add_argument("--chunk", type=float, default=0.5)
    ap.add_argument("--out", default="")
    ap.add_argument("--word-timestamps", action="store_true", default=True)
    ap.add_argument("--no-word-timestamps", dest="word_timestamps", action="store_false")
    args = ap.parse_args()

    arch = {model_arch_to_string(a): a for a in ModelArch}[args.arch]
    model_dir = Path(args.model_dir)
    if not model_dir.is_absolute():
        model_dir = HERE / model_dir

    options = {"word_timestamps": "true"} if args.word_timestamps else {}
    audio = decode(Path(args.audio))
    sample_rate = 16000

    t_load = time.time()
    tr = Transcriber(model_path=model_dir, model_arch=arch, options=options)
    load_s = time.time() - t_load

    t0 = time.time()
    if args.mode == "streaming":
        lines = run_streaming(tr, audio, sample_rate, args.chunk)
    else:
        lines = run_batch(tr, audio, sample_rate)
    seconds = time.time() - t0
    tr.close()

    words = words_of(lines)
    audio_seconds = len(audio) / sample_rate
    result = {
        "file": str(args.audio),
        "duration": round(audio_seconds, 2),
        "model": "tiny-streaming",
        "model_dir": str(model_dir),
        "arch": model_arch_to_string(arch),
        "mode": args.mode,
        "word_timestamps_requested": args.word_timestamps,
        "attention_decoder_present": (model_dir / "decoder_kv_with_attention.ort").exists(),
        "load_seconds": round(load_s, 2),
        "seconds": round(seconds, 2),
        "audio_seconds": round(audio_seconds, 2),
        "rtf": round(audio_seconds / seconds, 3) if seconds else None,
        "n_words": len(words),
        "words": words,
        "segments": [
            {
                "start": round(line.start_time, 2),
                "end": round(line.start_time + line.duration, 2),
                "text": line.text.strip(),
                "words": [
                    {"w": w["w"], "s": w["s"], "e": w["e"]}
                    for w in words_of([line])
                ],
            }
            for line in lines
        ],
    }
    out = Path(args.out) if args.out else HERE / "out" / (Path(args.audio).parent.name + ".json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=1))
    print(
        f"{Path(args.audio).name}: {audio_seconds / 60:.1f} min audio in {seconds / 60:.2f} min "
        f"(RTF {result['rtf']}, {len(words)} words, {len(lines)} lines) -> {out}",
        flush=True,
    )


if __name__ == "__main__":
    main()

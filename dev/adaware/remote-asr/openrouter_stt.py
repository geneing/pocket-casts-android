#!/usr/bin/env python3
"""Probe OpenRouter's speech-to-text endpoint for the Nemotron ASR model.

This is not app code. It is the script that answered the questions the remote
recognizer's design depends on:

  * does wav work, does mp3 work
  * does verbose_json return segment and word timestamps for this model
  * does source_url (the provider downloads a podcast URL itself) work
  * how large may one request be, and what does a too-large one return
  * does ``stream: true`` do anything (SSE), in case URL input is unavailable
  * what does usage.cost / usage.seconds report

Run it from WSL: ``OPENROUTER_API_KEY=... python3 openrouter_stt.py <mode>``.

Modes:
  fixtures                  download the test clips into ./data
  file <path> [--url]       transcribe a local file by base64 or by multipart upload
  multipart <path>          the OpenAI-style multipart upload (25 MB file cap)
  source-url <url>          let the provider download the audio from <url>
  feed <rss url>            print the enclosure URLs of a podcast feed
  stream <path>             ask for an SSE stream and report what comes back
  pcm <path> [seconds]      raw 16 kHz mono s16le instead of a container
  language <path> [lang]    omit the language to see what the model auto-detects
  boundary <path> <cut>     transcribe either side of a hard cut and show the seam
  formats <path> [seconds]  the same clip as wav, mp3, m4a and ogg, and what each costs
  limits <path> [seconds…]  probe what payload size and duration are accepted
  suite                     run every question above in order
  suite-fast                everything except the size ladder
"""

from __future__ import annotations

import base64
import json
import mimetypes
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

MODEL = "nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b"
ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"
DATA = Path(__file__).resolve().parent / "data"
KEY = os.environ.get("OPENROUTER_API_KEY", "")

MIME = {
    "wav": "audio/wav",
    "mp3": "audio/mpeg",
    "m4a": "audio/mp4",
    "flac": "audio/flac",
    "ogg": "audio/ogg",
    "webm": "audio/webm",
}


def die(message: str) -> None:
    print(f"FAILED: {message}")
    sys.exit(1)


def require_key() -> str:
    if not KEY:
        die("OPENROUTER_API_KEY is not set")
    return KEY


def pretty(value) -> str:
    return json.dumps(value, indent=2, ensure_ascii=False)


def post(body: bytes, content_type: str, label: str, headers: dict[str, str] | None = None) -> dict:
    """POST [body] and return {status, seconds, body, headers} for inspection."""
    request = urllib.request.Request(ENDPOINT, data=body, method="POST")
    request.add_header("Authorization", f"Bearer {require_key()}")
    request.add_header("Content-Type", content_type)
    request.add_header("HTTP-Referer", "https://pocketcasts.com")
    request.add_header("X-Title", "Pocket Casts remote ASR probe")
    for name, value in (headers or {}).items():
        request.add_header(name, value)

    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=900) as response:
            status = response.status
            raw = response.read()
            received = dict(response.headers)
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read()
        received = dict(error.headers)
    except Exception as error:  # noqa: BLE001
        return {"label": label, "status": "transport", "error": repr(error), "seconds": round(time.time() - started, 2)}

    elapsed = round(time.time() - started, 2)
    text = raw.decode("utf-8", errors="replace")
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        parsed = text[:4000]
    print(f"\n=== {label}: HTTP {status} in {elapsed}s, {len(raw)} bytes")
    print(pretty(parsed)[:12000])
    return {"label": label, "status": status, "seconds": elapsed, "body": parsed, "headers": received}


def audio_format(path: Path) -> str:
    suffix = path.suffix.lower().lstrip(".")
    if suffix in MIME:
        return suffix
    guessed = mimetypes.guess_type(path.name)[0] or ""
    return guessed.split("/")[-1] or "wav"


def base64_body(path: Path, verbose: bool, granularities: list[str], language: str | None) -> tuple[bytes, int]:
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    payload: dict = {
        "model": MODEL,
        "input_audio": {"data": encoded, "format": audio_format(path)},
    }
    if verbose:
        payload["response_format"] = "verbose_json"
        payload["timestamp_granularities"] = granularities
    if language:
        payload["language"] = language
    body = json.dumps(payload).encode("utf-8")
    return body, len(encoded)


def multipart_body(path: Path, verbose: bool, granularities: list[str], language: str | None) -> tuple[bytes, str]:
    boundary = "----pocketcasts-remote-asr-boundary"
    audio_format_name = audio_format(path)
    parts: list[bytes] = []

    def field(name: str, value: str) -> None:
        parts.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
                f"{value}\r\n"
            ).encode("utf-8")
        )

    field("model", MODEL)
    if verbose:
        field("response_format", "verbose_json")
        for granularity in granularities:
            field("timestamp_granularities[]", granularity)
    if language:
        field("language", language)

    parts.append(
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
            f"Content-Type: {MIME.get(audio_format_name, 'application/octet-stream')}\r\n\r\n"
        ).encode("utf-8")
    )
    parts.append(path.read_bytes())
    parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def report_shape(label: str, result: dict) -> None:
    """Summarise the parts of a transcription response the app would consume."""
    if result.get("status") != 200:
        print(f"[shape] {label}: not a 200, nothing to inspect")
        return
    body = result["body"]
    if not isinstance(body, dict):
        print(f"[shape] {label}: body is not JSON")
        return
    usage = body.get("usage") or {}
    segments = body.get("segments") or []
    words = body.get("words") or []
    print(
        f"[shape] text={len(body.get('text') or '')} chars, "
        f"seconds={usage.get('seconds')}, cost=${usage.get('cost')}, "
        f"segments={len(segments)}, words={len(words)}, "
        f"language={body.get('language')!r}, duration={body.get('duration')!r}"
    )
    if segments:
        print(f"[shape] first segment: {pretty(segments[0])[:600]}")
    if words:
        print(f"[shape] first words: {pretty(words[:3])[:600]}")
    text = (body.get("text") or "").strip().replace("\n", " ")
    print(f"[shape] text: {text[:300]}")


# --------------------------------------------------------------------------- fixtures


def run(command: list[str], label: str) -> None:
    print(f"$ {' '.join(command)}")
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode != 0:
        die(f"{label}: {completed.stderr[-800:]}")
    if completed.stdout.strip():
        print(completed.stdout.strip()[:800])


def download(url: str, target: Path) -> Path:
    if target.is_file() and target.stat().st_size > 0:
        print(f"have {target.name} ({target.stat().st_size} bytes)")
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    print(f"fetching {url} -> {target.name}")
    request = urllib.request.Request(url, headers={"User-Agent": "PocketCastsRemoteAsrProbe/1.0"})
    with urllib.request.urlopen(request, timeout=300) as response:
        target.write_bytes(response.read())
        # The feed hands out a tracking URL that redirects; keep the final, direct one so the URL
        # test can tell "the provider follows redirects" apart from "the provider can fetch at all".
        (target.parent / f"{target.stem}.url").write_text(response.url)
        print(f"wrote {target.name} ({target.stat().st_size} bytes), resolved from {response.url[:120]}")
    return target


def fixtures() -> dict[str, Path]:
    """jfk.wav (11 s speech), the same clip as mp3, and a real podcast mp3."""
    wav = download("https://raw.githubusercontent.com/ggerganov/whisper.cpp/master/samples/jfk.wav", DATA / "jfk.wav")
    mp3 = DATA / "jfk.mp3"
    if not mp3.is_file() or mp3.stat().st_size == 0:
        run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-codec:a", "libmp3lame", "-b:a", "96k", str(mp3)], "mp3")
    podcast = DATA / "podcast.mp3"
    if not podcast.is_file() or podcast.stat().st_size == 0:
        url = os.environ.get("PODCAST_URL", "")
        if not url:
            url = first_enclosure("https://feeds.npr.org/510289/podcast.xml")
        download(url, podcast)
    return {"wav": wav, "mp3": mp3, "podcast": podcast}


def first_enclosure(feed: str) -> str:
    request = urllib.request.Request(feed, headers={"User-Agent": "PocketCastsRemoteAsrProbe/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        root = ET.fromstring(response.read())
    for element in root.iter():
        if element.tag.endswith("enclosure"):
            url = element.attrib.get("url", "")
            if url:
                return url
    die(f"no enclosure in {feed}")


def clips(source: Path, seconds: list[int]) -> list[Path]:
    """Cut [source] into the given lengths, as 16 kHz mono wav, for limit probing."""
    made = []
    for length in seconds:
        target = DATA / f"{source.stem}-{length}s.wav"
        if not target.is_file() or target.stat().st_size == 0:
            run(
                [
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-i", str(source),
                    "-t", str(length),
                    "-ac", "1",
                    "-ar", "16000",
                    str(target),
                ],
                f"clip {length}s",
            )
        made.append(target)
    return made


# --------------------------------------------------------------------------- modes


def mode_file(path: Path, via_url: bool) -> dict:
    if not path.is_file():
        die(f"no such file: {path}")
    duration = probe_duration(path)
    verbose = True
    granularities = ["word", "segment"]
    if via_url:
        return mode_source_url(str(path))
    body, encoded_size = base64_body(path, verbose, granularities, "en")
    print(f"[file] {path.name} {path.stat().st_size} bytes, ~{duration:.1f}s, base64 {encoded_size} bytes, body {len(body)} bytes")
    result = post(body, "application/json", f"base64 verbose_json {path.name}")
    report_shape(path.name, result)
    return result


def mode_multipart(path: Path) -> dict:
    body, content_type = multipart_body(path, verbose=True, granularities=["word", "segment"], language="en")
    print(f"[multipart] {path.name} body {len(body)} bytes")
    result = post(body, content_type, f"multipart verbose_json {path.name}")
    report_shape(path.name, result)
    return result


def mode_source_url(url: str) -> dict:
    payload = {
        "model": MODEL,
        "input_audio": {"url": url},
        "response_format": "verbose_json",
        "timestamp_granularities": ["word", "segment"],
        "language": "en",
    }
    result = post(json.dumps(payload).encode("utf-8"), "application/json", f"source_url {url[:90]}")
    report_shape("source_url", result)
    return result


def mode_stream(path: Path) -> dict:
    payload = {
        "model": MODEL,
        "input_audio": {"data": base64.b64encode(path.read_bytes()).decode("ascii"), "format": audio_format(path)},
        "stream": True,
    }
    result = post(json.dumps(payload).encode("utf-8"), "application/json", "stream=true", {"Accept": "text/event-stream"})
    body = result.get("body")
    if isinstance(body, str):
        print(f"[stream] first 1200 chars: {body[:1200]}")
    return result


def mode_limits(path: Path, wanted: list[int] | None = None) -> list[dict]:
    """Probe the accepted payload size, largest first, and stop at the first refusal."""
    require_key()
    results = []
    # 16 kHz mono wav is ~32 kB/s, so these are roughly 1, 2, 5, 10, 20 and 26 minutes.
    wanted = wanted or [60, 120, 300, 600, 1200, 1500, 1590]
    source = path
    for clip in clips(source, wanted):
        seconds = int(clip.stem.split("-")[-1].rstrip("s"))
        body, encoded_size = base64_body(clip, verbose=True, granularities=["segment"], language="en")
        print(f"\n[limits] trying {seconds}s, audio {clip.stat().st_size} bytes, request {len(body)} bytes")
        result = post(body, "application/json", f"limits {seconds}s")
        body_json = result.get("body")
        usage = body_json.get("usage") if isinstance(body_json, dict) else None
        results.append(
            {
                "seconds": seconds,
                "bytes": clip.stat().st_size,
                "request_bytes": len(body),
                "status": result.get("status"),
                "cost": (usage or {}).get("cost"),
                "error": (body_json or {}).get("error") if isinstance(body_json, dict) else None,
            }
        )
        summary = results[-1]
        print(
            f"[limits] {summary['seconds']}s: status={summary['status']} body={summary['request_bytes']} "
            f"cost={summary['cost']} error={json.dumps(summary['error'])[:300] if summary['error'] else 'none'}"
        )
        if result.get("status") != 200:
            print(f"[limits] refused at {seconds}s; stopping")
            break
    print("\n[limits] summary")
    print(pretty(results))
    (DATA / "limits.json").write_text(pretty(results))
    return results


def probe_duration(path: Path) -> float:
    completed = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(path)],
        capture_output=True,
        text=True,
    )
    try:
        return float(completed.stdout.strip())
    except ValueError:
        return 0.0


def mode_feed(feed: str) -> None:
    print(first_enclosure(feed))


def mode_pcm(path: Path, seconds: int = 60) -> dict:
    """Raw 16 kHz mono s16le, which the API documents as format "pcm" with no container."""
    raw = DATA / f"{path.stem}-{seconds}s.pcm"
    if not raw.is_file() or raw.stat().st_size == 0:
        run(
            [
                "ffmpeg", "-y", "-loglevel", "error",
                "-i", str(path), "-t", str(seconds),
                "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "1", "-ar", "16000",
                str(raw),
            ],
            "pcm",
        )
    payload = {
        "model": MODEL,
        "input_audio": {"data": base64.b64encode(raw.read_bytes()).decode("ascii"), "format": "pcm"},
        "response_format": "verbose_json",
        "timestamp_granularities": ["segment"],
        "language": "en",
    }
    body = json.dumps(payload).encode("utf-8")
    print(f"[pcm] {raw.name} {raw.stat().st_size} bytes, request {len(body)} bytes")
    result = post(body, "application/json", f"pcm {seconds}s")
    report_shape("pcm", result)
    return result


def mode_boundary(path: Path, cut_seconds: int) -> None:
    """Transcribe [0, cut) and [cut, ...) and show the seam, which is what chunking costs."""
    left = DATA / f"{path.stem}-0-{cut_seconds}.wav"
    right = DATA / f"{path.stem}-{cut_seconds}-end.wav"
    for target, extra in ((left, ["-t", str(cut_seconds)]), (right, ["-ss", str(cut_seconds)])):
        if not target.is_file() or target.stat().st_size == 0:
            run(
                ["ffmpeg", "-y", "-loglevel", "error", "-i", str(path)] + extra + ["-ac", "1", "-ar", "16000", str(target)],
                target.name,
            )
    for label, clip in (("left", left), ("right", right)):
        body, _ = base64_body(clip, verbose=True, granularities=["word"], language="en")
        result = post(body, "application/json", f"boundary {label}")
        if result.get("status") != 200:
            print(f"[boundary] {label} failed: {pretty(result.get('body'))[:400]}")
            continue
        response = result["body"]
        words = response.get("words") or []
        print(f"[boundary] {label}: duration={response.get('duration')} words={len(words)}")
        print(f"[boundary] {label} first words: {' '.join(word['word'] for word in words[:12])}")
        print(f"[boundary] {label} last words: {' '.join(word['word'] for word in words[-12:])}")


def mode_language(path: Path, language: str | None) -> dict:
    payload: dict = {
        "model": MODEL,
        "input_audio": {"data": base64.b64encode(path.read_bytes()).decode("ascii"), "format": audio_format(path)},
        "response_format": "verbose_json",
        "timestamp_granularities": ["segment"],
    }
    if language:
        payload["language"] = language
    result = post(json.dumps(payload).encode("utf-8"), "application/json", f"language={language or 'auto'} {path.name}")
    if result.get("status") == 200:
        response = result["body"]
        print(f"[language] detected={response.get('language')!r} text={ (response.get('text') or '')[:160]!r}")
    else:
        print(f"[language] refused: {pretty(result.get('body'))[:400]}")
    return result


def mode_formats(path: Path, seconds: int = 60) -> list[dict]:
    """Which compressed containers the provider accepts, and what each costs in bytes and accuracy."""
    require_key()
    source = DATA / f"{path.stem}-{seconds}s.wav"
    if not source.is_file() or source.stat().st_size == 0:
        run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(path), "-t", str(seconds), "-ac", "1", "-ar", "16000", str(source)],
            "format source",
        )
    variants = [
        ("wav", "wav", []),
        ("mp3-96k", "mp3", ["-c:a", "libmp3lame", "-b:a", "96k"]),
        ("mp3-48k", "mp3", ["-c:a", "libmp3lame", "-b:a", "48k"]),
        ("mp3-32k", "mp3", ["-c:a", "libmp3lame", "-b:a", "32k"]),
        ("m4a-48k", "m4a", ["-c:a", "aac", "-b:a", "48k"]),
        ("m4a-32k", "m4a", ["-c:a", "aac", "-b:a", "32k"]),
        ("m4a-24k", "m4a", ["-c:a", "aac", "-b:a", "24k"]),
        ("ogg-24k", "ogg", ["-c:a", "libopus", "-b:a", "24k"]),
    ]
    results = []
    for label, extension, arguments in variants:
        target = DATA / f"{path.stem}-{seconds}s-{label}.{extension}"
        if not target.is_file() or target.stat().st_size == 0:
            run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(source)] + arguments + [str(target)], label)
        payload = {
            "model": MODEL,
            "input_audio": {"data": base64.b64encode(target.read_bytes()).decode("ascii"), "format": extension},
            "response_format": "verbose_json",
            "timestamp_granularities": ["word"],
            "language": "en",
        }
        result = post(json.dumps(payload).encode("utf-8"), "application/json", f"format {label}")
        body = result.get("body") if isinstance(result.get("body"), dict) else {}
        usage = body.get("usage") or {}
        words = body.get("words") or []
        summary = {
            "format": label,
            "upload_bytes": target.stat().st_size,
            "status": result.get("status"),
            "seconds": result.get("seconds"),
            "cost": usage.get("cost"),
            "audio_seconds": usage.get("seconds"),
            "words": len(words),
            "text": (body.get("text") or "")[:160],
        }
        results.append(summary)
        print(f"[formats] {json.dumps(summary, ensure_ascii=False)}")
    (DATA / "formats.json").write_text(pretty(results))
    return results


def mode_suite(run_limits: bool = True) -> None:
    require_key()
    clips_by_name = fixtures()
    print("\n########## 1. wav and mp3 by base64 JSON")
    mode_file(clips_by_name["wav"], via_url=False)
    mode_file(clips_by_name["mp3"], via_url=False)

    print("\n########## 2. timestamps (word and segment in verbose_json)")
    wav_result = mode_file(clips_by_name["wav"], via_url=False)
    body = wav_result.get("body") or {}
    print(
        "[verdict] segments=%s words=%s language=%r duration=%r"
        % (len(body.get("segments") or []), len(body.get("words") or []), body.get("language"), body.get("duration"))
    )

    print("\n########## 3. multipart upload (the OpenAI-style shape, 25 MB cap)")
    mode_multipart(clips_by_name["mp3"])

    print("\n########## 4. source_url: the provider downloads the audio itself")
    raw_url = "https://raw.githubusercontent.com/ggerganov/whisper.cpp/master/samples/jfk.wav"
    mode_source_url(raw_url)

    print("\n########## 5. a real podcast URL, the redirecting enclosure the feed hands out")
    feed = "https://feeds.npr.org/510289/podcast.xml"
    enclosure = first_enclosure(feed)
    mode_source_url(enclosure)

    print("\n########## 5b. the same podcast from its direct CDN URL")
    direct_file = DATA / "podcast.url"
    direct = direct_file.read_text().strip() if direct_file.is_file() else enclosure
    podcast_result = mode_source_url(direct)

    print("\n########## 6. stream: true")
    mode_stream(clips_by_name["wav"])

    if not run_limits:
        return
    print("\n########## 7. chunk limits on the podcast audio")
    limits = mode_limits(clips_by_name["podcast"])
    print(f"[suite] last accepted: {json.dumps(limits[-1]) if limits else 'none'}")


def main(argv: list[str]) -> None:
    if len(argv) < 2:
        print(__doc__)
        return
    mode = argv[1]
    arguments = argv[2:]
    if mode == "fixtures":
        fixtures()
    elif mode == "file":
        mode_file(Path(arguments[0]), via_url="--url" in arguments)
    elif mode == "multipart":
        mode_multipart(Path(arguments[0]))
    elif mode == "pcm":
        mode_pcm(Path(arguments[0]), int(arguments[1]) if len(arguments) > 1 else 60)
    elif mode == "boundary":
        mode_boundary(Path(arguments[0]), int(arguments[1]))
    elif mode == "language":
        mode_language(Path(arguments[0]), arguments[1] if len(arguments) > 1 else None)
    elif mode == "formats":
        mode_formats(Path(arguments[0]), int(arguments[1]) if len(arguments) > 1 else 60)
    elif mode == "source-url":
        mode_source_url(arguments[0])
    elif mode == "feed":
        mode_feed(arguments[0])
    elif mode == "stream":
        mode_stream(Path(arguments[0]))
    elif mode == "limits":
        mode_limits(Path(arguments[0]), [int(value) for value in arguments[1:]] or None)
    elif mode == "suite":
        mode_suite()
    elif mode == "suite-fast":
        mode_suite(run_limits=False)
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv)

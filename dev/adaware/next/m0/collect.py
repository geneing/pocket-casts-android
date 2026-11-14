"""M0 data collection: download sibling takes, transcribe, label, delete audio.

Detection works on transcripts only (TF-IDF before, the jev classifier now), so audio is
needed just long enough to run STT and to compare siblings. Every episode is processed as:

  download take0, take1 -> transcribe -> derive ads by sibling diff -> drop mp3s

Peak disk is one episode (~40-120 MB); transcripts and labels are a few KB per take.

Resumable: work is keyed by episode slug, and `manifest.json` records what is done.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx

HERE = Path(__file__).resolve().parent
ADWARE = HERE.parents[1]
FEEDS = ADWARE / "data" / "feeds.json"
CORPUS = HERE / "corpus"
AUDIO = HERE / ".audio"
TRANSCRIPTS = CORPUS / "transcripts"
LABELS = CORPUS / "labels"
MANIFEST = CORPUS / "manifest.json"
WHISPER_CACHE = ADWARE / "data" / "models" / "whisper"

UAS = [
    "PocketCasts/8.0 (Android 14; Pixel 8)",
    "PocketCasts/7.60 (iPhone15,2; iOS 17.4)",
    "Podcasts/1730.60 CFNetwork/1490 Darwin/23.2.0",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36",
]

# Shows reserved for evaluation, chosen so no test show shares a feed host pattern that
# leaks into training through the same production house. Split happens by show.
TEST_SHOWS = 14
MIN_AUDIO_SECONDS = 8
MAX_TAKES = 2


def slug(text: str, limit: int = 42) -> str:
    s = re.sub(r"[^a-zA-Z0-9]+", "-", text.strip().lower()).strip("-")
    return s[:limit].rstrip("-")


def read_json(path: Path, default=None):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, indent=1, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def parse_duration(value) -> float:
    if value in (None, ""):
        return 0.0
    if isinstance(value, (int, float)):
        return float(value)
    s = str(value).strip()
    if ":" in s:
        parts = [p for p in s.split(":") if p != ""]
        try:
            nums = [float(p) for p in parts]
        except ValueError:
            return 0.0
        return sum(n * 60 ** (len(nums) - 1 - i) for i, n in enumerate(nums))
    try:
        return float(s)
    except ValueError:
        return 0.0


# --------------------------------------------------------------------- selection


# Hosts that stitch ads per request (verified with the take comparison). Ordering the plan by
# these first raises the labelled yield per downloaded hour; other hosts still run afterwards.
PREFERRED_HOSTS = {"simplecast", "acast", "omnycontent", "megaphone", "npr", "publicfeeds"}


def select(feeds: list[dict], per_show: int, seed: int) -> list[dict]:
    rng = random.Random(seed)
    shows = [f for f in feeds if f.get("episodes") and f.get("host") != "mumbaicrime"]
    shows.sort(key=lambda f: f["title"])
    rng.shuffle(shows)
    test = {f["title"] for f in shows[:TEST_SHOWS]}
    shows.sort(key=lambda f: 0 if f.get("host") in PREFERRED_HOSTS else 1)
    picked = []
    for feed in shows:
        eps = [
            e
            for e in feed["episodes"]
            if "audio" in e and e["audio"].startswith("http")
        ]
        rng.shuffle(eps)
        chosen = eps[:per_show]
        for ep in chosen:
            picked.append(
                {
                    "show": feed["title"],
                    "feed_url": feed["url"],
                    "host": feed.get("host", ""),
                    "episode": ep.get("title", ""),
                    "audio": ep["audio"],
                    "published": ep.get("published", ""),
                    "listed_duration": parse_duration(ep.get("duration", "")),
                    "split": "test" if feed["title"] in test else "train",
                }
            )
    return picked


# --------------------------------------------------------------------- download


def download(url: str, dest: Path, take: int) -> dict:
    headers = {"User-Agent": UAS[take % len(UAS)], "Accept-Encoding": "identity"}
    sep = "&" if "?" in url else "?"
    req = f"{url}{sep}_take={take}"
    t0 = time.time()
    with httpx.Client(timeout=300, follow_redirects=True) as client:
        with client.stream("GET", req, headers=headers) as response:
            response.raise_for_status()
            dest.parent.mkdir(parents=True, exist_ok=True)
            with dest.open("wb") as f:
                for chunk in response.iter_bytes(1 << 16):
                    f.write(chunk)
            final = str(response.url)
    return {
        "request_url": req,
        "final_url": final,
        "bytes": dest.stat().st_size,
        "seconds": round(time.time() - t0, 1),
        "ua": headers["User-Agent"],
    }


def md5(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def probe_duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(path)],
        capture_output=True,
        text=True,
        check=True,
    )
    return float(out.stdout.strip())


# ------------------------------------------------------------------- transcribe


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


def build_model():
    from faster_whisper import WhisperModel

    attempts = [("cuda", "float16"), ("cpu", "int8")]
    last = None
    for device, compute in attempts:
        try:
            model = WhisperModel(
                "small",
                device=device,
                compute_type=compute,
                download_root=str(WHISPER_CACHE),
                cpu_threads=os.cpu_count() or 8,
            )
            print(f"whisper: {device}/{compute}", flush=True)
            return model
        except Exception as exc:  # noqa: BLE001
            last = exc
            print(f"whisper {device} failed: {exc!r}"[:300], flush=True)
    raise SystemExit(f"no whisper backend: {last!r}")


def decode_audio(path: Path):
    import numpy as np

    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", "16000", "-f", "f32le", "-"],
        capture_output=True,
        check=True,
    )
    return np.frombuffer(out.stdout, dtype=np.float32).copy()


def transcribe(model, path: Path) -> dict:
    import numpy as np

    t0 = time.time()
    segments, info = model.transcribe(
        decode_audio(path),
        language=None,
        beam_size=1,
        word_timestamps=False,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500},
        condition_on_previous_text=False,
    )
    rows = []
    for seg in segments:
        text = seg.text.strip()
        if text:
            rows.append({"start": round(seg.start, 2), "end": round(seg.end, 2), "text": text})
    return {
        "duration": round(info.duration, 2),
        "language": info.language,
        "segments": rows,
        "seconds": round(time.time() - t0, 1),
    }


# ----------------------------------------------------------------------- labels

WORD_RE = re.compile(r"[a-z0-9$%]+")
AD_PATTERNS = [
    (r"brought to you by", 4),
    (r"sponsored by", 4),
    (r"support(ed)? (comes|for this|us|our)", 3),
    (r"this (episode|show) is (sponsored|supported|brought)", 5),
    (r"promo code", 5),
    (r"use code|enter code|code [a-z]{3,}", 4),
    (r"dot com slash|\.com/|dot com", 3),
    (r"free trial|try it free|start your free", 3),
    (r"terms (and conditions )?apply|subject to credit approval", 4),
    (r"limited time offer", 3),
    (r"discount|save \d+%|\d+% off", 3),
    (r"sign up (today|now)|subscribe (today|now)", 2),
    (r"call \d{3}|1-800|800-|855-", 3),
    (r"visit .* (dot com|\.com)", 4),
    (r"available (now|wherever)", 2),
    (r"our (partner|sponsor)", 3),
    (r"thanks to (our|the) sponsor", 5),
    (r"message from|a word from", 4),
]
AD_PATTERNS_C = [(re.compile(p), w) for p, w in AD_PATTERNS]
AD_VOCAB = {
    "sponsor", "sponsored", "promo", "promotion", "code", "offer", "discount", "trial",
    "subscription", "subscribe", "terms", "conditions", "visit", "dot", "com", "app",
    "free", "save", "percent", "deal", "shop", "buy", "order", "today", "call",
    "insurance", "mortgage", "credit", "loan", "sale", "annual", "plan", "policy",
    "rewards", "shipping", "delivery", "brand", "partner",
}


def words_of(segments: list[dict]) -> list[tuple[str, float, float]]:
    out = []
    for seg in segments:
        for piece in WORD_RE.findall(str(seg.get("text", "")).lower().replace("’", "'")):
            out.append((piece, float(seg["start"]), float(seg["end"])))
    return out


def ad_score(text: str) -> float:
    t = text.lower()
    score = sum(w for pat, w in AD_PATTERNS_C if pat.search(t))
    words = WORD_RE.findall(t)
    if words:
        score += 2.5 * sum(w in AD_VOCAB for w in words) / len(words)
    return score


def anchors(wa, wb, which: str, k: int = 4) -> list[tuple[float, float]]:
    from difflib import SequenceMatcher

    if len(wa) < k or len(wb) < k:
        return []
    sa = [" ".join(w[0] for w in wa[i : i + k]) for i in range(len(wa) - k + 1)]
    sb = [" ".join(w[0] for w in wb[i : i + k]) for i in range(len(wb) - k + 1)]
    words = wa if which == "a" else wb
    sm = SequenceMatcher(None, sa, sb, autojunk=False)
    out = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag != "equal" or i2 <= i1 or j2 <= j1:
            continue
        lo = i1 if which == "a" else j1
        hi = i2 + k - 2 if which == "a" else j2 + k - 2
        out.append((words[lo][1], words[hi][2]))
    return out


def ad_regions(other: list[dict], segs: list[dict], min_len: float = 8.0) -> list[dict]:
    from rapidfuzz import fuzz

    wa, wb = words_of(other), words_of(segs)
    if not wa or not wb:
        return []
    an = sorted(anchors(wa, wb, "b"))
    if not an:
        return []
    end = wb[-1][2]
    regions, prev = [], wa[0][1]
    for s, e in an + [(end + 1e9, end + 1e9)]:
        if s - prev >= min_len:
            regions.append((prev, s))
        prev = max(prev, e)
    out = []
    for s, e in regions:
        s, e = max(0.0, s - 0.3), min(end, e + 0.3)
        if e - s < min_len:
            continue
        words = [w for w in wb if w[1] >= s - 0.5 and w[2] <= e + 0.5]
        if not words:
            continue
        text = " ".join(w[0] for w in words)
        if e - s < 20.0:
            if ad_score(text) < 2.0:
                continue
            window = " ".join(w[0] for w in wa if w[1] >= s - 20 and w[2] <= e + 20)
            if window and fuzz.token_set_ratio(text, window) >= 55:
                continue
        out.append({"start": round(s, 2), "end": round(e, 2), "text": text})
    return out


def merge(spans: list[dict], gap: float = 2.0) -> list[dict]:
    if not spans:
        return []
    spans = sorted(spans, key=lambda s: s["start"])
    out = [dict(spans[0])]
    for s in spans[1:]:
        if s["start"] - out[-1]["end"] <= gap:
            out[-1]["end"] = max(out[-1]["end"], s["end"])
            out[-1]["text"] = (out[-1]["text"] + " " + s["text"])[:2000]
        else:
            out.append(dict(s))
    return out


def derive_labels(takes: list[dict]) -> dict[str, list[dict]]:
    per_take: dict[str, list[dict]] = {}
    for i in range(len(takes)):
        for j in range(i + 1, len(takes)):
            for side, segs, other in (
                (str(i), takes[i]["segments"], takes[j]["segments"]),
                (str(j), takes[j]["segments"], takes[i]["segments"]),
            ):
                per_take.setdefault(side, []).extend(ad_regions(other, segs))
    return {k: merge(v) for k, v in per_take.items() if merge(v)}


# ------------------------------------------------------------------------- main


def process(entry: dict, model, take_count: int) -> dict | None:
    key = f"{slug(entry['show'])}__{slug(entry['episode'])}"
    work = AUDIO / key
    work.mkdir(parents=True, exist_ok=True)
    takes_meta = []
    try:
        def fetch(take: int) -> dict:
            dest = work / f"take{take}.mp3"
            if not dest.exists() or dest.stat().st_size < 100_000:
                info = download(entry["audio"], dest, take)
            else:
                info = {"bytes": dest.stat().st_size}
            info.update({"take": take, "md5": md5(dest), "duration": round(probe_duration(dest), 1)})
            return info

        with ThreadPoolExecutor(max_workers=min(2, take_count)) as pool:
            takes_meta = list(pool.map(fetch, range(take_count)))
        for info in takes_meta:
            print(
                f"  take{info['take']}: {info['bytes'] / 1e6:.1f} MB {info['duration'] / 60:.1f} min md5 {info['md5'][:8]}",
                flush=True,
            )
        distinct = len({t["md5"] for t in takes_meta}) > 1
        if not distinct:
            print("  takes identical: no dynamic ads, skipping episode", flush=True)
            return None

        transcripts = []
        for take in range(len(takes_meta)):
            out = TRANSCRIPTS / key / f"take{take}.json"
            if out.exists():
                payload = read_json(out)
                print(f"  take{take}: transcript cached ({len(payload['segments'])} segments)", flush=True)
            else:
                payload = transcribe(model, work / f"take{take}.mp3")
                payload["file"] = f"{key}/take{take}.mp3"
                payload["model"] = "small"
                write_json(out, payload)
                print(
                    f"  take{take}: {payload['duration'] / 60:.1f} min audio in {payload['seconds'] / 60:.1f} min, "
                    f"{len(payload['segments'])} segments",
                    flush=True,
                )
            transcripts.append(payload)

        slots = derive_labels(transcripts)
        if not slots:
            print("  no ad labels derived, skipping episode", flush=True)
            return None
        total = sum(s["end"] - s["start"] for v in slots.values() for s in v)
        labels = read_json(LABELS / "ads.json", default={})
        labels[key] = {
            "duration": max(t["duration"] for t in transcripts),
            "takes": [str(i) for i in range(len(transcripts))],
            "dynamic_slots": slots,
            "baked_candidates": [],
            "show": entry["show"],
            "episode": entry["episode"],
            "split": entry["split"],
        }
        write_json(LABELS / "ads.json", labels)
        print(f"  labels: {sum(len(v) for v in slots.values())} slots, {total / 60:.1f} min", flush=True)
        return {
            "key": key,
            "show": entry["show"],
            "episode": entry["episode"],
            "split": entry["split"],
            "language": transcripts[0]["language"],
            "duration": max(t["duration"] for t in transcripts),
            "slots": sum(len(v) for v in slots.values()),
            "ad_minutes": round(total / 60, 2),
            "takes": takes_meta,
        }
    finally:
        shutil.rmtree(work, ignore_errors=True)


def should_process(record: dict | None, max_attempts: int = 3) -> bool:
    """Missing entries run; finished and intentionally label-less entries never do.

    Errors are retried, because most are transient (network, CDN, a truncated download), but
    only up to `max_attempts` so a permanently broken feed cannot loop forever.
    """
    if record is None:
        return True
    status = record.get("status")
    if status in {"ok", "no-labels"}:
        return False
    if status == "error":
        return int(record.get("attempts", 1)) < max_attempts
    return True


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-show", type=int, default=2)
    ap.add_argument("--limit", type=int, default=0, help="stop after N episodes (0 = all)")
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--takes", type=int, default=MAX_TAKES)
    ap.add_argument("--max-attempts", type=int, default=3, help="retries for entries that failed")
    args = ap.parse_args()

    CORPUS.mkdir(parents=True, exist_ok=True)
    AUDIO.mkdir(parents=True, exist_ok=True)
    # Leftovers from an interrupted run: finished episodes live in the manifest, so the
    # partial audio is always safe to drop. Keeps peak disk at one episode.
    for stale in AUDIO.iterdir():
        shutil.rmtree(stale, ignore_errors=True)
    setup_cuda_libs()
    model = build_model()

    feeds = read_json(FEEDS, default=[])
    plan = select(feeds, args.per_show, args.seed)
    write_json(CORPUS / "plan.json", plan)
    manifest = read_json(MANIFEST, default={"episodes": {}})
    done = manifest["episodes"]

    started = time.time()
    processed = 0
    for i, entry in enumerate(plan, 1):
        key = f"{slug(entry['show'])}__{slug(entry['episode'])}"
        if not should_process(done.get(key), args.max_attempts):
            continue
        print(f"[{i}/{len(plan)}] {entry['show']} | {entry['episode'][:60]}", flush=True)
        try:
            result = process(entry, model, args.takes)
        except Exception as exc:  # noqa: BLE001
            print(f"  FAILED: {exc!r}"[:300], flush=True)
            attempts = int(done.get(key, {}).get("attempts", 0)) + 1
            done[key] = {
                "status": "error",
                "attempts": attempts,
                "error": repr(exc)[:300],
                "show": entry["show"],
                "split": entry["split"],
            }
            write_json(MANIFEST, manifest)
            continue
        if result:
            done[key] = {"status": "ok", **result}
            processed += 1
        else:
            done[key] = {"status": "no-labels", "show": entry["show"], "split": entry["split"]}
        write_json(MANIFEST, manifest)
        if args.limit and processed >= args.limit:
            break
    elapsed = (time.time() - started) / 60
    ok = [v for v in done.values() if v.get("status") == "ok"]
    print(f"done: {len(ok)} episodes with labels, {sum(v['ad_minutes'] for v in ok):.0f} ad minutes, {elapsed:.0f} min", flush=True)


if __name__ == "__main__":
    main()

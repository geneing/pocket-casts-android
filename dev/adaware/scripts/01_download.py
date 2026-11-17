"""Download podcast episodes, several takes each, to expose dynamic ad insertion.

Why several takes: Megaphone, Acast, Omny and Simplecast/AdsWizz all stitch ads
server-side per request. Requesting the same enclosure several times (with a
rotating user agent and a cache-busting parameter) yields the same episode body
with *different* ad slots, which gives us free ground truth: anything that differs
between takes is an ad, anything identical is show content.

Usage:
  uv run scripts/01_download.py                 # download all selected episodes
  uv run scripts/01_download.py --only hardfork # substring filter
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import time

import httpx

from common import AUDIO, DATA, ffprobe_duration, md5, read_json, slug, write_json

UAS = [
    "PocketCasts/8.0 (Android 14; Pixel 8)",
    "PocketCasts/7.60 (iPhone15,2; iOS 17.4)",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36",
    "Podcasts/1730.60 CFNetwork/1490 Darwin/23.2.0",
]

# Ten shows across five ad-stitching networks, biased towards short episodes so
# the whole training set stays small and fast to transcribe.
SELECTION = [
    ("Hard Fork", 0),
    ("The Daily", 0),
    ("Planet Money", 0),
    ("99% Invisible", 0),
    ("Freakonomics Radio", 0),
    ("Dan Snow's History Hit", 0),
    ("The Economics Show with Soumaya Keynes", 0),
    ("Odd Lots", 0),
    ("Revisionist History", 0),
    ("The Rest Is Politics", 0),  # 15 min Megaphone episode: a 320 kbps outlier
]

TAKES = 3


def pick(feeds: list[dict], title: str, index: int) -> dict:
    for feed in feeds:
        if feed["title"] == title:
            ep = feed["episodes"][index]
            return {"feed": title, "feed_url": feed["url"], "host": feed.get("host", ""), **ep}
    raise SystemExit(f"feed not found: {title}")


def download(url: str, dest, take: int) -> dict:
    headers = {
        "User-Agent": UAS[take % len(UAS)],
        "Accept-Encoding": "identity",
        "Accept": "audio/mpeg,*/*",
    }
    sep = "&" if "?" in url else "?"
    req_url = f"{url}{sep}_take={take}"
    started = time.time()
    with httpx.Client(timeout=300, follow_redirects=True) as client:
        r = client.get(req_url, headers=headers)
        r.raise_for_status()
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(r.content)
        return {
            "take": take,
            "request_url": req_url,
            "final_url": str(r.url),
            "ua": headers["User-Agent"],
            "status": r.status_code,
            "bytes": len(r.content),
            "seconds": round(time.time() - started, 1),
            "fetched_at": dt.datetime.now().isoformat(timespec="seconds"),
        }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="", help="substring filter on show name")
    ap.add_argument("--takes", type=int, default=TAKES)
    args = ap.parse_args()

    feeds = read_json(DATA / "feeds.json")
    manifest_path = DATA / "downloads.json"
    manifest = read_json(manifest_path, default={})

    for title, index in SELECTION:
        if args.only and args.only.lower() not in title.lower():
            continue
        ep = pick(feeds, title, index)
        key = f"{slug(title)}__{slug(ep['title'], 40)}"
        entry = manifest.setdefault(key, {"episode": ep, "takes": []})
        have = {t["take"] for t in entry["takes"]}
        for take in range(args.takes):
            if take in have:
                continue
            dest = AUDIO / key / f"take{take}.mp3"
            print(f"[{key}] take {take} <- {ep['audio'][:80]}...", flush=True)
            try:
                info = download(ep["audio"], dest, take)
            except Exception as exc:  # noqa: BLE001
                print(f"  FAILED: {exc!r}", flush=True)
                entry.setdefault("errors", []).append({"take": take, "error": repr(exc)[:300]})
                continue
            info["file"] = str(dest.relative_to(DATA)).replace("\\", "/")
            info["md5"] = md5(dest)
            info["duration"] = round(ffprobe_duration(dest), 2)
            entry["takes"].append(info)
            print(
                f"  {info['bytes'] / 1e6:.1f} MB, {info['duration'] / 60:.1f} min, md5 {info['md5'][:10]}",
                flush=True,
            )
            write_json(manifest_path, manifest)

    write_json(manifest_path, manifest)
    rows = []
    for key, entry in manifest.items():
        takes = entry["takes"]
        sizes = {t["bytes"] for t in takes}
        hashes = {t["md5"] for t in takes}
        rows.append((key, len(takes), len(sizes), len(hashes)))
    print("\nepisode                                            takes  sizes  hashes")
    for key, n, ns, nh in rows:
        print(f"{key[:50]:50s} {n:5d} {ns:6d} {nh:7d}")


if __name__ == "__main__":
    main()

"""Grab a few minutes of Spanish *speech* for the Spanish smoke test.

There is no Spanish episode in dev/adaware. This pulls a Spanish-language episode from the
iTunes directory, slices a window out of the middle (the first minutes of many podcasts are
an English ad pod, which is not what we want to test), and writes a mono 16 kHz mp3.

Run:  .venv/bin/python spanish_audio.py [--term "radio ambulante"] [--start 600] [--seconds 300]
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, ".audio", "_spanish")
UA = "PocketCasts/8.0 (Android 14; Pixel 8)"


def get(url: str, ua: str = UA) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--term", default="radio ambulante")
    ap.add_argument("--start", type=float, default=600)
    ap.add_argument("--seconds", type=float, default=300)
    args = ap.parse_args()

    q = urllib.parse.urlencode({"term": args.term, "entity": "podcast", "limit": 5})
    hits = json.loads(get(f"https://itunes.apple.com/search?{q}"))["results"]
    feed = hits[0]["feedUrl"]
    root = ET.fromstring(get(feed))
    item = next(i for i in root.iter("item") if (i.find("enclosure") is not None))
    url = item.find("enclosure").get("url")
    title = (item.findtext("title") or "").strip()
    print("feed:", hits[0].get("collectionName"), "| episode:", title[:70])

    os.makedirs(OUT, exist_ok=True)
    full = os.path.join(OUT, "full.mp3")
    data = get(url)
    with open(full, "wb") as f:
        f.write(data)
    print(f"downloaded {len(data)} B")

    dest = os.path.join(OUT, f"speech_{int(args.start)}_{int(args.seconds)}.mp3")
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-ss", str(args.start), "-t", str(args.seconds),
            "-i", full, "-ac", "1", "-ar", "16000", "-b:a", "64k", dest,
        ],
        check=True,
    )
    meta = {"show": hits[0].get("collectionName"), "title": title, "url": url,
            "start": args.start, "seconds": args.seconds, "file": dest}
    with open(os.path.join(OUT, "source.json"), "w") as f:
        json.dump(meta, f, indent=1)
    print(json.dumps(meta, indent=1))


if __name__ == "__main__":
    main()

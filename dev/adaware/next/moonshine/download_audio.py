"""Fetch audio for the episodes M1 scores, using the URLs recorded in
`dev/adaware/data/downloads.json` (the same request that produced the Whisper transcripts).

Note: dynamic ad insertion stitches a different ad pod on every request, so a fresh
download does NOT reproduce the file the committed Whisper transcript/labels describe.
M1 downloads a fresh *pair* of takes and re-derives both the Whisper reference and the
ad labels from them (see whisper_reference.py / derive_labels.py).

Files land in `.audio/<episode>/takeN.mp3` next to this script (gitignored, *.mp3).

Run:  .venv/bin/python download_audio.py [--takes 0 1] [--episodes SLUG ...]
"""

import argparse
import json
import os
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
AUDIO = os.path.join(HERE, ".audio")

DEFAULT = [
    "hard-fork__jensen-huang-is-out-at-nvidia",
    "the-daily__what-jensen-huang-s-resignation-will-mea",
    "planet-money__how-bodegas-led-to-the-yemeni-coffeehous",
]


def fetch(url: str, dest: str, ua: str) -> int:
    tmp = dest + ".partial"
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        while chunk := r.read(1 << 16):
            f.write(chunk)
    os.replace(tmp, dest)
    return os.path.getsize(dest)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--episodes", nargs="+", default=DEFAULT)
    ap.add_argument("--takes", nargs="+", type=int, default=[0])
    args = ap.parse_args()

    man = json.load(open(os.path.join(ROOT, "data", "downloads.json")))
    for ep in args.episodes:
        for take in args.takes:
            t = man[ep]["takes"][take]
            d = os.path.join(AUDIO, ep)
            os.makedirs(d, exist_ok=True)
            dest = os.path.join(d, f"take{take}.mp3")
            if os.path.exists(dest):
                print(f"{ep} take{take}: already present ({os.path.getsize(dest)} B)")
                continue
            t0 = time.time()
            n = fetch(t["final_url"], dest, t["ua"])
            print(
                f"{ep} take{take}: {n} B in {time.time() - t0:.1f}s "
                f"(original request was {t['bytes']} B, {t['duration']} s)"
            )


if __name__ == "__main__":
    main()

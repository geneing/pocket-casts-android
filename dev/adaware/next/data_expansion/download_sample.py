"""Download a two-take proof sample from a feed already in data/feeds.json.

The script deliberately keeps its output under next/data_expansion and never
records or prints feed/audio URLs (which may contain signed query parameters).
"""

from __future__ import annotations

import hashlib
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
FEEDS = REPO / "dev" / "adaware" / "data" / "feeds.json"
OUT = HERE / "sample_audio"
MANIFEST = HERE / "sample_downloads.json"

SHOW = "NPR's Book of the Day"
EPISODE = "Journalist Emily Feng on how Uyghur militants took up arms in Syria's civil war"
TAKES = 2
USER_AGENT = "PocketCasts-AdAware-Research/1.0"


def slug(text: str) -> str:
    return "-".join("".join(c.lower() if c.isalnum() else " " for c in text).split())[:72]


def append_take(url: str, take: int) -> str:
    parts = urlsplit(url)
    query = [(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True) if k != "_take"]
    query.append(("_take", str(take)))
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))


def get_episode() -> tuple[dict, dict]:
    feeds = json.loads(FEEDS.read_text(encoding="utf-8"))
    feed = next((item for item in feeds if item.get("title") == SHOW), None)
    if feed is None:
        raise SystemExit(f"Feed not found in existing manifest: {SHOW}")
    episode = next((item for item in feed.get("episodes", []) if item.get("title") == EPISODE), None)
    if episode is None:
        raise SystemExit(f"Episode not found in existing feed manifest: {EPISODE}")
    if not episode.get("audio"):
        raise SystemExit("Selected episode has no audio enclosure in the manifest")
    return feed, episode


def fetch(url: str, target: Path) -> tuple[int, float, str]:
    request = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "audio/mpeg,audio/*;q=0.9,*/*;q=0.5",
            "Accept-Encoding": "identity",
        },
    )
    started = time.monotonic()
    digest = hashlib.sha256()
    size = 0
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        with urlopen(request, timeout=300) as response, target.open("wb") as output:
            while block := response.read(1024 * 1024):
                output.write(block)
                digest.update(block)
                size += len(block)
    except HTTPError as exc:
        target.unlink(missing_ok=True)
        raise RuntimeError(f"HTTP status {exc.code}") from None
    except URLError as exc:
        target.unlink(missing_ok=True)
        raise RuntimeError(f"Network error ({type(exc.reason).__name__})") from None
    return size, round(time.monotonic() - started, 1), digest.hexdigest()


def main() -> None:
    feed, episode = get_episode()
    key = f"{slug(SHOW)}__{slug(EPISODE)}"
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {}
    row = manifest.setdefault(
        key,
        {
            "show": SHOW,
            "host": feed.get("host", ""),
            "episode": EPISODE,
            "duration": episode.get("duration", ""),
            "takes": [],
        },
    )
    have = {entry["take"]: entry for entry in row["takes"]}
    for take in range(TAKES):
        target = OUT / key / f"take{take}.mp3"
        if take in have and target.exists():
            entry = have[take]
            print(f"take {take}: reused {entry['bytes']} bytes; sha256 {entry['sha256'][:12]}")
            continue
        started_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
        try:
            size, seconds, digest = fetch(append_take(episode["audio"], take), target)
        except Exception as exc:  # Keep error output free of signed URLs.
            print(f"take {take}: failed ({exc})")
            continue
        entry = {
            "take": take,
            "file": target.relative_to(HERE).as_posix(),
            "bytes": size,
            "seconds": seconds,
            "sha256": digest,
            "fetched_at_utc": started_at,
        }
        row["takes"] = [item for item in row["takes"] if item["take"] != take] + [entry]
        row["takes"].sort(key=lambda item: item["take"])
        MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"take {take}: {size} bytes in {seconds}s; sha256 {digest[:12]}")
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

"""Probe podcast feeds: parse OPML, list latest episodes and audio URLs."""

from __future__ import annotations

import json
import re
from pathlib import Path

import feedparser
import httpx

ROOT = Path(__file__).resolve().parents[1]
OPML = ROOT.parent / "Pocket Casts - Backup of Podcast List.html"
OUT = ROOT / "data" / "feeds.json"

UA = "PocketCasts/8.0 (Android 14; Pixel 8)"


def parse_opml(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8")
    feeds = []
    for m in re.finditer(r'<outline[^>]*type="rss"[^>]*/>', text):
        tag = m.group(0)
        title = re.search(r'text="([^"]*)"', tag)
        url = re.search(r'xmlUrl="([^"]*)"', tag)
        if not url:
            continue
        feeds.append(
            {
                "title": (title.group(1) if title else "").replace("&amp;", "&"),
                "url": url.group(1).replace("&amp;", "&"),
            }
        )
    return feeds


def host_of(url: str) -> str:
    try:
        return httpx.URL(url).host.split(".")[-2]
    except Exception:
        return "?"


def probe(feed: dict, client: httpx.Client) -> dict:
    try:
        r = client.get(feed["url"], timeout=30, follow_redirects=True)
        r.raise_for_status()
    except Exception as exc:  # noqa: BLE001
        return {**feed, "error": str(exc)}
    parsed = feedparser.parse(r.content)
    eps = []
    for e in parsed.entries[:5]:
        audio = ""
        for link in e.get("links", []):
            if link.get("rel") == "enclosure" and "audio" in link.get("type", ""):
                audio = link.get("href", "")
                break
        if not audio:
            audio = e.get("id", "") if str(e.get("id", "")).startswith("http") else ""
        eps.append(
            {
                "title": e.get("title", ""),
                "published": e.get("published", e.get("updated", "")),
                "duration": e.get("itunes_duration", ""),
                "audio": audio,
            }
        )
    return {
        **feed,
        "host": host_of(feed["url"]),
        "episodes": eps,
        "count": len(parsed.entries),
    }


def main() -> None:
    feeds = parse_opml(OPML)
    print(f"{len(feeds)} feeds in OPML")
    out = []
    with httpx.Client(headers={"User-Agent": UA}, follow_redirects=True) as client:
        for feed in feeds:
            info = probe(feed, client)
            out.append(info)
            status = "ERR " + info["error"] if "error" in info else str(info["count"]) + " eps"
            print(f"{info['title'][:45]:47s} {status}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()

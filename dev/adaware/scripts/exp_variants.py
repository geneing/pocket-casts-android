"""Experiment: does one episode URL yield different audio (i.e. different dynamic ads)?

Compares response bytes across request variants (user agent, cache buster, provider
targeting params). Ad-inserted responses differ in size and bytes, even when most of
the body is identical.
"""

from __future__ import annotations

import base64
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

import httpx

OUT = Path(__file__).resolve().parents[1] / "data" / "variants"
OUT.mkdir(parents=True, exist_ok=True)

UAS = {
    "android": "PocketCasts/8.0 (Android 14; Pixel 8)",
    "ios": "PocketCasts/7.60 (iPhone15,2; iOS 17.4)",
    "web": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36",
}


@dataclass
class Case:
    name: str
    url: str


def fetch(name: str, url: str, ua: str = "android", cb: int | None = None) -> dict:
    headers = {"User-Agent": UAS[ua], "Accept-Encoding": "identity"}
    if cb is not None:
        url += ("&" if "?" in url else "?") + f"cb={cb}"
    try:
        with httpx.Client(timeout=120, follow_redirects=True) as c:
            r = c.get(url, headers=headers)
        body = r.content
        return {
            "name": name,
            "status": r.status_code,
            "bytes": len(body),
            "md5": hashlib.md5(body).hexdigest()[:12],
            "final": str(r.url)[:160],
            "path": None,
        }
    except Exception as exc:  # noqa: BLE001
        return {"name": name, "status": "ERR", "error": repr(exc)[:200]}


def main() -> None:
    feeds = {f["title"]: f for f in json.loads((OUT.parent / "feeds.json").read_text())}
    cases = {
        "hardfork": feeds["Hard Fork"]["episodes"][0]["audio"],
        "restishistory": feeds["The Rest Is History"]["episodes"][0]["audio"],
        "dansnow": feeds["Dan Snow's History Hit"]["episodes"][0]["audio"],
        "oddlots": feeds["Odd Lots"]["episodes"][0]["audio"],
        "planetmoney": feeds["Planet Money"]["episodes"][0]["audio"],
    }
    results = []
    for key, url in cases.items():
        print(f"\n### {key}\n{url[:180]}")
        for label, ua, cb in [
            ("a1", "android", None),
            ("a2", "ios", 1),
            ("a3", "web", 2),
        ]:
            res = fetch(f"{key}_{label}", url, ua, cb)
            print(json.dumps(res))
            results.append(res)

    (OUT / "experiment.json").write_text(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()

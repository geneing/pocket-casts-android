"""Download the Moonshine models M1 evaluates: English + Spanish tiny streaming, with
the optional attention decoder that enables word timestamps.

The downloader renames each finished file into place. On the WSL `/mnt/i` (DrvFs) mount
that rename intermittently returns EACCES while Windows still holds the freshly written
file, so this script downloads into a native Linux cache and copies the bundle into
`models/` afterwards (cp creates, it does not rename).

Run:  .venv/bin/python download_models.py [--languages en es] [--word-timestamps]
"""

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

from moonshine_voice.download import get_model_for_language
from moonshine_voice.moonshine_api import ModelArch, model_arch_to_string

HERE = Path(__file__).resolve().parent
DEST = HERE / "models"
STAGING = Path.home() / ".cache" / "moonshine-m1-staging"


def describe(lang: str, arch: str, path: Path) -> dict:
    files = sorted(
        p.name for p in path.iterdir() if p.is_file() and not p.name.endswith((".partial", ".lock"))
    )
    return {
        "language": lang,
        "requested_arch": arch,
        "model_dir": str(path),
        "total_bytes": sum((path / f).stat().st_size for f in files),
        "has_attention_decoder": "decoder_kv_with_attention.ort" in files,
        "files": [{"name": f, "bytes": (path / f).stat().st_size} for f in files],
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--languages", nargs="+", default=["en", "es"])
    ap.add_argument("--arch", default="tiny-streaming")
    ap.add_argument("--word-timestamps", action="store_true")
    args = ap.parse_args()

    arches = {model_arch_to_string(a): a for a in ModelArch}
    arch = arches[args.arch]
    STAGING.mkdir(parents=True, exist_ok=True)
    DEST.mkdir(parents=True, exist_ok=True)

    out = []
    for lang in args.languages:
        path, got_arch = get_model_for_language(
            lang, arch, cache_root=STAGING, include_word_timestamps=args.word_timestamps
        )
        staging_path = Path(path)
        dest_path = DEST / lang / staging_path.name
        dest_path.mkdir(parents=True, exist_ok=True)
        for f in staging_path.iterdir():
            if f.is_file() and not f.name.endswith((".partial", ".lock")):
                shutil.copyfile(f, dest_path / f.name)
        info = describe(lang, model_arch_to_string(got_arch), dest_path)
        out.append(info)
        print(json.dumps(info, indent=1))
        sys.stdout.flush()

    (DEST / "bundles.json").write_text(json.dumps(out, indent=1) + "\n")


if __name__ == "__main__":
    main()

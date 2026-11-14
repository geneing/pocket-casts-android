# M0 — transcript TF-IDF detector at scale

Collection, training and export for the method in `../RESULTS.md`.

## What it does

1. `collect.py` picks one episode per show from the OPML list, downloads **two sibling
   takes**, transcribes both with faster-whisper small, derives ad pods from the sibling
   diff, writes transcripts + labels, then **deletes the audio** in a `finally` block.
   Peak disk is one episode (~40-190 MB); the corpus itself is a few KB per take.
2. `train.py` fits the word + char TF-IDF model and the semi-Markov decoder on the
   training shows, tunes only the decoder on grouped inner folds, evaluates on held-out
   shows, and exports `bundle/model.json` plus Kotlin parity fixtures.

Shows are split into train/test before any download (`plan.json`), so a test show never
contributes an episode to training.

## Commands

```bash
cd dev/adaware
uv run python -u next/m0/collect.py --per-show 1 --limit 150   # resumable, hours
uv run python -u next/m0/train.py --out-dir next/m0/bundle
```

Collected data lives in `corpus/` (`transcripts/<episode>/takeN.json`,
`labels/ads.json`, `manifest.json`, `plan.json`). `collect.log` shows progress.

## Status

Collection is long-running and resumable: a show with no dynamic ad inventory returns
byte-identical takes, is skipped after one download, and is recorded as `no-labels`.
Entries that fail with an error stay in the manifest and are retried on the next run up to
`--max-attempts` (default 3), so a transient CDN failure no longer skips an episode forever.
Hosts known to stitch ads per request (Simplecast, Acast, Omny, Megaphone, NPR,
publicfeeds) are ordered first to raise the labelled yield; takes download in parallel.
`--per-show 2` roughly doubles the corpus.

The exported bundle is copied to
`modules/services/repositories/src/main/assets/ad_detector/model.json`; the parity
fixtures go to `modules/services/repositories/src/test/resources/`.

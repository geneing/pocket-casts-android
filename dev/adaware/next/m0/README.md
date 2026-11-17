# M0 — corpus collection for the jev ad detector

Collection and labelling for podcast ad detection.

The TF-IDF detector this directory was built for was **retired** on branch
`feature/adaware_jev` in favour of the `jev` classifier served by the OpenCode System One
API. Its training/export code (`train.py`, `publish.py`, `finish.sh`) and the exported
bundles are gone; the code is in `retired_tfidf/` and git history has the runnable
version. `corpus/` is now the labelling/evaluation corpus for `jev`.

## What it does

1. `collect.py` picks one episode per show from the OPML list, downloads **two sibling
   takes**, transcribes both with faster-whisper small, derives ad pods from the sibling
   diff, writes transcripts + labels, then **deletes the audio** in a `finally` block.
   Peak disk is one episode (~40-190 MB); the corpus itself is a few KB per take.
2. `merge_legacy.py` folds the older ten-episode v3 corpus into `corpus/` (test shows
   excluded); `manifest_stats.py` prints collection status. `eval/` keeps the last TF-IDF
   run's report as a historical record only.

Shows are split into train/test before any download (`plan.json`), so a test show never
contributes an episode to training. The split is now only bookkeeping: `jev` does not
train on this corpus, it is scored against it. Shows with no dynamic ad inventory stay in
`manifest.json` as `no-labels` and remain valid negatives for evaluation.

## Commands

```bash
cd dev/adaware
uv run python -u next/m0/collect.py --per-show 1 --limit 150   # resumable, hours
uv run python next/m0/merge_legacy.py                          # optional, one-off
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

Nothing here is exported to the app any more: the TF-IDF bundle and its Kotlin parity
fixtures were built by `publish.py` (now in `retired_tfidf/`), and no code under
`next/m0` writes into `modules/services/repositories`.

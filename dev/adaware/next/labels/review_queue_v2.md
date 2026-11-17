# V2 boundary review queue

`review_queue.csv` and `review_queue.json` contain all 49 v2 intervals whose
overall boundary uncertainty is greater than 10 seconds. Rows are sorted from
widest to narrowest uncertainty and include the episode/take, interval and
per-edge uncertainty, boundary basis, provenance/classification, nearby
transcript words, and source audio path.
The `audio_path` values are relative to `dev/adaware/data/`; clip paths are
relative to this labels directory.

The 10 widest-uncertainty intervals have a 20-second start clip and a 20-second
end clip under `review_clips/` (mono MP3, 32 kbps). The queue records the clip
paths and source-time window. The clips were extracted with FFmpeg; they have
not been listened to or manually verified. A clip is context for a reviewer,
not confirmation that the labeled boundary is correct.

Regenerate the queue and clips from the frozen labels and transcript/audio
inputs with the bundled Python runtime:

```powershell
& 'C:\Users\genei\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' `
  dev/adaware/next/labels/build_review_queue.py
```

The builder writes only queue files and `review_clips/` in this directory. It
checks that `ads_v2.json` has not changed during generation. Its recorded
SHA-256 is `d6a5279dbacea5d78e8735028943fb8940150486f27b125ad7144eb67ff1c2be`.

Current output: 49 queue rows, 20 clips, about 1.4 MB of clip data, no clip
generation errors. The source labels remain frozen at the recorded hash.

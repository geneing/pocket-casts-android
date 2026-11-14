# V3 boundary review queue

Source labels: `ads_v3.json` (SHA-256 `2c600b4e798c07b3afd8fb66bc64427953c3c9db3e7d537e59a626ab62a78870`).
The queue contains 67 v3 pods whose estimated overall boundary uncertainty exceeds 10 seconds.
Each row includes episode/take, interval, overall and per-edge uncertainty, boundary basis, provenance, nearby transcript context, and source audio path.
The 10 widest-uncertainty intervals have 20-second start and end excerpts (20 clips, 1469514 bytes total) under `review_clips_v3/`.
Clips were machine-extracted with FFmpeg. They have not been listened to or manually verified.

Regenerate the queue and clips from the frozen v3 labels with the bundled Python runtime:

```powershell
& 'C:\Users\genei\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' `
  dev/adaware/next/labels/build_review_queue_v3.py
```

The v2 queue remains available as the frozen checkpoint in `review_queue.json`, `review_queue.csv`, and `review_clips/`.

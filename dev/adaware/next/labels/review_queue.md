# Boundary review queue

The current review queue is for **v3**, matching `ads_v3.json`. It includes all
67 intervals with estimated boundary uncertainty over 10 seconds, with nearby
transcript text, audio paths, and per-edge provenance. The 10 widest intervals
also have short start/end clips for review.

- Current queue: [`review_queue_v3.csv`](review_queue_v3.csv), [`review_queue_v3.json`](review_queue_v3.json)
- Queue guide and regeneration steps: [`review_queue_v3.md`](review_queue_v3.md)
- Compact clips: `review_clips_v3/`
- Current labels: [`ads_v3.json`](ads_v3.json)

The earlier v2 queue remains as a checkpoint. The original `review_queue.csv`,
`review_queue.json`, and `review_clips/` are tied to the frozen v2 labels and
their hash, documented in [`review_queue_v2.md`](review_queue_v2.md). They are
not the latest queue.

V3 clips were extracted by FFmpeg and have not been listened to or manually
verified. The v3 label corrections triggered by detector/TF-IDF discrepancies
are marked as prediction-triggered and are not independent held-out gold.

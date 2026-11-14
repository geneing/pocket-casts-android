# AdAware v2 label audit

## Annotation definition

One label is one maximal contiguous paid-commercial or promotional pod. Cross-podcast and clear own-show subscription promos count; ordinary editorial speech and show outros are excluded. Three seconds is an automatic merge heuristic only; targeted transcript-supported ads within one break include longer spoken or silent transitions. Source spans remain under `sub_creatives`.

## Method and verification

The build starts from the frozen transcript-difference spans, excludes the documented Madison quotation and Yemeni bodega interview false positives plus sparse fragments, and adds explicit sponsor wording when not already covered. MFCC boundary proposals use only a candidate-centered +/-90 second region and require ordered local anchors; consensus across sibling takes is used when available. Exact MP3 byte-gap results are attached as corroboration only; they can omit shared creatives, so they never replace the maximal semantic pod. Shared candidates are found by exact 4-word shingle overlap in all takes and are marked separately.

Audio was not listened to. No labels are marked manually verified. Audio boundary proposals are algorithmic; shared-creative matches are transcript-only and not independent evidence for a transcript-based detector. A deterministic take-0 sample is reserved for future audio review.

## Validation

- Episodes / takes: 10 / 30 (includes the ad-free control).
- V2 pods: 92; excluded v1 records: 7.
- Boundary uncertainty: 49 pods exceed 10 s; 12 are in the 5–10 s band; 31 are estimated at 5 s or less. Wider estimates are flagged and should not be treated as meeting the 10 s target.
- Exact MP3 byte-gap corroboration: `{"full_pod_corroborated": 32, "no_byte_support": 8, "partial_or_discordant": 52}`. Partial changed spans do not replace a complete semantic pod, because shared creatives can sit outside byte-different regions.
- Descriptive v1 overlap at +/-10 s: TP 42, FP 50, FN 61, precision 0.457, recall 0.408. This is not independent accuracy because v1 has known boundary and semantic defects.

## Per-episode coverage

| Episode | Takes | Pods | Shared groups | Per-take pods |
|---|---:|---:|---:|---|
| `99-invisible__perfectly-leveraged-for-the-squat` | 3 | 6 | 0 | t0=2, t1=2, t2=2 |
| `dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with` | 3 | 12 | 0 | t0=4, t1=4, t2=4 |
| `freakonomics-radio__694-does-democracy-really-need-elections` | 3 | 9 | 1 | t0=3, t1=3, t2=3 |
| `hard-fork__jensen-huang-is-out-at-nvidia` | 3 | 15 | 5 | t0=5, t1=5, t2=5 |
| `odd-lots__what-nvidia-s-megaspeed-scandal-is-reall` | 3 | 11 | 0 | t0=4, t1=3, t2=4 |
| `planet-money__how-bodegas-led-to-the-yemeni-coffeehous` | 3 | 12 | 0 | t0=4, t1=4, t2=4 |
| `revisionist-history__from-the-big-dig-mitt-ted` | 3 | 9 | 0 | t0=3, t1=3, t2=3 |
| `the-daily__what-jensen-huang-s-resignation-will-mea` | 3 | 12 | 2 | t0=5, t1=3, t2=4 |
| `the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell` | 3 | 6 | 0 | t0=2, t1=2, t2=2 |
| `the-rest-is-politics__what-living-under-isis-taught-me-about-e` | 3 | 0 | 0 | t0=0, t1=0, t2=0 |

## Confidence and boundary basis

Confidence counts: `{"high": 12, "low": 19, "medium": 61}`.

Boundary basis counts: `{"audited_transcript_extent": 32, "localized_audio_match_consensus": 17, "matched_word_timestamps": 3, "sibling_transcript_inference": 16, "transcript_word_times": 24}`.

## Files

- `ads_v2.json`: compatible episode/take interval layout, with per-interval provenance, confidence, and uncertainty.
- `provenance.json`: sources, exclusions, method details, label-level evidence, and a future audio-review holdout.
- `validation_stats.json`: all-episode coverage and descriptive v1 comparison.
- `build_labels_v2.py`: reproducible builder; original `data/labels` files are not modified.

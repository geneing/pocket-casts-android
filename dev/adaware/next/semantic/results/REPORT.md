# Transcript-context ad detector results

Label set: `next/labels/ads_v2.json`  
SHA256: `d6a5279dbacea5d78e8735028943fb8940150486f27b125ad7144eb67ff1c2be`  
References: 92 pods across 10 held-out episodes.

## Primary results

A hit requires both boundaries within 10 seconds. Every prediction is scored against all reference labels; overlap metrics below are diagnostic only.

| Model | Strict TP/FP/FN | Strict P/R | 1s frame P/R/AP | Any-overlap P/R | IoU ≥ 0.5 P/R | Overlap match, edge missed |
|---|---:|---:|---:|---:|---:|---:|
| semantic | 17/112/75 | 0.132/0.185 | 0.594/0.619/0.470 | 0.612/0.859 | 0.357/0.500 | 62 |
| tfidf | 34/88/58 | 0.279/0.370 | 0.710/0.814/0.602 | 0.730/0.967 | 0.533/0.707 | 55 |

## Episode-held-out results

| Model | Episode | TP/FP/FN | P/R |
|---|---|---:|---:|
| semantic | 99-invisible__perfectly-leveraged-for-the-squat | 0/24/6 | 0.000/0.000 |
| semantic | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 0/9/12 | 0.000/0.000 |
| semantic | freakonomics-radio__694-does-democracy-really-need-elections | 1/15/8 | 0.062/0.111 |
| semantic | hard-fork__jensen-huang-is-out-at-nvidia | 3/11/12 | 0.214/0.200 |
| semantic | odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 1/20/10 | 0.048/0.091 |
| semantic | planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 5/7/7 | 0.417/0.417 |
| semantic | revisionist-history__from-the-big-dig-mitt-ted | 1/10/8 | 0.091/0.111 |
| semantic | the-daily__what-jensen-huang-s-resignation-will-mea | 6/8/6 | 0.429/0.500 |
| semantic | the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell | 0/8/6 | 0.000/0.000 |
| semantic | the-rest-is-politics__what-living-under-isis-taught-me-about-e | 0/0/0 | 1.000/1.000 |
| tfidf | 99-invisible__perfectly-leveraged-for-the-squat | 1/10/5 | 0.091/0.167 |
| tfidf | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 0/16/12 | 0.000/0.000 |
| tfidf | freakonomics-radio__694-does-democracy-really-need-elections | 3/11/6 | 0.214/0.333 |
| tfidf | hard-fork__jensen-huang-is-out-at-nvidia | 8/7/7 | 0.533/0.533 |
| tfidf | odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 2/14/9 | 0.125/0.182 |
| tfidf | planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 9/12/3 | 0.429/0.750 |
| tfidf | revisionist-history__from-the-big-dig-mitt-ted | 3/8/6 | 0.273/0.333 |
| tfidf | the-daily__what-jensen-huang-s-resignation-will-mea | 8/5/4 | 0.615/0.667 |
| tfidf | the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell | 0/5/6 | 0.000/0.000 |
| tfidf | the-rest-is-politics__what-living-under-isis-taught-me-about-e | 0/0/0 | 1.000/1.000 |

## Label provenance and boundary support

### semantic

Matched reference recall by ad identity source:

- semantic-only: 17/92 matched (recall 0.185)
- Unmatched predictions (false positives): 112

Matched reference recall by edge support (not ad identity):

- localized-audio-supported: 1/17 matched (recall 0.059)
- transcript-boundary: 16/75 matched (recall 0.213)
- Unmatched predictions (false positives): 112

### tfidf

Matched reference recall by ad identity source:

- semantic-only: 34/92 matched (recall 0.370)
- Unmatched predictions (false positives): 88

Matched reference recall by edge support (not ad identity):

- localized-audio-supported: 4/17 matched (recall 0.235)
- transcript-boundary: 30/75 matched (recall 0.400)
- Unmatched predictions (false positives): 88

## Boundary refinement ablation

- semantic: P/R 0.116/0.163 (15 TP, 114 FP, 77 FN); 120 edges adjusted over 2237.4s of local audio.
- tfidf: P/R 0.254/0.337 (31 TP, 91 FP, 61 FN); 110 edges adjusted over 2004.4s of local audio.

## Coverage, grouping, and duration diagnostics

- semantic: 12 gold pods have no overlapping prediction; 9 gold pods overlap multiple predictions; 1 predictions span multiple gold pods. Among maximum any-overlap matches, median absolute start/end errors are 13.3s/8.6s.
- semantic: predicted median 46.1s (P10–P90 28.1–125.7s); gold median 85.3s (P10–P90 27.2–149.5s).
- tfidf: 2 gold pods have no overlapping prediction; 8 gold pods overlap multiple predictions; 1 predictions span multiple gold pods. Among maximum any-overlap matches, median absolute start/end errors are 9.0s/6.1s.
- tfidf: predicted median 66.0s (P10–P90 24.6–137.9s); gold median 85.3s (P10–P90 27.2–149.5s).

## Compute and limits

The MiniLM ONNX model files occupy about 86.9 MiB. Encoding all 17,591 transcript segments took 173.4 seconds on the Windows CPU used here, with repeated text/context pairs deduplicated across takes. The 48 MiB embedding cache is not shipped. Android runtime, memory, and battery use remain unmeasured; the app would also need the ONNX runtime and tokenizer.

The v2 references are same-ASR pseudo-gold/corpus-wide candidate labels, not an independently listened and annotated acceptance set. QA found that 99% Invisible take1's labeled 1497–1534s pod continues in the transcript to about 1627s, so this frozen reference is incomplete. Some apparent false positives and duration comparisons are unreliable. The transcript-derived ad identity and localized audio boundary evidence are reported separately. One episode per show means leave-one-episode-out is show-held-out for this small corpus. Neither model approaches the 0.90/0.90 target, and these results do not justify a production success claim.

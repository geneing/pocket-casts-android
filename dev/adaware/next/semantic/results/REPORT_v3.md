# Transcript-context ad detector results

Label set: `dev/adaware/next/labels/ads_v3.json`  
SHA256: `2c600b4e798c07b3afd8fb66bc64427953c3c9db3e7d537e59a626ab62a78870`  
References: 101 pods across 10 held-out episodes.

## Primary results

A hit requires both boundaries within 10 seconds. Every prediction is scored against all reference labels; overlap metrics below are diagnostic only.

| Model | Strict TP/FP/FN | Strict P/R | 1s frame P/R/AP | Any-overlap P/R | IoU ≥ 0.5 P/R | Overlap match, edge missed |
|---|---:|---:|---:|---:|---:|---:|
| semantic | 28/91/73 | 0.235/0.277 | 0.717/0.678/0.655 | 0.723/0.851 | 0.454/0.535 | 58 |
| tfidf | 39/57/62 | 0.406/0.386 | 0.915/0.760/0.798 | 0.938/0.891 | 0.750/0.713 | 51 |

## Episode-held-out results

| Model | Episode | TP/FP/FN | P/R |
|---|---|---:|---:|
| semantic | 99-invisible__perfectly-leveraged-for-the-squat | 0/20/9 | 0.000/0.000 |
| semantic | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 1/12/11 | 0.077/0.083 |
| semantic | freakonomics-radio__694-does-democracy-really-need-elections | 10/5/2 | 0.667/0.833 |
| semantic | hard-fork__jensen-huang-is-out-at-nvidia | 3/9/12 | 0.250/0.200 |
| semantic | odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 2/15/9 | 0.118/0.182 |
| semantic | planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 5/2/10 | 0.714/0.333 |
| semantic | revisionist-history__from-the-big-dig-mitt-ted | 1/9/8 | 0.100/0.111 |
| semantic | the-daily__what-jensen-huang-s-resignation-will-mea | 6/8/6 | 0.429/0.500 |
| semantic | the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell | 0/11/6 | 0.000/0.000 |
| semantic | the-rest-is-politics__what-living-under-isis-taught-me-about-e | 0/0/0 | 1.000/1.000 |
| tfidf | 99-invisible__perfectly-leveraged-for-the-squat | 4/6/5 | 0.400/0.444 |
| tfidf | dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with | 1/11/11 | 0.083/0.083 |
| tfidf | freakonomics-radio__694-does-democracy-really-need-elections | 9/3/3 | 0.750/0.750 |
| tfidf | hard-fork__jensen-huang-is-out-at-nvidia | 6/8/9 | 0.429/0.400 |
| tfidf | odd-lots__what-nvidia-s-megaspeed-scandal-is-reall | 1/10/10 | 0.091/0.091 |
| tfidf | planet-money__how-bodegas-led-to-the-yemeni-coffeehous | 9/2/6 | 0.818/0.600 |
| tfidf | revisionist-history__from-the-big-dig-mitt-ted | 2/8/7 | 0.200/0.222 |
| tfidf | the-daily__what-jensen-huang-s-resignation-will-mea | 7/6/5 | 0.538/0.583 |
| tfidf | the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell | 0/3/6 | 0.000/0.000 |
| tfidf | the-rest-is-politics__what-living-under-isis-taught-me-about-e | 0/0/0 | 1.000/1.000 |

## Label provenance and boundary support

### semantic

Matched reference recall by ad identity source:

- semantic-only: 28/101 matched (recall 0.277)
- Unmatched predictions (false positives): 91

Matched reference recall by edge support (not ad identity):

- localized-audio-supported: 2/14 matched (recall 0.143)
- transcript-boundary: 26/87 matched (recall 0.299)
- Unmatched predictions (false positives): 91

### tfidf

Matched reference recall by ad identity source:

- semantic-only: 39/101 matched (recall 0.386)
- Unmatched predictions (false positives): 57

Matched reference recall by edge support (not ad identity):

- localized-audio-supported: 2/14 matched (recall 0.143)
- transcript-boundary: 37/87 matched (recall 0.425)
- Unmatched predictions (false positives): 57

## Boundary refinement ablation

- semantic: not run.
- tfidf: not run.

## Coverage, grouping, and duration diagnostics

- semantic: 14 gold pods have no overlapping prediction; 7 gold pods overlap multiple predictions; 1 predictions span multiple gold pods. Among maximum any-overlap matches, median absolute start/end errors are 9.6s/7.7s.
- semantic: predicted median 49.4s (P10–P90 25.9–143.6s); gold median 92.2s (P10–P90 26.2–146.5s).
- tfidf: 9 gold pods have no overlapping prediction; 5 gold pods overlap multiple predictions; 2 predictions span multiple gold pods. Among maximum any-overlap matches, median absolute start/end errors are 6.9s/4.2s.
- tfidf: predicted median 68.3s (P10–P90 25.9–138.1s); gold median 92.2s (P10–P90 26.2–146.5s).

## Compute and limits

The MiniLM ONNX model files occupy about 86.9 MiB. Encoding all 17,591 transcript segments took 173.4 seconds on the Windows CPU used here, with repeated text/context pairs deduplicated across takes. The 48 MiB embedding cache is not shipped. Android runtime, memory, and battery use remain unmeasured; the app would also need the ONNX runtime and tokenizer.

EXPLORATORY: These labels were created after reviewing apparent false positives from the prior detector; they are prediction-triggered and are not independent held-out ground truth or an acceptance set. The transcript-derived ad identity and localized audio boundary evidence are reported separately. One episode per show means leave-one-episode-out is show-held-out for this small corpus. Neither model approaches the 0.90/0.90 target, and these results do not justify a production success claim.

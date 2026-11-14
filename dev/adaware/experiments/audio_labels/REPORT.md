# Audio-derived ad interval labels

This isolated experiment aligns sibling audio takes using existing MFCC features. It reads, but does not modify, the existing transcript labels and detector pipeline.

## Method

MFCC coefficients 1–12 from the 250 ms feature grid are averaged into the configured time bins. Each take pair is compared with cosine similarity; fixed-speed windows are matched, low-confidence and non-unique anchors are rejected, and the remaining anchors are required to stay in order. A smoothed local similarity check at the expected offset distinguishes shared content from unmatched audio. Long unsupported regions in each take are proposed as inserted audio intervals. Per-take proposals from sibling comparisons are deduplicated.

Transcript-derived `dynamic_slots` are the reference. A true positive requires both predicted boundaries to be within 10 seconds of one gold interval. Matching is one-to-one. Settings were selected on the development episode; held-out episodes are reported separately.

## Held-out metrics

| TP | FP | FN | Precision | Recall | Gold | Predicted |
|---:|---:|---:|---:|---:|---:|---:|
| 22 | 184 | 75 | 0.107 | 0.227 | 97 | 206 |

## Development episode

All episodes including development: TP 25, FP 191, FN 78, P 0.116, R 0.243.
`99-invisible__perfectly-leveraged-for-the-squat`: TP 3, FP 7, FN 3, P 0.300, R 0.500.
Held-out overlap-only context: 78/97 one-to-one overlaps at IoU ≥ 0.1; 40/97 at IoU ≥ 0.5. These do not meet the two-boundary criterion.

## Per episode

| Split | Episode | TP | FP | FN | P | R |
|---|---|---:|---:|---:|---:|---:|
| development | `99-invisible__perfectly-leveraged-for-the-squat` | 3 | 7 | 3 | 0.300 | 0.500 |
| held-out | `dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with` | 2 | 61 | 11 | 0.032 | 0.154 |
| held-out | `freakonomics-radio__694-does-democracy-really-need-elections` | 2 | 7 | 10 | 0.222 | 0.167 |
| held-out | `hard-fork__jensen-huang-is-out-at-nvidia` | 5 | 25 | 7 | 0.167 | 0.417 |
| held-out | `odd-lots__what-nvidia-s-megaspeed-scandal-is-reall` | 6 | 38 | 13 | 0.136 | 0.316 |
| held-out | `planet-money__how-bodegas-led-to-the-yemeni-coffeehous` | 2 | 19 | 10 | 0.095 | 0.167 |
| held-out | `revisionist-history__from-the-big-dig-mitt-ted` | 1 | 10 | 9 | 0.091 | 0.100 |
| held-out | `the-daily__what-jensen-huang-s-resignation-will-mea` | 2 | 17 | 11 | 0.105 | 0.154 |
| held-out | `the-economics-show-with-soumaya-keynes__can-economics-fight-fascism-with-isabell` | 2 | 7 | 4 | 0.222 | 0.333 |
| held-out | `the-rest-is-politics__what-living-under-isis-taught-me-about-e` | 0 | 0 | 0 | 0.000 | 0.000 |

## Boundary errors

- Start delta (prediction minus label), median / p10 / p90: 0.48 / -6.19 / 5.14 s
- End delta (prediction minus label), median / p10 / p90: -4.96 / -7.95 / 1.66 s

### Example audio-to-transcript boundaries

| Episode | Take | Audio start–end | Transcript start–end | Start Δ | End Δ |
|---|---:|---:|---:|---:|---:|
| `dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with` | 0 | 3055.0–3139.0 s | 3056.1–3137.3 s | -1.1 s | +1.7 s |
| `dan-snow-s-history-hit__how-rasputin-destroyed-the-romanovs-with` | 2 | 1597.0–1634.0 s | 1595.7–1626.8 s | +1.3 s | +7.2 s |
| `freakonomics-radio__694-does-democracy-really-need-elections` | 2 | 4288.0–4324.0 s | 4286.1–4332.2 s | +1.9 s | -8.2 s |
| `freakonomics-radio__694-does-democracy-really-need-elections` | 0 | 1453.0–1483.0 s | 1459.4–1490.8 s | -6.4 s | -7.8 s |
| `hard-fork__jensen-huang-is-out-at-nvidia` | 0 | 2569.0–2596.0 s | 2565.6–2598.1 s | +3.4 s | -2.1 s |
| `hard-fork__jensen-huang-is-out-at-nvidia` | 0 | 0.0–29.0 s | 0.0–35.1 s | +0.0 s | -6.1 s |
| `hard-fork__jensen-huang-is-out-at-nvidia` | 0 | 1268.0–1332.0 s | 1262.8–1333.5 s | +5.2 s | -1.5 s |
| `hard-fork__jensen-huang-is-out-at-nvidia` | 1 | 0.0–29.0 s | 1.0–36.7 s | -1.0 s | -7.7 s |

## Reproduce

From `dev/adaware`:

```powershell
uv run python experiments/audio_labels/align_audio_labels.py
```

Defaults: 1 s bins, 8 s match windows, similarity ≥ 0.75, uniqueness margin 0.08, 5 s-smoothed frame similarity ≥ 0.50, minimum gap 8 s, and 10 s boundary tolerance. Detailed predictions and pair diagnostics are in `audio_labels_results.json`.

## Limitations

- The default matching bins are 1 second, using precomputed 250 ms features; boundaries are audio-feature-resolution estimates, not sample-accurate waveform splice points.
- The transcript labels are not independent physical stitch annotations. Because they come from word timestamps and matched transcript text, non-speech jingles are not represented; nearby show speech may also be included. For example, the 99% Invisible tail slot label begins at 2315.2 s and contains the outro through about 2328 s, while the detected ad audio begins at 2329 s.
- Repeated, quiet, or strongly re-encoded shared audio can reduce alignment confidence.
- Offline use requires a sibling take or reference audio for the same episode to be present on-device; a single downloaded take alone is insufficient.
- The evaluation measures label alignment only; it does not train or validate a detector at 0.9 precision/recall.

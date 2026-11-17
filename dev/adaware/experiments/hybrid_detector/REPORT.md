# Hybrid ad interval detector: evaluation

## Result

The tested offline hybrid does **not** meet the requested 0.90 precision and recall.
Under nested leave-one-episode-out evaluation, with both boundaries required within 10 s:

| System | TP | FP | FN | Precision | Recall | F1 |
|---|---:|---:|---:|---:|---:|---:|
| Acoustic + ad-pod cap/duration prior + lexical cues | 4 | 96 | 99 | 0.040 | 0.039 | 0.039 |
| Acoustic + same nested tuning, lexical cues off | 4 | 103 | 99 | 0.037 | 0.039 | 0.038 |

The set contains 103 labeled intervals across 27 takes from 9 episodes. Lexical cues raised
mean held-out episode frame average precision from **0.341 to 0.388** and removed 7 false
intervals, but did not recover more correctly bounded intervals: strict recall remained
4/103. They help rank ad-like frames, but do not reliably locate both edges.

The repository's earlier selected acoustic detector was separately reported at 10 s as
7 TP, 243 FP, and 96 FN (P=0.028, R=0.068). That prior figure uses its saved selected
post-processing; the nested comparison above is the reproducible result produced here.

## Approach and leakage controls

The inference combines saved 0.25-second acoustic probabilities with word-level log-odds
learned from ad versus content words and adjacent word pairs. It smooths the scores, forms
hysteresis intervals, merges short gaps, ranks candidates by mean confidence and an
optional training-fold log-duration prior, and applies an optional top-K ad-pod cap.
Count/duration priors and candidate ranking are inexpensive additions for a phone.

For each held-out episode, thresholds, smoothing, merging, minimum duration, cap, duration
prior, and lexical weight are selected from the other episodes only. Lexical weights for
the outer test episode use only the other episodes' transcripts and labels. For tuning,
each training episode's lexical scores are cross-fitted using still other episodes. The
held-out episode is never used to select thresholds or vocabulary weights. Acoustic
probabilities are the repository's saved leave-one-episode-out predictions.

The held-out transcript provides word text and timestamps, as if an offline transcript
were already available. The app does not currently provide these transcripts, so this
result assumes text is present; it does not establish that running ASR on a phone is cheap.

## Fingerprints and data limits

The provided 64 transcript-derived creative clusters have **zero cross-episode matches**
in this corpus. This is specific to these clusters and this corpus, not a universal claim
that podcast ads never recur. There are no cross-episode positive examples here for
training creative fingerprints. The detector does not use cluster metadata.

Labels mark recognized speech spans found by comparing alternate downloads. They can omit
non-speech material such as music or jingles before the first word. This experiment scores
against those provided labels, not manually verified stitched-audio cut points.

## Resource implications

- The cap and duration prior add a few scalar parameters and a sort over the candidate
  list; their cost is negligible beside audio feature extraction.
- Lexical scoring is linear in recognized word count. The Python reference stores
  string-based unigram/bigram weights; no compact mobile export or model-footprint
  benchmark was made.
- The existing transcripts were produced with Whisper small (about 464 MB in the cache)
  using CUDA, at roughly 20x real-time on an RTX 4060. On-phone ASR latency, battery use,
  and memory were not measured. Lexical results assume text already exists.
- The existing acoustic feature/model path is documented in `dev/adaware/HANDOFF.md`.
  This experiment does not modify or export it into the app.

## Reproduction

Run `evaluate_hybrid.py` from the repository root with Python 3 and NumPy. It reads saved
OOF probabilities, transcripts, and labels and writes `results.json`; no network access or
model download is needed. See `README.md` for the Windows command used here.

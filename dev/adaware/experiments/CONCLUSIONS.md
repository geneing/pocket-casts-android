# Ad interval detection: experiment conclusions

## Acceptance rule and current status

The requested score is precision and recall at least 0.90, with both predicted
start and end within 10 seconds of a complete ad interval. No experiment here
has established that score. The saved acoustic model, re-scored without
retraining, produces **7 TP, 243 FP, 96 FN** against the existing 103
transcript-difference labels: P 0.028, R 0.068. See
[`baseline_audit/README.md`](baseline_audit/README.md).

Those labels cannot yet serve as a definitive acceptance set. A targeted
transcript audit found at least 13 of 103 interval records with partial
commercial pods, partial creatives, or ordinary editorial speech. This is a
record count across 27 takes, not a random estimate of the dataset-wide error
rate, and the audio still needs human review. See
[`astra/label_audit.md`](astra/label_audit.md).

## Tested alternatives

| Method | Evaluation | TP | FP | FN | Precision | Recall |
|---|---|---:|---:|---:|---:|---:|
| Saved acoustic model | 27 takes, 103 labels, 10 s | 7 | 243 | 96 | 0.028 | 0.068 |
| Audio alignment of sibling takes | Held-out episodes after one development episode, 97 labels, 10 s | 22 | 184 | 75 | 0.107 | 0.227 |
| Nested acoustic model with a pod prior | 27 takes, 103 labels, 10 s | 4 | 103 | 99 | 0.037 | 0.039 |
| Nested acoustic plus lexical cues and pod prior | 27 takes, 103 labels, 10 s | 4 | 96 | 99 | 0.040 | 0.039 |

See [`audio_labels/REPORT.md`](audio_labels/REPORT.md) and
[`hybrid_detector/REPORT.md`](hybrid_detector/REPORT.md) for exact methods and
per-episode results. The settings and splits differ, so the table is not a
controlled ranking of algorithms. It does show that none meets the acceptance
criterion. The audio-alignment method has a different inference contract: it
requires at least one local sibling take of the same episode. The nested
hybrid's average held-out episode frame AP improves from 0.341 to 0.388 with
lexical cues, but both variants find only four boundary-correct intervals.

## Most promising architecture to test next

For a **single downloaded episode with no other episode reference**, decode
all speech offline and tag each sentence or short word group as ad, editorial,
or uncertain using surrounding transcript context. A duration-aware sequence
decoder should form whole ad pods. Audio change points, silence, and metadata
can adjust the two edges after the semantic decision. Known-creative audio
fingerprints can confidently cover repeated spots, but must not be the only
detector: a new host-read spot has no prior fingerprint and can sound like
editorial content. Full-episode speech coverage is necessary because an
acoustic gate could discard seamless host reads before they reach the text
model.

There is a useful **conditional** alternative when a second take or a
canonical reference fingerprint is already stored locally. Align its
unchanged body to the downloaded audio and mark long unmatched gaps as
potential dynamic insertions. This can locate physical stitch points without
full-episode ASR. The app already has a `FingerprintTimingManager` for mapping
playback audio to a reference when that reference is available. The method
cannot identify ads shared by both versions, baked-in ads, or commercial
speech that is part of the unchanged program. A second download or a server
reference cannot be assumed for a strict one-file offline requirement.

## Evidence and limitations from prior work

- [Reddy et al. (EACL 2021)](https://aclanthology.org/2021.eacl-main.99.pdf)
  report transcript sentence classification F1 0.769, with precision 0.690
  and recall 0.870 for the transcript task. This supports semantic context,
  but does not demonstrate boundary-accurate interval detection.
- [Nguyen et al. (2010)](https://link.springer.com/article/10.1155/2010/572571)
  use fingerprints to find repeated podcast ads and trace their audio edges.
  Their 97.5% detection rate is for repeats in a collection, not new host-read
  spots in one file or the 10-second two-boundary metric.
- [Ramires et al. (2018)](https://arxiv.org/abs/1811.02411)
  detect TV ad regions from boundary silences. Silence is a candidate edge
  cue here, not reliable ad identity evidence for every podcast.
- [Álvarez et al. (2024)](https://arxiv.org/abs/2403.03538)
  report F1-macro 0.878 for speech/text radio ad classification. Their task
  and corpus differ from the requested podcast interval metric.

## Test plan for a credible 0.90 claim

1. Decide whether the target is a complete commercial pod or each creative,
   and whether house promos and transition music count. Independently listen
   and annotate whole test episodes, with uncertainty ranges and a second
   annotator for disputed edges. Include shared, baked-in, and host-read ads.
2. Collect multiple episodes from each network and show. Keep all takes of an
   episode in one split; reserve unseen shows or networks for a final test.
   Tune thresholds only on training/validation episodes.
3. Score each predicted interval one-to-one at the 10-second rule. Report TP,
   FP, FN, per-show results, and whether an auxiliary local reference existed.
   Count abstentions as misses for the stated recall target.
4. Test full-episode ASR, semantic tagging, boundary refinement, and known
   fingerprints separately. Benchmark the complete pipeline on Android for
   runtime, peak memory, battery use, and model/storage size while offline.

The existing nine labelled episodes and imperfect labels do not justify a
general 0.90/0.90 claim. The experiments are useful for choosing a direction,
but an acceptance result requires an independently annotated evaluation set.

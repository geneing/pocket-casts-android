# Single-episode ad identifiability audit

This is a targeted transcript inspection, not an audio-verified replacement gold set.
Read `data/labels/ads.json` and the corresponding `data/transcripts/<episode>/takeN.json`.
Times below are seconds. A pod means contiguous commercial material; endpoints are approximate
speech endpoints, sufficient to establish errors well above 10 seconds.

## Quantified lower bound

Among 103 labelled intervals, **13 (12.6%) have a demonstrated semantic or boundary problem**:
10 contain only part of an advertisement or commercial pod, with at least one edge displaced
by more than 10 seconds; 3 contain ordinary episode subject matter. An additional 3 (2.9%)
are highly suspicious 20-second ASR/alignment spans with only one or two words.
These are a selected audit, not an unbiased error-rate estimate. The boundary count assumes
whole contiguous commercial pods are the desired annotation unit; two Odd Lots examples
also fail even if the unit is an individual creative. No audio was listened to for this audit.

The denominator is the sum of the lengths of every `dynamic_slots[take]` list in
`data/labels/ads.json`: 103 interval records across 27 takes of nine positive episodes.
The tenth episode has three takes but zero interval records. Alternate takes are counted
separately, including duplicated content and duplicated defects; 103 is not a count of
independent episodes, independent creatives, or independently observed errors. The numerator
is exactly the 13 distinct episode/take/label records in the table below. It excludes the
three suspicious sparse-transcript records and the wholly missing closing Ramp advertisement.
Inspection deliberately focused on conspicuously short/sparse labels and repeated commercial
text. Thus 12.6% is a corpus-record lower bound demonstrated under this semantic annotation
definition, not a representative sample estimate or a claimed population error rate.

| Episode | Take | Label | Evidence of commercial extent or content | Defect |
|---|---:|---|---|---|
| Hard Fork | 0 | 184.40–215.20 | Ramp starts 153.32; IBM follows; Ripple ends 247.55 | Both edges truncate pod by ~31–32 s |
| Hard Fork | 0 | 1262.80–1333.50 | Ramp starts 1236.95, before IBM and Ripple | Start ~26 s late |
| Hard Fork | 0 | 2566.80–2599.50 | Ramp starts 2502.88; Ripple then IBM | Start ~64 s late |
| Hard Fork | 1 | 217.12–250.31 | Ramp starts 153.32; Ripple then IBM | Start ~64 s late |
| Hard Fork | 1 | 1225.90–1299.01 | Ripple ad continues through 1331.51, content resumes 1333.35 | End ~32 s early |
| Hard Fork | 1 | 2490.50–2533.54 | Ramp begins 2533.24, Ripple continues to 2597.30 | End ~64 s early |
| Hard Fork | 2 | 215.81–248.80 | Ramp starts 151.49; Ripple then IBM | Start ~64 s late |
| Hard Fork | 2 | 2565.58–2598.10 | Ramp starts 2501.60; Ripple then IBM | Start ~64 s late |
| Odd Lots | 0 | 1036.04–1059.00 | Underdog creative 1019.26–1087.26 | Both edges truncate single creative by ~17–28 s |
| Odd Lots | 0 | 2468.97–2492.37 | Underdog creative 2440.17–2506.27 | Both edges truncate single creative by ~29–14 s |
| Freakonomics | 1 | 1569.58–1589.86 | Madison quotation about elections, ambition and personal interest | Plainly on-topic content |
| Planet Money | 0 | 613.39–634.03 | Interview about Yemeni bodegas and recognizing fellow Yemenis | Plainly on-topic content |
| Planet Money | 2 | 613.48–633.61 | Same Yemeni bodega interview | Plainly on-topic content |

Additional suspicious spans: Freakonomics takes 0/2 at 1586.42–1606.48 have text `and`;
Planet Money take1 at 614.11–634.26 has text `altogether and`. Their homologous regions in
other takes are the ordinary episode content listed above.

Hard Fork take0 additionally has a wholly unlabelled Ramp ad at 2677.34–2708.64.
The transcript exposes five commercial pods: ~0–31.34, 153.32–247.55,
1236.95–1331.47, 2502.88–2595.09, and 2677.34–2708.64. A perfect detector of these
five semantic intervals would score just 1 TP / 4 FP / 3 FN against the four current
labels at 10-second tolerance: precision 0.20, recall 0.25. These are a manually
constructed semantic oracle example, not measured model predictions.

## Consequence

Current labels represent differences between alternate downloads, not advertisements.
No single-file detector can know which sections happened to differ in unavailable alternate
downloads. The same ad is labelled positive or negative depending on inventory coincidence.
Changing an acoustic classifier cannot repair this target-definition mismatch.

Audio-to-audio alignment can recover the true boundaries of differing regions, but cannot
identify shared or baked-in ads and cannot make a partial creative/pod a complete ad label.
Cached server fingerprint alignment has the same observability limit. Equal-duration
replacement can also produce no offset jump; unmatched local spans, not offset changes alone,
would be necessary. Speech/topic semantics are necessary for general host-read coverage.

## Recommended experiment and eventual architecture

1. Define gold as maximal contiguous paid-ad/promo pods, explicitly deciding whether house
   promos count and where transition music belongs. Audit all takes acoustically, preserving
   uncertainty intervals. Group alternate takes as one episode for all splits.
2. Test semantic detection on existing timestamped transcripts before paying for a new audio
   model. Use a pretrained text encoder with contextual sentence/token BIO tagging, then a
   duration-aware sequence decoder. A generative teacher can supply candidate labels, but
   human-audited held-out labels must remain independent.
3. For deployment use quantized on-device ASR plus a compact distilled sequence classifier.
   Let acoustic change points, silences, and cached reference gaps refine boundaries only
   after semantic detection. Do not gate ASR solely on acoustic anomalies: host reads may
   have none, irreversibly capping recall. Benchmark ASR energy/RAM/latency on a phone.
4. Optional known-creative fingerprints improve cost/precision; evaluate unseen creatives
   and unseen shows separately. Do not treat fingerprint-matched regions as guaranteed content.
5. Tune on inner validation shows, freeze, and evaluate interval matching at ±10 s on unseen
   shows, reporting coverage and confidence intervals. Nine positive episodes cannot support
   a dependable universal 0.9/0.9 claim.

The target is a research objective, not established feasibility. A payment relationship can
be acoustically/textually invisible in a seamless endorsement. Allowing an uncertain/manual
class is appropriate, but abstention must still count against recall in the reported target.

## Primary literature

[Reddy et al., EACL 2021](https://aclanthology.org/2021.eacl-main.99.pdf) trained contextual
BERT classifiers on podcast transcripts. Table 3 reports transcript F1 0.769; the text reports
precision 0.690 and recall 0.870. Table 4's 88.7% is sentence accuracy, **not F1 or ad-interval
accuracy**. Native-ad boundaries and noisy ASR are explicitly identified as difficult. This
supports semantic context as a useful direction, not proof of 0.9 interval P/R feasibility.

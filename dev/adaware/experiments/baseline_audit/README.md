# Ten-second baseline audit

Run `python audit_10s.py` from this directory with NumPy installed. The script
reads the saved leave-one-episode-out probability arrays and the selected
post-processing settings from `../../data/model`. It does not train a new
model. Matching is one-to-one and requires **both** boundaries within the
stated tolerance.

| Boundary tolerance | TP | FP | FN | Precision | Recall |
|---:|---:|---:|---:|---:|---:|
| 5 s | 6 | 244 | 97 | 0.024 | 0.058 |
| 10 s | 7 | 243 | 96 | 0.028 | 0.068 |
| 20 s | 16 | 234 | 87 | 0.064 | 0.155 |
| 30 s | 27 | 223 | 76 | 0.108 | 0.262 |

The optional `--sweep` searches 120 threshold/duration/gap combinations on
the same test predictions. This is **optimistically biased** because it tunes
on held-out outcomes, and it fixes the already chosen six-second smoothing.
Even so, its best 10-second F1 was 0.046 (P 0.035, R 0.068; 7 TP, 195 FP,
96 FN). Increasing the timing allowance or changing these post-processing
parameters cannot rescue the current probability ranking.

At the original settings, 77 of 103 gold intervals have a distinct proposal
with at least 0.1 intersection-over-union, but only 33 have one with at least
0.5. This separates broad proposal coverage from accurate segmentation.

## Research notes

- [Reddy et al., EACL 2021](https://aclanthology.org/2021.eacl-main.99/)
  classify extraneous podcast content using transcript text and listening
  patterns. Their best transcript sentence classifier reports F1 0.769;
  precision 0.690 and recall 0.870 are reported for the transcript task.
  This supports testing lexical evidence for host-read spots, but their
  sentence-level evaluation does not establish 10-second ad intervals.
- [Nguyen et al., 2010](https://link.springer.com/article/10.1155/2010/572571)
  find repeated podcast ads with audio fingerprints and trace matching frames
  to their ends. Their reported 97.5% detection rate concerns repeated ads in
  a corpus; it is not interval P/R at 10 seconds for an unseen single take.
- [Ramires et al., 2018](https://arxiv.org/abs/1811.02411) use silence and
  grouping for TV ads. These are useful boundary candidates but do not
  identify host-read ads that share a voice and acoustic mix with the show.

## Evaluation needed for an acceptance claim

1. Define whether a segment is a creative, a whole stitched pod, a host-read
   sponsorship, a show promo, or any of these. The current labels sometimes
   represent only transcribed words inside a larger pod.
2. Independently mark the actual audio start and end on complete test takes,
   including music, silence, and shared or baked-in ads. Keep ambiguous cases
   explicit and check agreement between annotators.
3. Split by episode, preferably by show for a general detector, and tune
   thresholds only on training/validation episodes. Score all predictions on
   each held-out take one-to-one with both boundaries within 10 seconds.
4. Record on-phone runtime, peak memory, battery use, model size, and whether
   all inference inputs are truly present while offline.

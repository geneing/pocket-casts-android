# Hybrid offline ad interval experiment

This experiment evaluates a lightweight pod prior plus optional lexical scoring on top
of the repository's existing acoustic frame probabilities. It does not modify the app or
the main `dev/adaware` pipeline.

## Reproduce

From the repository root, with Python and NumPy available:

```powershell
& 'C:\Users\genei\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' `
  dev/adaware/experiments/hybrid_detector/evaluate_hybrid.py
```

The script reads only the existing OOF acoustic predictions, word-timestamp transcripts,
ground-truth labels, and creative-cluster metadata under `dev/adaware/data/`. It writes
`results.json` beside the script. The OOF acoustic probabilities were produced by the
repository's leave-one-episode-out classifier.

Measured nested LOEO results at 10-second tolerance: hybrid P/R 0.040/0.039 (4 TP, 96 FP,
99 FN); acoustic-only P/R 0.037/0.039 (4 TP, 103 FP, 99 FN). Mean held-out episode frame
AP rose from 0.341 to 0.388 with lexical cues. See `REPORT.md` for methodology and limits.

## Method

For each held-out episode, the experiment tunes thresholds, smoothing, short-gap merging,
minimum duration, an optional top-K interval cap, a broad log-duration prior, and the
weight for word-level lexical log-odds using the other episodes only. The training
episodes' lexical scores are themselves cross-fitted by episode. The held-out lexical
weights use only other episodes' ad/content transcripts and labels. A match requires both
boundaries to be within 10 seconds. The report compares this hybrid with an acoustic-only
variant under the same nested protocol and also reports frame average precision.

Lexical cues require timestamped text for the downloaded episode. They are therefore an
offline ASR-assisted option, not a claim that Pocket Casts currently has transcripts or
that the ASR stage is cheap enough to run on a phone. The ad-count and duration priors
need only a few constants and a sort over candidate intervals.

## Interpretation

The corpus contains one episode per show and labels ads from transcript differences among
alternate downloads. The labels start/end at recognized speech, so music, jingles, and
other non-speech ad material can lie outside the target interval. The supplied creative
clusters are derived from those transcripts and are used only for a corpus-specific
recurrence diagnostic; they are not detector inputs. A zero cross-episode recurrence
count means only that these 64 clusters contain no cross-episode match, not that ad
creatives never repeat in other podcasts or datasets.

See `REPORT.md` for the measured results and resource implications.

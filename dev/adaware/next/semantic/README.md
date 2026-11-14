# Transcript-context ad pod detector

This experiment tags the full timestamped transcript of each take. It does not
use sibling-take comparisons, reference audio, or predicted change points to
propose intervals. `ads_v2.json` is preferred automatically; the legacy labels
are used only while v2 is absent.

## Models

- **Semantic:** `sentence-transformers/all-MiniLM-L6-v2` ONNX sentence vectors
  for each Whisper segment and a three-segment context window, followed by a
  regularized logistic classifier.
- **TF-IDF baseline:** word bigrams plus character n-grams over the same
  sentence and neighboring transcript context.
- **Decoder:** a semi-Markov interval selector scores runs of ad-tagged
  segments, applies a broad log-duration prior, limits implausible runs, and
  merges short gaps into one maximal commercial or promotional pod.
- **Optional audio refinement:** after text-only pod proposals, local low-energy
  windows may adjust either edge by up to eight seconds. Audio never creates a
  candidate pod. Only short windows around predicted edges are decoded. Use
  `--ffmpeg-path` or `FFMPEG_PATH` to point at an ffmpeg executable on Windows.

Each outer fold holds out one entire episode, keeping all its take variants in
the test fold. There is one episode per show in this collection, so this is also
show-held-out. Decoder settings are selected from grouped inner folds over the
outer training episodes. Interval matches require both boundaries within ten
seconds. Frame metrics use one-second bins.

The source labels are inferred and are not an independent acceptance set.
Results report ad identity provenance separately from boundary support. A
transcript-derived ad with an audio-supported edge remains a semantic-only ad
label. Do not interpret a high score against these generated labels as a 0.90
production guarantee.

## Run

From the repository root, create/sync the local environment and run:

```powershell
New-Item -ItemType Directory -Force dev/adaware/next/semantic/.uv-cache | Out-Null
$env:UV_CACHE_DIR = (Resolve-Path dev/adaware/next/semantic/.uv-cache).Path
uv sync --project dev/adaware/next/semantic --group dev --python 3.12
dev/adaware/next/semantic/.venv/Scripts/python.exe -m pytest -q dev/adaware/next/semantic/tests
dev/adaware/next/semantic/.venv/Scripts/python.exe dev/adaware/next/semantic/detector.py --audio-refine
```

The default run writes `results/evaluation.json`; the embedding cache and model
download stay under the ignored `.cache/` directory. The embedding model is
about 90 MB; evaluation also needs the ONNX runtime and tokenizer. The runtime,
memory, and battery cost have not been measured on Android.

On the Windows CPU used for this experiment, encoding the current 17,591
segments (with repeated sentence/context strings deduplicated across takes)
took 173.4 seconds. The cached 768-value vectors occupy about 48 MB and are
not needed on device. These are desktop measurements, not Android benchmarks;
the phone would encode one take and would also need the embedding model and
runtime.

## Evaluation

The committed results are produced against whichever label set the run prints
at startup. Record the label filename and per-provenance scores when updating
this section. Legacy transcript-difference labels are known to contain partial
pods and editorial spillover; v2 is an inferred, reviewed candidate set and
still requires independent listening before acceptance.

### Frozen v3 boundary-head experiment

The boundary-head follow-up used `ads_v3.json`, frozen at SHA256
`2c600b4e798c07b3afd8fb66bc64427953c3c9db3e7d537e59a626ab62a78870`. V3 was
created after reviewing earlier detector predictions, so these results are
prediction-triggered and exploratory, not independent acceptance metrics.
The strict interval and frame baseline report is in
[`results/REPORT_v3.md`](results/REPORT_v3.md); the nested boundary-head report
is in [`results/boundary_head_v3_REPORT.md`](results/boundary_head_v3_REPORT.md).

Recreate the Windows environment from the repository root, then reproduce the
boundary-head report with:

```powershell
New-Item -ItemType Directory -Force dev/adaware/next/semantic/.uv-cache | Out-Null
$env:UV_CACHE_DIR = (Resolve-Path dev/adaware/next/semantic/.uv-cache).Path
uv sync --project dev/adaware/next/semantic --group dev --python 3.12
dev/adaware/next/semantic/.venv/Scripts/python.exe dev/adaware/next/semantic/detector.py --label-file dev/adaware/next/labels/ads_v3.json --evaluation-note "prediction-triggered v3 labels" --out dev/adaware/next/semantic/results/evaluation_v3.json
dev/adaware/next/semantic/.venv/Scripts/python.exe dev/adaware/next/semantic/boundary_head.py --out dev/adaware/next/semantic/results/boundary_head_v3.json
```

The run uses the frozen TF-IDF v3 baseline in `results/evaluation_v3.json` and
the fixed v3 label snapshot in `results/ads_v3_input_2c600b4e.json`. It took
955.8 seconds on the Windows CPU used here (65 base-model fits and 40 cache
hits). The result JSON keeps selected predictions and fold summaries; per-row
training arrays and redundant predictions for unselected lambda values were
removed after verifying that scores, folds, and selected predictions were
preserved.

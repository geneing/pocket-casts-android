# AdAware handoff — podcast ad detection

State of the work on branch `feature/adaware_dev_M0`, folder `dev/adaware/`. Everything
below is measured, not planned. Where something is a hypothesis it says so.

> **Retired (branch `feature/adaware_jev`):** the transcript TF-IDF detector is no longer
> the method. Ad detection is now the `jev` classifier served by the OpenCode System One
> API. The TF-IDF training/export scripts (`train.py`, `publish.py`, `finish.sh`), the
> exported `bundle/` and `legacy/` bundles were removed and the scripts moved intact to
> `next/m0/retired_tfidf/` (git history has the runnable version). `next/m0/corpus` — the
> per-episode transcripts, sibling-take ad labels, manifest and plan — is now the
> labelling/evaluation corpus for `jev`. Sections 5-11 below describe the earlier
> audio-feature pipeline and are history only.

## 1. Problem and acceptance criterion

Given a downloaded podcast episode (mp3, mono/stereo 16 kHz decodable), find the time
intervals that contain advertisements, offline, on the phone, cheaply. Acceptance:
**precision and recall 0.9** where a hit means both predicted boundaries within **5 s** of
the labelled ad boundaries.

Current state: pipeline complete end to end, **target not met** (see §6).

## 2. Environment / how to run

- Windows host + WSL Ubuntu. Python work runs in WSL; the repo is visible at
  `/mnt/i/Android_Projects/Pocket_Casts`.
- `cd dev/adaware && uv sync` (uv 0.12, Python 3.12). All scripts are run as
  `uv run scripts/<script>.py` from `dev/adaware`.
- ffmpeg/ffprobe required (present).
- whisper runs on CUDA: `faster-whisper` 1.2.1, small model, `float16`. cuBLAS/cuDNN come
  from the `nvidia-*` wheels; `02_transcribe.py` builds `LD_LIBRARY_PATH` itself.
  Throughput ~20x realtime on an RTX 4060. Two pinned constraints: `huggingface-hub<1.0`
  (faster-whisper breaks on 2.x) and audio is passed as a float32 array because PyAV 19
  dropped the `metadata_errors` kwarg faster-whisper passes.
- Set `OMP_NUM_THREADS=4` (or the script's own default) before sklearn; letting OpenBLAS
  use all 22 cores made a single logistic fit take 12+ minutes instead of 24 s.

## 3. Data

Source: `dev/Pocket Casts - Backup of Podcast List.html` (OPML, ~90 feeds).

`scripts/probe_feeds.py` parses it into `data/feeds.json` (feed URL, host, last 5 episodes
with audio URLs). Download selection is hard-coded in `01_download.py`:

| show | network | episode | takes |
|---|---|---|---|
| Hard Fork | Simplecast/Stitcher | Jensen Huang Is Out at Nvidia | 3 |
| The Daily | Simplecast | What Jensen Huang's Resignation… | 3 |
| Planet Money | NPR/Simplecast | How bodegas led to the Yemeni coffeehouse… | 3 |
| 99% Invisible | Simplecast/Stitcher | Perfectly Leveraged for the Squat | 3 |
| Freakonomics Radio | Simplecast | Does Democracy Really Need Elections? | 3 |
| Dan Snow's History Hit | Acast | How Rasputin Destroyed the Romanovs | 3 |
| The Economics Show (Soumaya Keynes) | Acast | Can Economics Fight Fascism? | 3 |
| Odd Lots | Omny/Triton | What Nvidia's Megaspeed Scandal… | 3 |
| Revisionist History | Omny | From The Big Dig: Mitt & Ted | 3 |
| The Rest Is Politics | Megaphone | What Living Under ISIS Taught Me | 3 (identical) |

- 10 episodes x 3 takes = 30 files, **20.9 h audio, 1.2 GB** in `data/audio/<show>__<episode>/take{0,1,2}.mp3`.
- Manifest with URLs, final (redirected) URL, UA, bytes, md5, duration: `data/downloads.json`.
- Megaphone episode returned byte-identical takes (no ad inventory in that drop), so it
  has no dynamic labels. Everything else differs per request.

Layout of derived data:

```
data/audio/<ep>__<slug>/takeN.mp3       1.2 GB, originals
data/transcripts/<ep>/takeN.json        faster-whisper small, segments + word timestamps
data/labels/ads.json                    ground truth: per take, ad intervals + text
data/labels/ad_spans.json               flat corpus of ad spans
data/labels/ad_creatives.json           clustered recurring creatives
data/labels/ads_report.md               human-readable ad inventory
data/features/<ep>/takeN.npz            times (N,), feats (N,650), names (650,), silence_dist
data/model/model.json                   scaler + logistic weights + post-processing params
data/model/metrics.json                 LOEO interval metrics
data/model/coverage_metrics.json        frame-level / coverage metrics
data/model/oof/<ep>__takeN.npz          out-of-fold prob, gold mask, gold starts/ends
data/reports/evaluation.md, detector_timeline.png
out/AdDetector.kt                       generated Kotlin (not integrated in the app)
data/models/whisper/                    464 MB, cached whisper small
```

## 4. Getting different ads for the same episode (the enabling trick)

Requesting the same enclosure several times yields the same body with a different ad pod,
because DAI stitches per request. Measured with `scripts/exp_variants.py`
(`data/variants/experiment.json`):

| host | varies per request? | note |
|---|---|---|
| Simplecast/Stitcher (`simplecastaudio.com`) | yes | used by NYT, NPR, 99% PI; add `&_take=N` |
| Acast (`sphinx.acast.com`) | yes | redirects to per-request `livestitches/<hash>.mp3` |
| Omny/Triton (`traffic.omny.fm`) | yes | per-request `media-session/<uuid>` |
| Megaphone (`traffic.megaphone.fm`) | usually yes | `dcs-spotify…request_event_id=…`; one episode cached and identical |
| NPR (`feeds.npr.org`) | yes | |

Acast's `tk=` token is base64 JSON with `"ads":true` but it is signed (`sig=`); editing it
does not disable ads (`scripts/exp_acast_tk.py`). UA rotation and cache-busting params are
cosmetic; plain re-requests are enough. Files differ in md5 and duration (20 s - 2 min),
the decoded body outside ad slots is identical.

## 5. Pipeline

1. `probe_feeds.py` -> `data/feeds.json`.
2. `01_download.py` -> takes, manifest. Idempotent.
3. `02_transcribe.py` -> word-timestamped transcripts (GPU, ~2.5 min per 40 min episode).
4. `03_ads_from_transcripts.py` -> labels.
   Method: align the word streams of two takes on **4-word shingles** with difflib. An ad
   pod has no shingle correspondence in the other take, so the **complement of the matched
   anchors** is the ad interval. This is insensitive to the body shifting after a pre-roll.
   Filters: resulting region >= 8 s; for regions < 20 s require ad vocabulary
   (`ad_score`) and reject if the other take says nearly the same thing nearby
   (rapidfuzz token_set_ratio >= 55). Pairs are all take pairs, spans deduped by IoU then
   merged with a 2 s gap.
   Output: 103 slots, 102.8 min of ad audio, all >= 8 s, over 9 episodes (8 with DAI).
   60 distinct creatives at TF-IDF cosine 0.6 (Ramp, Capital One, Peloton, US Bank,
   Ozempic/Adyen, Hartford, Verizon, Lowe's, Apple Card, Smartless/Hidden Brain promos,
   plus "additional taxes, fees and terms apply" disclaimers). See `data/labels/ads_report.md`.
5. `04_features.py` -> 650 features per 0.25 s frame (see §6).
6. `05_train_eval.py` -> LOEO logistic regression + post-processing tuning + `model.json`.
7. `06_export_kotlin.py` -> `out/AdDetector.kt` (package
   `au.com.shiftyjelly.pocketcasts.repositories.ads`; mean/scale/weights/bias + constants).
8. `07_report.py` -> markdown + timeline plot; `07b_coverage.py` -> frame/coverage numbers.

Diagnostics (not pipeline): `exp_variants.py`, `exp_acast_tk.py`, `exp_models.py`,
`exp_norm.py`, `exp_post.py`, `diag_boundaries.py`, `05b_segments.py` (change-point
alternative), `show_labels.py`, `stats.py`.

## 6. Features

16 kHz mono, 25 ms frames, 10 ms hop -> 43 base features: dBFS, peak, crest, ZCR,
centroid, bandwidth, 85% rolloff, flatness, flux, autocorrelation F0 + voicing, 7 octave
band levels relative to frame total, band spread, **13 MFCCs** (26 mel filters + DCT).
Pooled to 0.25 s with mean/std/min/max (172), then per feature: deviation from rolling
+/-2 s and +/-10 s means, rolling std at both scales (4 more blocks), plus:
**modulation** (0.5-2, 2-6, 6-12 Hz energy of the loudness / brightness / 2-4 kHz
envelopes), **silence** (seconds to nearest 12 dB dip, quiet fraction in +/-5 s),
**position** (distance from start / end capped at 10 min). Total 650 per frame; an STFT,
one 26x26 mel projection, one autocorrelation and running means — cheap on a phone.

## 7. Model and results

Model: per-frame logistic regression, `class_weight="balanced"`, C=0.3, then moving-average
smoothing, hysteresis thresholds (hi to fire, lo to extend), gap merge 6 s, minimum
duration tuned. Tuning picks post-processing on out-of-fold predictions. Splits are
**leave-one-episode-out** (no take of the test episode in training).

| metric | value |
|---|---|
| frame-level average precision (mean over 9 episodes) | **0.33** (range 0.11 - 0.89) |
| frame precision / recall at chosen threshold | 0.23 / 0.49 |
| predicted ad minutes that are really ads | 22.5 % |
| fraction of ad minutes found | 49 % |
| **interval P / R at 5 s boundary tolerance** | **0.02 / 0.06** (tp 6, fp 244, fn 97) |

Per-episode AP: Dan Snow 0.63, The Economics Show 0.89, Hard Fork 0.33, Odd Lots 0.31,
Planet Money 0.27, Daily 0.17, Revisionist History 0.13, 99% Invisible 0.11.

What was tried and did not help:

- Segment-then-classify (`05b_segments.py`): acoustic change points (3 s before/after
  median feature distance), pool features per segment, logistic per segment. 3861 segments,
  interval P 0.04 / R 0.06.
- Per-episode normalisation (`exp_norm.py`): raw 0.33 AP, relative-only 0.29, z-score
  variants no better.
- Post-processing (`exp_post.py`): simple threshold, hysteresis, peak-growing; top-K
  intervals per episode was added but the sweep was not completed.
- Silence snapping (`to_intervals(..., silence=...)`): no gain.
- Boundary diagnostic (`diag_boundaries.py`): of 70 matched gold ads, median IoU 0.48,
  start delta median -6.4 s (p10 -71, p90 +26), end +0.7 s; only 9 % of matched pairs have
  both boundaries within 5 s. Predictions systematically include the jingle/music before
  the first word.
- Model families (`exp_models.py`): logreg C=0.1/1.0 both AP ~0.33; HGB/RF runs were
  killed before finishing (too slow on 290k x 650 with 9 folds), so the tree ceiling is
  **not measured**.

## 8. Why it fails, as far as the evidence goes

1. **Labels are ASR-based, not audio-based.** They start at the first transcribed word, so
   the real stitched boundary (jingle, music bed, 5-30 s of non-speech) is outside the
   label. That alone breaks the 5 s criterion for otherwise correct detections. Evidence:
   Planet Money take0 label 273.1-338.2 while the ad audio starts ~241 (the model's
   prediction starts there); transcript at 267-272 is "stay tuned for the following
   announcements…".
2. **Host-read ads are acoustically identical to the show.** Produced spots (Dan Snow,
   Economics Show) are detected well (AP 0.6-0.9); host reads are not separable from
   content once the stitcher re-encodes the episode at one bitrate.
3. **Ads identical across takes are unlabelled.** If two takes got the same national spot,
   the diff cannot see it, so those minutes are labelled `content` and teach the model
   that ad-like audio is content. This explains false positives on intros/promos.
4. **One episode per show.** Cross-show generalisation is the hard part and the model has
   no per-show scale to learn.
5. **Frame-level linear model has a ceiling.** The probability curve is diffuse
   (`detector_timeline.png`): long stretches at p~1 on some shows, near-zero on others.

## 9. Directions worth trying next (untested unless noted)

- **Audio-derived labels.** Align two takes by feature correlation (e.g. windowed
  cross-correlation on MFCC/loudness, piecewise offset), then take the uncovered region as
  the ad, with sample-accurate edges. Fixes both training targets and the 5 s evaluation.
  This is the single highest-value change.
- **Label the shared ads.** Cross-episode clustering of the transcript corpus already
  finds them; add them as positives (and use them as a "recurring creative" fingerprinter).
- **More data.** 10 episodes/show minimum; 3-5 takes per episode; keep the take-diff trick
  as the label source.
- **Better model.** Small 1-D CNN on log-mel (2-3 conv layers) with a boundary head, or
  segment-level classifier over change points with a CRF/HMM over segment labels; export
  via TFLite/ONNX or a tiny hand-rolled inference. A linear model over handcrafted
  features is a floor.
- **Ad-pod prior.** 1 pre-roll + 1-2 mid-rolls; at most K intervals per episode; duration
  priors. Cheap precision win, untested properly.
- **Non-audio signals available on device**: chapter markers, feed metadata, recurring
  creative fingerprints (Pocket Casts has the audio and the episode cache).

## 10. Exact commands

```bash
cd dev/adaware
uv sync
uv run scripts/probe_feeds.py            # OPML -> data/feeds.json
uv run scripts/01_download.py            # 30 takes, resumes, ~1.2 GB
uv run scripts/02_transcribe.py          # GPU, ~20x realtime
uv run scripts/03_ads_from_transcripts.py
uv run scripts/04_features.py --workers 5
uv run scripts/05_train_eval.py          # writes model.json + metrics.json
uv run scripts/06_export_kotlin.py
uv run scripts/07_report.py && uv run scripts/07b_coverage.py
```

## 11. Known rough edges

- `03_ads_from_transcripts.py` is O(pairs) difflib on 4-shingles; ~5 min for 10 episodes.
- `05_train_eval.py` needs `OMP_NUM_THREADS=4` (set in-file) or fits take 25x longer.
- `exp_post.py` has a half-finished `topk` argument (added to `strategy`, not swept).
- `05b_segments.py` is exploratory and not wired into the export.
- The Megaphone episode is kept as a no-DAI control; it has no labels, so it is excluded
  from training and evaluation.
- `out/AdDetector.kt` is generated and **not** added to the Android build.
- The `data/` tree is ~2 GB (audio + features + whisper cache); whisper cache can be
  deleted, features are regenerable in ~8 min.

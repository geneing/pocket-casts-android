# AdAware — podcast ad detection research

Goal: find the time intervals that contain ads in a downloaded podcast episode, on the
phone, offline, with enough accuracy to skip them automatically (target precision and
recall 0.9 with 5 s boundary tolerance).

Everything here is research code. The phone-ready artifact is a small linear model over
cheap spectral features, exported by `scripts/06_export_kotlin.py`.

> **Retired (branch `feature/adaware_jev`):** the transcript TF-IDF detector and the
> exported phone model described below are history. Detection is now the `jev` classifier
> served by the OpenCode System One API; the retired training code is in
> `next/m0/retired_tfidf/`.

## Pipeline

| step | script | output |
|---|---|---|
| 1. feeds | `scripts/probe_feeds.py` | `data/feeds.json` (OPML → RSS with audio URLs) |
| 2. download | `scripts/01_download.py` | `data/audio/<show>__<episode>/take{0,1,2}.mp3` + `data/downloads.json` |
| 3. transcribe | `scripts/02_transcribe.py` | `data/transcripts/<episode>/takeN.json` (faster-whisper small, word timestamps) |
| 4. labels | `scripts/03_ads_from_transcripts.py` | `data/labels/ads.json`, `ad_spans.json`, `ad_creatives.json`, `ads_report.md` |
| 5. features | `scripts/04_features.py` | `data/features/<episode>/takeN.npz` (0.25 s grid, 650 features) |
| 6. model | `scripts/05_train_eval.py` | `data/model/model.json` + leave-one-episode-out metrics |
| 7. port | `scripts/06_export_kotlin.py` | `out/AdDetector.kt` |
| 8. report | `scripts/07_report.py`, `07b_coverage.py` | `data/reports/` |

Diagnostics, not part of the pipeline: `exp_variants.py` (DAI experiment),
`exp_acast_tk.py` (Acast token tampering), `exp_models.py` (model families),
`exp_norm.py` (per-show normalisation), `exp_post.py` (post-processing),
`diag_boundaries.py` (boundary error), `05b_segments.py` (change-point alternative),
`show_labels.py`, `stats.py`.

Run with `uv run scripts/XX.py` from this folder (see `pyproject.toml`).

## Dynamic ad insertion: how to get different ads for the same episode

The training trick is that most podcast hosts stitch ads per request. Downloading the
same episode several times gives the same body with *different* ad pods, and anything
that differs between takes is an ad by construction.

What actually worked (`scripts/exp_variants.py`, `data/variants/experiment.json`):

| host | behaviour | way to vary |
|---|---|---|
| **Simplecast/Stitcher** (`simplecastaudio.com`, used by NYT, NPR, 99% PI) | full DAI, remuxes per request | any request, add `&_take=N` / `&cb=N` |
| **Acast** (`sphinx.acast.com`) | redirects to a per-request `livestitches/<hash>.mp3` | re-request; `tk=` token has `"ads":true` but is signed, tampering with it only changes the cache bucket, ads stay |
| **Omny/Triton** (`traffic.omny.fm`) | per-request `media-session` URL | re-request |
| **Megaphone** (`traffic.megaphone.fm`) | per-request `dcs-spotify...&request_event_id=...`, sizes differ by ad pod | re-request; some episodes (e.g. subscriber feed) come back byte-identical, i.e. no inventory |
| **NPR** (`feed.npr.org`) | source files already differ per request | re-request |

Practical notes:

- Do the takes within one session. Ad inventory rotates, and late-night requests hand out
  fewer ads, so a take at a very different time of day mostly differs by *missing* ads,
  which is still usable but noisier.
- Vary `User-Agent` (Pocket Casts Android/iOS, Apple Podcasts, browser) — some providers
  bucket creatives per device class.
- The body itself is byte-identical between takes (different MD5 but identical decoded
  audio outside the ad slots), which is what makes the transcript diff clean.
- Some shows bake ads in (host reads). Those never differ between takes; the transcript
  stage catches them with ad vocabulary and cross-episode copy clustering.

## Labels from transcripts

`03_ads_from_transcripts.py` aligns the word stream of two takes on 4-word shingles. An
ad pod has no shingle correspondence in the other take, so the complement of the matched
anchors is the ad interval — no drift heuristics, robust to the body shifting when a
pre-roll is inserted. Spans are then:

- kept when ≥ 8 s (ads are rarely shorter; speaker turns are not),
- for spans < 20 s, kept only with ad vocabulary and only when the other take does not say
  nearly the same thing nearby (rapidfuzz ≥ 55) — that removes ASR disagreement on names
  and credits.

`ad_creatives.json` clusters ad copy by TF-IDF cosine ≥ 0.6, which is what "typical ad
text" looks like: recurring national spots, network promos, disclaimers ("additional
taxes, fees and terms apply").

## Features

25 ms frames, 10 ms hop, pooled to 0.25 s with mean/std/min/max, then relative to rolling
±2 s and ±10 s means and standard deviations (so the model sees "louder than the last ten
seconds", not absolute loudness), plus envelope modulation and silence neighbourhood:

- loudness: dBFS, peak, crest factor
- spectrum: centroid, bandwidth, 85 % rolloff, flatness, flux
- 13 MFCCs (26 mel filters, DCT): voice and music colour
- 7 octave-ish band levels relative to the frame total (loudness-invariant timbre)
- pitch: autocorrelation F0 + voicing strength
- modulation: 0.5-2, 2-6, 6-12 Hz energy of the loudness and brightness envelopes
  (speech has a 2-8 Hz syllable rhythm; music beds are steadier)
- silence: seconds to the nearest 12 dB dip, fraction of quiet frames in ±5 s
- position: distance from episode start / end, capped at 10 min

650 numbers per 0.25 s. All of it is an STFT, a 26x26 mel projection, an autocorrelation
and running means, so a phone does it at real time with a fraction of one core.

## Model

Logistic regression on the frame grid, `class_weight="balanced"`, then a moving-average
smoother, a hysteresis threshold, a gap merge and a minimum duration. Leave-one-episode-out
CV (no take of a test episode appears in training) reports interval precision/recall with
5 s boundary tolerance, plus average precision per episode.

## Dataset and results (this run)

- 10 episodes, 30 takes, 20.9 h of audio (1.2 GB mp3), 5 networks.
- 103 dynamic ad spans, 103 min of ad audio, all >= 8 s. One Megaphone episode came back
  byte-identical on every take (no DAI inventory in that drop), so it contributed no
  dynamic labels.
- 60 distinct creatives clustered by TF-IDF cosine 0.6: Ramp, Capital One, Peloton,
  US Bank, Ozempic/Adyen, Hartford, Verizon, Lowe's, Apple Card, Smartless and
  Hidden Brain promos, plus the standard disclaimers.

Leave-one-episode-out (`data/model/metrics.json`, `data/reports/evaluation.md`):

| metric | value |
|---|---|
| frame-level AP | 0.33 (0.11 - 0.89 per episode) |
| frame precision / recall | 0.23 / 0.49 |
| predicted ad minutes that are ads | 22.5 % |
| ad minutes found | 49 % |
| interval P / R at 5 s boundary tolerance | 0.02 / 0.06 |

**The 0.9 target is not met.** What the diagnostics show:

- The frame model does learn ad-ish audio: AP 0.63 on Dan Snow (produced Verizon/Apple
  spots), 0.89 on The Economics Show (music-bedded sponsor reads), but 0.11-0.17 on shows
  whose ads are host-read and acoustically identical to the show. Host-read copy is the
  wall here: no spectral or prosodic cue separates it from content once the stitcher has
  re-encoded the whole episode at one bitrate.
- Boundaries are the bigger problem. Ads usually begin with a jingle or music bed and end
  in music, while the transcript labels start at the first *word*. Predicted intervals
  overlap the ads (median IoU 0.48) but miss the ±5 s boundary test; the label itself is
  5-30 s inside the real ad. Snapping to nearby silences (`to_intervals(..., silence=)`)
  did not recover it.
- 244 false intervals over 9 episodes: the model fires on host intros and on promo
  reads that are not in the labels because they were identical across takes.

### What would move the needle

1. More episodes per show - 1 episode/show cannot separate show identity from ad-ness.
   Ten per show is a realistic minimum.
2. Label the exact stitched boundaries from audio, not from ASR. Two takes of the same
   episode can be aligned by feature correlation; the inserted region then has sample
   accurate edges. That fixes both training targets and the 5 s criterion.
3. Label the ads that are identical across takes (cross-episode clustering of the
   transcript corpus already finds them; they are the false positives above).
4. Replace the linear model with a small 1-D CNN on log-mel frames (2-3 conv layers,
   still phone-fast) and a boundary head. This is the biggest single accuracy jump in the
   literature; a linear model over handcrafted features is a floor, not a ceiling.
5. Consider non-audio signals available on device: chapter markers, "ad" flags in the
   feed, and fingerprinting recurring creatives - Pocket Casts already has the audio.


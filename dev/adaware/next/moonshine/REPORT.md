# M1 — Moonshine tiny-streaming word timestamps vs the Whisper corpus

Measured on the development laptop (Windows host, WSL Ubuntu, CPU only), branch
`feature/adaware_jev_moonshine`. Everything below is a measured number; where a number is
missing the report says why.

The question M1 answers: **are tiny-streaming's word times accurate enough to cut ad
boundaries on**, and does the model bundle we plan to ship actually carry the attention
decoder that produces them?

## Verdict

| Question | Answer | Number |
|---|---|---|
| Word timestamps vs Whisper, same audio | tight, with a constant offset | median **0.24 s**, p90 **0.38 s**, 96.2 % within 0.5 s, 99.4 % within 1 s |
| Ad-boundary accuracy (the metric that matters), 22 labelled boundaries | good enough by a wide margin | median **0.07 s**, p90 **0.26 s**, max **0.29 s**, 100 % within 1 s |
| Same metric, Whisper (label source) | the floor, not an independent check | median 0.05 s, p90 0.17 s, max 0.30 s |
| Attention decoder in the downloaded English bundle | **YES** — `decoder_kv_with_attention.ort`, 32,676,624 B | total bundle 74.5 MiB (43.3 MiB without it) |
| Attention decoder for Spanish | **NO** — not in the catalog or on the CDN | `word_timestamps = true` yields **0 words**, text still fine |
| Speed on this laptop, tiny streaming | batch 4-6x realtime, streaming 2.3x at 0.5 s chunks | **645-950 s and ~1,550 s of wall clock per hour of audio** |

**Yes, tiny-streaming's timestamps are good enough to cut ad boundaries.** The measured
boundary error is 0.07 s median / 0.29 s worst, against a skip tolerance of 1 s (and the
project's own 5 s acceptance criterion). Timestamp accuracy is not the bottleneck for ad
skipping; ad *detection* and pods that open with music (no word to snap to) are.

Two things to carry into the Android work:

1. The error is mostly **bias, not jitter**: Moonshine's word times sit a constant
   **+0.24 s later** than Whisper's (p10 +0.16, p90 +0.40). That is invisible at 1-2 s
   tolerance, but it means the two engines must not be mixed without a correction.
2. **Spanish has no word timestamps at all** (see §2/§8). The line-level fallback is
   cheap: a Moonshine line opens only ~0.16 s before the first spoken word (|d| median
   0.17 s, p90 0.42 s), so line-only cues barely move the boundary.

## 1. What was measured

Three English episodes, `take0` only, full episodes, 100.9 min of audio, 11 labelled ad
pods (8.2 min of ad audio; 22 boundaries):

| episode | audio | ad pods | boundaries | Whisper reference |
|---|---|---|---|---|
| `hard-fork__jensen-huang-is-out-at-nvidia` | 45.3 min | 3 (38/34/49 s) | 6 | re-transcribed, same file |
| `planet-money__how-bodegas-led-to-the-yemeni-coffeehous` | 27.4 min | 4 (27/28/30/24 s) | 8 | re-transcribed, same file |
| `the-daily__what-jensen-huang-s-resignation-will-mea` | 28.2 min | 4 (37/115/73/36 s) | 8 | re-transcribed, same file |

Spanish: one 5 min window of real Spanish speech from *Radio Ambulante* (no Spanish episode
exists in `dev/adaware`), used only for the word-timestamp and text checks.

## 2. The model bundles — the critical unknown

`moonshine-voice` 0.1.6, Python 3.12.10, `pip install moonshine-voice`, models fetched with
`get_model_for_language(lang, ModelArch.TINY_STREAMING, include_word_timestamps=True)`
(`download_models.py`).

English, `models/en/quantized_26_11_02/` (catalog URL
`https://download.moonshine.ai/model/tiny-streaming-en/quantized_26_11_02`):

| file | bytes |
|---|---|
| `adapter.ort` | 1,319,616 |
| `cross_kv.ort` | 1,287,504 |
| `decoder_kv.ort` | 32,748,720 |
| **`decoder_kv_with_attention.ort`** | **32,676,624** |
| `encoder.ort` | 7,675,392 |
| `frontend.model.ort` | 23,392 |
| `frontend.weights.ort` | 2,093,416 |
| `streaming_config.json` | 509 |
| `tokenizer.bin` | 249,974 |
| **total** | **78,075,147 B = 74.5 MiB** |

**The attention decoder is present and was used.** Evidence:

* the file is in the directory above after a download with `include_word_timestamps=True`;
* without that flag the same catalog entry resolves to 8 files /
  45,398,523 B (43.3 MiB) — the decoder is an optional extra, not part of the base bundle;
* a transcription with `word_timestamps: "true"` returns populated
  `TranscriptLine.words[]` (8,240 words on a 45 min episode; 0 words on a 2 min clip with
  the option off), which is the code path that loads the decoder
  (`compute_word_timestamps` / `decoder_kv_with_attention.ort` in `libmoonshine.so`).

Spanish, `models/es/quantized_26_08_24/`:

| file | bytes |
|---|---|
| `adapter.ort` | 1,318,472 |
| `cross_kv.ort` | 1,288,120 |
| `decoder_kv.ort` | 19,717,336 |
| `encoder.ort` | 7,772,792 |
| `frontend.model.ort` | 23,176 |
| `frontend.weights.ort` | 2,093,280 |
| `streaming_config.json` | 509 |
| `tokenizer.bin` | 102,888 |
| **total** | **32,316,573 B = 30.8 MiB** |

**No `decoder_kv_with_attention.ort` for Spanish, even with `include_word_timestamps=True`**
— the catalog simply has no such entry for `es`, and the CDN has no such object. What
happens at runtime is a silent degradation, not an error: the model loads, transcription
is correct Spanish, and `words` is `[]` on every line (verified in both batch and streaming
modes: 0 words, 70 lines). A "Failed to load decoder_kv_with_attention" warning string
exists in the library, but the Python layer surfaces no failure — code that assumes words
exist will silently get none.

So: **word timestamps are English-only.** The bundle you ship decides the cue path:
74.5 MiB for English-with-words, 30.8 MiB for Spanish-without.

## 3. Method, and why the committed corpus could not be reused

The committed Whisper transcripts and labels describe audio that no longer exists, and a
re-download is **not** the same file: dynamic ad insertion stitches a different pod per
request. Measured (`data_check.py`, `results/data_check.json`):

| episode | manifest | downloaded | delta | committed spans | re-derived |
|---|---|---|---|---|---|
| hard-fork | 2710.6 s / 43,369,630 B | 2716.6 s / 43,465,763 B | **+6.0 s** | 4 | 3 |
| planet-money | 1657.4 s / 26,518,328 B | 1641.7 s / 26,267,971 B | **−15.7 s** | 3 | 4 |
| the-daily | 1737.8 s / 27,805,306 B | 1694.1 s / 27,106,476 B | **−43.7 s** | 6 | 4 |

Reusing the committed labels would have measured the wrong audio. So M1, per episode:

1. downloads `take0` **and** `take1` fresh (`download_audio.py`);
2. re-runs faster-whisper `small` (CUDA, float16, beam 5, VAD on, word timestamps) on those
   files — the same model/settings as `dev/adaware/scripts/02_transcribe.py`
   (`whisper_reference.py`, `run_whisper.sh`); 100.9 min × 2 takes in 8.5 min on the GPU;
3. re-derives ad spans from the fresh sibling pair with the repo's algorithm (4-word
   shingle `difflib` diff, ≥8 s, ad-vocabulary gate under 20 s) — `derive_labels.py`;
4. transcribes `take0` with Moonshine (`transcribe.py`), 100.9 min in 22.9 min;
5. scores (`score.py`, `summarize.py`).

**Whisper is the reference, not ground truth.** Two consequences, stated because they bound
every number below:

* The WER is *agreement* with Whisper small, not accuracy. A word both models get wrong the
  same way counts as correct.
* The labels are derived from Whisper's own word times, so **Whisper's boundary error
  (0.05 s median) is a floor by construction, not an independent measurement.** The useful
  reading of the boundary table is "how close is Moonshine's nearest word boundary to the
  boundary Whisper drew", i.e. a bound on how much Moonshine would move the cut relative to
  the current pipeline. An audio-derived reference (energy/silence onset at pod edges) is
  the only way to get absolute truth, and was not built.

Normalisation: NFKD accent folding, lowercase, apostrophes dropped, tokens `[a-z0-9$%]+`,
one token per word piece (so `ChatGBT` vs `Chat Gbt` is not a matching failure). Word pairs
come from an **optimal** Levenshtein alignment (`rapidfuzz`), not `difflib` — `difflib` is a
greedy LCS, drifts after the first disagreement and inflated the Spanish WER from 0.58 to
0.85 before it was swapped out. Metric: `|moon_start − whisper_start|` and
`|moon_end − whisper_end|` over matched words; ad boundary error is the distance from the
label time to the nearest word boundary (start *or* end) in each transcript.

## 4. Text agreement

| episode | WER | sub / del / ins (of ref tokens) | CER, spaces removed |
|---|---|---|---|
| hard-fork | 0.147 | 363 / 84 / 719 of 7,913 | 0.093 |
| planet-money | 0.149 | 300 / 60 / 403 of 5,104 | 0.102 |
| the-daily | 0.167 | 284 / 26 / 397 of 4,243 | 0.108 |
| **pooled** | **0.153** | 2,636 errors / 17,260 ref tokens | ~0.10 |

Reading it honestly:

* Part of the WER is **tokenisation/formatting**, not wrong words: the no-space CER is
  ~0.10 against a word WER of ~0.15. `25` vs `twenty five`, `ChatGBT` vs `Chat Gbt`,
  hyphenation and fillers each cost a word slot while barely changing the characters.
* Insertions dominate the error (1,519 of 2,636). The English model does not drop audio; it
  emits more tokens than Whisper small, including fillers.
* A VAD sensitivity check on `the-daily` (Whisper re-run with `vad_filter=False`) moved WER
  only 0.167 → 0.159, so the insertion excess is not an artifact of Whisper's VAD; it is
  real tokenisation/lexical difference.

This is a *tiny* model against a *small* one: usable for classification, but the recurring
failure modes are names, spelled-out numbers and split identifiers.

## 5. Word timestamps

Matched words only (16,143 pairs out of 17,260 reference tokens), same audio:

| | n | median | p90 | p99 | max | ≤0.2 s | ≤0.5 s | ≤1.0 s | ≤2.0 s | signed median |
|---|---|---|---|---|---|---|---|---|---|---|
| start | 16,143 | **0.24 s** | **0.38 s** | 0.80 s | 14.69 s | 28.8 % | **96.2 %** | 99.4 % | 99.9 % | **+0.24 s** |
| end | 16,143 | 0.26 s | 0.43 s | 0.76 s | 14.63 s | 23.4 % | 94.4 % | 99.7 % | 99.9 % | +0.26 s |
| start, body only | 15,216 | 0.24 s | 0.39 s | 0.79 s | 10.66 s | 28.9 % | 96.2 % | 99.4 % | 99.9 % | +0.24 s |
| end, body only | 15,216 | 0.25 s | 0.43 s | 0.75 s | 10.23 s | 23.8 % | 94.7 % | 99.7 % | 100 % | +0.25 s |

* "body only" drops words inside a labelled pod, where the two transcripts describe
  *different ads* and their pairing is arbitrary. It barely moves the percentiles.
* The **tails are alignment artefacts, not timing errors**: the 14.69 s "worst word" is the
  word "the" (Whisper 36.91 s, Moonshine 22.22 s) inside a pre-roll pod — the optimal
  alignment paired unrelated "the"s. Every case above ~1 s is of this kind (pod audio, or a
  repeated function word after a big insertion).
* The distribution is a **narrow band around +0.24 s**, not noise: p10 +0.16, p90 +0.40.
  Whisper is known to place word onsets early; Moonshine's DTW may well be the closer of the
  two to the acoustic onset. **Not tested** — that needs an energy-onset reference, which is
  the follow-up in §12.

## 6. Ad boundaries — the metric that matters

For each labelled ad boundary (22 of them), the distance from the label time to the nearest
word boundary in each transcript, i.e. where a "skip to the word" rule would cut:

| transcript | n | median | p90 | max | ≤0.2 s | ≤0.5 s | ≤1 s | ≤2 s |
|---|---|---|---|---|---|---|---|---|
| **Moonshine tiny streaming** | 22 | **0.07 s** | **0.26 s** | **0.29 s** | 72.7 % | 100 % | 100 % | 100 % |
| Whisper small (label source) | 22 | 0.05 s | 0.17 s | 0.30 s | 95.5 % | 100 % | 100 % | 100 % |

Per boundary, worst first:

| episode | edge | label time | Whisper abs | Moonshine abs | Moonshine signed |
|---|---|---|---|---|---|
| hard-fork | end | 1258.88 | 0.04 | 0.29 | +0.29 |
| the-daily | end | 1538.55 | 0.02 | 0.28 | −0.28 |
| planet-money | end | 1150.47 | 0.12 | 0.26 | −0.26 |
| planet-money | end | 1616.84 | 0.18 | 0.26 | −0.26 |
| hard-fork | end | 249.20 | 0.18 | 0.24 | −0.24 |
| planet-money | end | 330.55 | 0.10 | 0.21 | −0.21 |
| hard-fork | start | 2665.33 | 0.12 | 0.16 | +0.16 |
| the-daily | start | 1654.88 | 0.09 | 0.10 | +0.10 |
| the-daily | end | 1691.00 | 0.00 | 0.10 | +0.10 |
| the-daily | start | 695.80 | 0.02 | 0.09 | +0.09 |
| hard-fork | end | 2714.65 | 0.00 | 0.07 | −0.07 |
| the-daily | end | 37.21 | 0.12 | 0.07 | −0.07 |
| the-daily | start | 1465.14 | 0.30 | 0.06 | −0.06 |
| hard-fork | start | 1224.99 | 0.04 | 0.04 | −0.04 |
| planet-money | start | 303.66 | 0.04 | 0.04 | −0.04 |
| planet-money | start | 1586.85 | 0.06 | 0.03 | +0.03 |
| planet-money | start | 1617.58 | 0.06 | 0.03 | −0.03 |
| hard-fork | start | 211.49 | 0.02 | 0.02 | +0.02 |
| planet-money | start | 1122.96 | 0.04 | 0.01 | −0.01 |
| planet-money | end | 1641.14 | 0.00 | 0.01 | +0.01 |
| the-daily | start | 0.00 | 0.00 | 0.00 | +0.00 |
| the-daily | end | 811.03 | 0.12 | 0.00 | +0.00 |

Worst cases are all **ad-end** boundaries, and all ≤0.29 s. Two reasons they are not
worse: (a) pods end on speech followed by silence, so there is a word boundary right at the
edge; (b) the nearest-of-start-or-end rule gets two candidates. Ad *starts* are ≤0.16 s.

Why the boundary error (0.07 s median) is *smaller* than the word error (0.24 s median): the
labels are padded ±0.3 s outside the pod, so the nearest word boundary is the pod's outer
edge, and Moonshine's constant +0.24 s lateness pushes that edge toward the pad rather than
away from it. The two effects partly cancel. This is worth knowing: the boundary metric is
lenient in a way the word metric is not.

**Caveats that limit what this proves**

* Whisper-derived labels ⇒ the Whisper column is a floor (see §3). The Moonshine column is
  "displacement relative to the current pipeline", which is the right decision variable.
* These pods happen to start and end on speech. A pod that opens with 10-30 s of music or a
  jingle has **no word boundary at the real edge** — no ASR, Whisper or Moonshine, can put
  one there. That is the same failure the audio detector hit (`dev/adaware/HANDOFF.md` §8),
  and it is a cue/edge problem, not a timestamp-accuracy problem.

## 7. Speed (this laptop, CPU only)

Hardware: **Intel Core Ultra 9 185H**, 22 threads, 15 GB RAM, Ubuntu 24.04 under WSL 2,
Python 3.12.10. **No GPU was used**: the shipped `libmoonshine.so` links only the CPU
execution provider (`strings ... | grep ExecutionProvider` → `CPUExecutionProvider`; no
CUDA libraries in the wheel, `ldd` shows only `libonnxruntime` + libc/libstdc++), and ONNX
Runtime itself logs `GPU device discovery failed: ... /sys/class/drm/card0/device/vendor`.

RTF = audio seconds / wall seconds (higher is faster). Times exclude model load
(1.4-1.5 s per model) and ffmpeg decode.

| run | audio | wall | RTF | s per hour of audio |
|---|---|---|---|---|
| batch, 2 min clip (idle machine) | 120 s | 8.5 s | **14.1x** | 255 |
| batch, 10 min clip | 600 s | 87.2 s | **6.9x** | 523 |
| batch, full episode 28.2 min (idle) | 1,692 s | 382.8 s | **4.4x** | 813 |
| batch, full episode 27.4 min | 1,642 s | 353.5 s | 4.6x | 773 |
| batch, full episode 45.3 min | 2,717 s | 717.5 s | 3.8x | 951 |
| streaming, 0.5 s chunks, 2 min clip | 120 s | 51.7 s | **2.3x** | 1,552 |
| streaming, 1.0 s chunks, 2 min clip | 120 s | 33.3 s | 3.6x | 1,000 |
| streaming, 2.0 s chunks, 2 min clip | 120 s | 24.1 s | 5.0x | 720 |
| streaming, 1.0 s chunks, 5 min Spanish | 300 s | 178.8 s | 1.7x | 2,145 |

Notes:

* Aggregate for the three-episode run: 100.9 min of audio in 22.9 min of wall clock,
  **RTF 4.41**. The four full-episode batch runs span 3.8-5.6x (645-950 s per hour), with
  the spread coming from run-to-run variance on this laptop, not from the audio: an
  isolated repeat of the 28 min episode gave 4.4x where the first pass gave 5.6x. Treat
  "roughly 4-6x for a full episode" as the laptop number.
* **Batch throughput falls with file length** (14x at 2 min → 3.8x at 45 min) on a single
  call. Chunking the file is the cheap fix if batch mode is ever used for bulk work.
* **Streaming throughput depends strongly on chunk size** (2.3x at 0.5 s → 5.0x at 2 s):
  the decoder redoes work per call. The Android feed feeds `MediaCodec` output buffers, so
  the real chunk size (and therefore the RTF) is a device-side property, not this number.
* These are *laptop* numbers. A phone will be several times slower; see §9.

## 8. Spanish (no word timestamps)

Source: *Radio Ambulante* "Su pequeño y propio mar", 5 min window from 15:00 (the first
minutes of that feed are an English ad pod, which is why the window starts late; the es
model transcribes English as gibberish, as expected for a language-specific model — there
is no language detection, so the app must gate on `Podcast.podcastLanguage`).

| | result |
|---|---|
| `word_timestamps: true`, batch | **0 words**, 70 lines, text correct Spanish |
| `word_timestamps: true`, streaming 1 s | **0 words**, 70 lines, 2.98 min wall (RTF 1.68) |
| attention decoder in bundle | absent (32.3 MB bundle, 8 files) |
| text vs Whisper small (`es`) | WER 0.579, CER 0.395, 286/7/123 on 719 ref tokens |

The Spanish text is noticeably weaker than English (CER 0.40 vs 0.10) — a 5 min window of
music-bedded, multi-speaker conversational audio, a tiny model, and no way to re-check on
more data without a Spanish corpus. Treat the Spanish text number as one sample, not a
distribution. The important, robust part is the first three rows: **no words, ever, for
Spanish**, in both API modes.

Line-level fallback, measured (Moonshine line start vs the first Whisper word inside that
line, 65 of 70 lines matched):

| | median | p90 | max | ≤0.5 s | ≤1 s |
|---|---|---|---|---|---|
| Spanish lines | +0.16 s | 0.42 s | 3.11 s | 97 % | 98 % |
| English lines (hard-fork, for comparison) | +0.08 s | 0.43 s | 1.72 s | 94 % | 99 % |

So the VAD window opens only ~0.1-0.2 s before the first word (median); line-only cues are
almost as tight as word-snapped ones. The 3.11 s maximum is a line that opened in
music/noise.

## 9. What this means for the Android integration

* **Download size.** The plan's "43 MiB" is the *English bundle without* word timestamps.
  With the attention decoder it is **74.5 MiB** (78,075,147 B); adding Spanish
  (30.8 MiB) makes 105.3 MiB if both are cached. If the 43 MiB figure was used for the
  metered-network copy or the Play data-safety note, it needs updating for the
  word-timestamp build. (Confirm with the app's own `AssetDownloader` — it reports what it
  actually fetched.)
* **Word timestamps work, for English only.** No fallback error is raised for Spanish; a
  `words.isEmpty()` check is the only signal. Keep the cue builder's line-time path (it is
  needed for every Spanish episode) and make the version tag reflect whether words were
  available, as `RESEARCH.md` D4 already plans.
* **Timestamp accuracy is a solved problem at our tolerance.** 0.07 s median / 0.29 s worst
  boundary displacement means a cue-snapped cut is within a third of a second of where
  Whisper would put it, against 1-2 s tolerance. Do not spend M3 effort here.
* **Speed is the open risk.** 2.3x realtime for streaming at 0.5 s chunks on a 22-thread
  laptop; a phone with 4-8 cores will be several times slower, and this is the number that
  decides whether a 45 min episode is analysed in 20 min or 90 min. Measure `rtf` on a real
  device before tuning anything else (the plan already logs it); if it comes out below ~1x,
  the options are chunking, a shorter analysis window, or accepting background time.
* **Do not batch the whole episode.** Batch mode is a laptop convenience: it needs the whole
  PCM buffer in memory (~230 MB of floats for 45 min, plus the library's copy) and gets
  *slower* per second of audio the longer the file. The streaming API is the right device
  path — which is what the plan already says.
* **No language detection.** A Spanish-configured engine fed English audio produces
  gibberish rather than an error; gating on the podcast language is mandatory, not just a
  nicety.
* **Surprises worth recording:**
  * the attention decoder is genuinely optional in the catalog, so a download without
    `include_word_timestamps` silently produces a bundle that cannot do word times;
  * the Spanish bundle's tokenizer logs `No byte fallback block in this vocabulary ... longest-match`
    on load — text is still correct, but it is a warning to expect in logs;
  * Moonshine emits ~9 % more tokens than Whisper small (numbers spelled out, identifiers
    split) — harmless for classification, relevant if anyone diffs transcripts;
  * on WSL's `/mnt/i` the downloader's atomic `rename` of the 31 MB decoder intermittently
    fails with `EACCES` (Windows still holds the fresh file); `download_models.py` works
    around it by downloading to a native Linux cache and copying. Not an Android issue, but
    it will bite anyone reproducing this.

## 10. Reproduction

```bash
cd /mnt/i/Android_Projects/Pocket_Casts/dev/adaware/next/moonshine

# 1. environment (fresh venv; does not touch dev/adaware/.venv)
uv venv .venv --python 3.12
uv pip install --python .venv/bin/python moonshine-voice rapidfuzz   # 0.1.6, 3.14.6

# 2. models: en + es tiny streaming, with the optional attention decoder
.venv/bin/python download_models.py --languages en es --word-timestamps
#    -> models/en/quantized_26_11_02 (74.5 MiB, has decoder_kv_with_attention.ort)
#    -> models/es/quantized_26_08_24 (30.8 MiB, no attention decoder)

# 3. audio: take0 + take1 of three episodes, straight from data/downloads.json URLs
.venv/bin/python download_audio.py --takes 0 1
.venv/bin/python data_check.py            # shows the DAI delta vs the committed corpus

# 4. Whisper reference on exactly that audio (project venv: faster-whisper + CUDA)
bash run_whisper.sh                       # 6 files, ~8.5 min on the RTX 4060

# 5. re-derive ad labels from the fresh sibling pair (repo's algorithm)
bash derive_all.sh

# 6. Moonshine, batch mode, word timestamps on
bash run_moonshine.sh                     # 100.9 min audio in 22.9 min

# 7. score and aggregate
bash run_score.sh
.venv/bin/python summarize.py             # -> results/summary.json + the tables above

# 8. speed (idle machine: batch vs streaming chunk sizes)
.venv/bin/python timing.py --out out/_timing2min.json
.venv/bin/python timing.py --clip .audio/_test/slice600.mp3 --modes batch --out out/_timing10min.json

# 9. Spanish smoke test (searches the iTunes directory for a Spanish podcast)
.venv/bin/python spanish_audio.py --start 900 --seconds 300
../../.venv/bin/python whisper_reference.py --audio .audio/_spanish/speech_900_300.mp3 \
    --language es --out ref/_spanish/speech_900_300.json
.venv/bin/python transcribe.py --audio .audio/_spanish/speech_900_300.mp3 \
    --model-dir models/es/quantized_26_08_24 --out out/_spanish/speech_900_300.json
.venv/bin/python lines_vs_words.py --moonshine out/_spanish/speech_900_300.json \
    --whisper ref/_spanish/speech_900_300.json
```

`transcribe.py` runs the batch API by handing a numpy buffer straight to
`moonshine_transcribe_without_streaming`; the Python wrapper builds a ctypes array from a
Python list, which is not viable for 45 min of audio. `--mode streaming` feeds chunks
through the same API a phone would use (`add_audio` + `stop`).

## 11. Artifacts in this directory

| path | what |
|---|---|
| `download_models.py` | model fetch + the evidence for §2; `models/bundles.json` records the file list and sizes (`models/` is gitignored) |
| `download_audio.py`, `data_check.py`, `spanish_audio.py` | audio fetch, DAI-vs-corpus check, Spanish source |
| `transcribe.py`, `timing.py`, `run_moonshine.sh` | Moonshine runs (batch/streaming, word timestamps) |
| `whisper_reference.py`, `run_whisper.sh` | Whisper reference on the same audio |
| `derive_labels.py`, `derive_all.sh` | sibling-diff ad labels for the fresh pair |
| `score.py`, `summarize.py`, `run_score.sh` | WER, word-timestamp stats, boundary metric |
| `lines_vs_words.py` | line-start vs first-word gap (the Spanish fallback number) |
| `ref/<episode>/takeN.json` | Whisper references actually scored (also `take0_novad.json` for the VAD check) |
| `out/<episode>/take0.json`, `out/_spanish/*.json` | Moonshine transcripts (words + lines + RTF) |
| `labels/<episode>.json` | re-derived ad spans for the downloaded audio |
| `results/*.json`, `results/summary.json` | scores, including every raw per-word delta and boundary row |
| `RESEARCH.md`, `INSTALL.md` | sibling work: integration decisions and device install steps |

Audio and model weights are gitignored (`.audio/`, `models/`, plus the existing `*.mp3` /
`*.ort` patterns). The `ref/`, `out/`, `labels/` and `results/` JSON are small and committed.

## 12. Open questions M1 did not answer

1. **Absolute timestamp truth.** Both engines are compared to each other; the +0.24 s offset
   could be Whisper being early. A reference from audio itself (energy onset after a
   silence gap at matched words, or hand-labelled onsets) would settle it in an hour of
   work and would tell us whether to subtract a constant.
2. **Phone RTF.** The only number that decides feasibility, and it needs a device.
3. **Pods that open on music.** No word boundary exists at the true edge; how often that
   happens in production podcasts, and whether the app should search backwards for a
   silence/music onset, is unmeasured.
4. **Non-English apart from Spanish.** Only `en` and `es` ships in the catalog.
5. **Long-run drift.** The offset is stable at half-episode granularity — median of matched
   words in the first vs second half: hard-fork +0.235 / +0.240 s, planet-money +0.240 /
   +0.240 s, the-daily +0.250 / +0.250 s — but that is a coarse two-bin check from
   `results/*.json`, not a drift regression. A per-minute plot would catch a slow ramp that
   happens to average out inside each half.

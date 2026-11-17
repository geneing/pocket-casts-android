# Moonshine STT: integration decisions (M0)

Replaces ML Kit GenAI advanced speech recognition (one-shot, clockless, timestamps faked by
energy profiling) with Moonshine streaming STT, whose cross-attention DTW gives real phrase- and
word-level times. Two hard requirements drive every decision below:

* **R1** ad pod edges must land on the words, not on a reconstructed speech region;
* **R2** one engine, one code path, for a downloaded file and for a streamed episode.

Everything here is grounded in the AAR `ai.moonshine:moonshine-voice:0.1.6` (extracted under
`%TEMP%\moonshine`, `javap` + `strings` on the shipped `.so`), the live CDN
`https://download.moonshine.ai/`, or the `moonshine-ai/moonshine` source at `main`. Section 8
records the exact commands so every number can be re-derived.

**Tree state when this was written (branch `feature/adaware_jev_moonshine`).** The ML Kit engine is
already removed: `MlKitEpisodeTranscriber.kt` and its test are deleted, the dependency is gone from
`gradle/libs.versions.toml` and `modules/services/repositories/build.gradle.kts`, the `@Binds` for
`EpisodeTranscriber` is gone from `AdAwareModule`, `TranscriptAdAnalyzer` now analyses **only**
existing transcripts, and `ON_DEVICE_TRANSCRIPT_VERSION = "on-device-transcript/0"` was introduced
as a placeholder identity. `EpisodeTranscriber` is therefore currently dead code with no binding.
M3 (section 7) is the work that puts Moonshine behind it again.

## Decision summary

| # | Decision | Rejected alternative |
|---|---|---|
| D1 | Use the `ai.moonshine:moonshine-voice:0.1.6` AAR as shipped (prebuilt JNI + ORT + model catalog) | vendor the C++ core; convert to LiteRT/TFLite; drive `onnxruntime-android` directly |
| D2 | CPU execution provider only, default thread count, no `ort_providers` option | NNAPI/XNNPACK (not shipped; requesting one returns an error) |
| D3 | `TINY_STREAMING` (arch 2) for `en` and `es`, always passed explicitly | no arch (catalog default is *medium* for en and *small* for es) |
| D4 | Word timestamps on for `en` only; cue building that also works with line-only times | requesting `word_timestamps` for every language (es has no attention decoder → warning + no words) |
| D5 | Models downloaded on demand into `noBackupFilesDir/moonshine-models`, unmetered only | bundle `en` in the APK; use `MoonshineDownloadWorker` |
| D6 | Cue = one **complete** streaming line; words used for boundary refinement only, not persisted | cue per word; cue per sentence across lines; persisting word lists in the VTT |
| D7 | Streaming API (`startStream`/`addAudioToStream`) for both file and stream audio | `transcribeWithoutStreaming` on the whole episode |
| D8 | One stream per episode starting at t=0; positions from cumulative sample count | one stream per PCM chunk, or trusting `MediaCodec` buffer timestamps |
| D9 | RTF + per-line latency + load time into `LogBuffer` (`MoonshineStt`) with `k=v` fields | analytics-only, or nothing |
| D10 | No model in the APK, ever; a private build gets them from `dev/adaware/next/moonshine/install-app.ps1` (adb push into `noBackupFilesDir/moonshine-models`) | `loadFromAssets` (heap copy, needs an Activity) or a first-run extraction (two copies on the phone) |

## 1. Which integration

**Decision: depend on the AAR.** `implementation("ai.moonshine:moonshine-voice:0.1.6")` in
`modules/services/repositories`. Rationale: the AAR is the whole inference stack, already built and
already 16 KB-page aligned; everything else in this document is about *using* it correctly, and
none of the alternatives removes a single line of that work while each adds a build system.

What the AAR actually contains (measured on the extracted artifact):

| ABI | `libmoonshine.so` | `libmoonshine-jni.so` | `libonnxruntime.so` | ABI total |
|---|---:|---:|---:|---:|
| arm64-v8a | 11.27 MB | 0.10 MB | 6.29 MB | **16.85 MiB** |
| armeabi-v7a | 9.53 MB | 0.06 MB | 3.87 MB | **12.83 MiB** |
| x86_64 | 11.61 MB | 0.11 MB | 6.67 MB | **17.54 MiB** |

**Inference stack.** ONNX Runtime with `.ort` flatbuffer models (`strings` on
`libmoonshine.so` → `MoonshineStreamingModel`, `replace_decoder_kv_from_path`,
`compute_cross_kv`, `compute_word_timestamps`). Streaming models are a six-file graph set:
`frontend.model.ort` + `frontend.weights.ort` → `encoder.ort` → `adapter.ort` → `cross_kv.ort`
→ `decoder_kv.ort` (optionally `decoder_kv_with_attention.ort`). Per
`docs/execution-providers.md` the library ships **CPU EP only**: the models are converted to `.ort`
at full graph optimization, which fuses whole regions into `com.microsoft` CPU kernels
(`FusedConv`, `MultiHeadAttention`, `MatMulNBits`, …), so CoreML/NNAPI fragment the graph and lose;
both providers are refused with an error rather than silently run slow. The bundled
`libonnxruntime.so` still carries provider *names* (Xnnpack, Nnapi, …) because it is a full ORT
build — the documented refusal is what counts, and although `ort_providers` is a parsed option
(`moonshine-c-api.cpp` accepts `ort_provider`/`ort_providers`), only `cpu` is honoured. The option
list otherwise covers `word_timestamps`, `model_arch`, `spelling`, `transcription_interval`,
`decode_incomplete_lines`, `vad_*`, `model_root`, `log_ort_run` — **no thread-count option exists**
in the C API options parser or in the Java surface, so intra-op threads are whatever ORT's default
is. That is a measurement risk, not a tuning knob (section 6).

**Emulator.** `x86_64` is shipped, so `:app:connectedDebugAndroidTest` on an API ≥ 26 x86_64 image
can load a model. `armeabi-v7a` is shipped too, so 32-bit devices are covered (and pay 12.8 MiB).

**minSdk / manifest.** The AAR declares `minSdkVersion 26`; `modules/services/repositories`
already pins `minSdk = 26` for exactly this reason, and the ML Kit AAR (`genai-common`, also
`minSdkVersion 26`) merged into the `minSdk 24` phone app without changing it — so no build change
is needed, and the same runtime gate stays necessary: `isAvailable()` must return false below API
26 because the app itself still installs on 24/25. The AAR's manifest also merges in
`RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE` and a `MicrophonePermissionActivity`. We must
re-remove `RECORD_AUDIO` and the activity in `app/src/main/AndroidManifest.xml` with
`tools:node="remove"` — the ML Kit justification for that permission is being deleted in the same
change, and a podcast app that never records must not ship a mic permission (Play data-safety).

**APK size.** Native libs are stored uncompressed above minSdk 23, so a universal APK grows by
**~47 MiB**; a Play AAB per-device download grows by ~17 MiB (arm64). Both are acceptable only
because the models are *not* bundled (section 2) and because the feature is gated behind
`Feature.AD_SKIPPING`. If size becomes a release blocker the lever is `abiFilters` dropping
`x86_64` from release variants (keep it for debug so tests still run), not dropping Moonshine.

**Rejected:**

* *Bundle the C++ core ourselves* — we would vendor CMake + ORT prebuilts + JNI glue, then follow
  upstream releases by hand; the AAR is that artifact with the same MIT licence.
* *LiteRT/TFLite conversion* — Moonshine publishes `.ort` only, the DTW pass and the fused decoder
  graphs are ORT-specific, and a conversion would have to be re-validated for both WER and
  timestamps. No upside.
* *`onnxruntime-android` directly* — we would re-implement the bin-tokenizer, the streaming
  encoder/adapter/cross-KV/decode loop, the VAD, the DTW alignment and the model catalog. Weeks of
  work to reach the same place, and every bug would be ours.

## 2. Model choice and language switching

**Decision: tiny-streaming, en + es, arch passed explicitly, downloaded per language.**

The catalog is compiled into the `.so` (`moonshine-model-catalog.cpp::stt_catalog`) and exposed as
`Transcriber.getSttDependencies(language, options)` — pure string work, no network. Two facts fall
out of it that change our defaults:

1. **The first entry is the default, and it is not tiny.** `en` lists
   `MEDIUM_STREAMING, SMALL_STREAMING, BASE, TINY_STREAMING, TINY`; `es` lists
   `SMALL_STREAMING, TINY_STREAMING, BASE`. `ModelSpec.stt("en")` with a null arch therefore
   resolves to *medium* (hundreds of MB). We always pass `JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING`
   (= 2), which is 34 M params and the model the product decision names (en 12.0% WER, es 6.2%).
2. **Only English publishes an attention decoder.** `stt_component_files()` gates it on
   `language_code == "en"`, and the CDN agrees: `decoder_kv_with_attention.ort` is **200** for
   `tiny-streaming-en/quantized_26_11_02/` and **404** for `tiny-streaming-es/quantized_26_08_24/`.
   Because the manifest is generated with the same flag, `word_timestamps=true` on Spanish simply
   omits the file (no failed download), the transcriber logs
   `Warning: Failed to load decoder_kv_with_attention`, and `TranscriptLine.words` comes back
   empty. Confirmed in `transcriber.cpp` (warn-only, `std::filesystem::exists` guard) and in
   `transcriber.cpp:1133` (`words` are only populated when `segment.is_complete` **and**
   `word_timestamps`).

Measured download (HTTP `Content-Length` on the live catalog):

| language | catalog dir | files | total |
|---|---|---:|---:|
| en | `tiny-streaming-en/quantized_26_11_02` | 8 (+1 attention) | **43.3 MiB** / **74.5 MiB** with word timestamps |
| es | `tiny-streaming-es/quantized_26_08_24` | 8 | **30.8 MiB** |

The en decoder (`decoder_kv.ort`, 31.2 MiB) is larger than the es one (18.8 MiB) — different
quantization epoch, not our business, but it explains why the two languages are not the same size.

**Where the files live.** `ModelCache.defaultRoot(context)` =
`context.noBackupFilesDir` (falling back to `filesDir`) + `moonshine-models`;
`ModelCache.key(spec)` builds `stt-<primary>-<arch>[-spelling][-wt]` with the numeric
`MOONSHINE_MODEL_ARCH_*` value, so our two directories are `stt-en-2-wt` (tiny streaming = 2, and the
English bundle ships the attention decoder) and `stt-es-2`. The word-timestamp build therefore keeps
its own directory and `ensureModelPresent`/`isModelPresent` stay cheap existence checks. Never back
this up (it is 43 MB of re-downloadable weights) — `noBackupFilesDir` is the right base and must not
be overridden to `filesDir`.

**Download UX.** On-demand, inside our existing per-episode work:

* `AdAnalysisScheduler` already enqueues `TranscriptAdAnalysisWorker` with
  `NetworkType.CONNECTED` + battery-not-low. The worker's first step is
  `AssetDownloader.ensureModelPresent(dir, spec, listener)`.
* **Unmetered only.** Before downloading, check `ConnectivityManager.isActiveNetworkMetered`; on a
  metered network return `Result.retry()` so WorkManager re-runs on Wi-Fi instead of burning 43 MB.
  This mirrors `MoonshineDownloadWorker.buildRequest(root, spec, requiresUnmetered)` which sets
  `NetworkType.UNMETERED`, without a second scheduling surface.
* Report progress through `setProgress(workDataOf("model_bytes_done" to n, "model_bytes_total" to t))`
  so the existing "Generating transcript…" banner on the episode details screen can say
  "Downloading speech model 40%" and never look stuck. `AssetDownloader`'s listener gives
  `(fileIndex, fileCount, bytesDownloaded, bytesTotal)` and writes `.part` files, so a killed
  download resumes at file granularity, not at zero.
* `AssetDownloader` is blocking OkHttp IO with a 120 s read timeout and no call timeout — call it on
  `Dispatchers.IO` inside the worker, never on the analyzer's coroutine that holds the mutex under
  a deadline.

**Language selection.** `Podcast.podcastLanguage` is free-form server data (`"en"`, `"es-us"`,
`"pt-BR"`…). Pure function `speechLanguage(podcastLanguage): MoonshineLanguage?`:
lowercase → take the primary subtag before `-`/`_` → exact match against the allowlist
`en → TINY_STREAMING(en)` and `es → TINY_STREAMING(es)`; anything else → `null`, which makes
`isAvailable()` false for that episode and leaves it to the audio analyzer exactly as an
unsupported language does today. Do **not** pass `"es-us"` to the library: `find_stt_language`
matches only exact codes or English names, so `"es-us"` would return no dependencies at all.
Other Moonshine languages exist (`de, ar, ja, zh, vi, tl`) — adding one is a one-line allowlist
entry plus an analysis-identity bump, deliberately out of scope until es ships.

**Rejected:** bundling `en` in the APK. 43 MiB in the base APK for a gated feature, on top of
47 MiB of native libs, and it would still have to download `es` later; the download path is
required anyway, so bundling only adds a second code path. Also rejected: `MoonshineDownloadWorker`
as the download mechanism — its progress goes to WorkManager tags we would have to observe, it
carries no `Feature.AD_SKIPPING` gate, and it would race our own per-episode unique work.

**Why no model is ever in the APK (checked against the AAR, not decided on size).**
`Transcriber.loadFromAssets(...)` takes an `AppCompatActivity` — it calls `getAssets()` on the
activity — and its body is `readAllBytes(assetManager, path)` per model file followed by
`loadFromMemory(...)`, so the whole model is copied into the Java heap (en with word timestamps,
74.5 MiB) and then into native memory: OOM-prone, and impossible from a `WorkManager` worker with no
Activity. `loadFromFiles(path, arch)` instead hands the directory to native code
(`moonshineLoadTranscriberFromFiles`) and ONNX Runtime opens the `.ort` files itself, so the weights
stay mmap-able and the process only pays for what ORT maps. Bundling therefore means either a
first-run extraction into app-private storage (the APK copy plus a 105 MiB extracted copy — two
copies on the phone) or the heap path above. The adb push writes the same directory the loader wants,
once: `dev/adaware/next/moonshine/install-app.ps1` builds `:app:assembleDebugProd`, installs it with
`adb install -r` and then runs `install-models.ps1` (`-SkipBuild`, `-SkipModels` skip either half),
and because the models land under `noBackupFilesDir` the reinstall keeps them.

## 3. Timestamp path → cues → WebVTT

**Mechanism (as implemented by the library).** Streaming decode collects cross-attention per token,
z-score normalizes and median-filters per head, averages across layers, runs DTW on the negated
matrix, groups sub-word tokens on SentencePiece `▁` boundaries and fixes overlaps. Output per line:
`startTime`, `duration`, `words[{word,start,end,confidence}]`, all in **absolute stream seconds**
(`transcriber.cpp` offsets every word by `segment.start_time`). Lines are **VAD-delimited
utterances**, not fixed windows — `TranscriberStream` starts a Silero VAD, so a line is roughly one
spoken turn (order of a few seconds), which is exactly the granularity `JevAdClassifier` wants
(`WINDOW_CUES = 20`, one cue = one classifier sentence).

**Cue building (pure function, no Android types).**

```
for each line (transcript.lines) where line.isComplete:
    startS = words.firstOrNull()?.start ?: line.startTime
    endS   = words.lastOrNull()?.end    ?: (line.startTime + line.duration)
    text   = line.text.trim()
    drop when text is empty, or endS <= startS
    startMs = round(startS*1000), endMs = round(endS*1000)
sort by startMs; clamp to [0, durationMs] when durationMs > 0;
after clamping, if startMs < previous endMs: startMs = previous endMs (no overlap);
drop zero/negative-length results; merge a line into the previous one when its text has no
terminal punctuation and the gap to it is < 150 ms (the streaming decoder restarts a line after
a mid-sentence VAD blip).
```

Only **complete** lines are used: `words` are absent on partial lines, and a partial line's
`duration` is provisional. With `decode_incomplete_lines = false` (the default) `LineCompleted` is
the only event that matters, which also means the cue list is final per line and never needs to be
rewritten — cheap and idempotent.

Word timings are used **inside** the transcriber only: they tighten the cue to the first and last
spoken word instead of the VAD window (that is the whole R1 win: a pod that starts mid-line starts
at the word, not at the silence before it). They also drive a per-episode alignment diagnostic —
`median(words per line)`, `median line duration`, share of lines whose first word starts > 400 ms
after `line.startTime` — so a bad decode is visible in logs instead of hiding in the audio score.

**What is stored.** Unchanged: `LocalTranscriptStore` writes
`filesDir/on-device-transcripts/<uuid>.vtt` plus the `episode_transcript` row with
`local-generated://<uuid>`, `type = "text/vtt"`. `TranscriptManagerImpl.readLocalTranscript` reads
that scheme from disk, `WebVttParser` (media3 `WebvttParser`) turns each cue into
`TranscriptEntry.Text(value, startTimeMs, endTimeMs)`, and everything downstream works: transcript
tab rows, `seekToTranscriptEntry`, bookmarks, `TranscriptWindowExtractor`, and
`Transcript.Text.toAnalysisCues()` → `JevAdClassifier` → `buildPods`. **One cue per line**, times
from the word boundary rule above.

**Why words are not persisted (yet).** `TranscriptEntry.Text.words` exists and is consumed by
`TranscriptCueHelper.findWordIndex` → `TranscriptLines` (karaoke highlight) and
`QuoteTiming`/`BookmarkTranscriptEdit` (char-offset seeking), but **no producer in the app
currently fills it**: `WebVttParser` cannot carry per-word times, and `TranscriptSegments`
(`TranscriptJsonConverter`) has no words field. `sanitize()` even invents one pseudo-word per entry
when joining unpunctuated lines (`joinSplitSentences`), so entry-level highlighting already behaves
as "one word = one cue". Persisting real words needs a new local format (JSON sidecar or inline
`<00:00:00.000>` cue times plus a parser extension) — a transcript-tab feature, not an ad-boundary
requirement, and it would roughly double the stored text. Deferred with a clear trigger: do it when
the transcript tab wants word karaoke, and then extend `TranscriptJsonConverter` (structured) rather
than the VTT.

**Rejected:** cue per word — it multiplies VTT size and row count, destroys the "one cue = one
sentence" contract the jev prompt and `WINDOW_CUES` depend on, and `buildPods`' `MIN_POD_MS = 8 s`
plus gap logic was tuned on sentence cues. **Rejected:** cue per sentence across several Moonshine
lines — it reintroduces exactly the smearing we are removing (a sentence spanning a pod break moves
the edge).

## 4. One code path for downloaded and streamed audio

**Decision: always the streaming API, fed from `MediaCodec`/`MediaExtractor`, chunk by chunk.**

```
val transcriber = Transcriber(options).apply { loadFromFiles(modelDir, TINY_STREAMING) }
transcriber.setUpdateInterval(0.5)         // DEFAULT_UPDATE_INTERVAL = 0.5 s
val id = transcriber.createStream()
transcriber.addListener { event -> when (event) {
    is TranscriptEvent.LineCompleted -> cues += toCue(event.line)   // collect final lines
    is TranscriptEvent.Error         -> fail(event)                // keep partial cues
    else -> {}                                                     // partial updates ignored
} }
transcriber.startStream(id)
decodeLoop { pcm: FloatArray, count, sampleRate -> transcriber.addAudioToStream(id, pcm, sampleRate) }
transcriber.stopStream(id); transcriber.freeStream(id); transcriber.close()
```

* **Both sources decode the same way.** `TranscriptSource.Downloaded(file)` →
  `MediaExtractor.setDataSource(path)`; `TranscriptSource.Stream(url)` → `downloadAudioStream`
  (existing helper, OkHttp → cache file) → same extractor. If the fetch fails, return
  `TranscriptResult.Failed`, never a cues-from-nothing success.
* **HLS is a hole to close, not a path that works.** `downloadAudioStream` is a plain `GET` +
  `copyTo`, so an `episode.isStreamUrlHls` URL yields a playlist file that `MediaExtractor` cannot
  open — the ML Kit engine had the same latent bug (it fetched every stream the same way) and it
  never surfaced because the analyzer only schedules episodes that are downloaded or already
  buffered. M3 checks `isStreamUrlHls` and returns `Unavailable("hls stream")`, and the follow-up
  reuses `AudioAdAnalyzer`'s `StreamingMediaDataSource`/`ExoPlayerDataSourceFactory` (media3 has the
  HLS module) to materialise a decodable file. Do not silently ship a playlist as "audio".
* **PCM.** `AudioAdDecoder` already pumps `MediaCodec` and hands out interleaved PCM16 with
  `sampleRate`/`channelCount` from the track format; add a float sink variant (same codec loop,
  `Short → Float / 32768`, mono by averaging channels) instead of a second decoder. **Do not
  hard-code 16 kHz**: `add_to_new_audio_buffer` calls `resample_audio(input, sample_rate, 16000)`
  internally, so we pass the file's real rate (44.1/48 kHz) and Moonshine resamples.
* **Absolute episode time.** One stream per episode, created at t=0, fed in decode order; the
  library reports line/word times relative to the stream start, which is the episode start. We never
  `seekTo`, never create a second stream for a later chunk, and never derive time from
  `BufferInfo.presentationTimeUs`. Because a decode loop can skip a buffer (codec error, format
  change, dropped sample), the feed loop also keeps a sample counter: if it falls behind the
  expected position by more than one chunk, pad silence for the difference so a gap cannot shift
  every later timestamp (the timestamps are computed from audio the model heard, not from container
  time).
* **Cancellation.** `isActive()` is polled in the decode loop (coroutine job), the `finally` block
  runs `stopStream` → `freeStream` → `close` in that order, and `delete` of the cached stream file
  stays in `finally` too. The analyzer already holds a `Mutex` and a deadline; reuse
  `AudioAdAnalyzer`'s budget shape (`FILE_BUDGET_MS`, `STREAM_BUDGET_MS`) with a deadline check in
  the loop so an hour-long episode cannot wedge the worker.
* **Why not `transcribeWithoutStreaming`.** It needs the whole clip as one `float[]`: 1 h at 16 kHz
  = 57.6 M floats = **230 MB**, plus the library's own copy — an OOM on mid-range devices. It also
  gives no progress, no cancellation point, no partial results, and no way to reach the streaming
  models we chose. Kept only as an offline debugging entry point (see section 8).
* **Same-path proof.** The cue builder and the session wrapper take `(audio feed, model)` and know
  nothing about files vs URLs, so "transcribe this episode" is one function; language/model choice
  happens before, storage after. That is what makes R2 cheap to test: feed a downloaded file twice,
  once via the file source and once via a local `file://`-style fetch, and assert cue equality.

**Live playback later (`MoonshineLiveTranscriber`, not M3).** Same `Transcriber`, second stream:
`startStream` when playback starts, `addAudioToStream` from a player PCM tap (the audio analyzer
already needs a buffer tap; if the player cannot expose one, a second `MediaExtractor` on the
downloaded file at the current position, re-anchored to the playback position, is the fallback),
`setUpdateInterval(0.5)`, render `LineUpdated` text as it arrives, drop everything on seek. It needs
(a) a PCM tap that survives backgrounding, (b) a preemption rule against the analysis worker (one
resident model at a time is the memory assumption in the risks), (c) the model already downloaded —
so shipping analysis first is a prerequisite, not just an ordering convenience.

## 5. Measuring inference inside the app

Instrumented at three points, all tagged `MoonshineStt` in `LogBuffer` (`k=v` fields, greppable):

| metric | source | why |
|---|---|---|
| `model_load_ms` | `SystemClock.elapsedRealtime` around `loadFromFiles` | cold start of the ORT sessions; the main first-episode cost |
| `audio_ms`, `wall_ms`, `rtf` | decode+feed envelope | the only number that says "faster than real time" on this device |
| `line_latency_ms` | `TranscriptLine.lastTranscriptionLatencyMs` (per line; also median/max) | how far behind the audio the decoder runs during streaming |
| `lines`, `words_per_line_median`, `line_ms_median`, `first_word_offset_ms_median` | cue builder output | alignment quality, comparable across models/devices |
| `model_download_ms`, `model_bytes` | `AssetDownloader` listener | distinguishes "slow download" from "slow inference" |
| `cues`, `failed_lines`, `cancel_at_ms` | session result | failure shape |

`rtf = audioMs / wallMs` (**>1 means faster than real time**) — log it with two decimals via a
formatted `String.format(Locale.US, "%.2f", rtf)` helper so log scraping is trivial and the helper is
unit-testable. Where the numbers surface: `LogBuffer` first (that is what bug reports attach);
`setProgress` for download/inference progress so the UI never lies; analytics **later**, one event
per completed analysis carrying `rtf`, `model_load_ms`, `language`, `arch`, `words_available` — the
shape is a product decision that should not block M3, and per-episode analytics on a worker would
need a sampling rule anyway.

## 6. Risks and unknowns, each with its check

| Risk | Status | Verification (and fallback) |
|---|---|---|
| Attention decoder missing for Spanish | **Verified**: catalog gates on `language_code == "en"`; CDN 404 for es | none needed. Fallback already designed in: cues work from `line.startTime/duration`. Check per release: `HEAD .../tiny-streaming-es/<dir>/decoder_kv_with_attention.ort` |
| es decoder file might appear later | UNVERIFIED | re-run the HEAD check on each AAR bump; if it appears, the allowlist flips on `includeWordTimestamps` for es and `version` bumps |
| Moonshine's resampler quality (box-average decimation, `resampler.cpp`) | UNVERIFIED | transcribe 10 clips at native 44.1/48 kHz and the same clips pre-resampled to 16 kHz with a proper filter; compare text and word times. Fallback: pre-resample in Kotlin (media3 `AudioProcessor` chain) and pass 16 kHz |
| Thread count / core usage on low-end devices | UNVERIFIED (no option exposed) | measure `rtf` on a 4-core 2020-era device while the app is foreground; watch `/proc/<pid>/status` `Threads`. Fallback: lower `setUpdateInterval`, or accept slower RTF and lengthen `FILE_BUDGET_MS` |
| Memory with two models resident (later: live + analysis) | UNVERIFIED | load en and es `Transcriber`s in one process on a 4 GB device, watch `dumpsys meminfo`; fallback: one instance, evicted by language, `close()` between episodes |
| Model download fails mid-analysis | handled by design | `ensureModelPresent` leaves `.part` files and is resumable per file; worker returns `Result.retry()`; analyzer treats `Failed` as "retry in 10 min" and the audio analyzer still produces segments |
| 43 MiB download over cellular | policy + UX | gated on `isActiveNetworkMetered` → `Result.retry()`; user-visible progress; Play "large downloads" guidance to be re-read in M3 (**UNVERIFIED** for the exact wording of the current policy) |
| Spanish/es-us mapping (`"es-us"` not in catalog) | **Verified** in `find_stt_language` (exact code or English name only) | covered by `MoonshineSpeechModelTest`; `"es-us" → "es"` |
| `MediaCodec` gap shifting timestamps | designed around | silence padding on sample-count drift; test with a deliberately skipped buffer in `MoonshineAudioFeedTest` |
| HLS stream URLs (`isStreamUrlHls`) | **Verified** gap: `downloadAudioStream` is a plain GET, so a playlist would be "decoded" | return `Unavailable("hls stream")` in M3 and add the media3 `ExoPlayerDataSourceFactory` resolver next; test with a real `.m3u8` URL |
| Emulator support | **Verified**: x86_64 in the AAR | run one `connectedDebugAndroidTest` on an API 34 x86_64 image with the `en` model preseeded into the cache dir |
| OkHttp/WorkManager ABI mismatch (AAR compiled against older libs; app has OkHttp 5.5.1) | UNVERIFIED | first `:app:assembleDebugProd` + one `AssetDownloader` call and one `MoonshineDownloadWorker`-free run; the AAR only uses `OkHttpClient.Builder`, `Request`, `Response.body` |
| Licence | **Verified**: repo `LICENSE` — code MIT, models MIT in every language and size | keep the notice in the third-party licences screen |
| APK size | **Verified**: +16.85 MiB arm64, +47 MiB universal | if blocked: `abiFilters` without `x86_64` in release |
| 16 KB page alignment (Play, SDK 35+) | **Verified**: all three `.so` have `LOAD Align 0x4000` | none |
| `RECORD_AUDIO` merged from the AAR | **Verified** in its manifest | `tools:node="remove"` for the permission and `MicrophonePermissionActivity`; assert with `:app:assembleRelease` + `aapt dump permissions` |
| AAR may not be on Play's approved-SDK radar for review | low | the AAR bundles no tracking code; note it in the release checklist |

## 7. M3 integration plan

Everything lands in `modules/services/repositories` (the module already owns the ad-aware text
stack), so no new module, no new feature flag, no schema change.

**Add** (`modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/ads/text/moonshine/`):

| file | contents |
|---|---|
| `MoonshineSpeechModel.kt` | `MoonshineLanguage` enum (`EN`, `ES` with `code`, `arch = TINY_STREAMING`, `wordTimestamps`), pure `speechLanguage(podcastLanguage: String?)`, `modelSpec()`, `cacheDirectory(context)`, `isPresent(context)`, `ensureDownloaded(context, progress)` (unmetered gate + `AssetDownloader`), `versionTag` (`"moonshine-0.1.6/tiny-streaming-en/wt"`) |
| `MoonshineCues.kt` | pure `fun cues(lines: List<TranscriptLine>, durationMs: Long): List<TranscriptCue>` and `fun spanOf(line): LongRange`; no Android imports |
| `MoonshineAudioFeed.kt` | extractor + `MediaCodec` loop → `FloatArray` chunks (native rate, mono) with sample-count position, `isActive` polling, silence padding on drift |
| `MoonshineEpisodeTranscriber.kt` | `EpisodeTranscriber` impl: resolve language, ensure model, `Transcriber` session (listener → completed lines), cue building, `close()` in `finally`, logging, `version` = `model.versionTag` |
| `MoonshineStats.kt` | `data class` + `rtf(audioMs, wallMs)`, `formatRtf` — pure |

**Modify:**

* `ads/AdAwareModule.kt` — re-add `@Binds @Singleton provideEpisodeTranscriber(impl: MoonshineEpisodeTranscriber): EpisodeTranscriber`, plus `@Provides @Singleton fun provideMoonshineModels(@ApplicationContext context: Context): MoonshineModels` (a thin holder for the per-language cache dir, so tests can fake it).
* `ads/text/TranscriptAdAnalyzer.kt` — restore the transcribe branch: when `existing == null` and
  `transcriber.isAvailable()`, `transcriber.transcribe(episodeUuid, source, episode.durationMs)`,
  `transcriptStore.save(episodeUuid, result.cues)` on `Success`, keep `noteFailure` on `Failed`.
  `sttVersion = existing?.analysisVersion(transcriber.version) ?: transcriber.version`; keep
  `ON_DEVICE_TRANSCRIPT_VERSION` only as the read-side fallback for rows written before M3.
* `ads/text/AdAnalysisScheduler.kt` — restore `&& transcriber.isAvailable()` in `isEnabled()`;
  keep `NetworkType.CONNECTED` (a stream must still be fetched) and let the worker retry on metered
  networks *before* `ensureModelPresent` downloads.
* `ads/text/TranscriptAdAnalysisWorker.kt` — restore the `isSupported()` early return, and route
  model download progress into `setProgress`.
* `ads/text/EpisodeTranscriber.kt` — `TranscriptSource`/`TranscriptResult` unchanged; `version` is
  the identity hook, so its string **must** contain engine + model + arch + word-timestamps flag.
* `ads/text/LocalTranscriptStore.kt` — no change (VTT + row stays the contract).
* `gradle/libs.versions.toml` — `[versions] moonshine = "0.1.6"`; `[libraries]` under a
  `# Moonshine` header: `moonshine-voice = { module = "ai.moonshine:moonshine-voice", version.ref = "moonshine" }`.
* `modules/services/repositories/build.gradle.kts` — `implementation(libs.moonshine.voice)` (replaces
  the deleted `libs.mlkit.genai.speech.recognition`); keep `minSdk = 26` and update its comment.
* `app/src/main/AndroidManifest.xml` — `xmlns:tools` + `<uses-permission android:name="android.permission.RECORD_AUDIO" tools:node="remove"/>` and a matching `tools:node="remove"` for `ai.moonshine.voice.MicrophonePermissionActivity`.

**Tests (JVM only, no device):**

| test | asserts |
|---|---|
| `moonshine/MoonshineCuesTest.kt` | words present → cue starts at first word, ends at last word; words absent (es) → line `startTime/duration`; partial (`isComplete=false`) lines dropped; out-of-order lines sorted; clamp to `durationMs`; overlap removal; mid-sentence VAD split merged; empty text dropped; zero-length dropped |
| `moonshine/MoonshineSpeechModelTest.kt` | `"en"→EN`, `"es-us"→ES`, `"ES"→ES`, `"pt-BR"→null`, `null/""→null`; spec carries `TINY_STREAMING` + `wordTimestamps` only for `EN`; `versionTag` distinct per language and changes with the flag |
| `moonshine/MoonshineStatsTest.kt` | `rtf(60_000, 30_000) == 2.0`, formatting to two decimals, zero/negative wall time guarded |
| `moonshine/MoonshineAudioFeedTest.kt` | PCM16→float scaling, stereo→mono averaging, sample-count position across chunk boundaries, silence padding on a skipped buffer (fake the sink, no `MediaCodec`) |
| `ads/text/LocalTranscriptStoreTest.kt` | `WEBVTT` header, `HH:MM:SS.mmm --> HH:MM:SS.mmm`, negative time clamped to 0, cue order, no trailing blank-line breakage (Robolectric, existing `filesDir`) |
| `ads/text/TranscriptAdAnalyzerTest.kt` (extend) | restored branch: `Success` saves cues then classifies; `Unavailable` does not mark failure; `Failed` marks failure and skips classification |

**Order of work.** (1) gradle + AAR compiles and packages for all variants, `assembleDebugProd`
green; (2) `MoonshineCues` + `MoonshineSpeechModel` + tests (pure, no device); (3) audio feed +
session with a downloaded `en` episode on a device, read `rtf`/`line_latency_ms` from `LogBuffer`;
(4) re-wire `TranscriptAdAnalyzer`/`AdAnalysisScheduler`/worker and DI; (5) measure es (line-only
cues) and log the absence of words; (6) `spotlessApply` + `aggregatedLintRelease` (120-col, no
`FIXME`), `buildHealth` for the new dependency edge.

## 8. How the facts above were checked

```powershell
# AAR contents and .so sizes
Get-ChildItem -Recurse $env:TEMP\moonshine\ext\jni -File | Select FullName,Length
# Java API surface (real signatures, constants, defaults)
& "C:\Program Files\Android\Android Studio\jbr\bin\javap.exe" -p -constants `
  -cp $env:TEMP\moonshine\classes.zip ai.moonshine.voice.Transcriber ai.moonshine.voice.ModelSpec `
  ai.moonshine.voice.AssetDownloader ai.moonshine.voice.ModelCache ai.moonshine.voice.TranscriptLine
# catalog + attention-decoder lookup compiled into the library, and 16 KB page alignment
# (strings/regex over libmoonshine.so) and: wsl -e bash -lc "readelf -lW .../libmoonshine.so"
# live catalog existence/size (this is where the es attention decoder 404s)
Invoke-WebRequest -Method Head https://download.moonshine.ai/model/tiny-streaming-es/quantized_26_08_24/decoder_kv_with_attention.ort
```

External sources: `core/moonshine-model-catalog.cpp` (`stt_catalog`, `stt_component_files`
`is_english` gate), `core/transcriber.cpp` (attention decoder warn-only load, `words` only on
complete segments, `resample_audio` to 16000, VAD-gated stream), `core/moonshine-c-api.cpp`
(`moonshine_get_stt_dependencies` option parsing — no thread option),
`docs/word-level-timestamps.md` (DTW pipeline, file names), `docs/execution-providers.md` (CPU-only,
no CoreML/NNAPI), `LICENSE` (MIT for code and models).

## 9. How the models get onto the phone (private builds)

This app is private-use for now, so the models are installed with the build rather than bundled or
downloaded: `dev/adaware/next/moonshine/install-app.ps1` builds `:app:assembleDebugProd`, installs
it and then runs `install-models.ps1`, which copies the already-downloaded bundles into
`/data/user/0/au.com.shiftyjelly.pocketcasts.debug/no_backup/moonshine-models/stt-en-2-wt` and
`.../stt-es-2` — exactly `ModelCache.key(spec)` for each language, so the app's presence check finds
them and no request is made. The download stays as the fallback for a device without the push, and
the metered-network gate covers only that download. Details, sizes and the verification commands are
in `INSTALL.md`.

# On-device speech to text: NVIDIA Parakeet on LiteRT

Ad detection reads only a transcript the app produced on the device. A transcript the show or our
servers publish is never used for it: its cue times do not match the audio and it is missing ad
reads. The old ONNX/Moonshine pipeline is gone, and so is the MediaPipe bundle that replaced it
briefly; this directory holds the tooling for the engine that runs now.

## The engine and the model

| | |
|---|---|
| Engine | LiteRT runtime, `com.google.ai.edge.litert:litert:2.3.2` (CPU, GPU) + `litert-npu-runtime-google-tensor` (Tensor NPU) |
| Model | NVIDIA **Parakeet TDT 0.6B v3**, AOT-compiled for **Google Tensor G6**, FP32, stateful, 5 s chunks |
| Graph | `parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite` — 1 303 768 096 bytes (~1.21 GiB) |
| Tokenizer | `tokenizer.json`, 8 192 BPE pieces — 1 159 960 bytes |
| Graph source | <https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/resolve/main/parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite> |
| Tokenizer source | <https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/resolve/main/tokenizer.json> |
| Licences | model: NVIDIA, CC-BY-4.0 · LiteRT: Apache-2.0 |

### Why this file and not the `.litertlm` bundle

The `.litertlm` bundle is CPU/GPU only: MediaPipe's `AudioTranscriber` loads it and cannot reach the
NPU, which is the whole point of this branch. NPU acceleration in LiteRT needs a graph compiled for
the SoC, and the Parakeet export publishes one per SoC family. Two exports exist for Google Tensor:

| File | Signatures | What happens |
|---|---|---|
| `parakeet_tdt_0.6b_v3_5s_f32_Google_Tensor_G6.tflite` | `encode`, `decode` | the decoder stops once its stateless token slots are full, losing the tail of every window |
| `parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite` ⭐ | `encode`, `decode`, `decode_1` | stateful decoding: the first tokens come from `decode`, then `decode_1` advances the LSTM one token at a time |

**Use the `_stateful_` one.** The app's decoder (`ParakeetRecognizer`) is written for it: it decodes
the first few tokens statelessly, switches to `decode_1` when the stateless slots are full, and
carries the two LSTM state tensors forward by swapping buffers. `grep -a -c decode_1 <file>` is the
quick way to tell them apart; the stateful export answers 179, the other answers 0.

The exact byte count of each file is the app's presence check: `ParakeetModel` treats a file of any
other size as absent and downloads it again. A truncated push, a half-finished download or a
different export therefore degrades to a fresh download instead of failing inside the interpreter.

**Attribution.** CC-BY-4.0 requires visible credit to NVIDIA wherever the app is distributed. The
in-app licences screen is generated from the Gradle dependency graph, so it will never list the
model — this paragraph, and the matching note in `AGENTS.md`, is what a release checklist has to
find. LiteRT itself is Apache-2.0 and is a normal Gradle dependency.

## Where it lives on the device

```
/data/user/0/au.com.shiftyjelly.pocketcasts.debug/   # package of the ".debug" application id
└── no_backup/parakeet-model/
    ├── parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite   # the graph
    └── tokenizer.json                                                 # the id → piece table
```

`no_backup` is deliberate: 1.2 GiB of re-downloadable weights must never be backed up. There is no
cache directory any more — LiteRT compiles nothing for an AOT-compiled graph, unlike MediaPipe. Keep
the directory and file names in step with `ParakeetModel.GRAPH` and `ParakeetModel.TOKENIZER` in
`modules/services/repositories/.../repositories/ads/text/parakeet/ParakeetModel.kt`.

## The scripts

All three are Windows PowerShell, like the rest of this checkout's build loop (see
[AGENTS.md](../../../../AGENTS.md#building-and-running-on-this-machine)). `install-app.ps1` sets
`ANDROID_HOME` when it is unset, and the two device scripts default to
`$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` (`-Adb` overrides it).

```powershell
# One command: build, install with adb install -r, fetch the files if this machine lacks them,
# then push them. This is the loop to use.
.\dev\adaware\next\parakeet\install-app.ps1

# Code changed, models already on the phone.
.\dev\adaware\next\parakeet\install-app.ps1 -SkipBuild

# Models changed or phone wiped, APK already installed.
.\dev\adaware\next\parakeet\install-model.ps1

# Only the small tokenizer, e.g. after editing how it is read.
.\dev\adaware\next\parakeet\install-model.ps1 -Only tokenizer.json

# Several phones attached.
.\dev\adaware\next\parakeet\install-app.ps1 -Serial <serial>

# Fetch the files on their own, without touching the phone.
.\dev\adaware\next\parakeet\download-model.ps1
```

- `download-model.ps1` uses `curl.exe -L -C -`, so re-running it resumes an interrupted transfer.
  Bytes live in `<name>.part` until the size check passes; only a file of exactly the right size is
  renamed, and a file that is too long is deleted as the wrong export. `-Destination` and
  `-TokenizerOnly` override where it goes and how much it fetches.
- `install-model.ps1` pushes through the app's own external files directory and copies across with
  `run-as <package> cp`, because adb cannot write app-private storage. It skips a push when the
  device copy already has the right size (which matters at 1.2 GiB), removes a stale `.part` and any
  wrong-sized copy, and fails loudly when the local file is missing or the wrong size. It does not
  need the app to have been launched — only for the package to be installed.
- `install-app.ps1` is the loop above: `:app:assembleDebugProd`, `adb install -r`, the download when
  this machine does not have the files yet, then `install-model.ps1`; `-SkipBuild` and `-SkipModels`
  skip either half. Switching between the staging `debug` and the production `debugProd` build
  replaces the app, because both carry the `.debug` application id, and **wipes the files** along
  with the account and library — the next `install-app.ps1` pushes them again. Use `debugProd` unless
  a staging server is what is being tested.

## Backends: NPU, GPU, CPU

The graph runs on three accelerators and the app picks one:

| Backend | When | Notes |
|---|---|---|
| `NPU` | default on a Google Tensor device (`NpuCompatibilityChecker.GoogleTensor`) | `encode` on the G6 graph on the NPU, `decode` on the portable graph on the CPU; `litert-npu-runtime-google-tensor` ships the dispatch library, and the APK must be built with `useLegacyPackaging = true` so it can be loaded by name |
| `CPU` | every other device, and the fallback the NPU's partially delegated graph needs | the portable i8 graph, always available |
| `GPU` | never by default | the TDT decoder is dozens of single-token invocations per window, and per-step dispatch latency dominates; its shader compile also costs ~50 s on the first run |

Two graphs exist because one cannot serve all three: the G6 export's compiled ops are a `DISPATCH_OP`
that only the NPU executes, so the CPU and GPU run the portable i8 export of the same weights.

`ParakeetEpisodeTranscriber.backend` is the knob; the benchmark below pins each one in turn.

## Measuring it on the phone

`app/src/androidTest/.../ParakeetBackendBenchmarkTest.kt` runs the **same clip through all three
backends** and logs, per backend:

```
BENCH backend=npu audio_ms=33623 wall_ms=... rtf=... cues=..
en pass: model_load_ms=.. audio_ms=33623 wall_ms=.. rtf=.. segments=.. cues=..
         segment_ms_median=.. backend=npu windows=11 mel_ms=.. encode_ms=.. decode_ms=.. model_rtf=..
```

- `rtf` — audio time over wall time for the whole pass, including the CPU-only mel front end.
- `model_rtf` — audio time over `encode_ms + decode_ms`, i.e. the graph alone. This is the number
  that says whether the NPU is earning its place, because the front end runs on the CPU whatever the
  backend is.

`rtf` is audio time divided by wall time, so **above 1 means faster than real time**, and 6.0 means an
hour of audio in ten minutes. Short clips understate every backend because opening the graph is paid
once per pass, so treat the ordering as the result and the absolute number as the floor.

### Measured on the Pixel 11 (Tensor G6), 33.6 s clip, 2026-11-17

One pass per backend over the same clip, same 5 s/3 s windows, same mel front end, warm phone:

| Backend | Where the model ran | Wall | `rtf` | `model_rtf` | mel | encode | decode | graph load |
|---|---|---|---|---|---|---|---|---|
| **CPU** | portable i8 graph, CPU | 6.65 s | **5.06** | 10.50 | 1.99 s | 1.86 s | 1.34 s | 1.0 s |
| **NPU** | G6 graph on NPU for `encode`, portable i8 on CPU for `decode` | 11.32 s | 2.97 | 8.93 | 2.66 s | **0.98 s** | 2.78 s | 4.1 s |
| **GPU** | portable i8 graph, GPU | 71.96 s | 0.47 | 2.51 | 3.49 s | 0.02 s | 13.37 s | 54.3 s |

What the numbers say:

- **The NPU encoder is worth 2× the CPU's**: 0.98 s against 1.86 s for eleven encoder passes. That is
  the 600M-parameter FastConformer, the part of the model with real parallelism, and it is the reason
  to keep the NPU path.
- **The decoder is what decides the pass.** TDT decoding is 60-90 *single-token* invocations per
  window. On the NPU each of those pays a dispatch round trip: decoding the same clip entirely on the
  NPU measured 8.13 s of decode, 2.9× the CPU's 2.78 s, for a `model_rtf` of 3.70 instead of 8.93. So
  the NPU path deliberately runs only `encode` on the NPU (see `ParakeetRecognizer.decodeFile`), which
  is the fastest NPU configuration measured.
- **The GPU loses on both halves.** Its kernel launch cost makes the 330 sequential decoder steps the
  slowest of the three, and its first-run shader compilation is the 54 s graph load. It stays in the
  code as a measurement, not as a default.
- **Pure CPU was still the fastest whole pass on this phone**, 5.06 against 2.97, because the NPU also
  pays a 4.1 s load for two graphs and its CPU decoder was slower than the same decoder on its own
  (2.78 s vs 1.34 s — with one graph the encoder's output buffers *are* the decoder's input buffers,
  so the two-model path costs a copy per window and holds a second compiled model's memory).
  `ParakeetBackend.preferred()` still returns NPU on a Tensor device on purpose: this branch is the
  NPU experiment. If the NPU is to be the shipping default, the next step is proving the hybrid's
  decoder overhead is not just measurement noise, and that the encoder win grows on long episodes.

The mel front end is the one cost no backend changes: 2-3.5 s per 33.6 s clip on the CPU, ~7% of real
time, and it is the largest single item for the CPU pass after the model itself.


```powershell
# The clip (not in git: a usable minute of podcast is too big).
adb push clip.mp3 /sdcard/Android/data/au.com.shiftyjelly.pocketcasts.debug/files/parakeet-fixture.mp3

$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebugAndroidTest
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb shell am instrument -w `
  -e class au.com.shiftyjelly.pocketcasts.ParakeetBackendBenchmarkTest `
  au.com.shiftyjelly.pocketcasts.testapp/androidx.test.runner.AndroidJUnitRunner

adb logcat -s ParakeetStt
```

## Languages

Parakeet TDT 0.6B v3 covers 25 European languages: bg, hr, cs, da, nl, el, en, et, fi, fr, de, hu, it,
lv, lt, mt, pl, pt, ro, sk, sl, es, sv, ru, uk. The engine picks the language from
`Podcast.podcastLanguage` by primary subtag (`es-us` → `es`) and falls back to English when the
podcast does not declare one. The model detects the language itself from the audio; the podcast's tag
only decides whether a pass runs at all. Any other language makes the engine unavailable for that
episode, which leaves it without on-device detection instead of falling back to a published
transcript.

## Reading the log

```powershell
adb logcat -s ParakeetStt AsrWorker OnDeviceTranscript
```

`ParakeetStt` carries the transcription trace; `AsrWorker` says what the queue is doing and why a
pass stopped; `OnDeviceTranscript` covers storing the WebVTT and handing the episode on to
detection.

Two lines matter. During a pass, `ParakeetStats.progress` prints:

```
en progress: chars=204 at=0:15 elapsed=0:09 audio=0:20 rtf=2.09 segments=12
```

- `chars` — characters transcribed so far.
- `at` — position in the episode the transcript has reached.
- `elapsed` — wall time this pass has been running.
- `audio` — audio decoded so far.
- `rtf` — `audio ÷ elapsed` for the pass so far.
- `segments` — words confirmed so far.

At the end of a pass:

```
en pass: model_load_ms=1400 audio_ms=33623 wall_ms=16396 rtf=2.05 segments=340 cues=11
         segment_ms_median=240 backend=npu windows=11 mel_ms=900 encode_ms=1200 decode_ms=2100 model_rtf=10.2
```

`segment_ms_median` is about 240 ms now: the recogniser reports words, not encoder windows, and the
cue rules join them into sentences.

## How a pass is shaped

1. `AudioFeed` decodes the episode to mono 16 kHz and hands over buffers of whatever size the codec
   produced.
2. `ParakeetWindowBuffer` cuts those into 5 s windows that step forward 3 s, keeping the last 2 s so
   a word spoken across a boundary is heard twice.
3. `ParakeetMel` turns each window into the encoder's flat `[128][500]` log-mel block. The `.tflite`
   graph carries no preprocessor, so this is now the app's job (0.97 pre-emphasis, 512-point Hann
   STFT at a 10 ms hop, 128 Slaney mel bins, natural log, per-feature mean/std).
4. `ParakeetRecognizer` runs `encode`, then the greedy TDT decoder (`decode` → `decode_1`) and
   returns token ids with the encoder frame each one starts on.
5. `ParakeetWords` detokenises with `ParakeetTokenizer` and releases a word only once a later window
   has started after it, which is what settles the overlap.
6. `ParakeetCues` joins the words into sentence-level cues for the WebVTT, as before.

## Resumability, identity and what a change invalidates

Recognition is durable per episode. Cues are written as they are produced, and the queue records how
far into the audio it got, so a process death or a dropped connection resumes the episode instead of
starting it again. The transcript is complete for one engine and model identity only: the identity is
stored with it, so a different LiteRT version, a different graph or a changed cue rule invalidates the
stored transcript and, with it, the ad analysis built from it. That is intended — an old transcript
may not agree with the new engine's cue times — but it does mean a model swap is followed by a full
re-transcription of the library.

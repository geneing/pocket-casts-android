# On-device speech to text: NVIDIA Parakeet on LiteRT

Ad detection reads only a transcript the app produced on the device. A transcript the show or our
servers publish is never used for it: its cue times do not match the audio and it is missing ad
reads. The old ONNX/Moonshine pipeline is gone, and so is the MediaPipe bundle that replaced it
briefly; this directory holds the tooling for the engine that runs now.

## The engine and the model

| | |
|---|---|
| Engine | LiteRT runtime, `com.google.ai.edge.litert:litert:2.3.2` (CPU, GPU) + `litert-npu-runtime-google-tensor` (Tensor NPU, pinned only) |
| Model | NVIDIA **Parakeet TDT 0.6B v3 Redux** (ternary encoder), 4-bit CPU encoder, 10 s windows — **the default** |
| Mel graph | `parakeet_redux_mel_10s.tflite` — 1 608 068 bytes (<https://huggingface.co/litert-community/parakeet-redux>) |
| Encoder | `parakeet_redux_encoder_10s_i4.tflite` — 345 895 584 bytes (~330 MiB) |
| Decoder | `parakeet_redux_decoder_i8.tflite` — 17 866 768 bytes (`predict` + `joint`) |
| Tokenizer | `tokenizer.json`, 8 192 BPE pieces — 1 159 960 bytes |
| Optional | `parakeet_redux_encoder_10s_f16.tflite`, 1 194 680 560 bytes (~1.11 GiB), only for a pass pinned to the GPU or NPU |
| Licences | model: NVIDIA / moondream, CC-BY-4.0 · LiteRT: Apache-2.0 |

### Why Redux, and why three graphs

`litert-community/parakeet-redux` is `moondream/parakeet-redux` — the Parakeet TDT 0.6B v3 weights
with a ternary encoder — converted to LiteRT. Compared with the earlier export it is a third of the
size on the CPU path (`i4`, 365 MB with the mel and decoder) and much faster, because it splits what
the `.litertlm` bundle kept in one graph:

| Graph | Signatures | What it does |
|---|---|---|
| `parakeet_redux_mel_10s` | `audio`, `n_valid` → `features`, `mel_mask`, `frame_mask` | the log-mel front end, in the graph rather than in Kotlin: pre-emphasis, 512-point STFT at a 10 ms hop, 128 Slaney bins, log and per-window normalisation |
| `parakeet_redux_encoder_10s_i4` / `_f16` | `features`, `mel_mask` → `enc` | the FastConformer: `[1, 128, 1000]` in, `[1, 125, 640]` out |
| `parakeet_redux_decoder_i8` | `predict` (`token`, `h`, `c` → `dec_out`, `h_out`, `c_out`) and `joint` (`enc_frame`, `dec_out` → `logits`) | the TDT prediction and joint networks: 8 198 logits = 8 193 token logits (8 192 is the blank) + 5 durations |

The mel graph is what makes the CPU path quick: the previous export needed the app's own 2 s of
Kotlin mel work per clip, and this one produces exactly the features the encoder was trained on. The
`frame_mask` also gives the decoder its stop point, so a short final window is a legal window rather
than something the app has to pad and then guess about.

The exact byte count of each file is the app's presence check: `ParakeetModel` treats a file of any
other size as absent and downloads it again. A truncated push, a half-finished download or a
different export therefore degrades to a fresh download instead of failing inside the interpreter.

`graph-names.sh` prints the signature keys and tensor names baked into the three graphs — the mel and
encoder graphs answer `serving_default`, the decoder `predict` and `joint` — which are what
`ParakeetRecognizer` binds its buffers to by name. Run it if a graph is ever swapped for another
export; a renamed tensor fails at open time, not silently at the decoder.

**Attribution.** CC-BY-4.0 requires visible credit to NVIDIA wherever the app is distributed. The
in-app licences screen is generated from the Gradle dependency graph, so it will never list the
model — this paragraph, and the matching note in `AGENTS.md`, is what a release checklist has to
find. LiteRT itself is Apache-2.0 and is a normal Gradle dependency.

## Where it lives on the device

```
/data/user/0/au.com.shiftyjelly.pocketcasts.debug/   # package of the ".debug" application id
└── no_backup/parakeet-model/
    ├── tokenizer.json                                 # the id → piece table
    ├── parakeet_redux_mel_10s.tflite                  # the log-mel front end
    ├── parakeet_redux_decoder_i8.tflite               # predict + joint
    └── parakeet_redux_encoder_10s_i4.tflite           # the encoder every pass uses
```

`no_backup` is deliberate: 366 MB of re-downloadable weights must never be backed up. A phone that
also carries `parakeet_redux_encoder_10s_f16.tflite` next to them is a phone that has had the
accelerator experiment pushed to it; nothing needs it otherwise. There is no cache directory: LiteRT
compiles nothing for these graphs. Keep the directory and file names in step with
`ParakeetModel.MEL`, `ParakeetModel.DECODER`, `ParakeetModel.ENCODER_I4`, `ParakeetModel.ENCODER_F16`
and `ParakeetModel.TOKENIZER` in
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

# Also push the float16 encoder, for a pass pinned to the GPU or NPU.
.\dev\adaware\next\parakeet\install-model.ps1 -WithF16

# Only the small tokenizer, e.g. after editing how it is read.
.\dev\adaware\next\parakeet\install-model.ps1 -Only tokenizer.json

# Several phones attached.
.\dev\adaware\next\parakeet\install-app.ps1 -Serial <serial>

# Fetch the files on their own, without touching the phone.
.\dev\adaware\next\parakeet\download-model.ps1
```

- `download-model.ps1` uses `curl.exe -L -C -`, so re-running it resumes an interrupted transfer.
  Bytes live in `<name>.part` until the size check passes; only a file of exactly the right size is
  renamed, and a file that is too long is deleted as the wrong export. `-Destination`, `-WithF16` and
  `-Names` override where it goes and what it fetches.
- `install-model.ps1` pushes through `/data/local/tmp` and copies across with
  `run-as <package> cp`, because adb cannot write app-private storage (and `run-as` cannot read the
  app's external files directory). It skips a push when the device copy already has the right size,
  removes a stale `.part` and any wrong-sized copy, and fails loudly when the local file is missing or
  the wrong size. It does not need the app to have been launched — only for the package to be
  installed. `-WithF16` adds the float16 encoder, and `-Only <name>` pushes just one file.
- `install-app.ps1` is the loop above: `:app:assembleDebugProd`, `adb install -r`, the download when
  this machine does not have the files yet, then `install-model.ps1`; `-SkipBuild` and `-SkipModels`
  skip either half. Switching between the staging `debug` and the production `debugProd` build
  replaces the app, because both carry the `.debug` application id, and **wipes the files** along
  with the account and library — the next `install-app.ps1` pushes them again. Use `debugProd` unless
  a staging server is what is being tested.

## Backends: CPU, GPU, NPU

The encoder is the only part that can move; the mel graph and the TDT decoder stay on the CPU on
every backend.

| Backend | When | Notes |
|---|---|---|
| `CPU` | **default everywhere**, including Google Tensor phones | the 4-bit encoder; the export's own CPU form and the one a normal install carries |
| `GPU` | only when pinned (`ParakeetBackend.GPU`) | the float16 encoder on the GPU, mel and decoder on the CPU; needs `-WithF16`, and pays a long shader compile on the first run |
| `NPU` | only when pinned (`ParakeetBackend.NPU`) | the float16 encoder on the NPU, mel and decoder on the CPU; a partially delegated graph still runs its leftover ops on the CPU |

The decoder is 60-100 *single-token* `predict`/`joint` runs per window, so offering it to an
accelerator would pay a dispatch round trip per token and lose to the CPU; that is why only the
encoder moves. Neither the GPU nor the NPU encoder is part of an install: they stay as plumbing, and
`ParakeetEpisodeTranscriber.backend` plus `-WithF16` is the knob and the file that reach them.

`ParakeetEpisodeTranscriber.backend` is the knob; the benchmark below pins each installed one in turn.

## Measuring it on the phone

`app/src/androidTest/.../ParakeetBackendBenchmarkTest.kt` runs the **same clip through every
installed backend** — the GPU and NPU rows only appear once the float16 encoder has been pushed with
`-WithF16` — and logs, per backend:

```
BENCH backend=cpu audio_ms=33623 wall_ms=... rtf=... cues=..
en pass: model_load_ms=.. audio_ms=33623 wall_ms=.. rtf=.. segments=.. cues=..
         segment_ms_median=.. backend=cpu windows=.. mel_ms=.. encode_ms=.. decode_ms=.. model_rtf=..
```

- `rtf` — audio time over wall time for the whole pass, including the CPU mel graph.
- `model_rtf` — audio time over `encode_ms + decode_ms`, i.e. the encoder and decoder alone. This is
  the number that says whether an accelerator is earning its place, because the mel graph and the
  decoder run on the CPU whatever the backend is.

`rtf` is audio time divided by wall time, so **above 1 means faster than real time**, and 6.0 means an
hour of audio in ten minutes. Short clips understate every backend because opening the graphs is paid
once per pass, so treat the ordering as the result and the absolute number as the floor.

For reference, the export's own README measures the CPU form (10/20/30 s buckets, 8 threads) at
`rtf` 0.035 on a Galaxy S26 and 0.112 on a Pixel 8a — 29x and 9x real time. This app asks the encoder
for every window at 10 s, which only costs the padding it would have had on a longer bucket.

### Measured on the Pixel 11 (Tensor G6), 33.6 s clip, 2026-11-17

One pass per backend over the same clip, the same 10 s/8 s windows, warm phone:

| Backend | Where the encoder ran | Wall | `rtf` | `model_rtf` | mel | encode | decode | graph load |
|---|---|---|---|---|---|---|---|---|
| **CPU** | 4-bit encoder, CPU | 4.76 s | **7.04** | 9.04 | 37 ms | 3.57 s | 0.14 s | 0.63 s |

- **The mel graph is essentially free**: 37 ms for four windows, against the ~2 s the Kotlin/jLibrosa
  front end took per clip on the same phone. It is also the only version whose features the encoder
  was trained on.
- **The encoder is the pass now**: 3.57 s of the 4.76 s. That is the 600M-parameter FastConformer, and
  the part an accelerator could still win.
- **The decoder is small**: 0.14 s for four windows, ~40 ms each, because `joint` runs once per
  encoder frame the duration skips and `predict` once per emitted token. There is nothing left in it
  for an accelerator to save.
- The four graphs open in 0.6 s, and the pass is 4.76 s against the previous export's 6.65 s on the
  same clip, with 4 windows instead of 11.

GPU and NPU were not measured on this run: their float16 encoder (1.1 GiB) was not pushed. To add the
rows, `install-model.ps1 -WithF16` and re-run the benchmark; it will log `Measuring [npu, gpu, cpu]`.


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
en pass: model_load_ms=631 audio_ms=33623 wall_ms=4764 rtf=7.06 segments=84 cues=9
         segment_ms_median=240 backend=cpu windows=4 mel_ms=37 encode_ms=3574 decode_ms=144 model_rtf=9.04
```

`segment_ms_median` is about 240 ms: the recogniser reports words, not encoder windows, and the cue
rules join them into sentences.

## How a pass is shaped

1. `AudioFeed` decodes the episode to mono 16 kHz and hands over buffers of whatever size the codec
   produced.
2. `ParakeetWindowBuffer` cuts those into 10 s windows that step forward 8 s, keeping the last 2 s so
   a word spoken across a boundary is heard twice.
3. `ParakeetRecognizer` runs the mel graph on the window, copies its `features` and `mel_mask` into the
   encoder, runs the encoder, and then greedy-decodes with `predict` and `joint` until the mel graph's
   `frame_mask` runs out. Token ids carry the encoder frame each one starts on.
4. `ParakeetWords` detokenises with `ParakeetTokenizer` and releases a word only once a later window
   has started after it, which is what settles the overlap.
5. `ParakeetCues` joins the words into sentence-level cues for the WebVTT, as before.

## Resumability, identity and what a change invalidates

Recognition is durable per episode. Cues are written as they are produced, and the queue records how
far into the audio it got, so a process death or a dropped connection resumes the episode instead of
starting it again. The transcript is complete for one engine and model identity only: the identity is
stored with it, so a different LiteRT version, a different graph or a changed cue rule invalidates the
stored transcript and, with it, the ad analysis built from it. That is intended — an old transcript
may not agree with the new engine's cue times — but it does mean a model swap is followed by a full
re-transcription of the library.

# On-device speech to text: NVIDIA Parakeet on MediaPipe

Ad detection reads only a transcript the app produced on the device. A transcript the show or our
servers publish is never used for it: its cue times do not match the audio and it is missing ad
reads. The old ONNX/Moonshine pipeline is gone; this directory holds the tooling for the engine that
replaced it.

## The engine and the model

| | |
|---|---|
| Engine | MediaPipe audio transcriber, `com.google.mediapipe:tasks-audio:1.2.3` (CPU only) |
| Model | NVIDIA **Parakeet TDT 0.6B v3**, LiteRT-LM export, int8, stateful, 5 s chunks |
| File | `parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm` |
| Size | **614 588 752 bytes** (~586 MiB) |
| Source | <https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/resolve/main/parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm> |
| Licences | model: NVIDIA, CC-BY-4.0 · MediaPipe: Apache-2.0 |

The exact byte count is the app's presence check: `ParakeetModel` treats a file of any other size as
absent and downloads it again. A truncated push, a half-finished download or a different export
therefore degrades to a fresh download instead of failing inside the interpreter.

**Attribution.** CC-BY-4.0 requires visible credit to NVIDIA wherever the app is distributed. The
in-app licences screen is generated from the Gradle dependency graph, so it will never list the
model — this paragraph, and the matching note in `AGENTS.md`, is what a release checklist has to
find. MediaPipe itself is Apache-2.0 and is a normal Gradle dependency.

## Where it lives on the device

```
/data/user/0/au.com.shiftyjelly.pocketcasts.debug/   # package of the ".debug" application id
├── no_backup/parakeet-model/
│   └── parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm  # the only file the app reads
└── no_backup/parakeet-cache/                         # MediaPipe's compiled artefacts, built on first use
```

`no_backup` is deliberate: 586 MiB of re-downloadable weights must never be backed up. The app
creates `parakeet-cache` itself; nothing is pushed there and nothing needs clearing unless a model
change has to be proven to take effect. Keep the directory and file name in step with
`ParakeetModel.file()` in
`modules/services/repositories/.../repositories/ads/text/parakeet/ParakeetModel.kt`.

## The scripts

All three are Windows PowerShell, like the rest of this checkout's build loop (see
[AGENTS.md](../../../../AGENTS.md#building-and-running-on-this-machine)). `install-app.ps1` sets
`ANDROID_HOME` when it is unset, and the two device scripts default to
`$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` (`-Adb` overrides it).

```powershell
# One command: build, install with adb install -r, fetch the bundle if this machine lacks it,
# then push it. This is the loop to use.
.\dev\adaware\next\parakeet\install-app.ps1

# Code changed, bundle already on the phone.
.\dev\adaware\next\parakeet\install-app.ps1 -SkipBuild

# Bundle changed or phone wiped, APK already installed.
.\dev\adaware\next\parakeet\install-model.ps1

# Several phones attached.
.\dev\adaware\next\parakeet\install-app.ps1 -Serial <serial>

# Fetch the bundle on its own, without touching the phone.
.\dev\adaware\next\parakeet\download-model.ps1
```

- `download-model.ps1` uses `curl.exe -L -C -`, so re-running it resumes an interrupted transfer.
  Bytes live in `<name>.part` until the size check passes; only a file of exactly 614 588 752 bytes
  is renamed to the model name, and a file that is too long is deleted as the wrong bundle.
  `-Destination` and `-Url` override where it goes and what it fetches.
- `install-model.ps1` pushes through `/data/local/tmp` and copies across with
  `run-as <package> cp`, because adb cannot write app-private storage. It skips the 586 MiB push
  when the device copy already has the right size, removes a stale `.part` and any wrong-sized
  copy, and fails loudly when the local file is missing or the wrong size. It does not need the app
  to have been launched — only for the package to be installed. `-Package` defaults to the
  `.debug` id.
- `install-app.ps1` is the loop above: `:app:assembleDebugProd`, `adb install -r`, the bundle fetch
  when this machine does not have it yet, then `install-model.ps1`; `-SkipBuild` and `-SkipModels`
  skip either half, and the push alone is a no-op when the phone is already seeded. Switching
  between the staging `debug` and the production `debugProd` build replaces the app, because both
  carry the `.debug` application id, and **wipes the model** along with the account and library —
  the next `install-app.ps1` pushes it again. Use `debugProd` unless a staging server is what is
  being tested.

## Measured performance

One real pass on a phone, over a 34 s fixture, logged `rtf=2.05`: 33.6 s of audio in 16.4 s of wall
time, with the model cold. Short clips understate the engine because the first `transcribe` call pays
for loading and compiling the bundle (MediaPipe caches the compiled artifact in `parakeet-cache`
afterwards), so expect a higher `rtf` on a full episode. Treat every number here as an ordering, not
a promise: the measurement is the `rtf` in the `ParakeetStt` lines. `rtf` is audio time divided by
wall time, so **above 1 means faster than real time**, and 6.0 means an hour of audio in ten minutes.

## Languages

Parakeet TDT 0.6B v3 covers 25 European languages: bg, hr, cs, da, nl, en, et, fi, fr, de, hu, it,
lv, lt, mt, pl, pt, ro, sk, sl, es, sv, ru, uk. The engine picks the language from
`Podcast.podcastLanguage` by primary subtag (`es-us` → `es`) and falls back to English when the
podcast does not declare one. English and Spanish are no longer special cases. Any other language
makes the engine unavailable for that episode, which leaves the episode without on-device detection
instead of falling back to a published transcript.

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
- `segments` — MediaPipe segments seen so far.

At the end of a pass:

```
en pass: model_load_ms=0 audio_ms=33623 wall_ms=16396 rtf=2.05 segments=24 cues=11 segment_ms_median=1250
```

`audio_ms` and `wall_ms` give the pass `rtf`; `cues` is how many WebVTT cues were written from the
`segments`, and `segment_ms_median` says how long a typical segment was. On the bundle this app ships
that median is about 1.25 s — MediaPipe reports fixed windows, not sentences, which is why cues are
assembled from the text (`ParakeetCues`) rather than from segment boundaries. `model_load_ms` is near
zero because MediaPipe loads lazily on the first `transcribe` call, so the cold-start cost shows up
as the gap between `start` and the first progress line instead.

## Resumability, identity and what a change invalidates

Recognition is still durable per episode. Cues are written as they are produced, and the queue
records how far into the audio it got, so a process death or a dropped connection resumes the
episode instead of starting it again. The transcript is complete for one engine and model identity
only: the identity is stored with it, so a different MediaPipe version or a different
`.litertlm` bundle invalidates the stored transcript and, with it, the ad analysis built from it.
That is intended — an old transcript may not agree with the new engine's cue times — but it does
mean a model swap is followed by a full re-transcription of the library.

## A scoring harness, if one is wanted again

The previous directory carried a Python harness that compared the old engine's transcript against
Whisper on the same audio and scored ad boundaries against hand labels. It was tied to that engine's
word timings and has not been carried over. If this engine needs the same treatment, the shape
would be: run Parakeet through MediaPipe on the desktop (the same `.litertlm` bundle), keep Whisper
as the reference text, and score **sentence-level cue boundaries** rather than word offsets, since
segments are the only timing MediaPipe reports. The hand-labelled episodes under
`dev/adaware/next/labels/` are engine-independent and are the part worth reusing.

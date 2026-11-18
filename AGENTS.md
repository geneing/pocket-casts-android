# AGENTS.md

This file provides guidance to AI coding assistants (Claude Code, Cursor, Windsurf, etc.) when working with code in this repository.

## Building and running on this machine

This checkout is developed on a **Windows** host, building against a phone over USB. Gradle runs on
Windows — `.\gradlew.bat` from PowerShell, never a system `gradle` and never inside WSL; WSL is only
for the POSIX scripts under `dev/`.

```powershell
# Gradle needs the Windows SDK path before it configures anything: the project has no
# local.properties, so an unset ANDROID_HOME fails configuration.
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Build :app:assembleDebugProd, install it with `adb install -r`, then fetch the 586 MiB speech
# bundle once and push it. Both halves of that are skipped when they are already in place.
.\dev\adaware\next\parakeet\install-app.ps1

# Code changed, model already on the phone.
.\dev\adaware\next\parakeet\install-app.ps1 -SkipModels

# Model changed or phone wiped, APK already installed.
.\dev\adaware\next\parakeet\install-model.ps1

# Several phones attached: name the target.
.\dev\adaware\next\parakeet\install-app.ps1 -Serial <serial>
```

`JAVA_HOME` is Android Studio's bundled JDK; the `java = "17"` toolchain is provisioned by Gradle, so
no JDK needs installing by hand. Add `--console=plain` when the output is read by a script (or by an
agent), and `-q` when only failures matter.

### Device builds are always `debugProd`

It points at the production servers, so the installed app sees the real library and account; the
staging `debug` build does not. Both carry the `.debug` application id, so they replace each other —
and because the data belongs to that id, **switching between `debug` and `debugProd` wipes the app**
(sign-in, library, and the pushed speech model). Stay on `debugProd` unless a staging server is the
thing being tested.

Never uninstall to get a clean state: `adb install -r` keeps the data, models and account, and a
reinstall of the same variant is what the install script does.

### Building without installing

```powershell
.\gradlew.bat :app:assembleDebugProd   # app\build\outputs\apk\debugProd\app-debugProd.apk
.\gradlew.bat :app:assembleDebug       # staging servers, same .debug application id
.\gradlew.bat :app:assemblePrototype   # minified pre-release
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:installDebugProd    # install what was just built
```

Installing by hand is the same half of the install script:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debugProd\app-debugProd.apk
```

### Testing

```powershell
# Unit tests: a whole module, or one package/class with --tests
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :modules:features:search:testDebugUnitTest
.\gradlew.bat :modules:services:repositories:testDebugUnitTest `
  --tests "au.com.shiftyjelly.pocketcasts.repositories.ads.*"
```

Instrumentation tests run on the attached phone. The Gradle tasks work
(`:app:connectedDebugProdAndroidTest`, or `:modules:features:search:connectedDebugAndroidTest`), but
running them by hand installs the test APK with `-r` and leaves the app's data — account, library,
pushed model — alone:

```powershell
.\gradlew.bat :app:assembleDebugProdAndroidTest
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\androidTest\debugProd\app-debugProd-androidTest.apk
& $adb shell am instrument -w `
  -e class au.com.shiftyjelly.pocketcasts.models.db.AppDatabaseMigrationTest `
  au.com.shiftyjelly.pocketcasts.testapp/androidx.test.runner.AndroidJUnitRunner
```

Drop `-e class` to run every test in the APK. The test application id is
`au.com.shiftyjelly.pocketcasts.test<module>` (`…testapp` for `:app`), and the runner is
`androidx.test.runner.AndroidJUnitRunner`.

### Code Quality

```powershell
# Check code formatting (must pass before merge)
.\gradlew.bat spotlessCheck

# Auto-format code
.\gradlew.bat spotlessApply

# Install git hooks to run spotless on pre-commit
.\gradlew.bat installGitHooks

# Run lint on all application modules
.\gradlew.bat aggregatedLintRelease

# Run dependency analysis
.\gradlew.bat buildHealth
```

Kotlin warnings are errors, so a build that "just warns" is a failing build.

### Build Variants
- **debug**: Development with `.debug` suffix, uses staging servers (*.pocketcasts.net)
- **debugProd**: Development build pointing to production servers (*.pocketcasts.com)
- **prototype**: Minified pre-release build with ProGuard/R8
- **release**: Production release build with minification and shrinking

### When the build lies

- **`[Dagger/MissingBinding]` for a DAO that obviously exists** — the Hilt aggregate task is stale.
  `.\gradlew.bat :app:hiltAggregateDepsDebugProd --rerun-tasks` fixes it; it is not a DI bug.
- **`:app:tasks` fails on Compose Preview** ("Android resources are excluded from unit tests") —
  that is the task listing, not the build. Use `:app:assembleDebugProd` to check the build itself.
- **A code change has no effect on the device** — check the APK timestamp
  (`app\build\outputs\apk\debugProd\app-debugProd.apk`) and reinstall with `-r`; Gradle's
  configuration cache and up-to-date checks are quick to blame and rarely wrong.

## Architecture Overview

### Multi-Module Structure

**Application Modules** (3):
- `app/` - Main mobile Android application
- `automotive/` - Android Automotive OS variant
- `wear/` - Wear OS variant

**Feature Modules** (`modules/features/`):
Self-contained features with UI, ViewModels, and feature-specific logic. Features depend on services but never on other features.

**Service Modules** (`modules/services/`):
Shared infrastructure and business logic. Core services include:
- `model` - Database entities and Room DAOs (122 database migrations!)
- `repositories` - Data layer abstracting servers and model (Repository pattern)
- `servers` - Network API clients (Retrofit + OkHttp)
- `compose` - Shared Compose components
- `ui` - Shared UI theming and components
- `analytics` - Analytics tracking
- `localization` - Strings and translations

**Dependency Flow**:
```
Applications (app, automotive, wear)
    ↓
Features (account, search, player, etc.)
    ↓
Services (repositories, ui, compose, analytics, etc.)
    ↓
Core Services (model, servers)
```

### Architecture Pattern: MVVM

- **ViewModels**: Annotated with `@HiltViewModel`, use `StateFlow` for state management
- **UI State Pattern**: Sealed interfaces/classes for representing UI states (e.g., `Idle`, `Loading`, `Success`, `Error`)
- **Unidirectional Data Flow**: ViewModels expose immutable state via `StateFlow`, accept actions via functions
- **Dependency Injection**: Hilt (Dagger 2) throughout the codebase
- **Reactive Programming**: Dual strategy with RxJava2 (legacy) and Coroutines/Flow (modern)

### Technology Stack

**UI**:
- **Jetpack Compose** (primary for new features) with Material (Material2)
  > Note: The codebase currently uses Material (Material2) for Compose components. Migration to Material3 is in progress. **For all new Compose UI, use Material3 components unless you are working in a module that has not yet migrated.** If unsure, check the module's dependencies or consult the team. Existing code may still use Material2 until migration is complete.
- **XML Views** (legacy)
- View Binding enabled for XML layouts

**Dependency Injection**: Hilt

**Database**: Room with extensive migration history

**Networking**: Retrofit + OkHttp + Moshi

**Media**: Media3 (ExoPlayer) + Cast Framework for Chromecast

**Image Loading**: Coil

**Async**: Coroutines + WorkManager

**Testing**:
- JUnit, Mockito, Turbine (for Flow testing)
- Compose UI Test, UIAutomator, MockWebServer

## Development Guidelines

### Git Workflow

**Develop feature work on a branch off `main`. The only reason to branch off a release branch is to cherry-pick an already-merged commit into that release.**

Branching feature *development* off a release branch causes confusion later: when
the release branch is merged back into `main`, it is unclear which commits belong
to the release and which belong to the unrelated feature work. To avoid this:

- **Create every feature branch from `main`**, even if the
  change is also needed in an in-flight release. Never develop a feature on a
  branch based on `release/*`.
- If a change must also land in a release branch (e.g. a fix needed for an
  upcoming release):
  1. Develop and merge it on a branch off `main` first.
  2. Create a new branch off the release branch, cherry-pick the relevant commit(s)
     from the origin branch onto it, then open a PR into the release branch.
- Keep cherry-picks limited to the specific commits the release needs, so the release branch never accumulates
  feature work that does not belong in it.
- Cherry-picking may surface merge conflicts. Resolve them carefully, then verify the result before opening the PR:
  - The apps build on all platforms (`app`, `automotive`, `wear`).
  - All quality gates pass (`./gradlew spotlessCheck`, lint).
  - Unit and integration tests succeed.

### Technology Preferences

**Use Jetpack Compose for new UI**:
- All new screens/views must be written in Compose
- Migrating existing XML views to Compose is encouraged when updating them
- Use Material3 components from `modules/services/compose` and `modules/services/ui`

**Prefer Coroutines over RxJava**:
- New code should use Coroutines and Flow
- Convert RxJava code to Coroutines when you have a good opportunity
- Use `StateFlow` for state management in ViewModels

**Write in Kotlin**:
- All new features must be written in Kotlin
- Convert Java code to Kotlin at the first opportunity

### Code Style

- Line length: **120 characters**
- Must pass `spotlessCheck` before merge (auto-format with `spotlessApply`)
- Use **TODO** for temporary notes, never use **FIXME** (not allowed in repository)
- ktlint enforces Kotlin style with custom rules for Compose
- All warnings treated as errors in Kotlin compilation

### String Resources

- Add English strings only to `modules/services/localization`
- Translations are managed through GlotPress and pulled automatically
- Mark non-translatable strings with `translatable="false"`

### Image Resources

- Add images to `modules/services/images` only

**Prefer vector graphics (.svg) over rasterized formats when possible**:
- Scalable to any screen density without quality loss
- Smaller file size for simple graphics
- Use for icons, logos, and simple illustrations

**Prefer WebP format over PNG**:
- New images should be `.webp` format for better compression and smaller APK size
- PNG acceptable only for:
  - App icons and launcher icons (when required by Android)
  - Images requiring transparency that are very small
  - Third-party assets that cannot be modified

### Testing

- Write unit tests for ViewModels, managers, and business logic
- Use `MainCoroutineRule` for testing coroutines
- Use Turbine for testing Flows
- Shared test utilities available in `modules/services/sharedtest`
- Test files in `src/test/` for unit tests, `src/androidTest/` for instrumentation tests

### Feature Flags

The codebase uses a `FeatureFlag` system for A/B testing and gradual rollout. Check for feature flags before implementing changes to flagged features.

### Module Dependencies

- Features can depend on services, never on other features
- Service modules can depend on other services (e.g., `compose` → `ui`, `repositories` → `model`)
- Most feature modules depend on: `model`, `repositories`, `ui`, `compose`, `analytics`, `localization`
- `repositories` is the central data access layer
- Dependency analysis plugin enforces these rules (`./gradlew buildHealth`)

## Project-Specific Notes

### Database Migrations

The Room database has 122 migration versions. When modifying entities:
- Always provide a migration path
- Export schema is enabled (`modules/services/model/schemas/`)
- Test migrations thoroughly

### Build Variants and Server URLs

- `debug` uses staging servers (*.pocketcasts.net)
- `debugProd`, `prototype`, and `release` use production servers (*.pocketcasts.com)
- Server URLs are configured via `buildConfigField` in build.gradle.kts

### Analytics

Use `AnalyticsTracker` service for event tracking. Analytics are integrated with Automattic Tracks.

### On-device speech to text (ad skipping)

Ad detection reads only a transcript the app produced on the device with NVIDIA Parakeet TDT 0.6B
v3, run through the LiteRT runtime. A transcript the show or our servers publish is never used for
detection or navigation: its cue times do not match the audio and it is missing ad reads.

- Engine: LiteRT, `com.google.ai.edge.litert:litert:2.3.2` with `litert-gpu` and
  `litert-npu-runtime-google-tensor`. MediaPipe (`tasks-audio`) is gone: its audio transcriber loads
  the `.litertlm` bundle on the CPU only, so it can never reach the phone's NPU.
- Model: two required files in `noBackupFilesDir/parakeet-model`, never bundled in the APK.
  - `parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite`, exactly 614 261 072 bytes (~586 MiB): the portable
    export every pass runs, on the CPU.
  - `tokenizer.json`, 1 159 960 bytes: a `.tflite` graph stops at token ids, so the 8 192-piece BPE
    table has to sit beside it.
  Both are downloaded on demand (unmetered only) into `noBackupFilesDir/parakeet-model`, and a file
  of any other size counts as absent, so a truncated copy falls back to the download instead of
  failing inside the interpreter.
- NPU: kept as plumbing, not installed. `parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite`
  (1 303 768 096 bytes) is AOT-compiled for the Pixel 11's NPU and is only asked for by a pass pinned
  to `ParakeetBackend.NPU`; push it with `install-model.ps1 -WithNpu`. Its compiled ops are a
  `DISPATCH_OP` only the Tensor dispatch library executes, so it cannot stand in for the portable
  graph on CPU or GPU. If it is picked up again, the `_stateful_` export is required: the stateless
  G6 export has no `decode_1`, so the decoder stops when its stateless token slots fill and loses the
  tail of every window.
- Licence: the model is NVIDIA's under CC-BY-4.0 (LiteRT is Apache-2.0). CC-BY-4.0 requires visible
  attribution to NVIDIA wherever the app is distributed, and the licences screen is built from the
  Gradle dependency graph, so it will never list the model — the note a release checklist has to
  find is in `dev/adaware/next/parakeet/README.md`.
- Backends: `ParakeetBackend` picks the accelerator and the default is **CPU**, on every device
  including Google Tensor: the portable graph on the CPU measured fastest end to end (rtf 5.06
  against the NPU's 2.97 and the GPU's 0.47 on a 33.6 s clip). `GPU` is only run when a test pins it —
  the TDT decoder is dozens of single-token invocations per window, so per-step GPU dispatch latency
  loses, and its shader compile costs ~50 s on the first run. `NPU` is plumbing kept for later: on it
  only `encode` goes to the NPU (the decoder's per-token dispatch measured 2.9x the CPU's, so
  `ParakeetRecognizer` opens the portable graph on the CPU for that half and copies a quarter of a
  megabyte across per window). `ParakeetEpisodeTranscriber.backend` is the knob; the whole table and
  how to reproduce it are in `dev/adaware/next/parakeet/README.md`.
- The APK is built with `packaging.jniLibs.useLegacyPackaging = true`, because the Google Tensor
  dispatch library is looked up by name in `nativeLibraryDir`; with the default (`extractNativeLibs
  = false`) the environment logs "NPU accelerator could not be loaded and registered" and every NPU
  pass falls back to the CPU.
- On a device, a private build gets the files with `dev/adaware/next/parakeet/install-app.ps1`
  (builds `:app:assembleDebugProd`, `adb install -r`, then pushes with `install-model.ps1`;
  `-SkipBuild`/`-SkipModels` skip either half, and a push is skipped when the device copy is already
  the right size). There is no cache directory: LiteRT compiles nothing for an AOT-compiled graph.
- Language: Parakeet covers 25 European languages (bg, hr, cs, da, nl, el, en, et, fi, fr, de, hu,
  it, lv, lt, mt, pl, pt, ro, sk, sl, es, sv, ru, uk), chosen from `Podcast.podcastLanguage` by
  primary subtag (`es-us` -> `es`) and defaulting to English when the podcast declares nothing. Any
  other language makes the engine unavailable for that episode, which leaves it without on-device
  detection instead of falling back to a published transcript.
- Chunks: Parakeet is not a streaming model. One graph run covers 5 s, so a pass slices the episode
  into 5 s windows that step 3 s (`ParakeetWindowBuffer`); the 2 s overlap is what lets
  `ParakeetWords` settle a word spoken across a boundary. The mel front end (`ParakeetMel`) is the
  app's own work now — 0.97 pre-emphasis, 512-point Hann STFT at a 10 ms hop, 128 Slaney bins,
  natural log, per-feature mean/std — and must stay that way: the graph has no preprocessor.
- Timestamps: the TDT decoder reports the encoder frame each token starts on (80 ms), so words carry
  their own start and end and cues are sentence-level, built by `ParakeetCues` and stored as WebVTT
  through `LocalTranscriptStore`. Nothing else may supply times: no DTW, no energy regions, no
  constant offset. When cue positions look wrong, read `segment_ms_median`, `encode_ms` and
  `decode_ms` in the pass log before changing anything.
- Recognition always runs for a queued episode. Scheduling drops the published transcript rows so
  the transcript tab and tap-to-seek can only read the recognized text. `AdAnalysisQueue.schedule`
  runs from every path that can start an episode: download completion, playback start (which is how
  a streamed episode gets queued), episode details, any Up Next mutation, the Up Next sweep (one
  minute after each launch, then every six hours), and `resumeInterruptedWork` at launch. The sweep
  exists because an episode can enter Up Next from another device or a backup restore without passing
  through `UpNextQueueImpl`.
- The pipeline is two durable queues (`episode_asr_queue`, `ad_detection_queue`) drained by one
  worker each, so several playlist episodes add rows instead of starting several passes. Each pass
  resumes from the last stored cue: cues are written after every completed window, and the
  detection queue records the end of the last scored cue. Failures and metered-network deferrals
  stay in the row with a retry time, so a process death or a dropped connection does not restart the
  episode.
- A transcript belongs to the engine and model identity that produced it. A different LiteRT
  version, a different graph or a changed cue rule invalidates the stored transcript and the ad
  analysis built from it, so a model swap is followed by a full re-transcription of the library. That
  is intended: old cues need not agree with the new engine's words.
- The queue is durable and serialised, but the work that drains it is one unique chain, so a
  *retrying* head blocks whatever is appended behind it. Work-level backoff is therefore LINEAR at 1
  minute, and the real retry schedule is the row's `next_attempt_at`. Never put exponential backoff
  on `TranscriptAsrWorker`: at 15 minutes it parked four ready episodes behind one deferral for two
  hours, which is why an added episode looked like it was never queued. A pass that throws before it
  can record its own failure marks the row failed (`crashed: …`) so one bad episode cannot starve the
  ones behind it. `resumeInterruptedWork` re-enqueues both workers at launch and drops a chain
  inherited from a previous process, because a force-stop cancels the requests while the rows stay.
- `TranscriptAsrWorker` logs `Recognition queue: pending=N due=M` on every run; `due=0` means the
  retry is the row's own retry time, not a stuck worker.
- Recognition is still minutes of CPU on a long episode, so `TranscriptAsrWorker` promotes itself to
  a `dataSync` foreground service with a quiet notification. An ordinary WorkManager job is stopped
  by the system after a few minutes and loses the pass in flight — a `JobCancellationException` in
  `AdAnalysisWorker` means exactly that, not a recognition error, and the pass resumes from its last
  stored cue.
- Logging: `ParakeetStats` writes the trace under the `ParakeetStt` tag. Progress lines carry
  `chars=`, `at=`, `elapsed=`, `audio=`, `rtf=` and `segments=`; the pass line is
  `pass: model_load_ms=… audio_ms=… wall_ms=… rtf=… segments=… cues=… segment_ms_median=…
  backend=… windows=… mel_ms=… encode_ms=… decode_ms=… model_rtf=…`. `rtf` is audio time over wall
  time, so above 1 is faster than real time; `model_rtf` counts only the graph, so the CPU-only mel
  front end cannot flatter an accelerator. The logged numbers, not an expectation, are the
  measurement; `app/src/androidTest/.../ParakeetBackendBenchmarkTest.kt` runs all three backends over
  one clip and logs each.
- Where: `modules/services/repositories/.../repositories/ads/text/parakeet/` for the engine (the
  LiteRT part is in its `litert/` subpackage) and `dev/adaware/next/parakeet/` for the model scripts
  (`download-model.ps1`, `install-model.ps1`, `install-app.ps1`) plus the log/RTF field reference in
  its `README.md`.

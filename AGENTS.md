# AGENTS.md

This file provides guidance to AI coding assistants (Claude Code, Cursor, Windsurf, etc.) when working with code in this repository.

## Common Commands

### Building
```bash
# Build debug APK (uses staging servers with .debug suffix)
./gradlew :app:assembleDebug

# Build debugProd APK (development build pointing to production servers)
./gradlew :app:assembleDebugProd

# Build prototype (minified pre-release build)
./gradlew :app:assemblePrototype

# Build release APK
./gradlew :app:assembleRelease

# Install debug build on connected device
./gradlew :app:installDebugProd
```

**Device builds on this machine use `debugProd`, always.** It points at the
production servers, so the installed app sees the real library and account; the
staging `debug` build does not. Both carry the `.debug` application id, so
installing one replaces the other:

```bash
./gradlew :app:assembleDebugProd && adb install -r app/build/outputs/apk/debugProd/app-debugProd.apk
```

### Testing
```bash
# Run unit tests for the app module
./gradlew :app:testDebugUnitTest

# Run unit tests for a specific feature module
./gradlew :modules:features:search:testDebugUnitTest

# Run instrumentation tests on connected device
./gradlew :app:connectedDebugAndroidTest

# Run instrumentation tests for a specific module
./gradlew :modules:features:search:connectedDebugAndroidTest
```

### Code Quality
```bash
# Check code formatting (must pass before merge)
./gradlew spotlessCheck

# Auto-format code
./gradlew spotlessApply

# Install git hooks to run spotless on pre-commit
./gradlew installGitHooks

# Run lint on all application modules
./gradlew aggregatedLintRelease

# Run dependency analysis
./gradlew buildHealth
```

### Build Variants
- **debug**: Development with `.debug` suffix, uses staging servers (*.pocketcasts.net)
- **debugProd**: Development build pointing to production servers (*.pocketcasts.com)
- **prototype**: Minified pre-release build with ProGuard/R8
- **release**: Production release build with minification and shrinking

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

Ad detection reads an episode's transcript, and a transcript the app does not already have is
produced on device by Moonshine (`ai.moonshine:moonshine-voice`, ONNX Runtime, CPU only). Code and
models are MIT.

- Models: **tiny streaming** for English and Spanish, requested with the architecture passed
  explicitly (`MOONSHINE_MODEL_ARCH_TINY_STREAMING`); the catalog's default for `en` is *medium* and
  for `es` is *small*, so relying on the default silently downloads hundreds of megabytes.
  Downloaded on demand (unmetered only) into `noBackupFilesDir/moonshine-models`, never bundled.
- On a device, a private build gets them with `dev/adaware/next/moonshine/install-app.ps1` (builds `:app:assembleDebugProd`, `adb install -r`, then pushes the models with `install-models.ps1`; `-SkipBuild`/`-SkipModels` skip either half).
- Language: chosen from `Podcast.podcastLanguage` by primary subtag (`es-us` -> `es`). Any other
  language makes the engine unavailable for that episode and the audio analyzer covers it.
- Timestamps: word-level times come from the model's cross-attention DTW. They are **English only**
  (the Spanish bundle ships no attention decoder), so cue building must also work from line-level
  `startTime`/`duration`. Cues are stored as WebVTT through `LocalTranscriptStore`, one cue per
  complete streaming line, and the old ML Kit energy-region reconstruction is gone for good.
  Measured against Whisper, Moonshine's word times sit a constant ~0.25 s late; at hand-labelled ad
  boundaries that still lands within 0.3 s at p90, so do not "fix" the offset without new evidence.
- Recognition only fills a gap: an episode whose show or our servers publish a transcript is analysed
  from that transcript and never starts a pass. `FORCE_ON_DEVICE_TRANSCRIPT` (developer settings, off
  by default) ignores published transcripts so the engine can be exercised on real episodes.
- Recognition is minutes of CPU (about 3x realtime here), so `TranscriptAdAnalysisWorker` promotes
  itself to a `dataSync` foreground service with a quiet notification. An ordinary WorkManager job
  is stopped by the system after a few minutes and loses the whole pass — a `JobCancellationException`
  in `AdAnalysisWorker` means exactly that, not a recognition error. `moonshineTranscribeStream:
  transcription error: 0` in logcat is the JNI logging its success code.
- Where: `modules/services/repositories/.../repositories/ads/text/moonshine/`. The decisions, the
  laptop comparison against the Whisper corpus and the integration notes live in
  `dev/adaware/next/moonshine/`; read `RESEARCH.md` before changing the engine, the model choice or
  the cue rules, and `REPORT.md` before trusting a timestamp change.
- The library merges `RECORD_AUDIO` and a microphone activity into the manifest; both are stripped
  in `app/src/main/AndroidManifest.xml` because the app never records audio.

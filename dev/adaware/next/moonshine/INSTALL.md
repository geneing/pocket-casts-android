# Installing the speech models on a private build

The app is private-use for now: it is built and installed from this laptop, and the Moonshine
models are copied onto the phone as part of the install. Bundling them in the APK was rejected
(43 MiB / 31 MiB in the base APK for a gated feature, and Spanish would still need the download
path), so the two paths — install push and download fallback — write to the same directory.

## One command

```powershell
.\dev\adaware\next\moonshine\install-app.ps1
```

That builds `:app:assembleDebugProd`, installs it with `adb install -r`, then pushes the models.
`-SkipBuild` re-pushes models onto the app that is already installed; `-SkipModels` only builds and
installs. Multi-device hosts need `-Serial <serial>`.

The build runs on Windows (`gradlew.bat`); the script sets `ANDROID_HOME` when it is not set and
uses `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`.

## Where the files go

`ModelCache` in the AAR owns the layout, and the app must not fight it:

```
/data/user/0/au.com.shiftyjelly.pocketcasts.debug/   # package of the .debug application id
└── no_backup/moonshine-models/                     # ModelCache.defaultRoot(context)
    ├── stt-en-2-wt/    # ModelCache.key(spec) for en, tiny-streaming (arch 2), word timestamps
    │   ├── encoder.ort  decoder_kv.ort  decoder_kv_with_attention.ort  cross_kv.ort
    │   └── adapter.ort  frontend.model.ort  frontend.weights.ort  tokenizer.bin
    │       streaming_config.json
    └── stt-es-2/       # es, tiny-streaming, no word timestamps
        └── (the same files minus decoder_kv_with_attention.ort)
```

The keys are `MoonshineLanguage.cacheKey` in the app; `MoonshineSpeechModelTest` asserts the exact
strings so a change to the model policy fails a unit test instead of silently orphaning the pushed
files. Only English ships `decoder_kv_with_attention.ort`, which is what produces word timestamps.

`no_backup` is deliberate: 74 MiB of re-downloadable weights must never be backed up. The models
are pushed with `adb push` into `/data/local/tmp` and copied across with `run-as <package> cp`,
because app-private storage is not writable by `adb push` and `run-as` works without root on a
debuggable build. Files already on the device with the same size are skipped, so re-running the
script is cheap.

## What the app does when the files are there (and when they are not)

- Present: one `AssetDownloader.isModelPresent` check (a native dependency manifest plus file
  existence — no network) and the pass goes straight to inference. Nothing is downloaded.
- Missing and on an unmetered network: logs `Moonshine ... model missing ... downloading`, reports
  progress through WorkManager (`model_bytes_done` / `model_bytes_total` / `model_percent`), and
  transcribes.
- Missing and on a metered network: the pass returns `Unavailable` and the worker returns
  `Result.retry()`, so the 43 MiB download waits for Wi-Fi instead of using the data allowance.
  The log names the directory the files belong in.

## Re-pushing after a model change

```powershell
.\dev\adaware\next\moonshine\install-models.ps1 -Source .\dev\adaware\next\moonshine\models
```

Bumping the model, the architecture or the word-timestamp policy changes `cacheKey` and/or
`MoonshineModels.engineVersion`, so old directories are ignored and every on-device transcript is
rebuilt. Delete the stale directory from `no_backup/moonshine-models/` afterwards if space matters.

## Checks that need a device

- `adb shell run-as au.com.shiftyjelly.pocketcasts.debug ls no_backup/moonshine-models/stt-en-2-wt`
  lists the pushed files.
- A first analysis on a downloaded English episode logs one `MoonshineStt` line with
  `model_load_ms`, `rtf`, `lines`, `cues` and the alignment medians; `rtf` above 1 means the pass
  ran faster than real time.
- `adb shell dumpsys package au.com.shiftyjelly.pocketcasts.debug | findstr RECORD_AUDIO` must find
  nothing: the merged permission is stripped from the manifest because the app never records.

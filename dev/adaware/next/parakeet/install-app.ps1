# Builds, installs and seeds a private-use build on an attached device.
#
# This is the whole loop for the ad-skipping work on this laptop:
#   1. assembleDebugProd (production servers, ".debug" application id, debuggable)
#   2. adb install -r
#   3. make sure the Parakeet bundle is on this machine (download-model.ps1 when it is missing)
#   4. push it into the app's private model directory (install-model.ps1)
#
# Step 4 is what keeps the app from downloading a 586 MiB bundle on first use; the download stays
# as a fallback for a device that does not have it. The push is skipped when the device copy
# already has the right size, so re-running with -SkipBuild is cheap.
#
# The application id is shared by the staging "debug" and the production "debugProd" build, so
# building the other one replaces this app and wipes its data: the model, the signed-in account and
# the downloaded episodes all go, and the next run pushes the bundle again.
#
# Example:
#   .\dev\adaware\next\parakeet\install-app.ps1
#   .\dev\adaware\next\parakeet\install-app.ps1 -SkipBuild   # app unchanged, bundle re-checked
#   .\dev\adaware\next\parakeet\install-app.ps1 -SkipModels  # app only

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipModels,
    [string]$Package = "au.com.shiftyjelly.pocketcasts.debug",
    [string]$Serial,

    [string]$Adb = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

# adb writes install progress to stderr, which PowerShell 5.1 turns into an error record; with
# "Stop" a successful install would abort the script. Every adb call is checked with $LASTEXITCODE.
$ErrorActionPreference = "Continue"

# dev/adaware/next/parakeet -> repository root (four levels up: parakeet, next, adaware, dev).
$repoRoot = (& git -C $PSScriptRoot rev-parse --show-toplevel)
if ($LASTEXITCODE -ne 0 -or -not $repoRoot) {
    $repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
}
$apk = Join-Path $repoRoot "app/build/outputs/apk/debugProd/app-debugProd.apk"

# Gradle always runs on Windows here, and the project has no local.properties.
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}

$adbTarget = @()
if ($Serial) { $adbTarget = @("-s", $Serial) }

if (-not $SkipBuild) {
    Write-Host "Building :app:assembleDebugProd"
    Push-Location $repoRoot
    try {
        & .\gradlew.bat :app:assembleDebugProd --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $apk)) {
    throw "No APK at $apk. Run without -SkipBuild first."
}

if (-not (Test-Path $Adb)) {
    throw "adb not found at $Adb. Install the Android SDK platform-tools or pass -Adb <path>."
}

$devices = & $Adb @adbTarget devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if (-not $devices) {
    throw "No device attached. Connect the phone, enable USB debugging and confirm it appears in `"$Adb devices`"."
}
if (-not $Serial -and $devices.Count -gt 1) {
    throw "More than one device attached. Pass -Serial <serial> (see `"$Adb devices`")."
}

Write-Host "Installing $apk"
& $Adb @adbTarget install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed with exit code $LASTEXITCODE"
}

if (-not $SkipModels) {
    # The bundle is not in git (586 MiB), so a fresh checkout has to fetch it once. Doing that here,
    # rather than leaving it to a separate command, is what makes plain `install-app.ps1` leave a
    # phone that can transcribe: without the bundle the app waits for Wi-Fi and downloads it itself.
    $modelFile = "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm"
    $modelBytes = [long]614588752
    $modelSource = Join-Path (Join-Path $PSScriptRoot "model") $modelFile
    $modelSize = if (Test-Path $modelSource) { (Get-Item -LiteralPath $modelSource).Length } else { -1 }
    if ($modelSize -ne $modelBytes) {
        Write-Host ""
        Write-Host ("Speech model is not on this machine yet (found {0} of {1} bytes); fetching it once" -f $modelSize, $modelBytes)
        # A PowerShell script reports failure by throwing, not by an exit code, and $LASTEXITCODE
        # would still hold whatever the previous adb or git call left there.
        try {
            & (Join-Path $PSScriptRoot "download-model.ps1")
        } catch {
            throw "Downloading the speech model failed: $($_.Exception.Message)"
        }
    }

    $installModel = Join-Path $PSScriptRoot "install-model.ps1"
    # Named splatting: array splatting binds positionally, which would pass "-Adb" as the serial.
    $modelArgs = @{ Package = $Package; Adb = $Adb }
    if ($Serial) { $modelArgs["Serial"] = $Serial }
    try {
        & $installModel @modelArgs
    } catch {
        throw "Pushing the speech model failed: $($_.Exception.Message)"
    }
}

Write-Host ""
Write-Host "Installed $Package. Launch it; the first analysis finds the model in place."

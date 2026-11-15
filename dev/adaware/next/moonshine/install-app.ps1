# Builds, installs and seeds a private-use build on an attached device.
#
# This is the whole loop for the ad-skipping work on this laptop:
#   1. assembleDebugProd (production servers, ".debug" application id, debuggable)
#   2. adb install -r
#   3. push the Moonshine models into the app's private model cache (install-models.ps1)
#
# Step 3 is what keeps the app from downloading 43 MiB / 31 MiB of weights on first use; the
# download stays as a fallback for a device that has no models.
#
# Example:
#   .\dev\adaware\next\moonshine\install-app.ps1
#   .\dev\adaware\next\moonshine\install-app.ps1 -SkipBuild   # app unchanged, models re-pushed
#   .\dev\adaware\next\moonshine\install-app.ps1 -SkipModels  # app only

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipModels,
    [string]$Package = "au.com.shiftyjelly.pocketcasts.debug",
    [string]$Serial,

    [string]$Adb = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

$ErrorActionPreference = "Stop"

# dev/adaware/next/moonshine -> repository root.
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
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
    $installModels = Join-Path $PSScriptRoot "install-models.ps1"
    $arguments = @("-Package", $Package, "-Adb", $Adb)
    if ($Serial) { $arguments += @("-Serial", $Serial) }
    & $installModels @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Pushing the speech models failed with exit code $LASTEXITCODE"
    }
}

Write-Host ""
Write-Host "Installed $Package. Launch it once; the first analysis finds the models in place."

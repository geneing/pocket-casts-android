# Pushes the Moonshine speech models onto a device for a private build.
#
# The app never bundles the models: it looks for them at
#   <noBackupFilesDir>/moonshine-models/<ModelCache.key(spec)>
# which is /data/user/0/<package>/no_backup/moonshine-models/stt-en-2-wt (English, word
# timestamps on) and .../stt-es-2 (Spanish, no attention decoder). This script writes exactly
# those two directories, so the app's presence check finds the files and never downloads.
#
# Re-running the script is cheap: files that are already on the device with the same size are
# skipped. It is safe to run before the app has ever launched (the app creates its own data
# directory at install time, and this script creates the model directories under it).
#
# Example:
#   .\dev\adaware\next\moonshine\install-models.ps1
#   .\dev\adaware\next\moonshine\install-models.ps1 -Package au.com.shiftyjelly.pocketcasts.debug -Verbose

[CmdletBinding()]
param(
    # Private builds are debugProd, which carries the ".debug" application id suffix.
    [string]$Package = "au.com.shiftyjelly.pocketcasts.debug",

    # Directory holding <language>/<bundle>/ trees, as downloaded by download_models.py.
    [string]$Source = (Join-Path $PSScriptRoot "models"),

    # Device serial, only needed when more than one device is attached.
    [string]$Serial,

    [string]$Adb = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

# adb writes its progress (and some of its errors) to stderr, which PowerShell 5.1 turns into an
# error record; with "Stop" that would abort a perfectly good push. Every adb call below is checked
# through $LASTEXITCODE instead, so failures still stop the script.
$ErrorActionPreference = "Continue"

# Mirrors MoonshineLanguage in the app: tiny-streaming (arch 2), word timestamps for English only,
# which is what ModelCache.key(spec) turns into these two directory names. Keep in step with
# MoonshineLanguage.cacheKey; MoonshineSpeechModelTest asserts both strings.
$Languages = [ordered]@{
    "en" = "stt-en-2-wt"
    "es" = "stt-es-2"
}

# Only English has an attention decoder; its absence would still "work" but without word times.
$RequiredFiles = @{
    "stt-en-2-wt" = @("encoder.ort", "decoder_kv.ort", "decoder_kv_with_attention.ort", "cross_kv.ort", "adapter.ort", "frontend.model.ort", "frontend.weights.ort", "tokenizer.bin", "streaming_config.json")
    "stt-es-2"    = @("encoder.ort", "decoder_kv.ort", "cross_kv.ort", "adapter.ort", "frontend.model.ort", "frontend.weights.ort", "tokenizer.bin", "streaming_config.json")
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $result = & $script:Adb @script:AdbTarget @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed:`n$($result -join "`n")"
    }
    return $result
}

function Invoke-RunAs {
    param([string]$Command)
    # One string, so the device shell parses it and PowerShell never does.
    $result = & $script:Adb @script:AdbTarget shell "run-as $Package $Command" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "run-as $Package $Command failed:`n$($result -join "`n")"
    }
    return ($result -join "`n").Trim()
}

function Get-DeviceSize {
    param([string]$RelativePath)
    # A file that is not there yet makes adb exit non-zero with a message on stderr, which the
    # global Stop preference would turn into a terminating error. That is an expected answer here.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:Adb @script:AdbTarget shell "run-as $Package stat -c %s $RelativePath" 2>$null
        if ($LASTEXITCODE -ne 0) { return -1 }
        $text = ($output -join "").Trim()
        if ($text -match '^\d+$') { return [long]$text }
        return -1
    } finally {
        $ErrorActionPreference = $previous
    }
}

if (-not (Test-Path $script:Adb)) {
    throw "adb not found at $Adb. Install the Android SDK platform-tools or pass -Adb <path>."
}

$script:Adb = $Adb
$script:AdbTarget = @()
if ($Serial) { $script:AdbTarget = @("-s", $Serial) }

$devices = & $Adb @script:AdbTarget devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if (-not $devices) {
    throw "No device attached. Connect the phone, enable USB debugging and confirm it appears in `"$Adb devices`"."
}
if (-not $Serial -and $devices.Count -gt 1) {
    throw "More than one device attached. Pass -Serial <serial> (see `"$Adb devices`")."
}

# The app-private data directory. run-as works because debugProd is debuggable; it needs no root.
$appRoot = Invoke-RunAs "pwd"
if ($appRoot -notmatch '^/data/') {
    throw "Could not resolve the data directory of $Package (run-as said '$appRoot'). Is the app installed?"
}
$deviceModelsRoot = "no_backup/moonshine-models"

Write-Host "Package : $Package"
Write-Host "Device  : $($appRoot)"
Write-Host "Source  : $Source"

$stagingRoot = "/data/local/tmp/moonshine-install"
$pushed = 0
$skipped = 0

foreach ($language in $Languages.Keys) {
    $cacheKey = $Languages[$language]
    $languageSource = Join-Path $Source $language
    if (-not (Test-Path $languageSource)) {
        Write-Warning "No $language models under $languageSource, skipping $language"
        continue
    }

    # <source>/<language>/<bundle>, e.g. models/en/quantized_26_11_02
    $bundles = Get-ChildItem -Path $languageSource -Directory | Sort-Object Name -Descending
    if (-not $bundles) {
        Write-Warning "No bundle directory under $languageSource, skipping $language"
        continue
    }
    if ($bundles.Count -gt 1) {
        Write-Verbose "Several $language bundles under $languageSource, using $($bundles[0].Name)"
    }
    $bundle = $bundles[0]

    $missing = $RequiredFiles[$cacheKey] | Where-Object { -not (Test-Path (Join-Path $bundle.FullName $_)) }
    if ($missing) {
        Write-Warning "$($bundle.FullName) is missing $(($missing -join ', ')), the app will report the model as absent"
    }

    $relativeTarget = "$deviceModelsRoot/$cacheKey"
    Invoke-RunAs "mkdir -p $relativeTarget" | Out-Null
    $stagingDir = "$stagingRoot/$cacheKey"
    Invoke-Adb shell "rm -rf $stagingDir" | Out-Null
    Invoke-Adb shell "mkdir -p $stagingDir" | Out-Null

    Write-Host ""
    Write-Host "$language -> $relativeTarget  ($($bundle.Name))"

    foreach ($file in Get-ChildItem -Path $bundle.FullName -File | Sort-Object Name) {
        $devicePath = "$relativeTarget/$($file.Name)"
        $deviceSize = Get-DeviceSize $devicePath
        if ($deviceSize -eq $file.Length) {
            Write-Verbose "  $($file.Name) already there ($($file.Length) bytes)"
            $skipped++
            continue
        }

        # adb push cannot write into app-private storage, so stage under /data/local/tmp and copy
        # across with run-as, which runs as the app's own uid.
        Invoke-Adb push $file.FullName "$stagingDir/$($file.Name)" | Out-Null
        Invoke-RunAs "cp $stagingDir/$($file.Name) $devicePath" | Out-Null

        $copiedSize = Get-DeviceSize $devicePath
        if ($copiedSize -ne $file.Length) {
            throw "Copying $($file.Name) failed: device has $copiedSize bytes, expected $($file.Length)"
        }
        Write-Host ("  pushed {0} ({1:N1} MB)" -f $file.Name, ($file.Length / 1MB))
        $pushed++
    }

    Invoke-Adb shell "rm -rf $stagingDir" | Out-Null

    $usedKb = (Invoke-RunAs "du -sk $relativeTarget") -split "\s+" | Select-Object -First 1
    Write-Host ("  on device: {0:N1} MB in {1}" -f ([double]$usedKb / 1024), $relativeTarget)
}

Write-Host ""
Write-Host "Done: $pushed file(s) pushed, $skipped already present."
if ($pushed -eq 0 -and $skipped -eq 0) {
    throw "Nothing was installed. Check that $Source contains <language>/<bundle> directories."
}
Write-Host "The app finds the models on its next pass; no download is attempted while they are present."

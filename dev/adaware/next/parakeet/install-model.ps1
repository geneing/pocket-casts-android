# Pushes the Parakeet Redux files onto a device for a private build.
#
# The app never bundles the model: it looks for four files at
#   <noBackupFilesDir>/parakeet-model/parakeet_redux_mel_10s.tflite
#   <noBackupFilesDir>/parakeet-model/parakeet_redux_decoder_i8.tflite
#   <noBackupFilesDir>/parakeet-model/parakeet_redux_encoder_10s_i4.tflite
#   <noBackupFilesDir>/parakeet-model/tokenizer.json
# which is /data/user/0/<package>/no_backup/parakeet-model/ for the ".debug" application id. This
# script writes those four files and nothing else; LiteRT compiles nothing for them, so there is no
# cache directory to seed or clear.
#
# -WithF16 also pushes the float16 encoder (1.1 GiB). It is not part of a normal install: only a pass
# pinned to the GPU or NPU asks for it.
#
# Re-running the script is cheap: a copy that is already on the device with the right size is
# skipped. It is safe to run before the app has ever launched (the app's data directory exists from
# install time, and this script creates the model directory under it).
#
# Example:
#   .\dev\adaware\next\parakeet\install-model.ps1
#   .\dev\adaware\next\parakeet\install-model.ps1 -WithF16
#   .\dev\adaware\next\parakeet\install-model.ps1 -Only tokenizer.json -Verbose

[CmdletBinding()]
param(
    # Private builds are debugProd, which carries the ".debug" application id suffix.
    [string]$Package = "au.com.shiftyjelly.pocketcasts.debug",

    # Directory holding the files, as produced by download-model.ps1.
    [string]$Source = (Join-Path $PSScriptRoot "model"),

    # Also push the float16 encoder, needed only for a pass pinned to the GPU or NPU.
    [switch]$WithF16,

    # Limit the push to one file, by name, e.g. "tokenizer.json".
    [string]$Only,

    # Device serial, only needed when more than one device is attached.
    [string]$Serial,

    [string]$Adb = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

# adb writes its progress (and some of its errors) to stderr, which PowerShell 5.1 turns into an
# error record; with "Stop" that would abort a perfectly good push. Every adb call below is checked
# through $LASTEXITCODE instead, so failures still stop the script.
$ErrorActionPreference = "Continue"

# Mirrors ParakeetModel in the app: the four CPU files with exact sizes, plus the optional float16
# encoder. The app treats a file of any other size as absent, so both the local and the device copy
# are checked against it. Smallest first, so an interrupted run still leaves the cheap files usable.
$files = @(
    @{ Name = "tokenizer.json"; Bytes = [long]1159960 },
    @{ Name = "parakeet_redux_mel_10s.tflite"; Bytes = [long]1608068 },
    @{ Name = "parakeet_redux_decoder_i8.tflite"; Bytes = [long]17866768 },
    @{ Name = "parakeet_redux_encoder_10s_i4.tflite"; Bytes = [long]345895584 }
)
if ($WithF16) {
    $files += @{ Name = "parakeet_redux_encoder_10s_f16.tflite"; Bytes = [long]1194680560 }
}
if ($Only) {
    $files = @($files | Where-Object { $_.Name -eq $Only })
    if (-not $files) { throw "-Only $Only is not one of the files this script pushes." }
}
$deviceModelRoot = "no_backup/parakeet-model"

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

foreach ($file in $files) {
    $sourceFile = Join-Path $Source $file.Name
    if (-not (Test-Path $sourceFile)) {
        throw "No $($file.Name) at $sourceFile. Run download-model.ps1 first, or pass -Source <directory>."
    }
    $sourceSize = (Get-Item -LiteralPath $sourceFile).Length
    if ($sourceSize -ne $file.Bytes) {
        throw "$sourceFile is $sourceSize bytes, but the app only accepts $($file.Bytes). Delete it and re-run download-model.ps1."
    }
}

if (-not (Test-Path $script:Adb) -and -not (Test-Path $Adb)) {
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

Write-Host "Package : $Package"
Write-Host "Device  : $appRoot"
Write-Host "Source  : $Source"

# Staging goes through /data/local/tmp: adb push cannot write into app-private storage, and run-as
# cannot read the app's own external files directory, so tmp is the one place both sides can reach.
$stagingDir = "/data/local/tmp/parakeet-install"
Invoke-RunAs "mkdir -p $deviceModelRoot" | Out-Null

foreach ($file in $files) {
    $sourceFile = Join-Path $Source $file.Name
    $devicePath = "$deviceModelRoot/$($file.Name)"
    $stagingFile = "$stagingDir/$($file.Name)"

    # A copy left behind by an interrupted run can never be resumed, and the app would only ignore it.
    Invoke-RunAs "rm -f $devicePath.part" | Out-Null

    $deviceSize = Get-DeviceSize $devicePath
    if ($deviceSize -eq $file.Bytes) {
        Write-Host ""
        Write-Host ("Already on the device: $devicePath ({0:N1} MiB), nothing to push" -f ($file.Bytes / 1MB))
        continue
    }
    if ($deviceSize -ge 0) {
        Write-Verbose "Device has $deviceSize bytes at $devicePath, replacing it"
    }

    # adb push cannot write into app-private storage, so stage outside and copy across with run-as,
    # which runs as the app's own uid.
    Invoke-Adb shell "rm -rf $stagingDir" | Out-Null
    Invoke-Adb shell "mkdir -p $stagingDir" | Out-Null

    Write-Host ""
    Write-Host ("Pushing {0} ({1:N1} MiB). This takes a while over USB and looks silent in between." -f $file.Name, ($file.Bytes / 1MB))
    Invoke-Adb push $sourceFile $stagingFile | Out-Null

    # Copy to ".part" and rename only after the size check, so a half-finished copy is never a file
    # the app might try to load.
    Invoke-RunAs "cp $stagingFile $devicePath.part" | Out-Null
    $copiedSize = Get-DeviceSize "$devicePath.part"
    if ($copiedSize -ne $file.Bytes) {
        throw "Copying $($file.Name) failed: device has $copiedSize bytes in $devicePath.part, expected $($file.Bytes)"
    }
    Invoke-RunAs "mv $devicePath.part $devicePath" | Out-Null
    Invoke-Adb shell "rm -rf $stagingDir" | Out-Null

    $checkSize = Get-DeviceSize $devicePath
    if ($checkSize -ne $file.Bytes) {
        throw "After the move the device has $checkSize bytes at $devicePath, expected $($file.Bytes)"
    }
    Write-Host ("  installed {0}" -f $devicePath)
}

$usedKb = (Invoke-RunAs "du -sk $deviceModelRoot") -split "\s+" | Select-Object -First 1
Write-Host ("  on device: {0:N1} MiB in {1}" -f ([double]$usedKb / 1024), $deviceModelRoot)
Write-Host ""
Write-Host "The app finds the files on its next pass; no download is attempted while they are there."

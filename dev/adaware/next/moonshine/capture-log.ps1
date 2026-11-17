# Streams logcat for the installed Pocket Casts build to the console and to a file.
#
# Handy while exercising on-device ad detection: start the app, then run this, and share
# moonshine.log.
#
# Example:
#   .\capture_log.ps1
#   .\capture_log.ps1 -Clear                      # drop the existing buffer first
#   .\capture_log.ps1 -LogPath .\logs\player.log  # write somewhere else
#   .\capture_log.ps1 -Serial R58M12345           # pick a device explicitly
#
# Filtering is by process id, so it captures everything the app logs, but only from the process
# that was running when the capture started. If the app is restarted, run the script again.

[CmdletBinding()]
param(
    [string]$Package = "au.com.shiftyjelly.pocketcasts.debug",
    [string]$LogPath = (Join-Path $PSScriptRoot "moonshine.log"),
    [string]$Serial,

    # Clear the device's log buffer before capturing, so the file starts at the current moment.
    [switch]$Clear,

    [string]$Adb = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
)

# adb writes some output to stderr, which PowerShell 5.1 turns into an error record; with
# "Stop" a successful call would abort the script. Every adb call is checked with $LASTEXITCODE.
$ErrorActionPreference = "Continue"

if (-not (Test-Path $Adb)) {
    throw "adb not found at $Adb. Install the Android SDK platform-tools or pass -Adb <path>."
}

$adbTarget = @()
if ($Serial) { $adbTarget = @("-s", $Serial) }

$devices = & $Adb @adbTarget devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if (-not $devices) {
    throw "No device attached. Connect the phone, enable USB debugging and confirm it appears in `"$Adb devices`"."
}
if (-not $Serial -and $devices.Count -gt 1) {
    throw "More than one device attached. Pass -Serial <serial> (see `"$Adb devices`")."
}

$appPid = (& $Adb @adbTarget shell pidof -s $Package | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $appPid) {
    throw "$Package is not running. Launch the app on the device, then run this script again."
}

if ($Clear) {
    & $Adb @adbTarget logcat -c
    if ($LASTEXITCODE -ne 0) {
        throw "adb logcat -c failed with exit code $LASTEXITCODE"
    }
}

$LogPath = [System.IO.Path]::GetFullPath($LogPath)
$logDir = Split-Path -Parent $LogPath
if ($logDir -and -not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

try {
    ([System.IO.File]::Open($LogPath, "Append", "Write", "Read")).Dispose()
} catch {
    throw "Cannot write to $LogPath. Is another capture still running? ($($_.Exception.Message))"
}

Write-Host "Capturing $Package (pid $appPid) to $LogPath"
Write-Host "Press Ctrl+C to stop."

& $Adb @adbTarget logcat -v time "--pid=$appPid" | Tee-Object -FilePath $LogPath

# Ctrl+C is the normal way to stop the capture and comes back as a non-zero exit code.
if ($LASTEXITCODE -ne 0) {
    Write-Host "Capture ended (adb exit code $LASTEXITCODE). Log written to $LogPath"
} else {
    Write-Host "Log written to $LogPath"
}

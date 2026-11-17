# Downloads the Parakeet speech bundle into dev/adaware/next/parakeet/model/.
#
# The app never bundles the model: it looks for one file of an exact size at
#   <noBackupFilesDir>/parakeet-model/parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm
# and treats any other size as absent. This script produces that file locally; install-model.ps1
# pushes it to a device. Keep the name and the byte count in step with ParakeetModel.file().
#
# The download is resumable: curl writes to <name>.part and the bytes already on disk are kept if
# the connection drops, so re-running picks up where it stopped. Only a file of exactly the right
# size is ever renamed to the real model name, which is also the rule the app applies.
#
# Example:
#   .\dev\adaware\next\parakeet\download-model.ps1
#   .\dev\adaware\next\parakeet\download-model.ps1 -Destination D:\models

[CmdletBinding()]
param(
    # Directory the bundle is written to. Created when it does not exist.
    [string]$Destination = (Join-Path $PSScriptRoot "model"),

    [string]$Url = "https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/resolve/main/parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm"
)

# curl's exit codes are checked by hand below; with "Stop" a retried transfer would still throw.
$ErrorActionPreference = "Continue"

# Mirrors ParakeetModel in the app: one file, one exact size.
$ModelFile = "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm"
$ModelBytes = [long]614588752

function Get-Size {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return -1 }
    return (Get-Item -LiteralPath $Path).Length
}

$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) {
    throw "curl.exe not found on PATH. It ships with Windows 10 1803 and later; a Git for Windows install also has one."
}

if (-not (Test-Path $Destination)) {
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
}
$Destination = [System.IO.Path]::GetFullPath($Destination)
$target = Join-Path $Destination $ModelFile
$partial = "$target.part"

$targetSize = Get-Size $target
if ($targetSize -eq $ModelBytes) {
    Write-Host "Already downloaded: $target"
    Write-Host ("  {0:N0} bytes ({1:N1} MiB)" -f $targetSize, ($targetSize / 1MB))
    return
}
if ($targetSize -ge 0) {
    # A different bundle, or a write that died before the size check. It cannot be resumed into a
    # known-good file, and keeping it under the model's name would look installed to the app.
    Write-Warning "$target is $targetSize bytes, expected $ModelBytes; removing it"
    Remove-Item -LiteralPath $target -Force
}

Write-Host "Downloading $Url"
Write-Host "  -> $target"
Write-Host ("  expected {0:N0} bytes ({1:N1} MiB)" -f $ModelBytes, ($ModelBytes / 1MB))

# -C - resumes from whatever .part already holds; -L follows Hugging Face's redirect to its CDN.
& curl.exe -L -C - --fail --retry 3 --retry-delay 5 --output $partial $Url
if ($LASTEXITCODE -ne 0) {
    $kept = Get-Size $partial
    throw ("curl exited with $LASTEXITCODE. {0:N0} bytes are kept in $partial; run the script again to resume." -f [math]::Max($kept, 0))
}

$partialSize = Get-Size $partial
if ($partialSize -ne $ModelBytes) {
    if ($partialSize -gt $ModelBytes) {
        # Too long means the file is not this model at all, so resuming cannot fix it.
        Remove-Item -LiteralPath $partial -Force
        throw "Downloaded $partialSize bytes but this model is $ModelBytes bytes. Removed the file; check -Url."
    }
    throw ("Download stopped at {0:N0} of {1:N0} bytes. $partial is kept; run the script again to resume." -f $partialSize, $ModelBytes)
}

Move-Item -LiteralPath $partial -Destination $target -Force
if ((Get-Size $target) -ne $ModelBytes) {
    throw "Moving $partial to $target did not produce a $ModelBytes byte file."
}

Write-Host ""
Write-Host ("Downloaded {0:N0} bytes ({1:N1} MiB) to {2}" -f $ModelBytes, ($ModelBytes / 1MB), $target)
Write-Host "Push it with install-model.ps1, or run install-app.ps1 to build, install and push in one step."

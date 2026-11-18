# Downloads the Parakeet Redux files the app runs into dev/adaware/next/parakeet/model/.
#
# The app never bundles the model: it looks for four files of exact sizes at
#   <noBackupFilesDir>/parakeet-model/parakeet_redux_mel_10s.tflite
#   <noBackupFilesDir>/parakeet-model/parakeet_redux_decoder_i8.tflite
#   <noBackupFilesDir>/parakeet-model/parakeet_redux_encoder_10s_i4.tflite
#   <noBackupFilesDir>/parakeet-model/tokenizer.json
# and treats any other size as absent. This script produces those files locally;
# install-model.ps1 pushes them to a device. Keep the names and byte counts in step with
# ParakeetModel.MEL, ParakeetModel.DECODER, ParakeetModel.ENCODER_I4 and ParakeetModel.TOKENIZER.
#
# These four are the CPU set and what every pass uses: the mel graph, the int8 prediction/joint
# networks, the 4-bit encoder and the tokenizer, 366 MB together. -WithF16 also fetches the float16
# encoder, which is only asked for by a pass pinned to the GPU or NPU and is not part of a normal
# install.
#
# Every download is resumable: curl writes to <name>.part and the bytes already on disk are kept if
# the connection drops, so re-running picks up where it stopped. Only a file of exactly the right
# size is ever renamed to the real name, which is also the rule the app applies.
#
# Example:
#   .\dev\adaware\next\parakeet\download-model.ps1
#   .\dev\adaware\next\parakeet\download-model.ps1 -WithF16
#   .\dev\adaware\next\parakeet\download-model.ps1 -Destination D:\models

[CmdletBinding()]
param(
    # Directory the files are written to. Created when it does not exist.
    [string]$Destination = (Join-Path $PSScriptRoot "model"),

    # Also fetch the float16 encoder, needed only for a pass pinned to the GPU or NPU.
    [switch]$WithF16,

    # Only fetch the named files, e.g. "tokenizer.json" or the float16 encoder.
    [string[]]$Names
)

# curl's exit codes are checked by hand below; with "Stop" a retried transfer would still throw.
$ErrorActionPreference = "Continue"

# Mirrors ParakeetModel in the app: the four CPU files, plus one optional accelerator encoder.
$tokenizerFile = "tokenizer.json"
$melFile = "parakeet_redux_mel_10s.tflite"
$decoderFile = "parakeet_redux_decoder_i8.tflite"
$encoderFile = "parakeet_redux_encoder_10s_i4.tflite"
$f16EncoderFile = "parakeet_redux_encoder_10s_f16.tflite"
$repo = "https://huggingface.co/litert-community/parakeet-redux/resolve/main"

$files = @(
    @{ Name = $tokenizerFile; Bytes = [long]1159960; Url = "$repo/$tokenizerFile" },
    @{ Name = $melFile; Bytes = [long]1608068; Url = "$repo/$melFile" },
    @{ Name = $decoderFile; Bytes = [long]17866768; Url = "$repo/$decoderFile" },
    @{ Name = $encoderFile; Bytes = [long]345895584; Url = "$repo/$encoderFile" }
)
if ($WithF16) {
    $files += @{ Name = $f16EncoderFile; Bytes = [long]1194680560; Url = "$repo/$f16EncoderFile" }
}
if ($Names) {
    $known = @($tokenizerFile, $melFile, $decoderFile, $encoderFile, $f16EncoderFile)
    $files = @($files | Where-Object { $Names -contains $_.Name })
    if (-not $files) { throw "-Names must contain at least one of: $($known -join ', ')" }
}

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

foreach ($file in $files) {
    $target = Join-Path $Destination $file.Name
    $partial = "$target.part"
    $bytes = [long]$file.Bytes

    $targetSize = Get-Size $target
    if ($targetSize -eq $bytes) {
        Write-Host "Already downloaded: $target"
        Write-Host ("  {0:N0} bytes ({1:N1} MiB)" -f $targetSize, ($targetSize / 1MB))
        continue
    }
    if ($targetSize -ge 0) {
        # A different export, or a write that died before the size check. It cannot be resumed into
        # a known-good file, and keeping it under the real name would look installed to the app.
        Write-Warning "$target is $targetSize bytes, expected $bytes; removing it"
        Remove-Item -LiteralPath $target -Force
    }

    Write-Host "Downloading $($file.Url)"
    Write-Host "  -> $target"
    Write-Host ("  expected {0:N0} bytes ({1:N1} MiB)" -f $bytes, ($bytes / 1MB))

    # -C - resumes from whatever .part already holds; -L follows Hugging Face's redirect to its CDN.
    & curl.exe -L -C - --fail --retry 3 --retry-delay 5 --output $partial $file.Url
    if ($LASTEXITCODE -ne 0) {
        $kept = Get-Size $partial
        throw ("curl exited with $LASTEXITCODE. {0:N0} bytes are kept in $partial; run the script again to resume." -f [math]::Max($kept, 0))
    }

    $partialSize = Get-Size $partial
    if ($partialSize -ne $bytes) {
        if ($partialSize -gt $bytes) {
            # Too long means the file is not this one at all, so resuming cannot fix it.
            Remove-Item -LiteralPath $partial -Force
            throw "Downloaded $partialSize bytes but $($file.Name) is $bytes bytes. Removed the file; check the URL."
        }
        throw ("Download stopped at {0:N0} of {1:N0} bytes. $partial is kept; run the script again to resume." -f $partialSize, $bytes)
    }

    Move-Item -LiteralPath $partial -Destination $target -Force
    if ((Get-Size $target) -ne $bytes) {
        throw "Moving $partial to $target did not produce a $bytes byte file."
    }
    Write-Host ("Downloaded {0:N0} bytes ({1:N1} MiB) to {2}" -f $bytes, ($bytes / 1MB), $target)
    Write-Host ""
}

Write-Host "Push them with install-model.ps1, or run install-app.ps1 to build, install and push in one step."

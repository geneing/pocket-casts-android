# Run from any directory. Read-only inspection supporting label_audit.md.
$dataRoot = Join-Path $PSScriptRoot '../../data'
$labels = Get-Content (Join-Path $dataRoot 'labels/ads.json') -Raw | ConvertFrom-Json
$cases = @(
    @{ Episode = 'hard-fork__jensen-huang-is-out-at-nvidia'; Takes = @(0, 1, 2); Windows = @(@(145, 255), @(1220, 1335), @(2480, 2711)) },
    @{ Episode = 'odd-lots__what-nvidia-s-megaspeed-scandal-is-reall'; Takes = @(0); Windows = @(@(1018, 1088), @(2440, 2507)) },
    @{ Episode = 'freakonomics-radio__694-does-democracy-really-need-elections'; Takes = @(0, 1, 2); Windows = @(@(1560, 1610)) },
    @{ Episode = 'planet-money__how-bodegas-led-to-the-yemeni-coffeehous'; Takes = @(0, 1, 2); Windows = @(@(600, 640)) }
)
foreach ($case in $cases) {
    foreach ($take in $case.Takes) {
        Write-Output "EPISODE $($case.Episode) TAKE $take"
        $labels.($case.Episode).dynamic_slots."$take" | Select-Object start, end, text | Format-List
        $transcript = Get-Content (Join-Path $dataRoot "transcripts/$($case.Episode)/take$take.json") -Raw | ConvertFrom-Json
        foreach ($window in $case.Windows) {
            $transcript.segments | Where-Object { $_.end -gt $window[0] -and $_.start -lt $window[1] } |
                Select-Object start, end, text | Format-Table -Wrap
        }
    }
}

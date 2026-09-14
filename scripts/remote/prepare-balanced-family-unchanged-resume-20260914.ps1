#requires -Version 7.0
param(
    [Parameter(Mandatory = $true)][string]$FirstManifest,
    [Parameter(Mandatory = $true)][string]$ThirdManifest
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$suite = Join-Path $repo 'experiment-suite/family-count-balanced-20260914/manifests'
$utf8 = [Text.UTF8Encoding]::new($false)

foreach ($hostSpec in @(
    [pscustomobject]@{ Name = 'arc'; Source = $FirstManifest },
    [pscustomobject]@{ Name = 'cluster'; Source = $ThirdManifest }
)) {
    $target = Join-Path $suite "$($hostSpec.Name)/unchanged-resume.tsv"
    if (Test-Path -LiteralPath $target) { throw "Target already exists: $target" }
    $all = @(Import-Csv -LiteralPath $hostSpec.Source -Delimiter "`t")
    if ($all.Count -ne 2160) { throw "Unexpected old manifest size: $($all.Count)" }
    $kept = @($all | Where-Object {
        -not ($_.setupType -eq 'family' -and $_.size -in @('50', '60'))
    })
    if ($kept.Count -ne 1620 -or ($all.Count - $kept.Count) -ne 540) {
        throw "Unexpected continuation size for $($hostSpec.Name)"
    }
    if (@($kept | Group-Object runId | Where-Object Count -ne 1).Count -ne 0 -or
        @($kept | Where-Object { [string]::IsNullOrWhiteSpace($_.args) -or $_.dependsOn }).Count -ne 0) {
        throw "Invalid continuation rows for $($hostSpec.Name)"
    }
    foreach ($algorithm in @('TIME_INDEXED', 'TIME_INDEXED_SRI')) {
        if (@($kept | Where-Object algorithm -eq $algorithm).Count -ne 810) {
            throw "Unexpected $algorithm count for $($hostSpec.Name)"
        }
    }
    $columns = @($all[0].PSObject.Properties.Name)
    $lines = @($columns -join "`t") + @($kept | ForEach-Object {
        $row = $_
        ($columns | ForEach-Object { [string]$row.$_ }) -join "`t"
    })
    [IO.File]::WriteAllLines($target, $lines, $utf8)
    "STAGED=$($hostSpec.Name) KEPT=$($kept.Count) EXCLUDED=540 SOURCE_SHA256=$((Get-FileHash -LiteralPath $hostSpec.Source -Algorithm SHA256).Hash)"
}

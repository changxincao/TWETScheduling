$ErrorActionPreference = 'Stop'
$root = 'D:\ccxWork'
$experiment = Join-Path $root 'work1\experiments\20260911-cluster-le60'
$runs = Join-Path $experiment 'runs\pricing-comparison'
$archive = Join-Path $experiment 'interrupted-before-f3-resume-20260914'
$ids = @(
    'pricing-n040-set02-m2-family-high-n20-wnarrow-time_indexed',
    'pricing-n040-set02-m2-family-high-n20-wnarrow-time_indexed_sri',
    'pricing-n040-set02-m2-family-high-n20-wwide-time_indexed',
    'pricing-n040-set02-m2-family-high-n20-wwide-time_indexed_sri',
    'pricing-n040-set02-m3-family-high-n20-wwide-time_indexed',
    'pricing-n040-set02-m3-family-high-n20-wwide-time_indexed_sri'
)
$allowed = (Resolve-Path -LiteralPath $root).Path.TrimEnd('\') + '\'
$runsResolved = (Resolve-Path -LiteralPath $runs).Path.TrimEnd('\') + '\'
if (-not $runsResolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Run directory is outside allowed remote root'
}
if (Test-Path -LiteralPath $archive) { throw "Archive already exists: $archive" }
$paths = foreach ($id in $ids) {
    $path = (Resolve-Path -LiteralPath (Join-Path $runs $id)).Path
    if (-not $path.StartsWith($runsResolved, [StringComparison]::OrdinalIgnoreCase) -or
        (Get-Item -LiteralPath $path).LinkType -or
        (Test-Path -LiteralPath (Join-Path $path 'SUCCESS'))) {
        throw "Unsafe or successful output: $path"
    }
    $path
}
New-Item -ItemType Directory -Path $archive | Out-Null
foreach ($path in $paths) {
    Move-Item -LiteralPath $path -Destination (Join-Path $archive (Split-Path $path -Leaf))
    "ARCHIVED=$(Split-Path $path -Leaf)"
}

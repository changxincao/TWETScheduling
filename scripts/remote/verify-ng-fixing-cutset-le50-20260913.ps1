#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$experiment = 'D:\ccx\work1\experiments\20260913-ng-fixing-cutset-le50'
$deployment = 'D:\ccx\work1\deployments\20260913-ng-cutset-supernode-v8'
$root = 'D:\ccx\'

function Check-File([string]$path) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing file: $path" }
}
function Check-Hashes([string]$name, [int]$expected) {
    $rows = @(Import-Csv -LiteralPath (Join-Path $experiment $name) -Delimiter "`t")
    if ($rows.Count -ne $expected) { throw "Unexpected $name count: $($rows.Count)" }
    foreach ($row in $rows) {
        $path = [IO.Path]::GetFullPath($row.path)
        if (!$path.StartsWith($experiment + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw "Artifact outside experiment: $path"
        }
        Check-File $path
        $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $row.sha256) { throw "Hash mismatch: $path" }
    }
    Write-Output "Verified $name : $expected"
}

if (!(Test-Path -LiteralPath $root -PathType Container)) { throw "Missing root: $root" }
Check-File (Join-Path $deployment 'solver.jar')
Check-File 'D:\Java\jdk-21\bin\java.exe'
Check-File 'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar'
Check-File (Join-Path $experiment 'run.cmd')
Check-File (Join-Path $experiment 'worker.cmd')
$properties = Get-Content -LiteralPath (Join-Path $experiment 'experiment.properties')
if ($properties -notcontains 'status=PREPARED_NOT_STARTED') { throw 'Batch status is not PREPARED_NOT_STARTED' }
$jarExpected = ($properties | Where-Object { $_ -like 'solverJarSha256=*' }) -replace '^solverJarSha256=', ''
$jarActual = (Get-FileHash -LiteralPath (Join-Path $deployment 'solver.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
if ($jarExpected -ne $jarActual) { throw 'Solver jar hash mismatch' }
Check-Hashes 'input-sha256.tsv' 810
Check-Hashes 'seed-sha256.tsv' 810

$rows = @(Import-Csv -LiteralPath (Join-Path $experiment 'solve.tsv') -Delimiter "`t")
$outputPrefix = $experiment.Replace('\','/') + '/runs/'
if ($rows.Count -ne 2430) { throw "Unexpected solve count: $($rows.Count)" }
$groups = $rows | Group-Object block
if ($groups.Count -ne 6 -or @($groups | Where-Object Count -ne 405).Count) {
    throw 'Unexpected variant counts'
}
if (@($rows | Group-Object runId | Where-Object Count -ne 1).Count) { throw 'Duplicate runId' }
foreach ($row in $rows) {
    if ($row.algorithm -ne 'NG_DSSR' -or $row.args -notmatch '--timeLimitSeconds="10800"' -or
        $row.args -notmatch '--maxNodes="100000"' -or
        !$row.outputDir.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Invalid base configuration: $($row.runId)"
    }
    $cutset = $row.block -in @('F-C','F-D')
    $rootOnly = $row.block -in @('F-B','F-D','R-B')
    $cluster = $row.setupType -eq 'family'
    $depth = if ($rootOnly) { '0' } else { '2147483647' }
    $settings = @(
        ('--enableClusterBranching="' + $cluster.ToString().ToLowerInvariant() + '"'),
        ('--enableCutSetBranching="' + $cutset.ToString().ToLowerInvariant() + '"'),
        ('--cutSetSupernodeSeeds="' + $cutset.ToString().ToLowerInvariant() + '"'),
        ('--timeIndexedCompletionBoundNodeArcFixingMaxDepth="' + $depth + '"'))
    foreach ($setting in $settings) {
        if (!$row.args.Contains($setting)) { throw "Invalid variant $setting in $($row.runId)" }
    }
}
if (Test-Path -LiteralPath (Join-Path $experiment 'runs')) { throw 'New batch has run outputs' }
Write-Output "PREPARED_NOT_STARTED; solves=2430; eachVariant=405; jar=$jarActual"

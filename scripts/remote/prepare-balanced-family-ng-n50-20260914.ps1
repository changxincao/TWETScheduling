#requires -Version 7.0
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$source = Join-Path $repo '.codex-tmp/ng-fixing-cutset-le50-20260913/payload/work1/experiments/20260913-ng-fixing-cutset-le50/solve.tsv'
$index = Join-Path $repo 'experiment-suite/family-count-balanced-20260914/instances/instances.tsv'
$stage = Join-Path $repo 'experiment-suite/family-count-balanced-20260914/manifests/ng-f3-n50'
$remoteWork = 'D:/ccx/work1'
$remoteBatch = "$remoteWork/experiments/20260914-balanced-family-ng-n50"
$remoteInputs = "$remoteWork/instances/family-count-balanced-20260914"
$utf8 = [Text.UTF8Encoding]::new($false)
if (Test-Path -LiteralPath $stage) { throw "Stage already exists: $stage" }
$old = @(Import-Csv -LiteralPath $source -Delimiter "`t" | Where-Object {
    $_.size -eq '50' -and $_.setupType -eq 'family'
})
$instances = @(Import-Csv -LiteralPath $index -Delimiter "`t" | Where-Object {
    $_.size -eq '50' -and $_.setupType -eq 'family'
})
if ($old.Count -ne 540 -or $instances.Count -ne 135) { throw 'Unexpected source counts' }
$byCase = @{}
foreach ($item in $instances) {
    $key = "$($item.taskSetId)|$($item.machines)|$($item.scaleLevel)|$($item.windowLevel)"
    if ($byCase.ContainsKey($key)) { throw "Duplicate input: $key" }
    $byCase[$key] = $item
}

function Write-Tsv([string]$path, [object[]]$items) {
    $columns = @($items[0].PSObject.Properties.Name)
    $lines = @($columns -join "`t") + @($items | ForEach-Object {
        $row = $_
        ($columns | ForEach-Object { [string]$row.$_ }) -join "`t"
    })
    [IO.File]::WriteAllLines($path, $lines, $utf8)
}

New-Item -ItemType Directory -Path $stage | Out-Null
$seeds = [Collections.Generic.List[object]]::new()
$solves = [Collections.Generic.List[object]]::new()
$hashes = [Collections.Generic.List[object]]::new()
$seen = [Collections.Generic.HashSet[string]]::new()
foreach ($oldRow in $old) {
    $key = "$($oldRow.taskSetId)|$($oldRow.machines)|$($oldRow.scaleLevel)|$($oldRow.windowLevel)"
    $item = $byCase[$key]
    if ($null -eq $item) { throw "No new input for $key" }
    $localInput = Join-Path (Split-Path $index) $item.instance
    if (-not (Test-Path -LiteralPath $localInput -PathType Leaf)) { throw "Missing input: $localInput" }
    $inputPath = "$remoteInputs/$($item.instance.Replace('\', '/'))"
    $scenario = "$($item.taskSetId)-m$($item.machines)-family-$($item.scaleLevel)-w$($item.windowLevel)-f3"
    $seedPath = "$remoteBatch/seeds/$scenario.seed"
    if ($seen.Add($key)) {
        $seedId = "seed-$scenario"
        $seedOutput = "$remoteBatch/runs/seeds/$seedId"
        $seedRow = [ordered]@{}
        foreach ($p in $oldRow.PSObject.Properties) { $seedRow[$p.Name] = [string]$p.Value }
        $seedRow.runId = $seedId
        $seedRow.args = "--action=`"seed`" --runId=`"$seedId`" --instance=`"$inputPath`" --seedFile=`"$seedPath`" --outputDir=`"$seedOutput`""
        $seedRow.outputDir = $seedOutput
        $seedRow.action = 'seed'
        $seedRow.block = 'seed'
        $seedRow.algorithm = ''
        $seeds.Add([pscustomobject]$seedRow)
        $hashes.Add([pscustomobject]@{path=$inputPath;sha256=(Get-FileHash -LiteralPath $localInput -Algorithm SHA256).Hash.ToLowerInvariant()})
    }
    $newId = $oldRow.runId.Replace('-ng_dssr-', '-f3-ng_dssr-')
    if ($newId -eq $oldRow.runId) { throw "Unexpected run ID: $($oldRow.runId)" }
    $output = "$remoteBatch/runs/$($oldRow.block)/$newId"
    $args = $oldRow.args.Replace($oldRow.runId, $newId)
    $args = [regex]::Replace($args, '--instance="[^"]+"', "--instance=`"$inputPath`"")
    $args = [regex]::Replace($args, '--seedFile="[^"]+"', "--seedFile=`"$seedPath`"")
    $args = [regex]::Replace($args, '--outputDir="[^"]+"', "--outputDir=`"$output`"")
    $newRow = [ordered]@{}
    foreach ($p in $oldRow.PSObject.Properties) { $newRow[$p.Name] = [string]$p.Value }
    $newRow.runId = $newId
    $newRow.args = $args
    $newRow.outputDir = $output
    $solves.Add([pscustomobject]$newRow)
}
if ($seeds.Count -ne 135 -or $solves.Count -ne 540 -or
    @($solves | Group-Object runId | Where-Object Count -ne 1).Count -ne 0) {
    throw 'Invalid new manifest counts or IDs'
}
Write-Tsv (Join-Path $stage 'seed.tsv') $seeds.ToArray()
Write-Tsv (Join-Path $stage 'solve.tsv') $solves.ToArray()
Write-Tsv (Join-Path $stage 'input-sha256.tsv') $hashes.ToArray()
$lines = @(
    '@echo off', 'setlocal', 'cd /d "%~dp0"',
    'set "JAVA=D:\Java\jdk-21\bin\java.exe"',
    'set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"',
    'set "CP=%~dp0../../deployments/20260913-ng-cutset-supernode-v8/solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"',
    '"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler "%~dp0%~1" %2 %3',
    'exit /b %ERRORLEVEL%'
)
[IO.File]::WriteAllLines((Join-Path $stage 'run.cmd'), $lines, $utf8)
$worker = @(
    '@echo off', 'setlocal', 'cd /d "%~dp0"',
    'mkdir worker.lock 2>nul', 'if errorlevel 1 exit /b 19',
    'echo SEED_RUNNING>experiment.status',
    'call run.cmd seed.tsv 6 1>scheduler-logs\seed.log 2>scheduler-logs\seed.err.log',
    'if errorlevel 1 goto failed',
    'echo SOLVE_RUNNING>experiment.status',
    'call run.cmd solve.tsv 6 1>scheduler-logs\solve.log 2>scheduler-logs\solve.err.log',
    'if errorlevel 1 goto failed',
    'echo FINISHED>experiment.status', 'exit /b 0',
    ':failed', 'echo FAILED>experiment.status', 'exit /b 1'
)
[IO.File]::WriteAllLines((Join-Path $stage 'worker.cmd'), $worker, $utf8)
[IO.File]::WriteAllLines((Join-Path $stage 'experiment.properties'), @(
    'parent=20260913-ng-fixing-cutset-le50',
    'solverDeployment=20260913-ng-cutset-supernode-v8',
    'sourceInputIndexSha256=' + (Get-FileHash -LiteralPath $index -Algorithm SHA256).Hash,
    'scope=n50-family-f3', 'seedCount=135', 'solveCount=540',
    'variants=F-A,F-B,F-C,F-D', 'solverThreads=1', 'maxParallel=6',
    'timeLimitSeconds=10800', 'maxNodes=100000'
), $utf8)
Write-Output "STAGED=$stage SEEDS=$($seeds.Count) SOLVES=$($solves.Count)"

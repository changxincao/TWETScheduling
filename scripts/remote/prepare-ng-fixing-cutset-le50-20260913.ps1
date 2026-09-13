#requires -Version 7.0
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$stage = Join-Path $repo '.codex-tmp/ng-fixing-cutset-le50-20260913'
$batch = '20260913-ng-fixing-cutset-le50'
$deploymentName = '20260913-ng-cutset-supernode-v8'
$remoteWork = 'D:/ccx/work1'
$remoteExperiment = "$remoteWork/experiments/$batch"
$remoteDeployment = "$remoteWork/deployments/$deploymentName"
$sourceData = Join-Path $repo 'experiment-suite/formal/instances/data'
$sourceSeeds = Join-Path $repo '.codex-tmp/third-host-seeds-le60'
$manifest = Join-Path $repo 'experiment-suite/formal/manifests/20260905-formal-scheduling-le60/solve.tsv'
$utf8 = [Text.UTF8Encoding]::new($false)
if (Test-Path -LiteralPath $stage) { throw "Stage already exists: $stage" }
$sourceRows = @(Import-Csv -LiteralPath $manifest -Delimiter "`t" | Where-Object {
    $_.algorithm -eq 'NG_DSSR' -and [int]$_.size -in 20,40,50
})
if ($sourceRows.Count -ne 810) { throw "Expected 810 source rows, found $($sourceRows.Count)" }

$payload = Join-Path $stage 'payload/work1'
$experiment = Join-Path $payload "experiments/$batch"
$deployment = Join-Path $payload "deployments/$deploymentName"
$classes = Join-Path $stage 'classes'
New-Item -ItemType Directory -Path $experiment,$deployment,$classes,
    (Join-Path $experiment 'inputs'),(Join-Path $experiment 'seeds'),
    (Join-Path $experiment 'scheduler-logs') -Force | Out-Null

$rows = [Collections.Generic.List[object]]::new()
$inputHashes = [ordered]@{}
$seedHashes = [ordered]@{}
foreach ($source in $sourceRows) {
    $originalInstance = [regex]::Match($source.args, '--instance="([^"]+)"').Groups[1].Value
    $relative = ($originalInstance -split '/instances/no_outsourcing/data/', 2)[1]
    if (!$relative) { throw "Invalid source instance in $($source.runId)" }
    $localInput = Join-Path $sourceData $relative
    $remoteInput = "$remoteExperiment/inputs/$relative"
    if (!$inputHashes.Contains($remoteInput)) {
        if (!(Test-Path -LiteralPath $localInput -PathType Leaf)) { throw "Missing input: $localInput" }
        $destination = Join-Path $experiment "inputs/$relative"
        New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
        Copy-Item -LiteralPath $localInput -Destination $destination
        $inputHashes[$remoteInput] = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $seedName = [regex]::Match($source.args, '--seedFile="[^"]*/([^/"]+)"').Groups[1].Value
    if (!$seedName) { throw "Missing seed in $($source.runId)" }
    $localSeed = Join-Path $sourceSeeds $seedName
    $remoteSeed = "$remoteExperiment/seeds/$seedName"
    if (!$seedHashes.Contains($remoteSeed)) {
        if (!(Test-Path -LiteralPath $localSeed -PathType Leaf)) { throw "Missing seed: $localSeed" }
        $destination = Join-Path $experiment "seeds/$seedName"
        Copy-Item -LiteralPath $localSeed -Destination $destination
        $seedHashes[$remoteSeed] = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $variants = if ($source.setupType -eq 'family') { @('F-A','F-B','F-C','F-D') }
        else { @('R-A','R-B') }
    foreach ($variant in $variants) {
        $cluster = $source.setupType -eq 'family'
        $cutset = $variant -in @('F-C','F-D')
        $rootOnly = $variant -in @('F-B','F-D','R-B')
        $depth = if ($rootOnly) { 0 } else { [int]::MaxValue }
        $runId = "$($source.runId)-$($variant.ToLowerInvariant())"
        $output = "$remoteExperiment/runs/$variant/$runId"
        $args = $source.args.Replace($source.runId, $runId)
        $args = $args.Replace($originalInstance, $remoteInput)
        $args = [regex]::Replace($args, '--seedFile="[^"]+"', "--seedFile=`"$remoteSeed`"")
        $args = [regex]::Replace($args, '--outputDir="[^"]+"', "--outputDir=`"$output`"")
        $args = $args.Replace('--enableClusterBranching="false"',
            ('--enableClusterBranching="' + $cluster.ToString().ToLowerInvariant() + '"'))
        $args = $args.Replace('--structuredArcStrictTypePriority="false"',
            ('--structuredArcStrictTypePriority="' + $cluster.ToString().ToLowerInvariant() + '"'))
        $args += ' --enableCutSetBranching="' + $cutset.ToString().ToLowerInvariant() + '"'
        $args += ' --cutSetSupernodeSeeds="' + $cutset.ToString().ToLowerInvariant() + '"'
        $args += ' --clusterMstTheta="0.5" --clusterTemporalWeight="0.0"'
        $args += ' --timeIndexedCompletionBoundNodeArcFixing="true"'
        $args += ' --timeIndexedCompletionBoundNodeArcFixingMaxDepth="' + $depth + '"'
        $row = [ordered]@{}
        foreach ($property in $source.PSObject.Properties) { $row[$property.Name] = [string]$property.Value }
        $row.runId = $runId
        $row.args = $args
        $row.outputDir = $output
        $row.dependsOn = ''
        $row.block = $variant
        $rows.Add([pscustomobject]$row)
    }
}
if ($rows.Count -ne 2430 -or $inputHashes.Count -ne 810 -or $seedHashes.Count -ne 810) {
    throw "Unexpected counts: solves=$($rows.Count) inputs=$($inputHashes.Count) seeds=$($seedHashes.Count)"
}
if (@($rows | Group-Object runId | Where-Object Count -ne 1).Count) { throw 'Duplicate runId' }
$familyCounts = @{20=3;40=3;50=4}
$orderedRows = @($rows | Sort-Object @{Expression={
    if ($_.setupType -eq 'family' -and [int]$_.machines -gt $familyCounts[[int]$_.size]) { 0 }
    elseif ($_.setupType -eq 'family') { 1 } else { 2 }
}}, @{Expression={[int]$_.size}},taskSetId,scaleLevel,windowLevel,machines,block)

function Write-Tsv($path, $items) {
    $head = $items[0].PSObject.Properties.Name
    $lines = @($head -join "`t") + @($items | ForEach-Object {
        $item = $_
        ($head | ForEach-Object { [string]$item.$_ }) -join "`t"
    })
    [IO.File]::WriteAllLines($path, $lines, $utf8)
}
Write-Tsv (Join-Path $experiment 'solve.tsv') $orderedRows
Write-Tsv (Join-Path $experiment 'input-sha256.tsv') @($inputHashes.GetEnumerator() | ForEach-Object {
    [pscustomobject]@{path=$_.Key;sha256=$_.Value}
})
Write-Tsv (Join-Path $experiment 'seed-sha256.tsv') @($seedHashes.GetEnumerator() | ForEach-Object {
    [pscustomobject]@{path=$_.Key;sha256=$_.Value}
})

$cplex = 'D:/软件/cplex/ILOG/CPLEX_Studio2211'
$classpath = "$cplex/cplex/lib/cplex.jar;$cplex/cpoptimizer/lib/ILOG.CP.jar"
$entrypoints = @('HEU/Move.java','HEU/ExperimentBatchScheduler.java',
    'Common/formal/FormalExperimentRunner.java','TWETBPC/BestBpcProfilesTest.java',
    'TWETBPC/BP/StructuredArcFlowBranchingTest.java') | ForEach-Object { Join-Path $repo "src/$_" }
& 'D:/软件/Java/jdk_22/bin/javac.exe' --release 21 -encoding UTF-8 -cp $classpath -sourcepath (Join-Path $repo 'src') -d $classes $entrypoints
if ($LASTEXITCODE -ne 0) { throw 'Solver compilation failed' }
& 'D:/软件/Java/jdk_22/bin/jar.exe' --create --file (Join-Path $deployment 'solver.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Jar creation failed' }

$run = @"
@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=%~dp0../../deployments/$deploymentName/solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler "%~dp0%~1" %2 %3
exit /b %ERRORLEVEL%
"@
$worker = @'
@echo off
setlocal
cd /d "%~dp0"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
echo SOLVE_RUNNING>experiment.status
call "%~dp0run.cmd" solve.tsv 6 1>"%~dp0scheduler-logs\solve.log" 2>"%~dp0scheduler-logs\solve.err.log"
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)
echo %RC%>worker.exit-code
exit /b %RC%
'@
[IO.File]::WriteAllText((Join-Path $experiment 'run.cmd'), $run.Replace("`n","`r`n"), $utf8)
[IO.File]::WriteAllText((Join-Path $experiment 'worker.cmd'), $worker.Replace("`n","`r`n"), $utf8)
$sourceHash = (git -C $repo rev-parse HEAD).Trim()
$jarHash = (Get-FileHash -LiteralPath (Join-Path $deployment 'solver.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $repo 'src') -Recurse -File -Filter '*.java' | ForEach-Object {
    [IO.Path]::GetRelativePath($repo, $_.FullName).Replace('\','/')
})
$sourceLines = @($sourceFiles | ForEach-Object {
    $path = Join-Path $repo $_
    "$_`t$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant())"
})
[IO.File]::WriteAllLines((Join-Path $deployment 'source-sha256.tsv'), @('path' + "`t" + 'sha256') + $sourceLines, $utf8)
& tar.exe -czf (Join-Path $deployment 'source-worktree.tar.gz') -C $repo src
if ($LASTEXITCODE -ne 0) { throw 'Source snapshot creation failed' }
$properties = @("sourceCommit=$sourceHash","solverJarSha256=$jarHash",
    'profileVersion=2026-09-12-v8','scope=NG-DSSR-n20-n40-n50',
    'solves=2430','inputs=810','sharedSeeds=810','maxParallel=6',
    'solverThreads=1','timeLimitSeconds=10800','maxNodes=100000',
    'family=Cluster-A/B;Cluster-CutSetSupernode-C/D',
    'random=Arc-A/B','status=PREPARED_NOT_STARTED')
[IO.File]::WriteAllLines((Join-Path $experiment 'experiment.properties'), $properties, $utf8)
[IO.File]::WriteAllLines((Join-Path $deployment 'deployment.properties'), $properties, $utf8)
& tar.exe -czf (Join-Path $stage 'payload.tar.gz') -C (Join-Path $stage 'payload') work1
if ($LASTEXITCODE -ne 0) { throw 'Payload archive creation failed' }
Write-Output "Prepared ${stage}: $($rows.Count) solves, $($inputHashes.Count) inputs/seeds, jar=$jarHash"

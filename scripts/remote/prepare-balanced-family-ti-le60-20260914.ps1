#requires -Version 7.0
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$suite = Join-Path $repo 'experiment-suite/family-count-balanced-20260914'
$inputRoot = Join-Path $suite 'instances'
$manifestRoot = Join-Path $suite 'manifests'
$rows = @(Import-Csv -LiteralPath (Join-Path $inputRoot 'instances.tsv') -Delimiter "`t" |
    Where-Object { $_.setupType -eq 'family' -and $_.size -in @('50', '60') } |
    Sort-Object @{ Expression = { -[int]$_.machines } }, size, taskSetId, scaleLevel, windowLevel)
if ($rows.Count -ne 270) { throw "Expected 270 n50/n60 family inputs, found $($rows.Count)" }
if ((@($rows | Group-Object instance | Where-Object Count -ne 1)).Count -ne 0) {
    throw 'Duplicate instance path in input index'
}
$utf8 = [Text.UTF8Encoding]::new($false)

function Write-Tsv([string]$path, [object[]]$items) {
    if ($items.Count -eq 0) { throw "Empty TSV: $path" }
    $columns = @($items[0].PSObject.Properties.Name)
    $lines = @($columns -join "`t") + @($items | ForEach-Object {
        $item = $_
        ($columns | ForEach-Object { [string]$item.$_ }) -join "`t"
    })
    [IO.File]::WriteAllLines($path, $lines, $utf8)
}

function Manifest-Row([string]$id, [string]$argumentText, [string]$output,
        [string]$block, [string]$action, [object]$input, [string]$algorithm) {
    [pscustomobject][ordered]@{
        runId = $id
        mainClass = 'Common.formal.FormalExperimentRunner'
        args = $argumentText
        outputDir = $output
        dependsOn = ''
        block = $block
        action = $action
        taskSetId = $input.taskSetId
        size = $input.size
        machines = $input.machines
        setupType = 'family'
        scaleLevel = $input.scaleLevel
        windowLevel = $input.windowLevel
        algorithm = $algorithm
        outsourcingModel = ''
        outsourcingRate = ''
        discountLevel = 'not-applicable'
    }
}

$hosts = @(
    [pscustomobject]@{
        name = 'arc'
        work = 'D:/ccx_work/考虑交付的机器调度/work1'
        deployment = '20260905-93685d4a'
        java = 'D:\software\Java\jdk-21\bin\java.exe'
        cplex = 'D:\software\IBM\ILOG\CPLEX_Studio2211'
        cluster = $false
    },
    [pscustomobject]@{
        name = 'cluster'
        work = 'D:/ccxWork/work1'
        deployment = '20260911-15570109-cluster-v6'
        java = 'D:\Java\jdk-21\bin\java.exe'
        cplex = 'D:\software\IBM\ILOG\CPLEX_Studio2211'
        cluster = $true
    }
)

foreach ($hostSpec in $hosts) {
    $stage = Join-Path $manifestRoot $hostSpec.name
    if (Test-Path -LiteralPath $stage) { throw "Stage already exists: $stage" }
    New-Item -ItemType Directory -Path $stage | Out-Null
    $experiment = "$($hostSpec.work)/experiments/20260914-balanced-family-ti-le60"
    $inputBase = "$($hostSpec.work)/instances/family-count-balanced-20260914"
    $seedRows = @()
    $solveRows = @()
    $auditRows = @()
    foreach ($input in $rows) {
        $localInput = Join-Path $inputRoot $input.instance
        if (-not (Test-Path -LiteralPath $localInput -PathType Leaf)) {
            throw "Missing input: $localInput"
        }
        $remoteInput = "$inputBase/$($input.instance.Replace('\', '/'))"
        $scenario = "$($input.taskSetId)-m$($input.machines)-family-$($input.scaleLevel)-w$($input.windowLevel)-f3"
        $seedId = "seed-$scenario"
        $seedFile = "$experiment/seeds/$scenario.seed"
        $seedOutput = "$experiment/runs/seeds/$seedId"
        $seedArgs = "--action=`"seed`" --runId=`"$seedId`" --instance=`"$remoteInput`" --seedFile=`"$seedFile`" --outputDir=`"$seedOutput`""
        $seedRows += Manifest-Row $seedId $seedArgs $seedOutput 'seed' 'seed' $input ''
        $auditRows += [pscustomobject]@{
            path = $remoteInput
            sha256 = (Get-FileHash -LiteralPath $localInput -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        foreach ($algorithm in @('TIME_INDEXED', 'TIME_INDEXED_SRI')) {
            $runId = "pricing-$scenario-$($algorithm.ToLowerInvariant())"
            $output = "$experiment/runs/pricing-comparison/$runId"
            $argumentText = "--action=`"solve`" --runId=`"$runId`" --instance=`"$remoteInput`" --algorithm=`"$algorithm`" --outputDir=`"$output`" --seedFile=`"$seedFile`" --timeLimitSeconds=`"10800`" --maxNodes=`"100000`""
            if ($hostSpec.cluster) {
                $argumentText += ' --enableClusterBranching="true" --structuredArcStrictTypePriority="true" --clusterMstTheta="0.5" --clusterTemporalWeight="0.0"'
            } else {
                $argumentText += ' --enableClusterBranching="false" --structuredArcStrictTypePriority="false"'
            }
            $solveRows += Manifest-Row $runId $argumentText $output 'pricing-comparison' 'solve' $input $algorithm
        }
    }
    if ($seedRows.Count -ne 270 -or $solveRows.Count -ne 540 -or $auditRows.Count -ne 270) {
        throw "Unexpected manifest counts for $($hostSpec.name)"
    }
    if (@($seedRows | Where-Object { $_.args -notmatch '^--action="seed" --runId=' }).Count -ne 0 -or
        @($solveRows | Where-Object { $_.args -notmatch '^--action="solve" --runId=' }).Count -ne 0) {
        throw "Missing task arguments for $($hostSpec.name)"
    }
    Write-Tsv (Join-Path $stage 'seed.tsv') $seedRows
    Write-Tsv (Join-Path $stage 'solve.tsv') $solveRows
    Write-Tsv (Join-Path $stage 'input-sha256.tsv') $auditRows
    $clusterValue = if ($hostSpec.cluster) { 'true' } else { 'false' }
    [IO.File]::WriteAllLines((Join-Path $stage 'experiment.properties'), @(
        'sourceInputIndexSha256=02EAA7DC0AE6DE83D8059C57FAB4B064DC42CE2ED0BCD548F1BE034C61E3AED2',
        "remoteWork=$($hostSpec.work)",
        "solverDeployment=$($hostSpec.deployment)",
        "clusterBranching=$clusterValue",
        'seedCount=270',
        'solveCount=540',
        'algorithms=TIME_INDEXED,TIME_INDEXED_SRI',
        'solverThreads=1',
        'maxParallel=6',
        'timeLimitSeconds=10800',
        'maxNodes=100000'
    ), $utf8)

    $runLines = @(
        '@echo off',
        'setlocal',
        'cd /d "%~dp0"',
        "set `"JAVA=$($hostSpec.java)`"",
        "set `"CPLEX=$($hostSpec.cplex)`"",
        "set `"CP=%~dp0../../deployments/$($hostSpec.deployment)/solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar`"",
        '"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler "%~dp0%~1" %2 %3',
        'exit /b %ERRORLEVEL%'
    )
    [IO.File]::WriteAllLines((Join-Path $stage 'run.cmd'), $runLines, $utf8)
    $workerLines = @(
        '@echo off',
        'setlocal',
        'cd /d "%~dp0"',
        'mkdir worker.lock 2>nul',
        'if errorlevel 1 exit /b 19',
        'mkdir scheduler-logs 2>nul',
        'echo SEED_RUNNING>experiment.status',
        'call run.cmd seed.tsv 6 1>scheduler-logs\seed.log 2>scheduler-logs\seed.err.log',
        'if errorlevel 1 goto failed',
        'echo SOLVE_RUNNING>experiment.status',
        'call run.cmd solve.tsv 6 1>scheduler-logs\solve.log 2>scheduler-logs\solve.err.log',
        'if errorlevel 1 goto failed',
        'echo FINISHED>experiment.status',
        'exit /b 0',
        ':failed',
        'echo FAILED>experiment.status',
        'exit /b 1'
    )
    [IO.File]::WriteAllLines((Join-Path $stage 'worker.cmd'), $workerLines, $utf8)
    "STAGED=$($hostSpec.name) SEEDS=$($seedRows.Count) SOLVES=$($solveRows.Count) PATH=$stage"
}

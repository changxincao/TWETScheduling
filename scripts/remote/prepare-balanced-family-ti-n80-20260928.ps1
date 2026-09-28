#requires -Version 7.0
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$balancedRoot = Join-Path $repo 'experiment-suite/family-count-balanced-20260914'
$balancedIndex = Join-Path $balancedRoot 'instances/instances.tsv'
$formalRoot = Join-Path $repo 'experiment-suite/formal/instances'
$formalIndex = Join-Path $formalRoot 'instances.tsv'
$manifestRoot = Join-Path $balancedRoot 'manifests/n80-ti-5h-20260928'
$utf8 = [Text.UTF8Encoding]::new($false)

$familyRows = @(Import-Csv -LiteralPath $balancedIndex -Delimiter "`t" |
    Where-Object { $_.size -eq '80' -and $_.setupType -eq 'family' })
$randomRows = @(Import-Csv -LiteralPath $formalIndex -Delimiter "`t" |
    Where-Object { $_.size -eq '80' -and $_.setupType -eq 'random' })
if ($familyRows.Count -ne 135 -or $randomRows.Count -ne 135) {
    throw "Expected 135 family and 135 random n80 inputs, found $($familyRows.Count)/$($randomRows.Count)"
}
$rows = @($familyRows + $randomRows | Sort-Object `
    @{ Expression = { if ($_.setupType -eq 'family') { 0 } else { 1 } } },
    @{ Expression = { -[int]$_.machines } }, taskSetId, scaleLevel, windowLevel)

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
        [string]$block, [string]$action, [object]$instanceRow, [string]$algorithm) {
    [pscustomobject][ordered]@{
        runId = $id
        mainClass = 'Common.formal.FormalExperimentRunner'
        args = $argumentText
        outputDir = $output
        dependsOn = ''
        block = $block
        action = $action
        taskSetId = $instanceRow.taskSetId
        size = $instanceRow.size
        machines = $instanceRow.machines
        setupType = $instanceRow.setupType
        scaleLevel = $instanceRow.scaleLevel
        windowLevel = $instanceRow.windowLevel
        algorithm = $algorithm
        outsourcingModel = ''
        outsourcingRate = ''
        discountLevel = 'not-applicable'
    }
}

function Physical-Key([object]$row) {
    "$($row.taskSetId)|$($row.machines)|$($row.setupType)|$($row.scaleLevel)|$($row.windowLevel)"
}

function Solve-Key([object]$row, [string]$algorithm) {
    "$(Physical-Key $row)|$algorithm"
}

$arcReused = @{
    'n080-set01|4|random|base|zero|TIME_INDEXED_SRI' = @('large-tisri-r80s01-zero-m4', '20260904-ti-tisri-large-random-4h/runs/large-tisri-r80s01-zero-m4')
    'n080-set03|4|random|base|zero|TIME_INDEXED' = @('large-ti-r80s03-zero-m4', '20260904-ti-tisri-large-random-4h/runs/large-ti-r80s03-zero-m4')
    'n080-set03|4|random|base|zero|TIME_INDEXED_SRI' = @('large-tisri-r80s03-zero-m4', '20260904-ti-tisri-large-random-4h/runs/large-tisri-r80s03-zero-m4')
    'n080-set05|4|random|base|zero|TIME_INDEXED' = @('large-ti-r80s05-zero-m4', '20260904-ti-tisri-large-random-4h/runs/large-ti-r80s05-zero-m4')
    'n080-set05|4|random|base|zero|TIME_INDEXED_SRI' = @('large-tisri-r80s05-zero-m4', '20260904-ti-tisri-large-random-4h/runs/large-tisri-r80s05-zero-m4')
    'n080-set03|4|random|medium|zero|TIME_INDEXED' = @('ts-ti-r80s03-medium-zero-m4', '20260904-time-scale-large-random-4h/runs/ts-ti-r80s03-medium-zero-m4')
    'n080-set03|4|random|medium|zero|TIME_INDEXED_SRI' = @('ts-tisri-r80s03-medium-zero-m4', '20260904-time-scale-large-random-4h/runs/ts-tisri-r80s03-medium-zero-m4')
}
$arcSeedReuse = @{
    'n080-set01|4|random|base|zero' = '20260904-arc-ng-large-random-4h/seeds/r80s01-zero-m4.seed'
    'n080-set03|4|random|base|zero' = '20260904-arc-ng-large-random-4h/seeds/r80s03-zero-m4.seed'
    'n080-set05|4|random|base|zero' = '20260904-arc-ng-large-random-4h/seeds/r80s05-zero-m4.seed'
    'n080-set03|4|random|medium|zero' = '20260904-time-scale-large-random-4h/seeds/r80s03-medium-zero-m4.seed'
    'n080-set03|4|random|high|zero' = '20260904-time-scale-large-random-4h/seeds/r80s03-high-zero-m4.seed'
}

$hosts = @(
    [pscustomobject]@{
        name = 'arc'
        work = 'D:/ccx_work/考虑交付的机器调度/work1'
        deployment = '20260905-93685d4a'
        java = 'D:\software\Java\jdk-21\bin\java.exe'
        cplex = 'D:\software\IBM\ILOG\CPLEX_Studio2211'
        cluster = $false
        randomInputBase = 'D:/ccx_work/考虑交付的机器调度/work1/instances/no_outsourcing'
        reused = $arcReused
        seedReuse = $arcSeedReuse
    },
    [pscustomobject]@{
        name = 'cluster'
        work = 'D:/ccxWork/work1'
        deployment = '20260911-15570109-cluster-v6'
        java = 'D:\Java\jdk-21\bin\java.exe'
        cplex = 'D:\software\IBM\ILOG\CPLEX_Studio2211'
        cluster = $true
        randomInputBase = 'D:/ccxWork/work1/instances/n80-random-20260928'
        reused = @{}
        seedReuse = @{}
    }
)

foreach ($hostSpec in $hosts) {
    $stage = Join-Path $manifestRoot $hostSpec.name
    if (Test-Path -LiteralPath $stage) { throw "Stage already exists: $stage" }
    New-Item -ItemType Directory -Path $stage | Out-Null
    $experiment = "$($hostSpec.work)/experiments/20260928-balanced-n80-ti-5h"
    $balancedInputBase = "$($hostSpec.work)/instances/family-count-balanced-20260914"
    $formalInputBase = $hostSpec.randomInputBase
    $seedRows = [Collections.Generic.List[object]]::new()
    $solveRows = [Collections.Generic.List[object]]::new()
    $auditRows = [Collections.Generic.List[object]]::new()
    $reuseRows = [Collections.Generic.List[object]]::new()

    foreach ($input in $rows) {
        $isFamily = $input.setupType -eq 'family'
        $localRoot = if ($isFamily) { Join-Path $balancedRoot 'instances' } else { $formalRoot }
        $remoteRoot = if ($isFamily) { $balancedInputBase } else { $formalInputBase }
        $localInput = Join-Path $localRoot $input.instance
        if (-not (Test-Path -LiteralPath $localInput -PathType Leaf)) { throw "Missing input: $localInput" }
        $remoteInput = "$remoteRoot/$($input.instance.Replace('\', '/'))"
        $familySuffix = if ($isFamily) { '-f4' } else { '' }
        $scenario = "$($input.taskSetId)-m$($input.machines)-$($input.setupType)-$($input.scaleLevel)-w$($input.windowLevel)$familySuffix"
        $physicalKey = Physical-Key $input
        $pendingAlgorithms = @(@('TIME_INDEXED', 'TIME_INDEXED_SRI') | Where-Object {
            -not $hostSpec.reused.ContainsKey("$physicalKey|$_")
        })

        foreach ($algorithm in @('TIME_INDEXED', 'TIME_INDEXED_SRI')) {
            $solveKey = "$physicalKey|$algorithm"
            if ($hostSpec.reused.ContainsKey($solveKey)) {
                $old = $hostSpec.reused[$solveKey]
                $reuseRows.Add([pscustomobject][ordered]@{
                    taskSetId = $input.taskSetId
                    machines = $input.machines
                    setupType = $input.setupType
                    scaleLevel = $input.scaleLevel
                    windowLevel = $input.windowLevel
                    algorithm = $algorithm
                    sourceRunId = $old[0]
                    sourceOutputDir = "$($hostSpec.work)/experiments/$($old[1])"
                    reason = 'existing-certified-optimal'
                })
                continue
            }
            $runId = "pricing-$scenario-$($algorithm.ToLowerInvariant())"
            $output = "$experiment/runs/pricing-comparison/$runId"
            $seedFile = if ($hostSpec.seedReuse.ContainsKey($physicalKey)) {
                "$($hostSpec.work)/experiments/$($hostSpec.seedReuse[$physicalKey])"
            } else {
                "$experiment/seeds/$scenario.seed"
            }
            $argumentText = "--action=`"solve`" --runId=`"$runId`" --instance=`"$remoteInput`" --algorithm=`"$algorithm`" --outputDir=`"$output`" --seedFile=`"$seedFile`" --timeLimitSeconds=`"18000`" --maxNodes=`"100000`""
            if ($hostSpec.cluster) {
                $argumentText += ' --enableClusterBranching="true" --structuredArcStrictTypePriority="true" --clusterMstTheta="0.5" --clusterTemporalWeight="0.0"'
            } else {
                $argumentText += ' --enableClusterBranching="false" --structuredArcStrictTypePriority="false"'
            }
            $solveRows.Add((Manifest-Row $runId $argumentText $output 'pricing-comparison' 'solve' $input $algorithm))
        }

        if ($pendingAlgorithms.Count -gt 0 -and -not $hostSpec.seedReuse.ContainsKey($physicalKey)) {
            $seedId = "seed-$scenario"
            $seedFile = "$experiment/seeds/$scenario.seed"
            $seedOutput = "$experiment/runs/seeds/$seedId"
            $seedArgs = "--action=`"seed`" --runId=`"$seedId`" --instance=`"$remoteInput`" --seedFile=`"$seedFile`" --outputDir=`"$seedOutput`""
            $seedRows.Add((Manifest-Row $seedId $seedArgs $seedOutput 'seed' 'seed' $input ''))
        }
        $auditRows.Add([pscustomobject][ordered]@{
            path = $remoteInput
            sha256 = (Get-FileHash -LiteralPath $localInput -Algorithm SHA256).Hash.ToLowerInvariant()
        })
    }

    $expectedSolves = if ($hostSpec.name -eq 'arc') { 533 } else { 540 }
    $expectedSeeds = if ($hostSpec.name -eq 'arc') { 265 } else { 270 }
    $expectedReuse = if ($hostSpec.name -eq 'arc') { 7 } else { 0 }
    if ($seedRows.Count -ne $expectedSeeds -or $solveRows.Count -ne $expectedSolves -or
        $reuseRows.Count -ne $expectedReuse -or $auditRows.Count -ne 270) {
        throw "Unexpected counts for $($hostSpec.name): seeds=$($seedRows.Count), solves=$($solveRows.Count), reuse=$($reuseRows.Count), audit=$($auditRows.Count)"
    }
    Write-Tsv (Join-Path $stage 'seed.tsv') $seedRows.ToArray()
    Write-Tsv (Join-Path $stage 'solve.tsv') $solveRows.ToArray()
    Write-Tsv (Join-Path $stage 'input-sha256.tsv') $auditRows.ToArray()
    if ($reuseRows.Count -gt 0) { Write-Tsv (Join-Path $stage 'reused-results.tsv') $reuseRows.ToArray() }

    $clusterValue = if ($hostSpec.cluster) { 'true' } else { 'false' }
    [IO.File]::WriteAllLines((Join-Path $stage 'experiment.properties'), @(
        'balancedInputIndexSha256=02EAA7DC0AE6DE83D8059C57FAB4B064DC42CE2ED0BCD548F1BE034C61E3AED2',
        "remoteWork=$($hostSpec.work)",
        "solverDeployment=$($hostSpec.deployment)",
        "clusterBranching=$clusterValue",
        "seedCount=$($seedRows.Count)",
        "solveCount=$($solveRows.Count)",
        "reusedCertifiedOptimalCount=$($reuseRows.Count)",
        'algorithms=TIME_INDEXED,TIME_INDEXED_SRI',
        'solverThreads=1',
        'maxParallel=6',
        'timeLimitSeconds=18000',
        'maxNodes=100000'
    ), $utf8)

    [IO.File]::WriteAllLines((Join-Path $stage 'run.cmd'), @(
        '@echo off',
        'setlocal',
        'cd /d "%~dp0"',
        "set `"JAVA=$($hostSpec.java)`"",
        "set `"CPLEX=$($hostSpec.cplex)`"",
        "set `"CP=%~dp0../../deployments/$($hostSpec.deployment)/solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar`"",
        '"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler "%~dp0%~1" %2 %3',
        'exit /b %ERRORLEVEL%'
    ), $utf8)
    [IO.File]::WriteAllLines((Join-Path $stage 'worker.cmd'), @(
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
    ), $utf8)
    [IO.File]::WriteAllLines((Join-Path $stage 'scan.cmd'), @(
        '@echo off',
        'call run.cmd seed.tsv 6 --scan-only',
        'if errorlevel 1 exit /b %ERRORLEVEL%',
        'call run.cmd solve.tsv 6 --scan-only'
    ), $utf8)
    $branchName = if ($hostSpec.cluster) { 'setup-only Cluster strict priority，失败时回退Arc' } else { '普通Arc，Cluster关闭' }
    [IO.File]::WriteAllLines((Join-Path $stage 'README.md'), @(
        '# n80 TI/TI+SRI五小时批次',
        '',
        "本批只包含n80纯调度：135个F=4 family输入和135个原random输入，机器数3/4/5、时间尺度base/medium/high、窗口zero/narrow/wide、任务集set01--set05。分支配置为$branchName。每项18000秒、100000节点、单CPLEX线程，调度器最多并发6。",
        '',
        "本机新增seed $($seedRows.Count)项、新增solve $($solveRows.Count)项。已有同输入、同算法、同分支且已经证明最优的历史结果复用$($reuseRows.Count)项，记录在reused-results.tsv；历史时限项不复用，按5小时重新运行。旧实验目录和结果不覆盖。"
    ), $utf8)
    "STAGED=$($hostSpec.name) SEEDS=$($seedRows.Count) SOLVES=$($solveRows.Count) REUSED=$($reuseRows.Count) PATH=$stage"
}

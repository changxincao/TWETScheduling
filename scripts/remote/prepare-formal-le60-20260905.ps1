#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$stage = Join-Path $root '.codex-tmp/formal-93685d4a'
$remote = 'D:/ccx_work/考虑交付的机器调度/work1'
$experiment = "$remote/experiments/20260905-formal-scheduling-le60"
$deployment = "$remote/deployments/20260905-93685d4a"
$rows = @(Import-Csv "$root/experiment-suite/formal/manifests/pricing-comparison.tsv" -Delimiter "`t" | Where-Object { [int]$_.size -le 60 })
$utf8 = [System.Text.UTF8Encoding]::new($false)
function Write-Tsv($name, $items) {
    $header = $items[0].PSObject.Properties.Name
    $lines = @($header -join "`t") + @($items | ForEach-Object {
        $row = $_
        ($header | ForEach-Object { [string]$row.$_ }) -join "`t"
    })
    [IO.File]::WriteAllLines((Join-Path $stage $name), $lines, $utf8)
}
$audit = @()
foreach ($row in $rows) {
    $localPrefix = '${WORKSPACE}/experiment-suite/formal'
    $row.args = $row.args.Replace("$localPrefix/instances/data", "$remote/instances/no_outsourcing/data").Replace("$localPrefix/seeds", "$experiment/seeds").Replace("$localPrefix/runs", "$experiment/runs")
    $row.outputDir = $row.outputDir.Replace("$localPrefix/runs", "$experiment/runs")
    if ($row.action -eq 'solve') {
        $row.args += ' --enableClusterBranching="false" --structuredArcStrictTypePriority="false"'
        $row.dependsOn = ''
    } else {
        $match = [regex]::Match($row.args, '--instance="([^"]+)"')
        $remotePath = $match.Groups[1].Value
        $localPath = $remotePath.Replace("$remote/instances/no_outsourcing/data", "$root/experiment-suite/formal/instances/data")
        $audit += [pscustomobject]@{ path=$remotePath; sha256=(Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
}
if ($rows.Count -ne 4320 -or @($audit).Count -ne 1080) { throw 'Unexpected scope' }
if (@($rows | Group-Object runId | Where-Object Count -ne 1).Count) { throw 'Duplicate runId' }
Write-Tsv 'seed.tsv' @($rows | Where-Object action -eq 'seed')
Write-Tsv 'solve.tsv' @($rows | Where-Object action -eq 'solve')
Write-Tsv 'input-sha256.tsv' $audit
$smoke = @($rows | Where-Object { $_.action -eq 'solve' -and $_.taskSetId -eq 'n020-set01' -and $_.setupType -eq 'random' -and $_.scaleLevel -eq 'base' -and $_.windowLevel -eq 'zero' -and $_.machines -eq '2' } | ForEach-Object {
    $copy = $_.PSObject.Copy()
    $copy.args = $copy.args.Replace('--timeLimitSeconds="10800"', '--timeLimitSeconds="120"').Replace('--maxNodes="100000"', '--maxNodes="2"').Replace("$experiment/runs/pricing-comparison", "$experiment/smoke")
    $copy.outputDir = $copy.outputDir.Replace("$experiment/runs/pricing-comparison", "$experiment/smoke")
    $copy
})
Write-Tsv 'smoke.tsv' $smoke
$launcher = @'
@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\software\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=DEPLOYMENT/solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler "%~dp0%~1" %2 %3
exit /b %ERRORLEVEL%
'@
[IO.File]::WriteAllText("$stage/run.cmd", $launcher.Replace('DEPLOYMENT','%~dp0../../deployments/20260905-93685d4a').Replace("`n","`r`n"), $utf8)
$worker = @'
@echo off
setlocal
cd /d "%~dp0"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
call "%~dp0run.cmd" solve.tsv 6 1>"%~dp0scheduler-logs\solve.log" 2>"%~dp0scheduler-logs\solve.err.log"
set "RC=%ERRORLEVEL%"
echo %RC%>"%~dp0worker.exit-code"
exit /b %RC%
'@
[IO.File]::WriteAllText("$stage/worker.cmd", $worker.Replace("`n","`r`n"), $utf8)
[IO.File]::WriteAllLines("$stage/deployment.properties", @('sourceCommit=93685d4a8160efddb859b7da2d37f1a65eb86eb6','profileVersion=2026-08-30-v5','solverThreads=1','maxParallel=6','timeLimitSeconds=10800','maxNodes=100000','seedCount=1080','solveCount=3240','sizes=20,40,50,60','cluster=false','outsourcing=false'), $utf8)
Write-Output "Prepared 1080 seeds and 3240 solves in $stage"

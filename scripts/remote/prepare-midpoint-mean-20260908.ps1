param([string]$Jar='D:/软件/Java/jdk_22/bin/jar.exe')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../..").Path
$work=Join-Path $repo '.codex-tmp/midpoint-mean-remote-20260908'
$payload=Join-Path $work 'payload'
if(Test-Path -LiteralPath "$payload/midpoint-overlay.jar"){throw 'Finished payload exists'}
New-Item -ItemType Directory -Path $payload -Force | Out-Null
$remote='D:/ccx/work1/experiments/20260908-midpoint-mean-ab'
$rows=@(Import-Csv -LiteralPath "$work/selected.tsv" -Delimiter "`t")
if($rows.Count -ne 8){throw 'Recheck case selection'}
# 最先比较已证明最优的两项，再比较超时项；每对使用同一个runner runId及共享seed。
$rows=@($rows | Sort-Object @{Expression={if($_.runId -match '-m4-.*wzero|-m5-.*wnarrow'){0}else{1}}},runId)
$lines=[Collections.Generic.List[string]]::new()
$lines.Add("runId`tmainClass`targs`toutputDir")
$index=0
foreach($row in $rows){
    $modes=if($index++ % 2 -eq 0){@('baseline','mean')}else{@('mean','baseline')}
    foreach($mode in $modes){
        $id="$($row.runId)-$mode"
        $output="$remote/runs/$id"
        $argsText=$row.args.Replace($row.outputDir,$output)
        if($argsText -eq $row.args){throw 'Output replacement failed'}
        $lines.Add("$id`texperiments.MidpointMeanExperiment`t$mode $argsText`t$output")
    }
}
[IO.File]::WriteAllLines("$payload/solve.tsv",$lines,[Text.UTF8Encoding]::new($false))
$smoke=[Collections.Generic.List[string]]::new()
$smoke.Add("runId`tmainClass`targs`toutputDir")
foreach($mode in @('baseline','mean')){
    $id="smoke-$mode"
    $output="$remote/smoke/$id"
    $argsText="--action=solve --runId=pricing-n020-set01-m2-random-base-n1-wzero-ng_dssr --instance=D:/ccx/work1/instances/no_outsourcing/data/n020-set01/random/base/zero/m2.dat --algorithm=NG_DSSR --outputDir=$output --seedFile=D:/ccx/work1/experiments/20260907-formal-n80-n100/seeds/n020-set01-m2-random-base-n1-wzero.seed --timeLimitSeconds=120 --maxNodes=100000 --enableClusterBranching=false --structuredArcStrictTypePriority=false"
    $smoke.Add("$id`texperiments.MidpointMeanExperiment`t$mode $argsText`t$output")
}
[IO.File]::WriteAllLines("$payload/smoke.tsv",$smoke,[Text.UTF8Encoding]::new($false))
$run=@'
@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=%~dp0midpoint-overlay.jar;D:\ccx\work1\deployments\20260907-93685d4a\solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler "%~dp0%~1" %2 %3
exit /b %ERRORLEVEL%
'@
$worker=@'
@echo off
setlocal
cd /d "%~dp0"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
echo RUNNING>experiment.status
call run.cmd solve.tsv 6 1>scheduler.log 2>scheduler.err.log
set "RC=%ERRORLEVEL%"
echo %RC%>worker.exit-code
if not "%RC%"=="0" goto failed
echo FINISHED>experiment.status
exit /b 0
:failed
echo FAILED>experiment.status
exit /b %RC%
'@
[IO.File]::WriteAllText("$payload/run.cmd",$run.Replace("`n","`r`n"),[Text.Encoding]::ASCII)
[IO.File]::WriteAllText("$payload/worker.cmd",$worker.Replace("`n","`r`n"),[Text.Encoding]::ASCII)
& $Jar --create --file "$payload/midpoint-overlay.jar" -C "$work/classes" .
if($LASTEXITCODE -ne 0){throw 'Jar failed'}
Copy-Item -LiteralPath "$work/selected.tsv" -Destination "$payload/selected-original.tsv"
Get-FileHash "$payload/midpoint-overlay.jar" -Algorithm SHA256
"Prepared $($lines.Count-1) runs in $payload"

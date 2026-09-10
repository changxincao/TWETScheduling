$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../..").Path
$local="$repo/.codex-tmp/ng-v6-switch"
$remoteHost='codex-runner@100.68.243.112'
$common=@('-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-i','C:/Users/Changxin/.ssh/codex_solver_auto_ed25519','-o','UserKnownHostsFile=C:/Users/Changxin/Downloads/solver_known_hosts')
$root='D:/ccx_work/考虑交付的机器调度/work1'
$control="$root/experiments/20260909-ng-v6-continuation"
$libs='D:/software/IBM/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;D:/software/IBM/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar'
[IO.File]::WriteAllLines("$local/old-continuation.properties",@("formal=$root/experiments/20260905-formal-scheduling-le60","ab=$root/experiments/20260908-midpoint-mean-ab","waitPid=18052","ngClasspath=$root/deployments/20260909-3038d8a0-v6/solver.jar;$libs"),[Text.UTF8Encoding]::new($false))
$worker=@'
@echo off
setlocal
cd /d "%~dp0"
"D:\software\Java\jdk-21\bin\java.exe" "-Dfile.encoding=UTF-8" "-Djava.library.path=D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64" -cp "control.jar;../../deployments/20260905-93685d4a/solver.jar;D:/software/IBM/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;D:/software/IBM/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar" NgV6Continuation . >scheduler.log 2>scheduler.err.log
exit /b %ERRORLEVEL%
'@
[IO.File]::WriteAllText("$local/old-worker.cmd",$worker.Replace("`n","`r`n"),[Text.Encoding]::ASCII)
& ssh @common $remoteHost ('cmd.exe /d /c "cd /d '+$root.Replace('/','\')+' && mkdir experiments\20260909-ng-v6-continuation"')
if($LASTEXITCODE){throw 'mkdir failed'}
foreach($pair in @(@('control.jar','control.jar'),@('old-continuation.properties','continuation.properties'),@('old-worker.cmd','worker.cmd'))) {
 & scp @common "$local/$($pair[0])" ($remoteHost+':'+$control+'/'+$pair[1]); if($LASTEXITCODE){throw 'upload failed'}
}
& ssh @common $remoteHost ('cmd.exe /d /c "cd /d '+$control.Replace('/','\')+' && D:\software\Java\jdk-21\bin\java.exe -Dfile.encoding=UTF-8 -cp control.jar;../../deployments/20260905-93685d4a/solver.jar NgV6Continuation . plan"')
if($LASTEXITCODE){throw 'plan failed'}

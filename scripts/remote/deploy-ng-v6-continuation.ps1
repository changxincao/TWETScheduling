$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../..").Path
$local="$repo/.codex-tmp/ng-v6-switch"
$remoteHost='codex-runner@100.68.243.192'
$common=@('-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-i','C:/Users/Changxin/.ssh/codex_solver_auto_ed25519','-o','UserKnownHostsFile=C:/Users/Changxin/.ssh/known_hosts')
$root='D:/ccx/work1'
$control="$root/experiments/20260909-ng-v6-continuation"
$old="$root/deployments/20260907-93685d4a/solver.jar"
$next="$root/deployments/20260909-3038d8a0-v6/solver.jar"
$libs='D:/software/IBM/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;D:/software/IBM/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar'
[IO.File]::WriteAllLines("$local/continuation.properties",@("formal=$root/experiments/20260907-formal-n80-n100","ab=$root/experiments/20260908-midpoint-mean-ab","waitPid=25716","ngClasspath=$next;$libs"),[Text.UTF8Encoding]::new($false))
$worker=@"
@echo off
setlocal
cd /d "%~dp0"
"D:\Java\jdk-21\bin\java.exe" "-Dfile.encoding=UTF-8" "-Djava.library.path=D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64" -cp "control.jar;$old;$libs" NgV6Continuation . >scheduler.log 2>scheduler.err.log
exit /b %ERRORLEVEL%
"@
[IO.File]::WriteAllText("$local/worker.cmd",$worker.Replace("`n","`r`n"),[Text.Encoding]::ASCII)
& ssh @common $remoteHost 'cmd.exe /d /c "cd /d D:\ccx\work1 && mkdir experiments\20260909-ng-v6-continuation"'
if($LASTEXITCODE){throw 'mkdir failed'}
foreach($name in @('control.jar','continuation.properties','worker.cmd')) {
 & scp @common "$local/$name" ($remoteHost+':'+$control+'/'+$name); if($LASTEXITCODE){throw 'upload failed'}
}
$cmd='cmd.exe /d /c "cd /d D:\ccx\work1\experiments\20260909-ng-v6-continuation && D:\Java\jdk-21\bin\java.exe -Dfile.encoding=UTF-8 -cp control.jar;'+$old+';'+$libs+' NgV6Continuation . plan"'
& ssh @common $remoteHost $cmd
if($LASTEXITCODE){throw 'plan failed'}

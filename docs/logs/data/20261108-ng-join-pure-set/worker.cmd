@echo off
setlocal
cd /d "%~dp0"
set "JAVA_TOOL_OPTIONS=-Xmx24g -Dtwet.bpc.fullDomainCompare.liveTrace=true -Dtwet.bpc.fullDomainCompare.stageHeartbeat=true -Dtwet.bpc.fullDomainCompare.nodeProgressSummary=true"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
if not exist scheduler-logs mkdir scheduler-logs
>experiment.status echo SOLVE_RUNNING
call "%~dp0run.cmd" "%~dp0solve.tsv" 4 1>"%~dp0scheduler-logs\solve.out.log" 2>"%~dp0scheduler-logs\solve.err.log"
if errorlevel 1 goto failed
>worker.exit-code echo 0
>experiment.status echo FINISHED
exit /b 0
:failed
>worker.exit-code echo 1
>experiment.status echo FAILED
exit /b 1

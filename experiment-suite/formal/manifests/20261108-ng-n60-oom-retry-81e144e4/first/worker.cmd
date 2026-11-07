@echo off
setlocal
cd /d "%~dp0"
set "JAVA_TOOL_OPTIONS=-Xmx24g -Dtwet.bpc.fullDomainCompare.liveTrace=true -Dtwet.bpc.fullDomainCompare.stageHeartbeat=true -Dtwet.bpc.fullDomainCompare.nodeProgressSummary=true"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
if not exist scheduler-logs mkdir scheduler-logs
echo SOLVE_RUNNING>experiment.status
call "%~dp0run.cmd" "%~dp0solve.tsv" 2 1>"%~dp0scheduler-logs\solve.out.log" 2>"%~dp0scheduler-logs\solve.err.log"
set "WORKER_RC=%ERRORLEVEL%"
echo %WORKER_RC%>worker.exit-code
if "%WORKER_RC%"=="0" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)
exit /b %WORKER_RC%

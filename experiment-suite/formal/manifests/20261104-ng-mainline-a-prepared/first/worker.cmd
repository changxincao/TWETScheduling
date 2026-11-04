@echo off
setlocal
cd /d "%~dp0"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
if not exist scheduler-logs mkdir scheduler-logs
echo SOLVE_RUNNING>experiment.status
call "%~dp0run.cmd" "%~dp0solve.tsv" 5 1>"%~dp0scheduler-logs\solve.out.log" 2>"%~dp0scheduler-logs\solve.err.log"
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)
echo %RC%>worker.exit-code
exit /b %RC%

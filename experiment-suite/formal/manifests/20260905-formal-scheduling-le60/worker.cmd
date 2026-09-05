@echo off
setlocal
cd /d "%~dp0"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
call "%~dp0run.cmd" solve.tsv 6 1>"%~dp0scheduler-logs\solve.log" 2>"%~dp0scheduler-logs\solve.err.log"
set "RC=%ERRORLEVEL%"
echo %RC%>"%~dp0worker.exit-code"
exit /b %RC%
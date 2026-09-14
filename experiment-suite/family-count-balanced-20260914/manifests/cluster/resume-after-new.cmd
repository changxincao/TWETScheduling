@echo off
setlocal
cd /d "%~dp0"
mkdir resume.lock 2>nul
if errorlevel 1 exit /b 19
echo WAITING>resume.status
:wait
set "STATE="
for /f "usebackq delims=" %%s in ("experiment.status") do set "STATE=%%s"
if /i "%STATE%"=="FINISHED" goto run
if /i "%STATE%"=="FAILED" goto run
ping -n 31 127.0.0.1 >nul
goto wait
:run
echo RUNNING>resume.status
call run.cmd unchanged-resume.tsv 6 1>scheduler-logs\resume.log 2>scheduler-logs\resume.err.log
if errorlevel 1 goto failed
echo FINISHED>resume.status
exit /b 0
:failed
echo FAILED>resume.status
exit /b 1

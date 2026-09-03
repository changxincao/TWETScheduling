@echo off
setlocal
for %%I in ("%~dp0..\..") do set "WORK1=%%~fI"
set "DEPLOY=%WORK1%\deployments\20260903-c887d5cd\TWETScheduling"
if not exist "%~dp0scheduler-logs" mkdir "%~dp0scheduler-logs"
call "%DEPLOY%\scripts\remote\run-remote-formal.cmd" "%~dp0seed.tsv" 6 1>"%~dp0scheduler-logs\seed.log" 2>"%~dp0scheduler-logs\seed.err.log"
exit /b %ERRORLEVEL%

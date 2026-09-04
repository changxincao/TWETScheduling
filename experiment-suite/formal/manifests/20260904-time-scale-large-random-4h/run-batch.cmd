@echo off
setlocal
for %%I in ("%~dp0..\..") do set "WORK1=%%~fI"
set "DEPLOY=%WORK1%\deployments\20260903-c887d5cd\TWETScheduling"
if not exist "%~dp0scheduler-logs" mkdir "%~dp0scheduler-logs"
call "%DEPLOY%\scripts\remote\run-remote-formal.cmd" "%~dp0seeds.tsv" 4 1>"%~dp0scheduler-logs\seeds.log" 2>"%~dp0scheduler-logs\seeds.err.log"
if errorlevel 1 exit /b 21
call "%DEPLOY%\scripts\remote\run-remote-formal.cmd" "%~dp0solves.tsv" 6 1>"%~dp0scheduler-logs\solves.log" 2>"%~dp0scheduler-logs\solves.err.log"
exit /b %ERRORLEVEL%

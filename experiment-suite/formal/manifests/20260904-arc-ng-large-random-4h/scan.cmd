@echo off
setlocal
for %%I in ("%~dp0..\..") do set "WORK1=%%~fI"
set "DEPLOY=%WORK1%\deployments\20260903-c887d5cd\TWETScheduling"
call "%DEPLOY%\scripts\remote\run-remote-formal.cmd" "%~dp0seed.tsv" 6 --scan-only
if errorlevel 1 exit /b 41
call "%DEPLOY%\scripts\remote\run-remote-formal.cmd" "%~dp0arc-ngdssr.tsv" 6 --scan-only
if errorlevel 1 exit /b 42
exit /b 0

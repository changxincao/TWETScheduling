@echo off
setlocal
for %%I in ("%~dp0..\..") do set "WORK1=%%~fI"
set "DEPLOY=%WORK1%\deployments\20260903-c887d5cd\TWETScheduling"
call "%DEPLOY%\scripts\remote\run-remote-formal.cmd" "%~dp0ti-tisri.tsv" 6 --scan-only
exit /b %ERRORLEVEL%

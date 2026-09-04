@echo off
setlocal
for %%I in ("%~dp0..\..") do set "WORK1=%%~fI"
set "DEPLOY=%WORK1%\deployments\20260903-c887d5cd\TWETScheduling"
call "%DEPLOY%\scripts\remote\run-remote-formal.cmd" "%~dp0seeds.tsv" 4 --scan-only
if errorlevel 1 exit /b %ERRORLEVEL%
call "%DEPLOY%\scripts\remote\run-remote-formal.cmd" "%~dp0solves.tsv" 6 --scan-only
exit /b %ERRORLEVEL%

@echo off
setlocal
for %%I in ("%~dp0..\..") do set "WORK1=%%~fI"
set "DEPLOY=%WORK1%\deployments\20260903-c887d5cd\TWETScheduling"
if not exist "%~dp0scheduler-logs" mkdir "%~dp0scheduler-logs"
:wait_current
"D:\software\Java\jdk-21\bin\jcmd.exe" -l | find "20260903-cluster-ng-large-6h\cluster-ngdssr.tsv" >nul
if not errorlevel 1 (
  timeout /t 60 /nobreak >nul
  goto wait_current
)
call "%DEPLOY%\scripts\remote\run-remote-formal.cmd" "%~dp0cluster-ngdssr.tsv" 6 1>"%~dp0scheduler-logs\cluster-active.log" 2>"%~dp0scheduler-logs\cluster-active.err.log"
exit /b %ERRORLEVEL%

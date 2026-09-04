@echo off
setlocal
if not exist "%~dp0scheduler-logs" mkdir "%~dp0scheduler-logs"
call :main 1>>"%~dp0scheduler-logs\deferred.log" 2>>"%~dp0scheduler-logs\deferred.err.log"
exit /b

:main
mkdir "%~dp0deferred.lock" 2>nul
if errorlevel 1 exit /b 31
>"%~dp0WAITING" echo Waiting for the current NG-DSSR Arc scheduler
:wait_current
"D:\software\Java\jdk-21\bin\jcmd.exe" -l | find "20260904-arc-ng-large-random-4h\arc-ngdssr.tsv" >nul
if not errorlevel 1 (
  ping -n 61 127.0.0.1 >nul
  goto wait_current
)
del "%~dp0WAITING" 2>nul
>"%~dp0RUNNING" echo Running twelve TI and TI+SRI solves
call "%~dp0run-ti-tisri.cmd"
if errorlevel 1 (
  >"%~dp0FAILED" echo Solve scheduler failed
  del "%~dp0RUNNING" 2>nul
  exit /b 32
)
del "%~dp0RUNNING" 2>nul
>"%~dp0COMPLETE" echo TI and TI+SRI batch completed
exit /b 0

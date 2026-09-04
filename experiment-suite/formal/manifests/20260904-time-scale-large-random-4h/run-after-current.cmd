@echo off
setlocal
if not exist "%~dp0scheduler-logs" mkdir "%~dp0scheduler-logs"
call :main 1>>"%~dp0scheduler-logs\deferred.log" 2>>"%~dp0scheduler-logs\deferred.err.log"
exit /b

:main
mkdir "%~dp0deferred.lock" 2>nul
if errorlevel 1 exit /b 31
>"%~dp0WAITING" echo Waiting for 20260904-ti-tisri-large-random-4h
:wait_current
"D:\software\Java\jdk-21\bin\jcmd.exe" -l | find "20260904-ti-tisri-large-random-4h\ti-tisri.tsv" >nul
if not errorlevel 1 (
  ping -n 61 127.0.0.1 >nul
  goto wait_current
)
del "%~dp0WAITING" 2>nul
>"%~dp0RUNNING" echo Running four seeds followed by twelve solves
call "%~dp0run-batch.cmd"
if errorlevel 1 (
  >"%~dp0FAILED" echo Batch failed
  del "%~dp0RUNNING" 2>nul
  exit /b 32
)
del "%~dp0RUNNING" 2>nul
>"%~dp0COMPLETE" echo Time-scale batch completed
exit /b 0

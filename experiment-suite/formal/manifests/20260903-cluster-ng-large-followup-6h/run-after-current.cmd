@echo off
setlocal
if not exist "%~dp0scheduler-logs" mkdir "%~dp0scheduler-logs"
call :main 1>>"%~dp0scheduler-logs\deferred.log" 2>>"%~dp0scheduler-logs\deferred.err.log"
exit /b

:main
mkdir "%~dp0deferred.lock" 2>nul
if errorlevel 1 exit /b 31
>"%~dp0WAITING" echo Waiting for the current large-cluster scheduler
:wait_current
"D:\software\Java\jdk-21\bin\jcmd.exe" -l | find "20260903-cluster-ng-large-6h\cluster-ngdssr.tsv" >nul
if not errorlevel 1 (
  timeout /t 60 /nobreak >nul
  goto wait_current
)
del "%~dp0WAITING" 2>nul
>"%~dp0SEEDING" echo Generating six ALNS seeds
call "%~dp0run-seeds.cmd"
if errorlevel 1 (
  >"%~dp0FAILED" echo Seed generation failed
  del "%~dp0SEEDING" 2>nul
  exit /b 32
)
del "%~dp0SEEDING" 2>nul
>"%~dp0RUNNING" echo Running six NG-DSSR Cluster solves
call "%~dp0run-cluster.cmd"
if errorlevel 1 (
  >"%~dp0FAILED" echo Solve scheduler failed
  del "%~dp0RUNNING" 2>nul
  exit /b 33
)
del "%~dp0RUNNING" 2>nul
>"%~dp0COMPLETE" echo Follow-up batch completed
exit /b 0

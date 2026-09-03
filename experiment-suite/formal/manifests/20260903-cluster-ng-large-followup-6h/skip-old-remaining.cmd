@echo off
setlocal
if not exist "%~dp0scheduler-logs" mkdir "%~dp0scheduler-logs"
mkdir "%~dp0skip-old-remaining.lock" 2>nul
if errorlevel 1 exit /b 51
>"%~dp0SKIP_OLD_REMAINING" echo Watching four queued n100/m4 runs

:watch
"D:\software\Java\jdk-21\bin\jcmd.exe" -l >"%~dp0scheduler-logs\old-jvm-snapshot.tmp"
findstr /C:"20260903-cluster-ng-large-6h\cluster-ngdssr.tsv" "%~dp0scheduler-logs\old-jvm-snapshot.tmp" >nul
if errorlevel 1 goto done
call :kill_run large-cluster-f100s02-zero-m4
call :kill_run large-cluster-f100s03-zero-m4
call :kill_run large-cluster-f100s04-zero-m4
call :kill_run large-cluster-f100s05-zero-m4
ping -n 6 127.0.0.1 >nul
goto watch

:kill_run
for /f "tokens=1" %%P in ('findstr /C:"--runId=%~1 " "%~dp0scheduler-logs\old-jvm-snapshot.tmp"') do (
  echo %date% %time% stopping %~1 PID %%P>>"%~dp0scheduler-logs\skip-old-remaining.log"
  tskill %%P>>"%~dp0scheduler-logs\skip-old-remaining.log" 2>>&1
)
exit /b 0

:done
del "%~dp0SKIP_OLD_REMAINING" 2>nul
>"%~dp0SKIP_OLD_DONE" echo Old scheduler exited; queued n100/m4 runs were skipped
del "%~dp0scheduler-logs\old-jvm-snapshot.tmp" 2>nul
exit /b 0

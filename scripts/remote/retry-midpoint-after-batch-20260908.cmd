@echo off
setlocal
cd /d D:\ccx\work1\experiments\20260908-midpoint-mean-ab
mkdir retry-after-batch.lock 2>nul
if errorlevel 1 exit /b 19
echo WAITING>retry.status
:wait
if exist worker.exit-code goto verify
:delay
echo %DATE% %TIME%>retry.waiting-heartbeat.txt
powershell -NoProfile -Command "Start-Sleep -Seconds 30"
goto wait
:verify
"D:\Java\jdk-21\bin\jcmd.exe" -l >retry-jvms-before.txt 2>retry-jvms-before.err.txt
if errorlevel 1 goto failed
findstr /L /C:"experiments.MidpointMeanExperiment " retry-jvms-before.txt >nul
if not errorlevel 1 goto delay
echo RUNNING>retry.status
call run.cmd retry.tsv 1 1>retry-scheduler.log 2>retry-scheduler.err.log
set "RC=%ERRORLEVEL%"
echo %RC%>retry.exit-code
if not "%RC%"=="0" goto failed
echo FINISHED>retry.status
exit /b 0
:failed
echo FAILED>retry.status
exit /b 1

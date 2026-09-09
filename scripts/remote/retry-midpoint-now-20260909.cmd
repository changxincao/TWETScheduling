@echo off
setlocal
cd /d D:\ccx\work1\experiments\20260908-midpoint-mean-ab
mkdir retry-now-02.lock 2>nul
if errorlevel 1 exit /b 19
echo RUNNING>retry.status
call run.cmd retry.tsv 1 1>retry-scheduler-02.log 2>retry-scheduler-02.err.log
set "RC=%ERRORLEVEL%"
echo %RC%>retry-02.exit-code
if not "%RC%"=="0" goto failed
echo FINISHED>retry.status
exit /b 0
:failed
echo FAILED>retry.status
exit /b %RC%

@echo off
setlocal
cd /d "%~dp0"
mkdir pipeline.lock 2>nul
if errorlevel 1 exit /b 19
echo SEED_RUNNING>pipeline.status
call "%~dp0run.cmd" seed.tsv 6 1>scheduler-logs\seed-resume.log 2>scheduler-logs\seed-resume.err.log
if errorlevel 1 goto failed
call "%~dp0guard.cmd" verify seed.tsv 1080 1>scheduler-logs\seed-verify.log 2>scheduler-logs\seed-verify.err.log
if errorlevel 1 goto failed
echo SMOKE_RUNNING>pipeline.status
call "%~dp0run.cmd" smoke.tsv 3 1>scheduler-logs\smoke.log 2>scheduler-logs\smoke.err.log
if errorlevel 1 goto failed
call "%~dp0guard.cmd" verify smoke.tsv 3 1>scheduler-logs\smoke-verify.log 2>scheduler-logs\smoke-verify.err.log
if errorlevel 1 goto failed
echo SOLVE_RUNNING>pipeline.status
call "%~dp0worker.cmd"
if errorlevel 1 goto failed
echo FINISHED>pipeline.status
exit /b 0
:failed
echo FAILED>pipeline.status
exit /b 1

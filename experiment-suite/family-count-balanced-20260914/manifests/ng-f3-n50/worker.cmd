@echo off
setlocal
cd /d "%~dp0"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
echo SEED_RUNNING>experiment.status
call run.cmd seed.tsv 6 1>scheduler-logs\seed.log 2>scheduler-logs\seed.err.log
if errorlevel 1 goto failed
echo SOLVE_RUNNING>experiment.status
call run.cmd solve.tsv 6 1>scheduler-logs\solve.log 2>scheduler-logs\solve.err.log
if errorlevel 1 goto failed
echo FINISHED>experiment.status
exit /b 0
:failed
echo FAILED>experiment.status
exit /b 1

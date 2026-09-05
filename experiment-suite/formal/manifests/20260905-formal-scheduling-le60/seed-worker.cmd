@echo off
setlocal
cd /d "%~dp0"
call "%~dp0run.cmd" seed.tsv 6 1>"%~dp0scheduler-logs/seed.log" 2>"%~dp0scheduler-logs/seed.err.log"
exit /b %ERRORLEVEL%

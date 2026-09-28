@echo off
call run.cmd seed.tsv 6 --scan-only
if errorlevel 1 exit /b %ERRORLEVEL%
call run.cmd solve.tsv 6 --scan-only

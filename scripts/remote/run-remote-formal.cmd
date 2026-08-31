@echo off
setlocal

for %%I in ("%~dp0..\..") do set "DEPLOY_ROOT=%%~fI"
set "JAVA=D:\software\Java\jdk-21\bin\java.exe"
set "CPLEX_ROOT=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CPLEX_NATIVE=%CPLEX_ROOT%\cplex\bin\x64_win64"
set "CLASSPATH=%DEPLOY_ROOT%\target\classes;%CPLEX_ROOT%\cplex\lib\cplex.jar;%CPLEX_ROOT%\cpoptimizer\lib\ILOG.CP.jar"

set "MANIFEST=%~1"
if "%MANIFEST%"=="" set "MANIFEST=%DEPLOY_ROOT%\experiment-suite\formal\manifests\pricing-comparison.tsv"
set "MAX_PARALLEL=%~2"
if "%MAX_PARALLEL%"=="" set "MAX_PARALLEL=4"

if not exist "%JAVA%" exit /b 11
if not exist "%MANIFEST%" exit /b 12
if not exist "%DEPLOY_ROOT%\target\classes\HEU\ExperimentBatchScheduler.class" exit /b 13
if not "%~3"=="" if not "%~3"=="--scan-only" exit /b 14

cd /d "%DEPLOY_ROOT%"
"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX_NATIVE%" -cp "%CLASSPATH%" HEU.ExperimentBatchScheduler "%MANIFEST%" "%MAX_PARALLEL%" %3
exit /b %ERRORLEVEL%

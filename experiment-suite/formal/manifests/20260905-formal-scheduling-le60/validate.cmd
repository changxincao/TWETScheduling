@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\software\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=%~dp0../../deployments/20260905-93685d4a/solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" -cp "%CP%" VerifyInputs "%~dp0input-sha256.tsv"
if errorlevel 1 exit /b 10
"%JAVA%" -cp "%CP%" TWETBPC.GC.TimeIndexedGraphOptimizationTest
if errorlevel 1 exit /b 11
"%JAVA%" -cp "%CP%" TWETBPC.BestBpcProfilesTest
exit /b %ERRORLEVEL%

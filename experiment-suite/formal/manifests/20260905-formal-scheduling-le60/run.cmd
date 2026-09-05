@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\software\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=%~dp0../../deployments/20260905-93685d4a/solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler "%~dp0%~1" %2 %3
exit /b %ERRORLEVEL%
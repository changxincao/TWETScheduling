@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\software\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=%~dp0tools.jar;%~dp0solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" -Xmx512m -Dfile.encoding=UTF-8 -cp "%~dp0audit-live.jar;%CP%" NgBitmapExperimentAudit "%~dp0."
exit /b %ERRORLEVEL%

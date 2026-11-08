@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\software\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=%~dp0tools.jar;%~dp0solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" -Xmx1g -Dfile.encoding=UTF-8 -cp "%CP%" NgMainlineConfigAudit "%~dp0."
exit /b %ERRORLEVEL%

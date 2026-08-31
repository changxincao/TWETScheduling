@echo off
setlocal

for %%I in ("%~dp0..\..") do set "DEPLOY_ROOT=%%~fI"
for %%I in ("%DEPLOY_ROOT%\..") do set "DEPLOY_ID=%%~nxI"
for %%I in ("%DEPLOY_ROOT%\..\..\..") do set "WORK1_ROOT=%%~fI"

set "JAVA=D:\software\Java\jdk-21\bin\java.exe"
set "CPLEX_ROOT=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CPLEX_NATIVE=%CPLEX_ROOT%\cplex\bin\x64_win64"
set "CLASSPATH=%DEPLOY_ROOT%\target\classes;%CPLEX_ROOT%\cplex\lib\cplex.jar;%CPLEX_ROOT%\cpoptimizer\lib\ILOG.CP.jar"
set "INSTANCE=%WORK1_ROOT%\instances\no_outsourcing\data\n020-set01\random\base\zero\m2.dat"
set "SEED=%WORK1_ROOT%\runtime\seeds\smoke-n020-set01-m2-random-base-n1-wzero.seed"
set "OUTPUT_ROOT=%WORK1_ROOT%\results\smoke\%DEPLOY_ID%"

if not exist "%JAVA%" exit /b 11
if not exist "%INSTANCE%" exit /b 12
if not exist "%DEPLOY_ROOT%\target\classes\Common\formal\FormalExperimentRunner.class" exit /b 13
if not exist "%WORK1_ROOT%\runtime\seeds" mkdir "%WORK1_ROOT%\runtime\seeds"
if not exist "%OUTPUT_ROOT%\seed" mkdir "%OUTPUT_ROOT%\seed"
if not exist "%OUTPUT_ROOT%\solve" mkdir "%OUTPUT_ROOT%\solve"

cd /d "%DEPLOY_ROOT%"
if not exist "%SEED%" (
  "%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX_NATIVE%" -cp "%CLASSPATH%" Common.formal.FormalExperimentRunner --action=seed --runId=remote-smoke-seed-n020 --instance="%INSTANCE%" --seedFile="%SEED%" --outputDir="%OUTPUT_ROOT%\seed" 1>"%OUTPUT_ROOT%\seed\stdout.log" 2>"%OUTPUT_ROOT%\seed\stderr.log"
  if errorlevel 1 exit /b 21
)

"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX_NATIVE%" -cp "%CLASSPATH%" Common.formal.FormalExperimentRunner --action=solve --runId=remote-smoke-n020-ng-dssr --instance="%INSTANCE%" --algorithm=NG_DSSR --seedFile="%SEED%" --outputDir="%OUTPUT_ROOT%\solve" --timeLimitSeconds=120 --maxNodes=100 1>"%OUTPUT_ROOT%\solve\stdout.log" 2>"%OUTPUT_ROOT%\solve\stderr.log"
if errorlevel 1 exit /b 22

echo smoke_succeeded output=%OUTPUT_ROOT%
exit /b 0

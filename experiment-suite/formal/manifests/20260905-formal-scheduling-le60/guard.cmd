@echo off
setlocal
cd /d "%~dp0"
"D:\software\Java\jdk-21\bin\java.exe" -cp "%~dp0guard.jar" FormalBatchGuard %1 "%~dp0%~2" %3
exit /b %ERRORLEVEL%

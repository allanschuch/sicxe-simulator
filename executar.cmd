@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-scripts\project-tools.ps1" -Mode Run
set "PROJECT_EXIT=%ERRORLEVEL%"
if not "%PROJECT_EXIT%"=="0" pause
exit /b %PROJECT_EXIT%

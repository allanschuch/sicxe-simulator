@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-scripts\project-tools.ps1" -Mode Maven %*
exit /b %ERRORLEVEL%

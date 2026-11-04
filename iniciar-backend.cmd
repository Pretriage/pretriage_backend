@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start_backend.ps1" %*
set "backendExitCode=%ERRORLEVEL%"
if not "%backendExitCode%"=="0" (
    echo.
    echo El backend termino con un error. Revisa los mensajes anteriores.
    pause
)
exit /b %backendExitCode%

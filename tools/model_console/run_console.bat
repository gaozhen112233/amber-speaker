@echo off
rem Runs the model console in the foreground. Started (minimized) by
rem start_console.cmd; all output goes to console.log so a silent death
rem ("cannot connect" in the browser) can be diagnosed afterwards.
rem
rem Args: [device] [port]
cd /d "%~dp0"

set "DEVICE=%~1"
if "%DEVICE%"=="" set "DEVICE=192.168.31.43:8788"
set "PORT=%~2"
if "%PORT%"=="" set "PORT=8790"

set "PY="
for /d %%D in ("%USERPROFILE%\.workbuddy\binaries\python\versions\*") do (
    if exist "%%D\python.exe" set "PY=%%D\python.exe"
)
if not defined PY (
    for %%P in (python.exe) do if not "%%~$PATH:P"=="" set "PY=%%~$PATH:P"
)

if not defined PY (
    echo [ERROR] No python interpreter found. > console.log
    exit /b 1
)

echo Using: %PY%   device=%DEVICE%   port=%PORT%
echo ==== %DATE% %TIME% ==== > console.log
"%PY%" server.py %DEVICE% %PORT% >> console.log 2>&1
echo [exited with %ERRORLEVEL%] >> console.log

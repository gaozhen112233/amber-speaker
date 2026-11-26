@echo off
rem Amber model console launcher (double-click this).
rem
rem It only does three things: kill a stale listener, start run_console.bat
rem minimized, open the page. The python interpreter hunt and the log redirect
rem live in run_console.bat -- "start" mangles quoted executable paths, which is
rem why an earlier version of this file just printed "Starting..." and did nothing.
rem
rem Usage:  start_console.cmd [device] [port]
rem         default 192.168.1.100:8788   8790
rem         or set AMBER_DEVICE=<speaker-ip>:8788 before running
rem
rem Output log: console.log (same folder). Check it when the page says
rem "cannot connect".
cd /d "%~dp0"

set "DEVICE=%~1"
if "%DEVICE%"=="" set "DEVICE=%AMBER_DEVICE%"
if "%DEVICE%"=="" set "DEVICE=192.168.1.100:8788"
set "PORT=%~2"
if "%PORT%"=="" set "PORT=8790"

rem A stale server on the port makes the new one die with WinError 10048, and the
rem browser then keeps talking to the OLD build (404 on endpoints added since).
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":%PORT% " ^| findstr LISTENING') do (
    echo Killing stale listener on port %PORT% ^(PID %%P^)
    taskkill /PID %%P /F >nul 2>&1
)

echo Starting model console: http://127.0.0.1:%PORT%/   device %DEVICE%
start "amber-model-console" /min run_console.bat %DEVICE% %PORT%
timeout /t 3 >nul
start "" http://127.0.0.1:%PORT%/

@echo off
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

REM ── Settings (edit if your Anaconda is installed elsewhere) ──────────────────
set "CONDA_ROOT=D:\software\Anaconda"
set "CONDA_ENV=python310"

REM ── Derived paths (no need to edit below) ────────────────────────────────────
set "CONDA_ACTIVATE=%CONDA_ROOT%\Scripts\activate.bat"
set "PYTHON=%CONDA_ROOT%\envs\%CONDA_ENV%\python.exe"

set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=start"

if /I "%ACTION%"=="docker" goto :docker_mode
if /I "%ACTION%"=="client" goto :client_only
if /I "%ACTION%"=="start" goto :start_all
echo  Usage: start_all.bat [start ^| docker ^| client]
pause & exit /b 1

:: ==============================================================================
:start_all
echo.
echo  =============================================
echo   Intelligent Agent -- Starting All Services
echo  =============================================
echo.

if not exist "%CONDA_ACTIVATE%" (
    echo  [ERROR] Conda not found: %CONDA_ROOT%
    echo          Edit CONDA_ROOT at the top of this script.
    pause & exit /b 1
)
if not exist "%PYTHON%" (
    echo  [ERROR] Conda env "%CONDA_ENV%" not found.
    echo          Run: conda create -n %CONDA_ENV% python=3.10
    pause & exit /b 1
)
if not exist "%ROOT%\backend\web\mvnw.cmd" (
    echo  [ERROR] Maven wrapper missing: backend\web\mvnw.cmd
    pause & exit /b 1
)

echo  [1/4] Agent        (port 8000) ...
start "Agent  [8000]" cmd /k "title Agent [port 8000] && call %CONDA_ACTIVATE% %CONDA_ROOT%\envs\%CONDA_ENV% && cd /d %ROOT%\agent && python -m uvicorn api.fastapi_app:app --host 0.0.0.0 --port 8000 --reload"

echo  [2/4] Backend      (port 8080) ...
start "Backend[8080]" cmd /k "title Backend [port 8080] && cd /d %ROOT%\backend\web && call mvnw.cmd spring-boot:run"

echo  [3/4] Frontend     (port 5173) ...
start "Frontend[5173]" cmd /k "title Frontend [port 5173] && cd /d %ROOT%\frontend && npm run dev"

echo.
echo  Three service windows opened.
echo  Waiting 15s for Agent to initialize...
timeout /t 15 /nobreak > nul

:client_only
echo.
echo  [4/4] Client -- http://localhost:8000
echo.
if not exist "%CONDA_ACTIVATE%" goto :client_no_conda
call "%CONDA_ACTIVATE%" "%CONDA_ROOT%\envs\%CONDA_ENV%"
cd /d "%ROOT%\client"
python main.py
goto :end

:client_no_conda
cd /d "%ROOT%\client"
python main.py

:end
pause
endlocal
exit /b 0

:: ==============================================================================
:docker_mode
echo.
echo  Starting via Docker Compose...
echo.
if not exist "%ROOT%\.env.docker" (
    echo  [ERROR] .env.docker not found.
    echo          Copy .env.docker.example to .env.docker and configure it.
    pause & exit /b 1
)
cd /d "%ROOT%"
docker compose up -d
echo.
echo  All containers started.
echo  Frontend : http://localhost:3000
echo  Backend  : http://localhost:8080
echo  Agent    : http://localhost:8000
echo.
echo  Stop: docker compose down
pause
endlocal
exit /b 0

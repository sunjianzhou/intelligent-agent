@echo off
setlocal enabledelayedexpansion

REM ============================================================================
REM  Intelligent Agent -- Java-only startup (Python Agent retired 2026-08-08)
REM   Usage: start_all.bat [start ^| docker ^| client]
REM   Backend is Java-only (Python Agent retired 2026-08-08)
REM ============================================================================

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=start"
shift

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

if not exist "%ROOT%\backend\web\mvnw.cmd" (
    echo  [ERROR] Maven wrapper missing: backend\web\mvnw.cmd
    pause & exit /b 1
)
if not exist "%ROOT%\backend\web\target\web-1.0-SNAPSHOT.jar" (
    echo  [INFO] Building backend jar (first run)...
    pushd "%ROOT%\backend\web"
    call mvnw.cmd -q package -DskipTests
    popd
)

echo  [1/3] Backend      (port 8080, java mode) ...
start "Backend[8080]" cmd /k "title Backend [port 8080] && call %ROOT%\start_java_mode.bat"

echo  [2/3] Frontend     (port 5173) ...
start "Frontend[5173]" cmd /k "title Frontend [port 5173] && cd /d %ROOT%\frontend && npm run dev"

echo.
echo  Two service windows opened. Backend waits for nothing (self-contained).
echo  Frontend : http://localhost:5173   Backend : http://localhost:8080
echo.
pause
goto :end

:client_only
echo.
echo  [3/3] Java CLI client
echo.
if not exist "%ROOT%\client\target\client-1.0-SNAPSHOT.jar" (
    echo  [INFO] Building CLI jar (first run)...
    pushd "%ROOT%\client"
    call "%ROOT%\backend\web\mvnw.cmd" -q package -DskipTests
    popd
)
if not defined JAVA_HOME set "JAVA_HOME=D:\software\jdk21\jdk-21.0.12+8"
"%JAVA_HOME%\bin\java.exe" -jar "%ROOT%\client\target\client-1.0-SNAPSHOT.jar" repl --url http://localhost:8080
goto :end

:: ==============================================================================
:docker_mode
echo.
echo  Starting via Docker Compose...
echo.
if not exist "%ROOT%\.env.docker" (
    echo  [ERROR] .env.docker not found.
    pause & exit /b 1
)
cd /d "%ROOT%"
REM 注意：cmd 的 %* 不受 shift 影响（仍含 ACTION 本身），须用 %1..%9 显式透传剩余参数
docker compose up -d %1 %2 %3 %4 %5 %6 %7 %8 %9
echo.
echo  All containers started.
echo  Frontend : http://localhost:3000
echo  Backend  : http://localhost:8080
echo.
echo  Profiles : --profile local   (+ Ollama + ComfyUI)
echo             --profile https   (+ Nginx TLS)
echo             --profile tunnel  (+ Cloudflare Tunnel)
echo  Usage    : start_all.bat docker [--profile local] [--build]
echo  Stop: docker compose down
pause

:end
endlocal
exit /b 0

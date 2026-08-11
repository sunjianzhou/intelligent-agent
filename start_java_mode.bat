@echo off
setlocal enabledelayedexpansion

REM ============================================================================
REM  Java-only startup (Plan 3 cutover / post-retirement)
REM   - Reads JWT_SECRET / ADMIN_PASSWORD from root .env
REM   - Backend data dir: backend/web/data (Java domain services persistence)
REM ============================================================================

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

if not defined JAVA_HOME set "JAVA_HOME=D:\software\jdk21\jdk-21.0.12+8"
set "JAVA=%JAVA_HOME%\bin\java.exe"

if exist "%ROOT%\.env" (
  for /f "usebackq delims=" %%L in ("%ROOT%\.env") do (
    set "LINE=%%L"
    if not "!LINE:~0,1!"=="#" (
      for /f "tokens=1,* delims==" %%A in ("!LINE!") do (
        if "%%A"=="JWT_SECRET" set "JWT_SECRET=%%B"
        if "%%A"=="ADMIN_PASSWORD" set "ADMIN_PASSWORD=%%B"
      )
    )
  )
)

if "%JWT_SECRET%"=="" (
  echo [ERROR] JWT_SECRET not found in .env
  exit /b 1
)

cd /d "%ROOT%\backend\web"
echo Starting Java backend on :8080 ...
"%JAVA%" -jar target\web-1.0-SNAPSHOT.jar
endlocal

@echo off
setlocal
rem Local Maven distribution shim (not a standard Maven Wrapper; no .mvn\wrapper).
rem Prefer the pinned distribution when present, otherwise fall back to mvn on PATH.
rem Keep this file ASCII-only: cmd.exe parses .cmd in the OEM code page.
set "MVN_DIST=%~dp0..\..\..\..\workspace\llm\mock_webflux\maven-dist\apache-maven-3.9.6\bin\mvn.cmd"
if exist "%MVN_DIST%" (
  "%MVN_DIST%" %*
) else (
  mvn %*
)
exit /b %ERRORLEVEL%

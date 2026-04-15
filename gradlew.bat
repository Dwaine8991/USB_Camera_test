@echo off
setlocal

set ROOT=%~dp0
set TOOLS_DIR=%ROOT%.tools
set GRADLE_HOME=%TOOLS_DIR%\gradle-8.10.2

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%scripts\bootstrap-gradle.ps1"
)

if exist "D:\Program Files\Android\Android Studio\jbr" (
  set JAVA_HOME=D:\Program Files\Android\Android Studio\jbr
)

call "%GRADLE_HOME%\bin\gradle.bat" %*

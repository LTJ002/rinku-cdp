@echo off
rem Package D:\wd-cdp-backend into a loadable NeoForge mod jar.
setlocal

set "JAVA_HOME_D=D:\IntelliJ IDEA 2026.3\jbr"
set "ROOT=%~dp0"
set "OUT=%ROOT%build\classes"
set "DIST=%ROOT%build\dist"
set "JAR=%DIST%\wdcdp-1.0.0.jar"

if not exist "%DIST%" mkdir "%DIST%"
if exist "%JAR%" del "%JAR%"

echo [1/2] compiling...
call "%ROOT%build.bat"
if errorlevel 1 exit /b 1

echo [2/2] packaging...
"%JAVA_HOME_D%\bin\jar.exe" --create --file "%JAR%" -C "%OUT%" .
if errorlevel 1 (
  echo PACKAGE FAILED
  exit /b 1
)

echo done: %JAR%
endlocal

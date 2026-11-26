@echo off
rem Build the CDP browser backend.
rem
rem Requires a JDK 25 and a classpath pointing at a NeoForge 26.1.2 install.
rem Copy cp.example.txt to cp.txt and edit the paths for your machine.
setlocal

if not defined JAVA_HOME (
  echo JAVA_HOME is not set - point it at a JDK 25.
  exit /b 1
)

set "ROOT=%~dp0"
set "OUT=%ROOT%build\classes"
set "RES=%ROOT%src\main\resources"

if not exist "%ROOT%cp.txt" (
  echo cp.txt is missing. Copy cp.example.txt to cp.txt and edit the paths.
  exit /b 1
)

if not exist "%OUT%" mkdir "%OUT%"

echo compiling...
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -proc:none -nowarn ^
  -d "%OUT%" ^
  -cp "@%ROOT%cp.txt" ^
  "@%ROOT%sources.txt"
if errorlevel 1 (
  echo COMPILE FAILED
  exit /b 1
)

echo copying resources...
if exist "%RES%" xcopy /E /I /Y /Q "%RES%" "%OUT%" >nul

echo built: %OUT%
endlocal

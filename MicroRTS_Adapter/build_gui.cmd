@echo off
rem Builds MicroRTS-GUI.jar, and MicroRTS-GUI.exe when gcc is available, in this folder.
rem Run it again after changing the code. Bots don't need a rebuild: put them in bots\
rem (.jar, .class or GP tree .ser) and reopen the GUI.
setlocal
cd /d "%~dp0"

if not exist build mkdir build
if exist build\gui-classes rmdir /s /q build\gui-classes
mkdir build\gui-classes

powershell -NoProfile -Command "Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { '\"' + $_.FullName.Replace('\','/') + '\"' } | Set-Content -Encoding ASCII build\gui-sources.txt"
javac -nowarn -encoding UTF-8 --release 17 -cp "lib/*" -d build\gui-classes @build\gui-sources.txt
if errorlevel 1 (
    echo build_gui: compiling failed
    exit /b 1
)

rem The jar finds the libraries in lib\ relative to its own location.
set "CP="
for %%j in (lib\*.jar) do call set "CP=%%CP%% lib/%%~nxj"
> build\gui-manifest.txt (
    echo Main-Class: gui.frontend.FrontEnd
    echo Class-Path:%CP%
)
jar cfm MicroRTS-GUI.jar build\gui-manifest.txt -C build\gui-classes .
if errorlevel 1 (
    echo build_gui: creating MicroRTS-GUI.jar failed
    exit /b 1
)
echo Built MicroRTS-GUI.jar

where gcc >nul 2>nul
if errorlevel 1 (
    echo gcc not found: skipping MicroRTS-GUI.exe. Double-click MicroRTS-GUI.jar instead.
    exit /b 0
)
gcc -O2 -municode -mwindows -o MicroRTS-GUI.exe tools\gui_launcher\MicroRTS-GUI.c
if errorlevel 1 (
    echo build_gui: building MicroRTS-GUI.exe failed; MicroRTS-GUI.jar still works
    exit /b 1
)
echo Built MicroRTS-GUI.exe

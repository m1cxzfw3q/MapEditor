@echo off
setlocal

:: ---- 路径准备 ----
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "TEMP_DIR=%SCRIPT_DIR%\temp"
set "BUILD_DIR=%SCRIPT_DIR%\build"
set "IMPL_DIR=%SCRIPT_DIR%\impl"
set "RES_DIR=%SCRIPT_DIR%\res"
set "OUT_JAR=%BUILD_DIR%\MapEditor.jar"

:: ---- 清理旧的临时目录，避免残留 ----
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"

:: ---- 创建目录 ----
if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

:: ---- 1. 编译 ----
javac -encoding UTF-8 -cp "%SCRIPT_DIR%;%IMPL_DIR%\*" -d "%TEMP_DIR%" "%SCRIPT_DIR%\*.java"
if errorlevel 1 goto :error

:: ---- 2. 解压所有依赖 jar ----
pushd "%TEMP_DIR%"
for %%J in ("%IMPL_DIR%\*.jar") do (
    jar xf "%%J"
    if errorlevel 1 ( popd & goto :error )
)

:: ---- 3. 打包 ----
jar cfm "%OUT_JAR%" "..\MANIFEST.MF" * -C "%RES_DIR%" .
if errorlevel 1 ( popd & goto :error )
popd

:: ---- 4. 清理 ----
rmdir /s /q "%TEMP_DIR%"

echo.
endlocal

:error
echo.
popd 2>nul
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
endlocal
pause
@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: ---- 路径准备 ----
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "TEMP_DIR=%SCRIPT_DIR%\temp"
set "BUILD_DIR=%SCRIPT_DIR%\build"
set "IMPL_DIR=%SCRIPT_DIR%\impl"
set "RES_DIR=%SCRIPT_DIR%\res"
set "OUT_JAR=%BUILD_DIR%\MapEditor.jar"
set "SOURCES_FILE=%BUILD_DIR%\sources.txt"

:: ---- 清理 ----
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"

:: ---- 创建目录 ----
if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
if exist "%SOURCES_FILE%" del "%SOURCES_FILE%"

:: ---- 1. 编译 ----
if not exist "%SCRIPT_DIR%\*.java" (
    echo [ERROR] No .java files found in "%SCRIPT_DIR%"
    goto :error
)

for %%F in ("%SCRIPT_DIR%\*.java") do (
    set "P=%%~fF"
    set "P=!P:\=/!"
    >>"%SOURCES_FILE%" echo "!P!"
)

javac -encoding UTF-8 -cp "%SCRIPT_DIR%;%IMPL_DIR%\*" -d "%TEMP_DIR%" @"%SOURCES_FILE%"
if errorlevel 1 goto :error

:: ---- 2. 解压依赖 jar 到 temp ----
pushd "%TEMP_DIR%"
for %%J in ("%IMPL_DIR%\*.jar") do (
    jar xf "%%J"
    if errorlevel 1 ( popd & goto :error )
)
popd

:: ---- 3. 打包 ----
jar cfm "%OUT_JAR%" "%SCRIPT_DIR%\MANIFEST.MF" -C "%TEMP_DIR%" . -C "%RES_DIR%" .
if errorlevel 1 goto :error

:: ---- 4. 清理 ----
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
if exist "%SOURCES_FILE%" del "%SOURCES_FILE%"

echo.
echo [OK] Built: %OUT_JAR%
endlocal
exit /b 0

:error
echo.
echo [FAILED] Build failed.
popd 2>nul
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
if exist "%SOURCES_FILE%" del "%SOURCES_FILE%"
endlocal
pause
exit /b 1
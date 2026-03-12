@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
rem Strip trailing backslash from SCRIPT_DIR to prevent CMake quote-escape issues
rem (%~dp0 always ends with \, which when quoted as "path\" causes CMake to
rem interpret \" as an escaped quote instead of end-of-string)
set NATIVE_DIR=%SCRIPT_DIR:~0,-1%
set PROJECT_ROOT=%NATIVE_DIR%\..\..
set BUILD_DIR=%NATIVE_DIR%\build
set MSDFGEN_DIR=%NATIVE_DIR%\msdfgen

if not exist "%MSDFGEN_DIR%" (
    echo Cloning msdfgen...
    git clone --depth 1 --branch v1.13 https://github.com/Chlumsky/msdfgen.git "%MSDFGEN_DIR%"
)

set PLATFORM=windows-x64
set OUTPUT_DIR=%PROJECT_ROOT%\src\main\resources\natives\%PLATFORM%

echo Building for: %PLATFORM%
echo MSDFgen source: %MSDFGEN_DIR%
echo Output: %OUTPUT_DIR%

if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"

set CMAKE_FREETYPE_FLAG=
if /i "%MSDFGEN_USE_FREETYPE%"=="ON" (
    set CMAKE_FREETYPE_FLAG=-DMSDFGEN_USE_FREETYPE=ON
    echo FreeType support: ENABLED
) else (
    echo FreeType support: DISABLED (set MSDFGEN_USE_FREETYPE=ON to enable)
)

rem Convert backslashes to forward slashes for CMake path compatibility
set MSDFGEN_DIR_CMAKE=%MSDFGEN_DIR:\=/%

cmake -S "%NATIVE_DIR%" -B "%BUILD_DIR%" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DMSDFGEN_SOURCE_DIR="%MSDFGEN_DIR_CMAKE%" ^
    -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded ^
    %CMAKE_FREETYPE_FLAG%
if errorlevel 1 (
    echo CMake configuration failed
    exit /b 1
)

cmake --build "%BUILD_DIR%" --config Release --parallel
if errorlevel 1 (
    echo Build failed
    exit /b 1
)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

if exist "%BUILD_DIR%\Release\msdfgen-jni.dll" (
    copy "%BUILD_DIR%\Release\msdfgen-jni.dll" "%OUTPUT_DIR%\"
) else (
    copy "%BUILD_DIR%\msdfgen-jni.dll" "%OUTPUT_DIR%\"
)

echo.
echo Native library built successfully: %OUTPUT_DIR%
dir "%OUTPUT_DIR%"

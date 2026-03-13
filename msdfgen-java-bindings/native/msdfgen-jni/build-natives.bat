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

set FREETYPE_VERSION=2.13.2

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

rem Convert backslashes to forward slashes for CMake path compatibility
set MSDFGEN_DIR_CMAKE=%MSDFGEN_DIR:\=/%

set CMAKE_FREETYPE_FLAGS=
if /i "%MSDFGEN_USE_FREETYPE%"=="ON" (
    echo FreeType support: ENABLED
    echo.

    set DEPS_DIR=%BUILD_DIR%\deps
    if not exist "!DEPS_DIR!" mkdir "!DEPS_DIR!"

    if not exist "!DEPS_DIR!\freetype-%FREETYPE_VERSION%" (
        echo === Downloading FreeType %FREETYPE_VERSION% ===
        curl -L "https://download.savannah.gnu.org/releases/freetype/freetype-%FREETYPE_VERSION%.tar.xz" -o "!DEPS_DIR!\freetype.tar.xz"
        cd /d "!DEPS_DIR!" && tar xf freetype.tar.xz
        cd /d "%NATIVE_DIR%"
    )

    echo === Building FreeType ===
    set FT_BUILD=!DEPS_DIR!\freetype-build
    if not exist "!FT_BUILD!" mkdir "!FT_BUILD!"
    if exist "!FT_BUILD!\CMakeCache.txt" del /f "!FT_BUILD!\CMakeCache.txt"

    cmake -S "!DEPS_DIR!\freetype-%FREETYPE_VERSION%" -B "!FT_BUILD!" ^
        -DCMAKE_BUILD_TYPE=Release ^
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON ^
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 ^
        -DBUILD_SHARED_LIBS=OFF ^
        -DFT_DISABLE_BZIP2=ON ^
        -DFT_DISABLE_PNG=ON ^
        -DFT_DISABLE_HARFBUZZ=ON ^
        -DFT_DISABLE_BROTLI=ON ^
        -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded ^
        "-DCMAKE_C_FLAGS_RELEASE=/MT /O2 /DNDEBUG" ^
        "-DCMAKE_CXX_FLAGS_RELEASE=/MT /O2 /DNDEBUG" ^
        "-DCMAKE_C_FLAGS=/MT" ^
        "-DCMAKE_CXX_FLAGS=/MT"
    if errorlevel 1 (
        echo FreeType cmake configure failed
        exit /b 1
    )

    cmake --build "!FT_BUILD!" --config Release
    if errorlevel 1 (
        echo FreeType build failed
        exit /b 1
    )

    set FT_LIB=
    for /r "!FT_BUILD!" %%f in (freetype.lib) do (
        if exist "%%f" set FT_LIB=%%f
    )
    if not defined FT_LIB (
        for /r "!FT_BUILD!" %%f in (freetyped.lib) do (
            if exist "%%f" set FT_LIB=%%f
        )
    )
    if not defined FT_LIB (
        echo ERROR: FreeType static lib not found
        exit /b 1
    )
    echo FreeType lib: !FT_LIB!

    set CMAKE_FREETYPE_FLAGS=-DMSDFGEN_USE_FREETYPE=ON -DFREETYPE_INCLUDE_DIRS="!DEPS_DIR!\freetype-%FREETYPE_VERSION%\include" -DFREETYPE_LIBRARIES="!FT_LIB!"
) else (
    echo FreeType support: DISABLED (set MSDFGEN_USE_FREETYPE=ON to enable)
)

echo.
echo === Building MSDFgen JNI library ===

cmake -S "%NATIVE_DIR%" -B "%BUILD_DIR%" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DMSDFGEN_SOURCE_DIR="%MSDFGEN_DIR_CMAKE%" ^
    -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded ^
    "-DCMAKE_CXX_FLAGS_RELEASE=/MT /O2 /DNDEBUG" ^
    "-DCMAKE_CXX_FLAGS=/MT" ^
    %CMAKE_FREETYPE_FLAGS%
if errorlevel 1 (
    echo CMake configuration failed
    exit /b 1
)

cmake --build "%BUILD_DIR%" --config Release
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

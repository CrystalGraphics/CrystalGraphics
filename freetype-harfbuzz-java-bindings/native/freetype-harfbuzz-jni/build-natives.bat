@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
rem Strip trailing backslash to prevent CMake quote-escape issues
set SCRIPT_DIR=%SCRIPT_DIR:~0,-1%
set PROJECT_ROOT=%SCRIPT_DIR%\..\..
set BUILD_DIR=%SCRIPT_DIR%\build
set OUTPUT_DIR=%PROJECT_ROOT%\src\main\resources\natives\windows-x64

set FREETYPE_VERSION=2.13.2
set HARFBUZZ_VERSION=8.3.0

set DEPS_DIR=%BUILD_DIR%\deps

if not exist "%DEPS_DIR%" mkdir "%DEPS_DIR%"

if not exist "%DEPS_DIR%\freetype-%FREETYPE_VERSION%" (
    echo === Downloading FreeType %FREETYPE_VERSION% ===
    curl -L "https://download.savannah.gnu.org/releases/freetype/freetype-%FREETYPE_VERSION%.tar.xz" -o "%DEPS_DIR%\freetype.tar.xz"
    cd /d "%DEPS_DIR%" && tar xf freetype.tar.xz
    cd /d "%SCRIPT_DIR%"
)

if not exist "%DEPS_DIR%\harfbuzz-%HARFBUZZ_VERSION%" (
    echo === Downloading HarfBuzz %HARFBUZZ_VERSION% ===
    curl -L "https://github.com/harfbuzz/harfbuzz/releases/download/%HARFBUZZ_VERSION%/harfbuzz-%HARFBUZZ_VERSION%.tar.xz" -o "%DEPS_DIR%\harfbuzz.tar.xz"
    cd /d "%DEPS_DIR%" && tar xf harfbuzz.tar.xz
    cd /d "%SCRIPT_DIR%"
)

echo === Building FreeType ===
set FT_BUILD=%DEPS_DIR%\freetype-build
if not exist "%FT_BUILD%" mkdir "%FT_BUILD%"
if exist "%FT_BUILD%\CMakeCache.txt" del /f "%FT_BUILD%\CMakeCache.txt"

cmake -S "%DEPS_DIR%\freetype-%FREETYPE_VERSION%" -B "%FT_BUILD%" ^
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

cmake --build "%FT_BUILD%" --config Release --parallel
if errorlevel 1 (
    echo FreeType build failed
    exit /b 1
)

set FT_LIB=
for /r "%FT_BUILD%" %%f in (freetype.lib) do (
    if exist "%%f" set FT_LIB=%%f
)
if not defined FT_LIB (
    for /r "%FT_BUILD%" %%f in (freetyped.lib) do (
        if exist "%%f" set FT_LIB=%%f
    )
)
if not defined FT_LIB (
    echo ERROR: FreeType static lib not found
    exit /b 1
)
echo FreeType lib: %FT_LIB%

echo === Building HarfBuzz ===
set HB_BUILD=%DEPS_DIR%\harfbuzz-build
if not exist "%HB_BUILD%" mkdir "%HB_BUILD%"
if exist "%HB_BUILD%\CMakeCache.txt" del /f "%HB_BUILD%\CMakeCache.txt"

cmake -S "%DEPS_DIR%\harfbuzz-%HARFBUZZ_VERSION%" -B "%HB_BUILD%" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON ^
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 ^
    -DBUILD_SHARED_LIBS=OFF ^
    -DHB_HAVE_FREETYPE=ON ^
    -DHB_HAVE_CORETEXT=OFF ^
    -DHB_HAVE_GLIB=OFF ^
    -DHB_HAVE_ICU=OFF ^
    -DFREETYPE_INCLUDE_DIRS="%DEPS_DIR%\freetype-%FREETYPE_VERSION%\include" ^
    -DFREETYPE_LIBRARY="%FT_LIB%" ^
    -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded ^
    "-DCMAKE_C_FLAGS_RELEASE=/MT /O2 /DNDEBUG" ^
    "-DCMAKE_CXX_FLAGS_RELEASE=/MT /O2 /DNDEBUG" ^
    "-DCMAKE_C_FLAGS=/MT" ^
    "-DCMAKE_CXX_FLAGS=/MT"
if errorlevel 1 (
    echo HarfBuzz cmake configure failed
    exit /b 1
)

cmake --build "%HB_BUILD%" --config Release --parallel
if errorlevel 1 (
    echo HarfBuzz build failed
    exit /b 1
)

set HB_LIB=
for /r "%HB_BUILD%" %%f in (harfbuzz.lib) do (
    if exist "%%f" set HB_LIB=%%f
)
if not defined HB_LIB (
    echo ERROR: HarfBuzz static lib not found
    exit /b 1
)
echo HarfBuzz lib: %HB_LIB%

echo === Building JNI library ===
set JNI_BUILD=%BUILD_DIR%\jni-build
if not exist "%JNI_BUILD%" mkdir "%JNI_BUILD%"
if exist "%JNI_BUILD%\CMakeCache.txt" del /f "%JNI_BUILD%\CMakeCache.txt"

cmake -S "%SCRIPT_DIR%\src\cpp" -B "%JNI_BUILD%" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DFREETYPE_INCLUDE_DIRS="%DEPS_DIR%\freetype-%FREETYPE_VERSION%\include" ^
    -DFREETYPE_LIBRARIES="%FT_LIB%" ^
    -DHARFBUZZ_INCLUDE_DIRS="%DEPS_DIR%\harfbuzz-%HARFBUZZ_VERSION%\src" ^
    -DHARFBUZZ_LIBRARIES="%HB_LIB%" ^
    -DSTATIC_LINK_DEPS=ON ^
    -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded ^
    "-DCMAKE_C_FLAGS_RELEASE=/MT /O2 /DNDEBUG" ^
    "-DCMAKE_CXX_FLAGS_RELEASE=/MT /O2 /DNDEBUG" ^
    "-DCMAKE_C_FLAGS=/MT" ^
    "-DCMAKE_CXX_FLAGS=/MT"
if errorlevel 1 (
    echo JNI cmake configure failed
    exit /b 1
)

cmake --build "%JNI_BUILD%" --config Release --parallel
if errorlevel 1 (
    echo JNI build failed
    exit /b 1
)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

if exist "%JNI_BUILD%\Release\freetype_harfbuzz_jni.dll" (
    copy "%JNI_BUILD%\Release\freetype_harfbuzz_jni.dll" "%OUTPUT_DIR%\"
) else (
    copy "%JNI_BUILD%\freetype_harfbuzz_jni.dll" "%OUTPUT_DIR%\"
)

echo.
echo === Native library built successfully: %OUTPUT_DIR% ===
dir "%OUTPUT_DIR%"

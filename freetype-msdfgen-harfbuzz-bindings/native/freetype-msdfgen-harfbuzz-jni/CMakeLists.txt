cmake_minimum_required(VERSION 3.15)
project(freetype_msdfgen_harfbuzz_jni C CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Force static CRT (/MT) on MSVC for all targets in this project.
# This must be set BEFORE any add_library/add_executable calls.
# Without this, MSVC defaults to /MD (dynamic CRT), which causes a dual-heap
# crash when the JVM loads msvcp140.dll from its own directory and our DLL
# also tries to use a different instance of the same CRT.
if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>" CACHE STRING "" FORCE)
    # Also inject /MT directly into compiler flags as a belt-and-suspenders measure,
    # since some CMake versions or toolchain files may override MSVC_RUNTIME_LIBRARY.
    foreach(flag_var
        CMAKE_C_FLAGS CMAKE_C_FLAGS_DEBUG CMAKE_C_FLAGS_RELEASE
        CMAKE_C_FLAGS_MINSIZEREL CMAKE_C_FLAGS_RELWITHDEBINFO
        CMAKE_CXX_FLAGS CMAKE_CXX_FLAGS_DEBUG CMAKE_CXX_FLAGS_RELEASE
        CMAKE_CXX_FLAGS_MINSIZEREL CMAKE_CXX_FLAGS_RELWITHDEBINFO)
        if(${flag_var} MATCHES "/MD")
            string(REPLACE "/MD" "/MT" ${flag_var} "${${flag_var}}")
        else()
            # /MD might not be present yet — ensure /MT is explicitly there
            set(${flag_var} "${${flag_var}} /MT")
        endif()
    endforeach()
endif()

# ── JNI ──────────────────────────────────────────────────────────────────────
# Only require JNI_INCLUDE_DIRS (headers), not AWT/JVM shared libs.
# This allows cross-compilation where JVM libs aren't available for target arch.
find_package(JNI)
if(NOT JNI_FOUND)
    if(DEFINED ENV{JAVA_HOME})
        set(JNI_INCLUDE_DIRS
            "$ENV{JAVA_HOME}/include"
        )
        if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
            list(APPEND JNI_INCLUDE_DIRS "$ENV{JAVA_HOME}/include/linux")
        elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
            list(APPEND JNI_INCLUDE_DIRS "$ENV{JAVA_HOME}/include/darwin")
        elseif(WIN32)
            list(APPEND JNI_INCLUDE_DIRS "$ENV{JAVA_HOME}/include/win32")
        endif()
        message(STATUS "JNI headers from JAVA_HOME: ${JNI_INCLUDE_DIRS}")
    else()
        message(FATAL_ERROR "Could not find JNI and JAVA_HOME is not set")
    endif()
endif()

# ── FreeType (shared dependency for both freetype-harfbuzz JNI and msdfgen) ──
if(NOT DEFINED FREETYPE_INCLUDE_DIRS OR NOT DEFINED FREETYPE_LIBRARIES)
    find_package(Freetype)
    if(NOT FREETYPE_FOUND)
        message(FATAL_ERROR "FreeType not found. Pass -DFREETYPE_INCLUDE_DIRS=... and -DFREETYPE_LIBRARIES=...")
    endif()
endif()
if(DEFINED FREETYPE_INCLUDE_DIRS)
    file(TO_CMAKE_PATH "${FREETYPE_INCLUDE_DIRS}" FREETYPE_INCLUDE_DIRS)
endif()
if(DEFINED FREETYPE_LIBRARIES)
    file(TO_CMAKE_PATH "${FREETYPE_LIBRARIES}" FREETYPE_LIBRARIES)
endif()

# ── HarfBuzz ─────────────────────────────────────────────────────────────────
if(NOT DEFINED HARFBUZZ_INCLUDE_DIRS OR NOT DEFINED HARFBUZZ_LIBRARIES)
    find_package(PkgConfig)
    if(PkgConfig_FOUND)
        pkg_check_modules(HARFBUZZ harfbuzz)
    endif()
    if(NOT HARFBUZZ_INCLUDE_DIRS OR NOT HARFBUZZ_LIBRARIES)
        message(FATAL_ERROR "HarfBuzz not found. Pass -DHARFBUZZ_INCLUDE_DIRS=... and -DHARFBUZZ_LIBRARIES=...")
    endif()
endif()

# ── MSDFgen source directory ─────────────────────────────────────────────────
# Set via -DMSDFGEN_SOURCE_DIR=<path> (typically <this-dir>/msdfgen)
if(NOT DEFINED MSDFGEN_SOURCE_DIR)
    message(FATAL_ERROR "MSDFGEN_SOURCE_DIR must be set to the msdfgen source root")
endif()
file(TO_CMAKE_PATH "${MSDFGEN_SOURCE_DIR}" MSDFGEN_SOURCE_DIR)

# Collect msdfgen core sources
file(GLOB MSDFGEN_CORE_SOURCES "${MSDFGEN_SOURCE_DIR}/core/*.cpp")
file(GLOB MSDFGEN_CORE_HEADERS "${MSDFGEN_SOURCE_DIR}/core/*.h" "${MSDFGEN_SOURCE_DIR}/core/*.hpp")

# Collect msdfgen ext sources (always included — FreeType is always available)
file(GLOB MSDFGEN_EXT_SOURCES "${MSDFGEN_SOURCE_DIR}/ext/*.cpp" "${MSDFGEN_SOURCE_DIR}/ext/*.h")
message(STATUS "MSDFgen FreeType support: ENABLED (always-on in unified build)")

# ── Build the unified JNI shared library ─────────────────────────────────────
add_library(freetype_msdfgen_harfbuzz_jni SHARED
    # FreeType + HarfBuzz JNI sources
    ${CMAKE_CURRENT_SOURCE_DIR}/src/cpp/freetype_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/cpp/harfbuzz_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/cpp/integration.cpp
    # MSDFgen JNI source
    ${CMAKE_CURRENT_SOURCE_DIR}/src/cpp/msdfgen_jni.cpp
    # MSDFgen C++ library sources (statically linked)
    ${MSDFGEN_CORE_SOURCES}
    ${MSDFGEN_EXT_SOURCES}
)

target_include_directories(freetype_msdfgen_harfbuzz_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${FREETYPE_INCLUDE_DIRS}
    ${HARFBUZZ_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/src/cpp
    # MSDFgen headers
    ${MSDFGEN_SOURCE_DIR}
    ${MSDFGEN_SOURCE_DIR}/core
    ${MSDFGEN_SOURCE_DIR}/ext
)

target_link_libraries(freetype_msdfgen_harfbuzz_jni PRIVATE
    ${FREETYPE_LIBRARIES}
    ${HARFBUZZ_LIBRARIES}
)

# FreeType may depend on zlib (for gzip font support). When statically linking
# libfreetype.a, zlib symbols must be resolved by our shared library.
# On macOS/Linux, link system zlib; on Windows, MSVC links it automatically.
if(NOT WIN32)
    find_package(ZLIB)
    if(ZLIB_FOUND)
        target_link_libraries(freetype_msdfgen_harfbuzz_jni PRIVATE ${ZLIB_LIBRARIES})
    else()
        target_link_libraries(freetype_msdfgen_harfbuzz_jni PRIVATE z)
    endif()
endif()

# ── Platform-specific settings ───────────────────────────────────────────────
if(WIN32)
    set_target_properties(freetype_msdfgen_harfbuzz_jni PROPERTIES
        PREFIX ""
        SUFFIX ".dll"
    )
    # Static linking: avoid runtime DLL dependencies on target systems
    if(MINGW)
        target_link_options(freetype_msdfgen_harfbuzz_jni PRIVATE
            -static-libgcc
            -static-libstdc++
            -Wl,-Bstatic,--whole-archive -lwinpthread -Wl,--no-whole-archive,-Bdynamic
        )
    endif()
elseif(APPLE)
    set_target_properties(freetype_msdfgen_harfbuzz_jni PROPERTIES
        PREFIX "lib"
        SUFFIX ".dylib"
        INSTALL_RPATH "@loader_path"
    )
    # Universal binary for Intel + Apple Silicon
    if(NOT DEFINED CMAKE_OSX_ARCHITECTURES)
        set(CMAKE_OSX_ARCHITECTURES "x86_64;arm64" CACHE STRING "macOS architectures")
    endif()
    # Minimum macOS version for Apple Silicon support
    set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING "Minimum macOS version")
else()
    set_target_properties(freetype_msdfgen_harfbuzz_jni PROPERTIES
        PREFIX "lib"
        SUFFIX ".so"
    )
    # On Linux with GCC, statically link C/C++ runtime to avoid
    # libgcc_s.so and libstdc++.so dependencies
    if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
        target_link_options(freetype_msdfgen_harfbuzz_jni PRIVATE
            -static-libgcc
            -static-libstdc++
        )
    endif()
endif()

set_target_properties(freetype_msdfgen_harfbuzz_jni PROPERTIES
    OUTPUT_NAME "freetype_msdfgen_harfbuzz_jni"
)

# ── Compiler flags ───────────────────────────────────────────────────────────
if(MSVC)
    target_compile_options(freetype_msdfgen_harfbuzz_jni PRIVATE /W3 /O2)
else()
    target_compile_options(freetype_msdfgen_harfbuzz_jni PRIVATE -Wall -Wextra -O2)
endif()

# MSDFgen compile definitions
target_compile_definitions(freetype_msdfgen_harfbuzz_jni PRIVATE
    MSDFGEN_PUBLIC=
    MSDFGEN_USE_CPP11
    MSDFGEN_USE_FREETYPE
    MSDFGEN_EXTENSIONS
)

# ── Static linking for STATIC_LINK_DEPS mode ────────────────────────────────
if(STATIC_LINK_DEPS)
    if(MINGW)
        find_library(FREETYPE_STATIC_LIB NAMES libfreetype.a PATHS ${FREETYPE_LIBRARY_DIRS})
        find_library(HARFBUZZ_STATIC_LIB NAMES libharfbuzz.a PATHS ${HARFBUZZ_LIBRARY_DIRS})
    else()
        find_library(FREETYPE_STATIC_LIB NAMES freetype libfreetype.a PATHS ${FREETYPE_LIBRARY_DIRS})
        find_library(HARFBUZZ_STATIC_LIB NAMES harfbuzz libharfbuzz.a PATHS ${HARFBUZZ_LIBRARY_DIRS})
    endif()
    if(FREETYPE_STATIC_LIB AND HARFBUZZ_STATIC_LIB)
        target_link_libraries(freetype_msdfgen_harfbuzz_jni PRIVATE
            ${FREETYPE_STATIC_LIB}
            ${HARFBUZZ_STATIC_LIB}
        )
    endif()
endif()

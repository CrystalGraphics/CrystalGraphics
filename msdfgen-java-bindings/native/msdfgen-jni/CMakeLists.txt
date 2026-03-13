cmake_minimum_required(VERSION 3.15)
project(msdfgen-jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 11)
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

# Find JNI - try standard first, fallback to JAVA_HOME headers only
# This allows cross-compilation where JVM libs aren't available for target arch
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

# MSDFgen source directory - set via -DMSDFGEN_SOURCE_DIR=<path>
if(NOT DEFINED MSDFGEN_SOURCE_DIR)
    message(FATAL_ERROR "MSDFGEN_SOURCE_DIR must be set to the msdfgen source root")
endif()

# FreeType support - ON by default; msdfgen builds its own private static copy
option(MSDFGEN_USE_FREETYPE "Enable FreeType font loading support" ON)

# Collect msdfgen core sources
file(GLOB MSDFGEN_CORE_SOURCES "${MSDFGEN_SOURCE_DIR}/core/*.cpp")
file(GLOB MSDFGEN_CORE_HEADERS "${MSDFGEN_SOURCE_DIR}/core/*.h" "${MSDFGEN_SOURCE_DIR}/core/*.hpp")

# Collect msdfgen ext sources (needed for FreeType)
set(MSDFGEN_EXT_SOURCES "")
if(MSDFGEN_USE_FREETYPE)
    # Accept explicit paths from build script (self-contained build);
    # fall back to find_package for system-installed FreeType.
    if(NOT DEFINED FREETYPE_INCLUDE_DIRS OR NOT DEFINED FREETYPE_LIBRARIES)
        find_package(Freetype)
        if(NOT FREETYPE_FOUND)
            message(FATAL_ERROR "FreeType not found. Pass -DFREETYPE_INCLUDE_DIRS=... and -DFREETYPE_LIBRARIES=...")
        endif()
    endif()
    file(GLOB MSDFGEN_EXT_SOURCES "${MSDFGEN_SOURCE_DIR}/ext/*.cpp" "${MSDFGEN_SOURCE_DIR}/ext/*.h")
    message(STATUS "FreeType support: ENABLED")
    message(STATUS "FreeType include: ${FREETYPE_INCLUDE_DIRS}")
    message(STATUS "FreeType library: ${FREETYPE_LIBRARIES}")
else()
    message(STATUS "FreeType support: DISABLED (use -DMSDFGEN_USE_FREETYPE=ON to enable)")
endif()

# Build the JNI shared library with msdfgen statically linked
add_library(msdfgen-jni SHARED
    src/cpp/msdfgen_jni.cpp
    ${MSDFGEN_CORE_SOURCES}
    ${MSDFGEN_EXT_SOURCES}
)

target_include_directories(msdfgen-jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${MSDFGEN_SOURCE_DIR}
    ${MSDFGEN_SOURCE_DIR}/core
)

if(MSDFGEN_USE_FREETYPE)
    target_include_directories(msdfgen-jni PRIVATE
        ${FREETYPE_INCLUDE_DIRS}
        ${MSDFGEN_SOURCE_DIR}/ext
    )
    target_link_libraries(msdfgen-jni PRIVATE ${FREETYPE_LIBRARIES})

    # FreeType may depend on zlib (for gzip font support). When statically linking
    # libfreetype.a, zlib symbols must be resolved by our shared library.
    # On macOS/Linux, link system zlib; on Windows, MSVC links it automatically.
    if(NOT WIN32)
        find_package(ZLIB)
        if(ZLIB_FOUND)
            target_link_libraries(msdfgen-jni PRIVATE ${ZLIB_LIBRARIES})
        else()
            target_link_libraries(msdfgen-jni PRIVATE z)
        endif()
    endif()
endif()

# Platform-specific settings
if(WIN32)
    set_target_properties(msdfgen-jni PROPERTIES
        PREFIX ""
        SUFFIX ".dll"
    )
    # Static linking: avoid runtime DLL dependencies on target systems
    if(MINGW)
        target_link_options(msdfgen-jni PRIVATE
            -static-libgcc
            -static-libstdc++
            -Wl,-Bstatic,--whole-archive -lwinpthread -Wl,--no-whole-archive,-Bdynamic
        )
    endif()
elseif(APPLE)
    set_target_properties(msdfgen-jni PROPERTIES
        PREFIX "lib"
        SUFFIX ".dylib"
    )
    # Universal binary for Intel + Apple Silicon
    if(NOT DEFINED CMAKE_OSX_ARCHITECTURES)
        set(CMAKE_OSX_ARCHITECTURES "x86_64;arm64" CACHE STRING "macOS architectures")
    endif()
    # Minimum macOS version for Apple Silicon support
    set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING "Minimum macOS version")
else()
    set_target_properties(msdfgen-jni PROPERTIES
        PREFIX "lib"
        SUFFIX ".so"
    )
    # On Linux with GCC, statically link C/C++ runtime to avoid
    # libgcc_s.so and libstdc++.so dependencies
    if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
        target_link_options(msdfgen-jni PRIVATE
            -static-libgcc
            -static-libstdc++
        )
    endif()
endif()

# Compiler flags
if(MSVC)
    target_compile_options(msdfgen-jni PRIVATE /W3 /O2)
else()
    target_compile_options(msdfgen-jni PRIVATE -Wall -Wextra -O2)
endif()

target_compile_definitions(msdfgen-jni PRIVATE
    MSDFGEN_PUBLIC=
    MSDFGEN_USE_CPP11
)

if(MSDFGEN_USE_FREETYPE)
    target_compile_definitions(msdfgen-jni PRIVATE
        MSDFGEN_USE_FREETYPE
        MSDFGEN_EXTENSIONS
    )
endif()

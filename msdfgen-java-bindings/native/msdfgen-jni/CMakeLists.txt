cmake_minimum_required(VERSION 3.15)
project(msdfgen-jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

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

# FreeType support - enable with -DMSDFGEN_USE_FREETYPE=ON
option(MSDFGEN_USE_FREETYPE "Enable FreeType font loading support" OFF)

# Collect msdfgen core sources
file(GLOB MSDFGEN_CORE_SOURCES "${MSDFGEN_SOURCE_DIR}/core/*.cpp")
file(GLOB MSDFGEN_CORE_HEADERS "${MSDFGEN_SOURCE_DIR}/core/*.h" "${MSDFGEN_SOURCE_DIR}/core/*.hpp")

# Collect msdfgen ext sources (needed for FreeType)
set(MSDFGEN_EXT_SOURCES "")
if(MSDFGEN_USE_FREETYPE)
    find_package(Freetype REQUIRED)
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
endif()

# Platform-specific settings
if(WIN32)
    set_target_properties(msdfgen-jni PROPERTIES
        PREFIX ""
        SUFFIX ".dll"
    )
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

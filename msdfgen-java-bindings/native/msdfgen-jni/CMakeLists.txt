cmake_minimum_required(VERSION 3.15)
project(msdfgen-jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Find JNI
find_package(JNI REQUIRED)

# MSDFgen source directory - set via -DMSDFGEN_SOURCE_DIR=<path>
if(NOT DEFINED MSDFGEN_SOURCE_DIR)
    message(FATAL_ERROR "MSDFGEN_SOURCE_DIR must be set to the msdfgen source root")
endif()

# Collect msdfgen core sources
file(GLOB MSDFGEN_CORE_SOURCES "${MSDFGEN_SOURCE_DIR}/core/*.cpp")
file(GLOB MSDFGEN_CORE_HEADERS "${MSDFGEN_SOURCE_DIR}/core/*.h" "${MSDFGEN_SOURCE_DIR}/core/*.hpp")

# Build the JNI shared library with msdfgen statically linked
add_library(msdfgen-jni SHARED
    src/cpp/msdfgen_jni.cpp
    ${MSDFGEN_CORE_SOURCES}
)

target_include_directories(msdfgen-jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${MSDFGEN_SOURCE_DIR}
    ${MSDFGEN_SOURCE_DIR}/core
)

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

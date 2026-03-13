cmake_minimum_required(VERSION 3.14)
project(freetype_harfbuzz_jni C CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_C_STANDARD 99)
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
            set(${flag_var} "${${flag_var}} /MT")
        endif()
    endforeach()
endif()

# JNI: only require JNI_INCLUDE_DIRS (headers), not AWT/JVM shared libs
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

if(NOT DEFINED FREETYPE_INCLUDE_DIRS OR NOT DEFINED FREETYPE_LIBRARIES)
    find_package(Freetype)
    if(NOT FREETYPE_FOUND)
        message(FATAL_ERROR "FreeType not found. Pass -DFREETYPE_INCLUDE_DIRS=... and -DFREETYPE_LIBRARIES=...")
    endif()
endif()

if(NOT DEFINED HARFBUZZ_INCLUDE_DIRS OR NOT DEFINED HARFBUZZ_LIBRARIES)
    find_package(PkgConfig)
    if(PkgConfig_FOUND)
        pkg_check_modules(HARFBUZZ harfbuzz)
    endif()
    if(NOT HARFBUZZ_INCLUDE_DIRS OR NOT HARFBUZZ_LIBRARIES)
        message(FATAL_ERROR "HarfBuzz not found. Pass -DHARFBUZZ_INCLUDE_DIRS=... and -DHARFBUZZ_LIBRARIES=...")
    endif()
endif()

add_library(freetype_harfbuzz_jni SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/freetype_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/harfbuzz_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/integration.cpp
)

target_include_directories(freetype_harfbuzz_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${FREETYPE_INCLUDE_DIRS}
    ${HARFBUZZ_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(freetype_harfbuzz_jni
    ${FREETYPE_LIBRARIES}
    ${HARFBUZZ_LIBRARIES}
)

if(NOT WIN32)
    find_package(ZLIB)
    if(ZLIB_FOUND)
        target_link_libraries(freetype_harfbuzz_jni ${ZLIB_LIBRARIES})
    else()
        target_link_libraries(freetype_harfbuzz_jni z)
    endif()
endif()

if(APPLE)
    set_target_properties(freetype_harfbuzz_jni PROPERTIES
        SUFFIX ".dylib"
        INSTALL_RPATH "@loader_path"
    )
elseif(WIN32)
    set_target_properties(freetype_harfbuzz_jni PROPERTIES
        SUFFIX ".dll"
    )
    if(MINGW)
        target_link_options(freetype_harfbuzz_jni PRIVATE
            -static-libgcc
            -static-libstdc++
            -Wl,-Bstatic,--whole-archive -lwinpthread -Wl,--no-whole-archive,-Bdynamic
        )
    endif()
else()
    set_target_properties(freetype_harfbuzz_jni PROPERTIES
        SUFFIX ".so"
    )
    if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
        target_link_options(freetype_harfbuzz_jni PRIVATE
            -static-libgcc
            -static-libstdc++
        )
    endif()
endif()

set_target_properties(freetype_harfbuzz_jni PROPERTIES
    OUTPUT_NAME "freetype_harfbuzz_jni"
    PREFIX "${CMAKE_SHARED_LIBRARY_PREFIX}"
)

if(STATIC_LINK_DEPS)
    if(MINGW)
        find_library(FREETYPE_STATIC_LIB NAMES libfreetype.a PATHS ${FREETYPE_LIBRARY_DIRS})
        find_library(HARFBUZZ_STATIC_LIB NAMES libharfbuzz.a PATHS ${HARFBUZZ_LIBRARY_DIRS})
    else()
        find_library(FREETYPE_STATIC_LIB NAMES freetype libfreetype.a PATHS ${FREETYPE_LIBRARY_DIRS})
        find_library(HARFBUZZ_STATIC_LIB NAMES harfbuzz libharfbuzz.a PATHS ${HARFBUZZ_LIBRARY_DIRS})
    endif()
    if(FREETYPE_STATIC_LIB AND HARFBUZZ_STATIC_LIB)
        target_link_libraries(freetype_harfbuzz_jni
            ${FREETYPE_STATIC_LIB}
            ${HARFBUZZ_STATIC_LIB}
        )
    endif()
endif()

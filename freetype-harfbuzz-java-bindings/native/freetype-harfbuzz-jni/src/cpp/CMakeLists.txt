cmake_minimum_required(VERSION 3.14)
project(freetype_harfbuzz_jni C CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# JNI: only require JNI_INCLUDE_DIRS (headers), not AWT/JVM shared libs
# This allows cross-compilation where JVM libs aren't available for target arch
find_package(JNI)
if(NOT JNI_FOUND)
    # Fallback: try to find just the headers from JAVA_HOME
    if(DEFINED ENV{JAVA_HOME})
        set(JNI_INCLUDE_DIRS
            "$ENV{JAVA_HOME}/include"
        )
        # Add platform-specific subdirectory
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
    # Try find_package first; if it fails and we have include dirs, that's OK
    find_package(Freetype)
    if(NOT FREETYPE_FOUND)
        message(FATAL_ERROR "FreeType not found. Pass -DFREETYPE_INCLUDE_DIRS=... and -DFREETYPE_LIBRARIES=...")
    endif()
    # find_package sets FREETYPE_INCLUDE_DIRS and FREETYPE_LIBRARIES
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
    # When building with MinGW, statically link the C/C++ runtime and pthreads
    # to avoid requiring libgcc_s_seh-1.dll, libstdc++-6.dll, libwinpthread-1.dll
    # on the target system. MSVC builds are unaffected by these flags.
    if(MINGW)
        target_link_options(freetype_harfbuzz_jni PRIVATE
            -static-libgcc
            -static-libstdc++
            -Wl,-Bstatic,--whole-archive -lwinpthread -Wl,--no-whole-archive,-Bdynamic
        )
    elseif(MSVC)
        # Use static MSVC runtime (/MT) instead of dynamic (/MD) to avoid
        # requiring VCRUNTIME140.dll / MSVCP140.dll on the target system
        set_property(TARGET freetype_harfbuzz_jni PROPERTY
            MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>"
        )
    endif()
else()
    set_target_properties(freetype_harfbuzz_jni PROPERTIES
        SUFFIX ".so"
    )
    # On Linux with GCC, statically link C/C++ runtime to avoid
    # libgcc_s.so and libstdc++.so dependencies
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

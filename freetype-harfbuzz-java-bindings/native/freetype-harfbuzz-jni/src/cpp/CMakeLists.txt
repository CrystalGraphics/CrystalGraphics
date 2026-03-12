cmake_minimum_required(VERSION 3.14)
project(freetype_harfbuzz_jni C CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED)
find_package(Freetype REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(HARFBUZZ REQUIRED harfbuzz)

add_library(freetype_harfbuzz_jni SHARED
    src/cpp/freetype_jni.cpp
    src/cpp/harfbuzz_jni.cpp
    src/cpp/integration.cpp
)

target_include_directories(freetype_harfbuzz_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${FREETYPE_INCLUDE_DIRS}
    ${HARFBUZZ_INCLUDE_DIRS}
    src/cpp
)

target_link_libraries(freetype_harfbuzz_jni
    ${FREETYPE_LIBRARIES}
    ${HARFBUZZ_LIBRARIES}
)

if(APPLE)
    set_target_properties(freetype_harfbuzz_jni PROPERTIES
        SUFFIX ".dylib"
        INSTALL_RPATH "@loader_path"
    )
elseif(WIN32)
    set_target_properties(freetype_harfbuzz_jni PROPERTIES
        SUFFIX ".dll"
    )
else()
    set_target_properties(freetype_harfbuzz_jni PROPERTIES
        SUFFIX ".so"
    )
endif()

set_target_properties(freetype_harfbuzz_jni PROPERTIES
    OUTPUT_NAME "freetype_harfbuzz_jni"
    PREFIX "${CMAKE_SHARED_LIBRARY_PREFIX}"
)

if(STATIC_LINK_DEPS)
    find_library(FREETYPE_STATIC_LIB libfreetype.a freetype PATHS ${FREETYPE_LIBRARY_DIRS})
    find_library(HARFBUZZ_STATIC_LIB libharfbuzz.a harfbuzz PATHS ${HARFBUZZ_LIBRARY_DIRS})
    if(FREETYPE_STATIC_LIB AND HARFBUZZ_STATIC_LIB)
        target_link_libraries(freetype_harfbuzz_jni
            ${FREETYPE_STATIC_LIB}
            ${HARFBUZZ_STATIC_LIB}
        )
    endif()
endif()

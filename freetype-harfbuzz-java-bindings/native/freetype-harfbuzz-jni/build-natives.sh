#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
OUTPUT_DIR="$PROJECT_ROOT/src/main/resources/natives"

FREETYPE_VERSION="2.13.2"
HARFBUZZ_VERSION="8.3.0"

detect_platform() {
    local os arch
    case "$(uname -s)" in
        Linux*)  os="linux" ;;
        Darwin*) os="macos" ;;
        MINGW*|MSYS*|CYGWIN*) os="windows" ;;
        *) echo "Unsupported OS: $(uname -s)"; exit 1 ;;
    esac
    case "$(uname -m)" in
        x86_64|amd64) arch="x64" ;;
        aarch64|arm64) arch="aarch64" ;;
        *) echo "Unsupported arch: $(uname -m)"; exit 1 ;;
    esac
    echo "${os}-${arch}"
}

build_deps() {
    local deps_dir="$BUILD_DIR/deps"
    mkdir -p "$deps_dir"

    if [ ! -d "$deps_dir/freetype-${FREETYPE_VERSION}" ]; then
        echo "=== Downloading FreeType ${FREETYPE_VERSION} ==="
        curl -L "https://download.savannah.gnu.org/releases/freetype/freetype-${FREETYPE_VERSION}.tar.xz" \
            -o "$deps_dir/freetype.tar.xz"
        cd "$deps_dir" && tar xf freetype.tar.xz && cd -
    fi

    if [ ! -d "$deps_dir/harfbuzz-${HARFBUZZ_VERSION}" ]; then
        echo "=== Downloading HarfBuzz ${HARFBUZZ_VERSION} ==="
        curl -L "https://github.com/harfbuzz/harfbuzz/releases/download/${HARFBUZZ_VERSION}/harfbuzz-${HARFBUZZ_VERSION}.tar.xz" \
            -o "$deps_dir/harfbuzz.tar.xz"
        cd "$deps_dir" && tar xf harfbuzz.tar.xz && cd -
    fi

    echo "=== Building FreeType ==="
    local ft_build="$deps_dir/freetype-build"
    mkdir -p "$ft_build"
    cmake -S "$deps_dir/freetype-${FREETYPE_VERSION}" -B "$ft_build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DBUILD_SHARED_LIBS=OFF \
        -DFT_DISABLE_BZIP2=ON \
        -DFT_DISABLE_PNG=ON \
        -DFT_DISABLE_HARFBUZZ=ON \
        -DFT_DISABLE_BROTLI=ON
    cmake --build "$ft_build" --config Release -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

    echo "=== Building HarfBuzz ==="
    local hb_build="$deps_dir/harfbuzz-build"
    mkdir -p "$hb_build"

    local ft_lib_for_hb
    ft_lib_for_hb=$(find "$ft_build" -name "libfreetype.a" -o -name "freetype.lib" 2>/dev/null | head -1)
    if [ -z "$ft_lib_for_hb" ]; then
        echo "ERROR: FreeType static lib not found after build"
        find "$ft_build" -type f -name "*.a" -o -name "*.lib" 2>/dev/null
        exit 1
    fi

    cmake -S "$deps_dir/harfbuzz-${HARFBUZZ_VERSION}" -B "$hb_build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DBUILD_SHARED_LIBS=OFF \
        -DHB_HAVE_FREETYPE=ON \
        -DHB_HAVE_CORETEXT=OFF \
        -DHB_HAVE_GLIB=OFF \
        -DHB_HAVE_ICU=OFF \
        -DFREETYPE_INCLUDE_DIRS="$deps_dir/freetype-${FREETYPE_VERSION}/include" \
        -DFREETYPE_LIBRARY="$ft_lib_for_hb"
    cmake --build "$hb_build" --config Release -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

    # Find the built static libs (location varies by platform/generator)
    FREETYPE_STATIC_LIB=$(find "$ft_build" -name "libfreetype.a" -o -name "freetype.lib" 2>/dev/null | head -1)
    HARFBUZZ_STATIC_LIB=$(find "$hb_build" -name "libharfbuzz.a" -o -name "harfbuzz.lib" 2>/dev/null | head -1)

    if [ -z "$FREETYPE_STATIC_LIB" ]; then
        echo "ERROR: Could not find built FreeType static library in $ft_build"
        find "$ft_build" -name "*.a" -o -name "*.lib" 2>/dev/null
        exit 1
    fi
    if [ -z "$HARFBUZZ_STATIC_LIB" ]; then
        echo "ERROR: Could not find built HarfBuzz static library in $hb_build"
        find "$hb_build" -name "*.a" -o -name "*.lib" 2>/dev/null
        exit 1
    fi

    echo "FreeType static lib: $FREETYPE_STATIC_LIB"
    echo "HarfBuzz static lib: $HARFBUZZ_STATIC_LIB"

    export FREETYPE_DIR="$deps_dir/freetype-${FREETYPE_VERSION}"
    export FREETYPE_LIB="$FREETYPE_STATIC_LIB"
    export HARFBUZZ_DIR="$deps_dir/harfbuzz-${HARFBUZZ_VERSION}"
    export HARFBUZZ_LIB="$HARFBUZZ_STATIC_LIB"
}

build_jni() {
    local platform=$(detect_platform)
    local jni_build="$BUILD_DIR/jni-build"
    mkdir -p "$jni_build"

    echo "=== Building JNI library for ${platform} ==="

    cmake -S "$SCRIPT_DIR/src/cpp" -B "$jni_build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DFREETYPE_INCLUDE_DIRS="${FREETYPE_DIR}/include" \
        -DFREETYPE_LIBRARIES="${FREETYPE_LIB}" \
        -DHARFBUZZ_INCLUDE_DIRS="${HARFBUZZ_DIR}/src" \
        -DHARFBUZZ_LIBRARIES="${HARFBUZZ_LIB}" \
        -DSTATIC_LINK_DEPS=ON

    cmake --build "$jni_build" --config Release -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

    local dest_dir="$OUTPUT_DIR/$platform"
    mkdir -p "$dest_dir"

    case "$platform" in
        windows-*) cp "$jni_build/freetype_harfbuzz_jni.dll" "$dest_dir/" ;;
        linux-*)   cp "$jni_build/libfreetype_harfbuzz_jni.so" "$dest_dir/" ;;
        macos-*)   cp "$jni_build/libfreetype_harfbuzz_jni.dylib" "$dest_dir/" ;;
    esac

    echo "=== Native library installed to: $dest_dir ==="
    ls -la "$dest_dir"
}

echo "Platform: $(detect_platform)"
echo "Building dependencies..."
build_deps
echo "Building JNI library..."
build_jni
echo "=== Build complete ==="

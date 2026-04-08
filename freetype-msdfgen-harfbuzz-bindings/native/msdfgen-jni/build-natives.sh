#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
NATIVE_DIR="$SCRIPT_DIR"
BUILD_DIR="$NATIVE_DIR/build"
MSDFGEN_DIR="${MSDFGEN_SOURCE_DIR:-$NATIVE_DIR/msdfgen}"
IMPORT_FONT_OVERRIDE="$NATIVE_DIR/overrides/msdfgen/ext/import-font.cpp"

FREETYPE_VERSION="2.13.2"

if [ ! -d "$MSDFGEN_DIR" ]; then
    echo "Cloning msdfgen..."
    git clone --depth 1 --branch v1.13 https://github.com/Chlumsky/msdfgen.git "$MSDFGEN_DIR"
fi

if [ -f "$IMPORT_FONT_OVERRIDE" ]; then
    echo "Applying local msdfgen import-font override..."
    cp "$IMPORT_FONT_OVERRIDE" "$MSDFGEN_DIR/ext/import-font.cpp"
fi

OS_NAME="$(uname -s)"

case "$OS_NAME" in
    Darwin)  OS_ID="macos" ;;
    Linux)   OS_ID="linux" ;;
    MINGW*|MSYS*|CYGWIN*) OS_ID="windows" ;;
    *)       echo "Unsupported OS: $OS_NAME"; exit 1 ;;
esac

if [ -n "${MACOS_TARGET_ARCH:-}" ] && [ "$OS_ID" = "macos" ]; then
    case "$MACOS_TARGET_ARCH" in
        x86_64|amd64) ARCH_ID="x64" ;;
        aarch64|arm64) ARCH_ID="aarch64" ;;
        *) echo "Unsupported MACOS_TARGET_ARCH: $MACOS_TARGET_ARCH"; exit 1 ;;
    esac
    echo "=== Cross-compiling: host=$(uname -m), target=$MACOS_TARGET_ARCH ===" >&2
else
    ARCH_NAME="$(uname -m)"
    case "$ARCH_NAME" in
        x86_64|amd64) ARCH_ID="x64" ;;
        aarch64|arm64) ARCH_ID="aarch64" ;;
        *) echo "Unsupported arch: $ARCH_NAME"; exit 1 ;;
    esac
fi

PLATFORM="${OS_ID}-${ARCH_ID}"
OUTPUT_DIR="$PROJECT_ROOT/src/main/resources/natives/$PLATFORM"

get_macos_cmake_flags() {
    local flags=()
    if [ "$(uname -s)" = "Darwin" ]; then
        local target_arch="${MACOS_TARGET_ARCH:-$(uname -m)}"
        case "$target_arch" in
            x86_64|amd64) flags+=(-DCMAKE_OSX_ARCHITECTURES=x86_64) ;;
            aarch64|arm64) flags+=(-DCMAKE_OSX_ARCHITECTURES=arm64) ;;
        esac
        flags+=(-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0)
    fi
    echo "${flags[@]+"${flags[@]}"}"
}

build_freetype() {
    local deps_dir="$BUILD_DIR/deps"
    mkdir -p "$deps_dir"

    if [ ! -d "$deps_dir/freetype-${FREETYPE_VERSION}" ]; then
        echo "=== Downloading FreeType ${FREETYPE_VERSION} ==="
        curl -L "https://download.savannah.gnu.org/releases/freetype/freetype-${FREETYPE_VERSION}.tar.xz" \
            -o "$deps_dir/freetype.tar.xz"
        cd "$deps_dir" && tar xf freetype.tar.xz && cd -
    fi

    local extra_cmake_flags=()
    if command -v gcc &>/dev/null && [[ "$(cc --version 2>&1)" == *"gcc"* || "$(cc --version 2>&1)" == *"GCC"* ]]; then
        echo "=== Detected GCC/MinGW compiler — will use static linking ==="
        extra_cmake_flags+=(-DCMAKE_C_FLAGS="-static-libgcc" -DCMAKE_CXX_FLAGS="-static-libgcc -static-libstdc++")
    fi

    local macos_flags
    macos_flags=$(get_macos_cmake_flags)

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
        -DFT_DISABLE_BROTLI=ON \
        -DFT_DISABLE_GZIP=ON \
        ${extra_cmake_flags[@]+"${extra_cmake_flags[@]}"} \
        $macos_flags
    cmake --build "$ft_build" --config Release -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

    FREETYPE_STATIC_LIB=$(find "$ft_build" -name "libfreetype.a" -o -name "freetype.lib" 2>/dev/null | head -1)
    if [ -z "$FREETYPE_STATIC_LIB" ]; then
        echo "ERROR: Could not find built FreeType static library in $ft_build"
        find "$ft_build" -name "*.a" -o -name "*.lib" 2>/dev/null
        exit 1
    fi

    echo "FreeType static lib: $FREETYPE_STATIC_LIB"

    export FREETYPE_DIR="$deps_dir/freetype-${FREETYPE_VERSION}"
    export FREETYPE_LIB="$FREETYPE_STATIC_LIB"
}

echo "Building for: $PLATFORM"
echo "MSDFgen source: $MSDFGEN_DIR"
echo "Output: $OUTPUT_DIR"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

CMAKE_ARGS=(
    -S "$NATIVE_DIR"
    -B "$BUILD_DIR"
    -DCMAKE_BUILD_TYPE=Release
    -DMSDFGEN_SOURCE_DIR="$MSDFGEN_DIR"
)

# === Build msdfgen's own private static FreeType copy ===
# msdfgen needs its own FreeType (not shared with freetype-harfbuzz-jni)
# because they operate in separate heap spaces and need isolated copies.
echo "FreeType support: ENABLED (always-on for msdfgen)"
build_freetype
CMAKE_ARGS+=(
    -DMSDFGEN_USE_FREETYPE=ON
    -DFREETYPE_INCLUDE_DIRS="${FREETYPE_DIR}/include"
    -DFREETYPE_LIBRARIES="${FREETYPE_LIB}"
)

if [ "$OS_ID" = "macos" ]; then
    if [ "$ARCH_ID" = "aarch64" ]; then
        CMAKE_ARGS+=(-DCMAKE_OSX_ARCHITECTURES=arm64)
    else
        CMAKE_ARGS+=(-DCMAKE_OSX_ARCHITECTURES=x86_64)
    fi
    CMAKE_ARGS+=(-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0)
fi

cmake "${CMAKE_ARGS[@]}"
cmake --build "$BUILD_DIR" --config Release -- -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

mkdir -p "$OUTPUT_DIR"

if [ "$OS_ID" = "windows" ]; then
    cp "$BUILD_DIR/Release/msdfgen-jni.dll" "$OUTPUT_DIR/" 2>/dev/null || \
    cp "$BUILD_DIR/msdfgen-jni.dll" "$OUTPUT_DIR/"
elif [ "$OS_ID" = "macos" ]; then
    cp "$BUILD_DIR/libmsdfgen-jni.dylib" "$OUTPUT_DIR/"
else
    cp "$BUILD_DIR/libmsdfgen-jni.so" "$OUTPUT_DIR/"
fi

echo "Native library built successfully: $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"

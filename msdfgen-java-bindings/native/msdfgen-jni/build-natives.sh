#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
NATIVE_DIR="$SCRIPT_DIR"
BUILD_DIR="$NATIVE_DIR/build"
MSDFGEN_DIR="${MSDFGEN_SOURCE_DIR:-$NATIVE_DIR/msdfgen}"

if [ ! -d "$MSDFGEN_DIR" ]; then
    echo "Cloning msdfgen..."
    git clone --depth 1 --branch v1.13 https://github.com/Chlumsky/msdfgen.git "$MSDFGEN_DIR"
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

# Enable FreeType support if requested via MSDFGEN_USE_FREETYPE=ON
if [ "${MSDFGEN_USE_FREETYPE:-OFF}" = "ON" ]; then
    CMAKE_ARGS+=(-DMSDFGEN_USE_FREETYPE=ON)
    echo "FreeType support: ENABLED"
else
    echo "FreeType support: DISABLED (set MSDFGEN_USE_FREETYPE=ON to enable)"
fi

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

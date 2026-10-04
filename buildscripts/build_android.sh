#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ROOT_DIR="$( cd "$DIR/.." && pwd )"

ARCH="arm64"
BUILD_TYPE="Release"
NDK_PATH="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$NDK_HOME}}"

usage() {
    echo "Usage: ./build_android.sh [--arch arm64|arm|x86_64] [--debug|--release] [--ndk /path/to/ndk]"
    echo "  --arch: target architecture (default: arm64)"
    echo "  --ndk: path to Android NDK"
    echo "  --debug: build debug binaries"
    echo "  --release: build release binaries (default)"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h)
            usage
            ;;
        --arch)
            ARCH="$2"
            shift 2
            ;;
        --ndk)
            NDK_PATH="$2"
            shift 2
            ;;
        --debug)
            BUILD_TYPE="Debug"
            shift
            ;;
        --release)
            BUILD_TYPE="Release"
            shift
            ;;
        *)
            echo "Unknown argument: $1"
            exit 1
            ;;
    esac
done

case "$ARCH" in
    arm64|aarch64)
        ABI="arm64-v8a"
        ;;
    arm|armeabi-v7a)
        ABI="armeabi-v7a"
        ;;
    x86_64)
        ABI="x86_64"
        ;;
    *)
        echo "Unsupported architecture: $ARCH (choose arm64, arm, or x86_64)"
        exit 1
        ;;
esac

if [ -z "$NDK_PATH" ] || [ ! -d "$NDK_PATH" ]; then
    COMMON_LOCATIONS=(
        "$ANDROID_HOME/ndk/"*
        "$HOME/Android/Sdk/ndk/"*
        "$HOME/Android/Sdk/ndk-bundle"
        "/usr/local/lib/android/sdk/ndk/"*
        "/opt/android-ndk"*
        "/opt/android-sdk/ndk/"*
    )
    for loc in "${COMMON_LOCATIONS[@]}"; do
        if [ -d "$loc" ]; then
            NDK_PATH="$loc"
            break
        fi
    done
fi

if [ -z "$NDK_PATH" ] || [ ! -d "$NDK_PATH" ]; then
    echo "================================================================="
    echo "ERROR: Android NDK not found in environment."
    echo "Please set ANDROID_NDK_HOME or specify --ndk /path/to/android-ndk"
    echo "================================================================="
    exit 1
fi

TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN_FILE" ]; then
    echo "Error: Android CMake toolchain file not found at: $TOOLCHAIN_FILE"
    exit 1
fi

NPROC=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
DEPS_ROOT="$ROOT_DIR/build_android/deps"
DEPS_PREFIX="$DEPS_ROOT/installed/$ABI"
SRC_CACHE="/tmp/nearchuckle_deps"
mkdir -p "$DEPS_PREFIX" "$SRC_CACHE"

echo "================================================================="
echo "Building NearChuckle (Far Cry) for Android"
echo "  ABI:         $ABI"
echo "  Build Type:  $BUILD_TYPE"
echo "  NDK Path:    $NDK_PATH"
echo "  Deps Prefix: $DEPS_PREFIX"
echo "================================================================="

# 1. Build SDL3
if [ ! -f "$DEPS_PREFIX/lib/libSDL3.so" ]; then
    echo "--> Building SDL3 for $ABI..."
    if [ ! -d "$SRC_CACHE/SDL" ]; then
        if [ -d "/tmp/SDL3" ]; then
            cp -r /tmp/SDL3 "$SRC_CACHE/SDL"
        else
            git clone --depth 1 https://github.com/libsdl-org/SDL "$SRC_CACHE/SDL"
        fi
    fi
    if [ -f "$ROOT_DIR/buildscripts/patches/sdl3_stack_size.patch" ]; then
        echo "Applying SDL3 16MB thread stack patch..."
        (cd "$SRC_CACHE/SDL" && git apply --ignore-whitespace "$ROOT_DIR/buildscripts/patches/sdl3_stack_size.patch" || true)
    fi
    cmake -B "$DEPS_ROOT/build-sdl3-$ABI" -S "$SRC_CACHE/SDL" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" \
        -DSDL_SHARED=ON \
        -DSDL_STATIC=OFF \
        -DSDL_TESTS=OFF \
        -DSDL_TEST_LIBRARY=OFF
    cmake --build "$DEPS_ROOT/build-sdl3-$ABI" --target install -- -j"$NPROC"
fi

# 2. Build libogg
if [ ! -f "$DEPS_PREFIX/lib/libogg.so" ]; then
    echo "--> Building libogg for $ABI..."
    if [ ! -d "$SRC_CACHE/ogg" ]; then
        git clone --depth 1 https://github.com/xiph/ogg "$SRC_CACHE/ogg"
    fi
    cmake -B "$DEPS_ROOT/build-ogg-$ABI" -S "$SRC_CACHE/ogg" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" \
        -DBUILD_SHARED_LIBS=ON \
        -DINSTALL_DOCS=OFF
    cmake --build "$DEPS_ROOT/build-ogg-$ABI" --target install -- -j"$NPROC"
fi

# 3. Build libvorbis
if [ ! -f "$DEPS_PREFIX/lib/libvorbis.so" ]; then
    echo "--> Building libvorbis for $ABI..."
    if [ ! -d "$SRC_CACHE/vorbis" ]; then
        git clone --depth 1 https://github.com/xiph/vorbis "$SRC_CACHE/vorbis"
    fi
    cmake -B "$DEPS_ROOT/build-vorbis-$ABI" -S "$SRC_CACHE/vorbis" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" \
        -DBUILD_SHARED_LIBS=ON \
        -DOGG_LIBRARY="$DEPS_PREFIX/lib/libogg.so" \
        -DOGG_INCLUDE_DIR="$DEPS_PREFIX/include" \
        -DBUILD_TESTING=OFF
    cmake --build "$DEPS_ROOT/build-vorbis-$ABI" --target install -- -j"$NPROC"
fi

# 4. Build openal-soft (pinned to 1.23.1 for Android NDK C++17 compatibility)
if [ ! -f "$DEPS_PREFIX/lib/libopenal.so" ]; then
    echo "--> Building openal-soft for $ABI..."
    if [ ! -d "$SRC_CACHE/openal-soft" ]; then
        git clone --branch 1.23.1 --depth 1 https://github.com/kcat/openal-soft "$SRC_CACHE/openal-soft"
    fi
    cmake -B "$DEPS_ROOT/build-openal-$ABI" -S "$SRC_CACHE/openal-soft" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" \
        -DLIBTYPE=SHARED \
        -DALSOFT_UTILS=OFF \
        -DALSOFT_EXAMPLES=OFF \
        -DALSOFT_TESTS=OFF
    cmake --build "$DEPS_ROOT/build-openal-$ABI" --target install -- -j"$NPROC"
fi

# 5. Build gl4es (OpenGL 2.1 / ARB shader translation on OpenGL ES)
if [ ! -f "$DEPS_PREFIX/lib/libGL.so" ]; then
    echo "--> Building gl4es for $ABI..."
    if [ ! -d "$SRC_CACHE/gl4es" ]; then
        git clone --depth 1 https://github.com/ptitSeb/gl4es "$SRC_CACHE/gl4es"
    fi
    if [ -f "$ROOT_DIR/buildscripts/patches/gl4es_fix_arb_programs.patch" ]; then
        echo "Applying gl4es ARB program crash fix patch..."
        (cd "$SRC_CACHE/gl4es" && git apply --ignore-whitespace "$ROOT_DIR/buildscripts/patches/gl4es_fix_arb_programs.patch" || true)
    fi
    cmake -B "$DEPS_ROOT/build-gl4es-$ABI" -S "$SRC_CACHE/gl4es" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" \
        -DANDROID=1 \
        -DNOX11=1 \
        -DDEFAULT_ES=2 \
        -DSTATICLIB=OFF
    cmake --build "$DEPS_ROOT/build-gl4es-$ABI" -- -j"$NPROC"
    mkdir -p "$DEPS_PREFIX/lib"
    GL4ES_SO=$(find "$DEPS_ROOT/build-gl4es-$ABI" "$SRC_CACHE/gl4es" -name "libGL.so*" -type f 2>/dev/null | head -n 1)
    if [ -n "$GL4ES_SO" ] && [ -f "$GL4ES_SO" ]; then
        cp -f "$GL4ES_SO" "$DEPS_PREFIX/lib/libGL.so"
        echo "Successfully built and deployed gl4es libGL.so from $GL4ES_SO!"
    else
        echo "ERROR: Failed to find built libGL.so from gl4es!"
        exit 1
    fi
fi

# 6. Build NearChuckle Engine
echo "=== STEP: CMAKE CONFIGURE ENGINE ==="
BUILD_DIR="$ROOT_DIR/build_android/$ABI"
mkdir -p "$BUILD_DIR"

cmake -B "$BUILD_DIR" -S "$ROOT_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DANDROID_STL=c++_shared \
    -DANDROID_ALLOW_UNDEFINED_SYMBOLS=ON \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
    -DCMAKE_PREFIX_PATH="$DEPS_PREFIX" \
    -DCMAKE_SHARED_LINKER_FLAGS="-L$DEPS_PREFIX/lib -Wl,--unresolved-symbols=ignore-all -Wl,--no-fatal-warnings" \
    -DCMAKE_EXE_LINKER_FLAGS="-L$DEPS_PREFIX/lib -Wl,--unresolved-symbols=ignore-all -Wl,--no-fatal-warnings" \
    -DDISABLE_CG=ON \
    -DDISABLE_FFMPEG=ON \
    -DCMAKE_EXPORT_COMPILE_COMMANDS=ON

echo "=== STEP: CMAKE BUILD ENGINE ==="
cmake --build "$BUILD_DIR" --verbose -- -j"$NPROC"

echo "=== STEP: DEPLOY SHARED LIBS ==="
JNI_LIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/$ABI"
mkdir -p "$JNI_LIBS_DIR"

# Copy libc++_shared
case "$ABI" in
    arm64-v8a)   LIBCXX_PATTERN="aarch64" ;;
    armeabi-v7a) LIBCXX_PATTERN="arm-linux" ;;
    x86_64)      LIBCXX_PATTERN="x86_64" ;;
    x86)         LIBCXX_PATTERN="i686" ;;
    *)           LIBCXX_PATTERN="$ABI" ;;
esac

echo "Searching libc++_shared.so for pattern: $LIBCXX_PATTERN..."
LIBCXX_FOUND=""
for candidate in $(find "$NDK_PATH" -name "libc++_shared.so" 2>/dev/null); do
    if echo "$candidate" | grep -q "$LIBCXX_PATTERN"; then
        LIBCXX_FOUND="$candidate"
        break
    fi
done

if [ -n "$LIBCXX_FOUND" ] && [ -f "$LIBCXX_FOUND" ]; then
    echo "Found libc++_shared.so: $LIBCXX_FOUND"
    cp -f "$LIBCXX_FOUND" "$JNI_LIBS_DIR/"
else
    echo "WARNING: libc++_shared.so not found for $LIBCXX_PATTERN in $NDK_PATH"
fi

# Copy dependency libraries (SDL3, openal, ogg, vorbis, vorbisfile)
find "$DEPS_PREFIX/lib" -name "*.so" -exec cp -L -f {} "$JNI_LIBS_DIR/" \; 2>/dev/null || true

# Copy built NearChuckle and CryEngine libraries
find "$BUILD_DIR" -name "*.so" -exec cp -L -f {} "$JNI_LIBS_DIR/" \; 2>/dev/null || true
find "$ROOT_DIR/bin" -name "*.so" -exec cp -L -f {} "$JNI_LIBS_DIR/" \; 2>/dev/null || true
rm -f "$JNI_LIBS_DIR"/*.so.* 2>/dev/null || true

# Ensure libGL.so (gl4es) is present in jniLibs
if [ -f "$DEPS_PREFIX/lib/libGL.so" ]; then
    cp -f "$DEPS_PREFIX/lib/libGL.so" "$JNI_LIBS_DIR/libGL.so"
fi

# Mesa's Zink through OSMesa (the launcher's "Mesa Zink" renderer, see AndroidGLBackend.cpp): the
# arm64 libOSMesa.so of PojavLauncher (Mesa 23.0.4 with Zink, MIT license), pinned and checked
if [ "$ABI" = "arm64-v8a" ]; then
    OSMESA_URL="https://raw.githubusercontent.com/PojavLauncherTeam/PojavLauncher/98947f23d338012c5869dc13de31cb39dadba333/app_pojavlauncher/src/main/jniLibs/arm64-v8a/libOSMesa.so"
    OSMESA_SHA256="d6e96eb4bbbfbdcdf79a77ba72e018d8a93ccf4643d5c1591265f2b162ad3160"
    OSMESA_CACHE="$SRC_CACHE/libOSMesa-$OSMESA_SHA256.so"
    if [ ! -f "$OSMESA_CACHE" ]; then
        curl -fsSL "$OSMESA_URL" -o "$OSMESA_CACHE.part"
        mv "$OSMESA_CACHE.part" "$OSMESA_CACHE"
    fi
    echo "$OSMESA_SHA256  $OSMESA_CACHE" | sha256sum -c -
    cp -f "$OSMESA_CACHE" "$JNI_LIBS_DIR/libOSMesa.so"
fi

# Verify critical libraries exist
if [ ! -f "$JNI_LIBS_DIR/libc++_shared.so" ]; then
    echo "ERROR: libc++_shared.so is missing from $JNI_LIBS_DIR!"
    exit 1
fi
if [ ! -f "$JNI_LIBS_DIR/libGL.so" ]; then
    echo "ERROR: libGL.so (gl4es) is missing from $JNI_LIBS_DIR!"
    exit 1
fi
if [ ! -f "$JNI_LIBS_DIR/libFarCry.so" ]; then
    echo "ERROR: libFarCry.so is missing from $JNI_LIBS_DIR!"
    exit 1
fi
if [ ! -f "$JNI_LIBS_DIR/libCrySystem.so" ]; then
    echo "ERROR: libCrySystem.so is missing from $JNI_LIBS_DIR!"
    exit 1
fi
if [ ! -f "$JNI_LIBS_DIR/libXRenderOGL.so" ]; then
    echo "ERROR: libXRenderOGL.so is missing from $JNI_LIBS_DIR!"
    exit 1
fi
if [ ! -f "$JNI_LIBS_DIR/libCryGame.so" ]; then
    echo "ERROR: libCryGame.so is missing from $JNI_LIBS_DIR!"
    exit 1
fi
if [ ! -f "$JNI_LIBS_DIR/libCrySoundSystem.so" ]; then
    echo "ERROR: libCrySoundSystem.so is missing from $JNI_LIBS_DIR!"
    exit 1
fi

# Strip binaries if release
echo "=== STEP: STRIP LIBS ==="
STRIP_TOOL=$(find "$NDK_PATH" -name "llvm-strip" 2>/dev/null | head -n 1)
if [ "$BUILD_TYPE" = "Release" ] && [ -n "$STRIP_TOOL" ] && [ -x "$STRIP_TOOL" ]; then
    echo "Stripping shared libraries..."
    for sofile in "$JNI_LIBS_DIR"/*.so; do
        [ -f "$sofile" ] && "$STRIP_TOOL" "$sofile" 2>/dev/null || true
    done
fi

echo "================================================================="
echo "Successfully built and deployed all Far Cry libraries for $ABI!"
echo "Destination: $JNI_LIBS_DIR"
ls -lh "$JNI_LIBS_DIR" 2>/dev/null || true
echo "================================================================="
exit 0

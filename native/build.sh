#!/bin/bash
# Builds libstickerenc.so (libvpx VP9 encoder + WebM writer + JNI glue) for every Android ABI
# and puts the results into app/src/main/jniLibs, which is committed to the repository.
#
#   ANDROID_NDK=/path/to/ndk LIBVPX_SRC=/path/to/libvpx native/build.sh
#
# libvpx: git clone --depth 1 --branch v1.15.2 https://github.com/webmproject/libvpx.git
# The x86_64 build needs nasm.
set -euo pipefail

: "${ANDROID_NDK:?set ANDROID_NDK to the NDK directory}"
: "${LIBVPX_SRC:?set LIBVPX_SRC to a libvpx checkout}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK_DIR:-$HERE/build}"
OUT="$HERE/../app/src/main/jniLibs"
BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26

build_abi() {
    local abi="$1" target="$2" triple="$3" extra="$4"
    local dir="$WORK/$abi"
    mkdir -p "$dir" "$OUT/$abi"
    (
        cd "$dir"
        export CC="$BIN/${triple}${API}-clang" CXX="$BIN/${triple}${API}-clang++"
        export LD="$CC" AR="$BIN/llvm-ar" NM="$BIN/llvm-nm" STRIP="$BIN/llvm-strip"
        if [ ! -f libvpx.a ]; then
            "$LIBVPX_SRC/configure" --target="$target" $extra \
                --disable-examples --disable-tools --disable-docs --disable-unit-tests \
                --disable-vp8 --disable-vp9-decoder --enable-vp9-encoder --disable-realtime-only \
                --enable-pic --disable-webm-io --disable-libyuv --enable-static --disable-shared
            make -j"$(nproc)" libvpx.a
        fi
        "$CC" -shared -fPIC -O2 -Wall -fvisibility=hidden \
            -I"$dir" -I"$LIBVPX_SRC" \
            "$HERE/stickerenc.c" "$HERE/jni.c" libvpx.a \
            -lm -llog \
            -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 \
            -o "$OUT/$abi/libstickerenc.so"
        "$STRIP" --strip-unneeded "$OUT/$abi/libstickerenc.so"
    )
    ls -la "$OUT/$abi/libstickerenc.so"
}

build_abi arm64-v8a   arm64-android-gcc  aarch64-linux-android    "--disable-runtime-cpu-detect"
build_abi armeabi-v7a armv7-android-gcc  armv7a-linux-androideabi "--disable-runtime-cpu-detect --disable-neon-asm"
build_abi x86_64      x86_64-android-gcc x86_64-linux-android     ""

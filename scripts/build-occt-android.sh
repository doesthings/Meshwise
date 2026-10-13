#!/usr/bin/env bash
# Builds the subset of Open CASCADE Technology (OCCT) that Meshwise needs for
# STEP / IGES / BREP import, as static libraries for one Android ABI.
#
# Usage: scripts/build-occt-android.sh <abi> [install-prefix]
#   abi            arm64-v8a | armeabi-v7a | x86_64 | x86
#   install-prefix defaults to third_party/occt/<abi> inside the repo
#
# Environment:
#   ANDROID_NDK_HOME  NDK root (defaults to $ANDROID_HOME/ndk/<NDK_VERSION>)
#   OCCT_SRC          existing OCCT checkout (cloned automatically otherwise)
#   OCCT_TAG          OCCT git tag (default V7_8_1)
#   JOBS              parallel build jobs (default: nproc)
set -euo pipefail

ABI="${1:?usage: $0 <abi> [install-prefix]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="${2:-$ROOT/third_party/occt/$ABI}"
OCCT_TAG="${OCCT_TAG:-V7_8_1}"
NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}"
JOBS="${JOBS:-$(nproc)}"
WORK="${OCCT_WORK:-$ROOT/build/occt}"
SRC="${OCCT_SRC:-$WORK/src-$OCCT_TAG}"

if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "NDK not found at $NDK" >&2; exit 1
fi

if [ ! -f "$SRC/CMakeLists.txt" ]; then
  mkdir -p "$(dirname "$SRC")"
  git clone --depth 1 --branch "$OCCT_TAG" https://github.com/Open-Cascade-SAS/OCCT.git "$SRC"
fi

BUILD="$WORK/build-$OCCT_TAG-$ABI"
mkdir -p "$BUILD"

# Only the toolkits needed to read STEP/IGES/BREP with names and colours (XCAF)
# and to triangulate the result. OCCT pulls in their dependencies itself.
# TKBinXCAF is only needed for a header that STEPCAFControl_Provider includes.
TOOLKITS="TKDESTEP TKDEIGES TKXCAF TKBinXCAF TKMesh TKBRep"

# Size/speed flags: sections allow the final link to drop unused code.
OPT_FLAGS="-O2 -g0 -ffunction-sections -fdata-sections -fvisibility-inlines-hidden -DNDEBUG"

cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS_RELEASE="$OPT_FLAGS" \
  -DCMAKE_CXX_FLAGS_RELEASE="$OPT_FLAGS" \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DINSTALL_DIR="$PREFIX" \
  -DINSTALL_DIR_LAYOUT=Unix \
  -DBUILD_LIBRARY_TYPE=Static \
  -DBUILD_MODULE_FoundationClasses=OFF \
  -DBUILD_MODULE_ModelingData=OFF \
  -DBUILD_MODULE_ModelingAlgorithms=OFF \
  -DBUILD_MODULE_Visualization=OFF \
  -DBUILD_MODULE_ApplicationFramework=OFF \
  -DBUILD_MODULE_DataExchange=OFF \
  -DBUILD_MODULE_DETools=OFF \
  -DBUILD_MODULE_Draw=OFF \
  -DBUILD_ADDITIONAL_TOOLKITS="$TOOLKITS" \
  -DBUILD_DOC_Overview=OFF \
  -DBUILD_SAMPLES_QT=OFF \
  -DBUILD_Inspector=OFF \
  -DBUILD_RELEASE_DISABLE_EXCEPTIONS=ON \
  -DUSE_FREETYPE=OFF \
  -DUSE_FREEIMAGE=OFF \
  -DUSE_TK=OFF \
  -DUSE_TCL=OFF \
  -DUSE_GLES2=OFF \
  -DUSE_OPENGL=OFF \
  -DUSE_RAPIDJSON=OFF \
  -DUSE_DRACO=OFF \
  -DUSE_TBB=OFF \
  -DUSE_VTK=OFF \
  -DUSE_FFMPEG=OFF \
  -DUSE_OPENVR=OFF

cmake --build "$BUILD" --parallel "$JOBS"
cmake --install "$BUILD"

# Record what was built so the app's CMake can verify the prefix.
echo "$OCCT_TAG" > "$PREFIX/OCCT_VERSION"
echo "OCCT $OCCT_TAG for $ABI installed to $PREFIX"

#!/usr/bin/env bash
# Fetches proot (from Termux's package repo) and the Ubuntu base rootfs, and
# places them where the Android build packs them into the APK:
#   app/src/main/jniLibs/arm64-v8a/libproot.so
#   app/src/main/jniLibs/arm64-v8a/libproot-loader.so
#   app/src/main/jniLibs/arm64-v8a/libtalloc.so
#   app/src/main/assets/rootfs.tar.gz
#
# Runs on the GitHub Actions Ubuntu runner (needs curl, dpkg-deb, patchelf, readelf).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
ASSET_DIR="$ROOT_DIR/app/src/main/assets"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"
UBUNTU_BASE="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"

# latest <index-url> <regex>: newest file name in a directory listing.
latest() {
  curl -fsSL "$1" | grep -oE "$2" | sort -uV | tail -n1
}

echo "==> Looking up packages"
PROOT_DEB="$(latest "$TERMUX_REPO/pool/main/p/proot/" 'proot_[A-Za-z0-9.+~-]+_aarch64\.deb')"
TALLOC_DEB="$(latest "$TERMUX_REPO/pool/main/libt/libtalloc/" 'libtalloc_[A-Za-z0-9.+~-]+_aarch64\.deb')"
ROOTFS="$(latest "$UBUNTU_BASE/" 'ubuntu-base-24\.04(\.[0-9]+)?-base-arm64\.tar\.gz')"

for v in PROOT_DEB TALLOC_DEB ROOTFS; do
  if [ -z "${!v}" ]; then
    echo "ERROR: could not find $v in the repository listing" >&2
    exit 1
  fi
  echo "    $v = ${!v}"
done

echo "==> Downloading"
curl -fsSL -o "$WORK/proot.deb" "$TERMUX_REPO/pool/main/p/proot/$PROOT_DEB"
curl -fsSL -o "$WORK/talloc.deb" "$TERMUX_REPO/pool/main/libt/libtalloc/$TALLOC_DEB"

echo "==> Extracting packages"
dpkg-deb -x "$WORK/proot.deb" "$WORK/pkg"
dpkg-deb -x "$WORK/talloc.deb" "$WORK/pkg"
PREFIX="$WORK/pkg/data/data/com.termux/files/usr"

LOADER="$(find "$PREFIX" -path '*proot*' -name loader -type f | head -n1)"
TALLOC="$(find "$PREFIX/lib" -name 'libtalloc.so.2*' | sort | tail -n1)"
[ -f "$PREFIX/bin/proot" ] || { echo "ERROR: proot binary not found in package" >&2; exit 1; }
[ -n "$LOADER" ] || { echo "ERROR: proot loader not found in package" >&2; exit 1; }
[ -n "$TALLOC" ] || { echo "ERROR: libtalloc not found in package" >&2; exit 1; }

mkdir -p "$JNI_DIR" "$ASSET_DIR"
cp "$PREFIX/bin/proot" "$JNI_DIR/libproot.so"
cp "$LOADER" "$JNI_DIR/libproot-loader.so"
cp -L "$TALLOC" "$JNI_DIR/libtalloc.so"

echo "==> Patching library names"
# Android only packs files named lib*.so, so libtalloc.so.2 becomes libtalloc.so.
patchelf --replace-needed libtalloc.so.2 libtalloc.so "$JNI_DIR/libproot.so"
patchelf --set-soname libtalloc.so "$JNI_DIR/libtalloc.so"
# The Termux RUNPATH points into com.termux; we use LD_LIBRARY_PATH instead.
patchelf --remove-rpath "$JNI_DIR/libproot.so" || true

echo "==> Checking dependencies"
ALLOWED='^(libc\.so|libdl\.so|libm\.so|liblog\.so|libtalloc\.so)$'
BAD=0
for lib in libproot.so libtalloc.so; do
  for need in $(readelf -d "$JNI_DIR/$lib" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'); do
    echo "    $lib needs $need"
    if ! echo "$need" | grep -qE "$ALLOWED"; then
      echo "ERROR: $lib needs $need, which is not on Android" >&2
      BAD=1
    fi
  done
done
[ "$BAD" -eq 0 ] || exit 1

echo "==> Downloading Ubuntu base rootfs"
curl -fsSL -o "$ASSET_DIR/rootfs.tar.gz" "$UBUNTU_BASE/$ROOTFS"

ls -la "$JNI_DIR" "$ASSET_DIR"
echo "==> Done"

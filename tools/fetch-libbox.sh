#!/bin/sh
# Fetches the sing-box core that the VPN client embeds.
#
# The AAR published by singbox-android carries the gomobile bindings and one native library
# per ABI. Only the bindings and the arm64 library are needed here, and the library is 81 MB,
# so it is not kept in the repository.
#
# Usage: sh tools/fetch-libbox.sh [version]
set -eu

VERSION="${1:-1.14.4}"
ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
APP="$ROOT/android-app/app"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK" 2>/dev/null || true' EXIT

AAR_URL="https://github.com/singbox-android/libbox/releases/download/${VERSION}/libbox.aar"
AAR_SHA256="d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465"

echo "Downloading libbox ${VERSION}"
curl -fsSL -o "$WORK/libbox.aar" "$AAR_URL"

echo "$AAR_SHA256  $WORK/libbox.aar" | shasum -a 256 -c - >/dev/null
echo "Checksum verified"

cd "$WORK"
unzip -q libbox.aar classes.jar
unzip -q libbox.aar "jni/arm64-v8a/libbox.so"

mkdir -p "$APP/libs" "$APP/src/main/jniLibs/arm64-v8a"
cp classes.jar "$APP/libs/libbox-classes.jar"
cp jni/arm64-v8a/libbox.so "$APP/src/main/jniLibs/arm64-v8a/libbox.so"

echo "Installed:"
echo "  $APP/libs/libbox-classes.jar"
echo "  $APP/src/main/jniLibs/arm64-v8a/libbox.so"

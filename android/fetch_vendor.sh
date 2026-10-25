#!/usr/bin/env bash
# Puts what the APK build needs but git does not hold into ../vendor.
# Step 1 only needs android-34.jar; the speech-to-text libraries (sherpa-onnx) join in step 3.
# Build tools (Ubuntu/Debian): apt install aapt zipalign apksigner dalvik-exchange openjdk-21-jdk python3-pil
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="${ND_VENDOR:-$ROOT/vendor}"
mkdir -p "$V"

# Android API 34 android.jar (Android SDK platforms/android-34/android.jar); fetched from a git mirror
# because dl.google.com may be unreachable from build machines
if [ ! -f "$V/android-34.jar" ]; then
  T="$(mktemp -d)"
  git clone -q --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms "$T/p"
  git -C "$T/p" sparse-checkout set android-34
  cp "$T/p/android-34/android.jar" "$V/android-34.jar"
  rm -rf "$T"
fi
echo "vendor ready in $V"

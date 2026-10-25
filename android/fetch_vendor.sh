#!/usr/bin/env bash
# Puts what the APK build needs but git does not hold into ../vendor.
# Build tools (Ubuntu/Debian): apt install aapt zipalign apksigner dalvik-exchange openjdk-21-jdk python3-pil
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="${ND_VENDOR:-$ROOT/vendor}"
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
mkdir -p "$V"/{jniLibs/arm64-v8a,jniLibs/armeabi-v7a,asr}

# speech-to-text: sherpa-onnx v1.13.8 Android libraries (JNI + ONNX Runtime) and the Silero voice detector
if [ ! -f "$V/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" ]; then
  T="$(mktemp -d)"
  curl -fL -o "$T/a.tar.bz2" "$GH/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
  tar xjf "$T/a.tar.bz2" -C "$T" ./jniLibs
  for abi in arm64-v8a armeabi-v7a; do cp "$T/jniLibs/$abi/libsherpa-onnx-jni.so" "$T/jniLibs/$abi/libonnxruntime.so" "$V/jniLibs/$abi/"; done
  rm -rf "$T"
fi
[ -f "$V/asr/silero_vad.onnx" ] || curl -fL -o "$V/asr/silero_vad.onnx" "$GH/asr-models/silero_vad.onnx"

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

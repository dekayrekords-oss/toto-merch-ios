#!/bin/bash
# Pinned sherpa-onnx iOS runtime (SherpaOnnxC, onnxruntime statically linked).
# Downloaded binaries are never committed (iosApp/Vendor/ is gitignored).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d /private/tmp/meowmba-sherpa-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
ZIP="$WORK/sherpa.xcframework.zip"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/sherpa-onnx-v1.13.8-ios-shared-onnxruntime-static.xcframework.zip"
SHA="e259a7d3b38ad7dec49bb078252a30bb42ede8355e2bb130cf8c1c78ed131f75"
curl --fail --location --retry 2 "$URL" -o "$ZIP"
printf '%s  %s\n' "$SHA" "$ZIP" | shasum -a 256 -c -
unzip -q "$ZIP" -d "$WORK/out"
mkdir -p "$ROOT/iosApp/Vendor"
rm -rf "$ROOT/iosApp/Vendor/SherpaOnnxC.xcframework"
mv "$WORK/out/SherpaOnnxC.xcframework" "$ROOT/iosApp/Vendor/SherpaOnnxC.xcframework"
echo "sherpa-onnx iOS runtime ready: iosApp/Vendor/SherpaOnnxC.xcframework"

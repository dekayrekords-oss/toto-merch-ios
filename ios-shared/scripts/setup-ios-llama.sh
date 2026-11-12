#!/bin/bash
# Pinned native dependency; downloaded binaries are never committed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
command -v cmake >/dev/null || { echo 'CMake 3.28+ is required (an isolated Python cmake package works).'; exit 1; }
NATIVE_WORK="$(mktemp -d /private/tmp/meowmba-llama-XXXXXX)"
trap 'rm -rf "$NATIVE_WORK"' EXIT
curl --fail --location --retry 2 https://github.com/ggml-org/llama.cpp/releases/download/b11163/llama-b11163-xcframework.zip -o "$NATIVE_WORK/framework.zip"
printf '%s  %s\n' d9abfc1438ceb73df76c9831d9e5d8561db4e32e8a3b08af5bf53ce9b2f7d377 "$NATIVE_WORK/framework.zip" | shasum -a 256 -c -
unzip -q "$NATIVE_WORK/framework.zip" -d "$NATIVE_WORK/release"
curl --fail --location --retry 2 https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11163.tar.gz -o "$NATIVE_WORK/source.tgz"
printf '%s  %s\n' 1bcd64e05060d5ca52623e8978a31add9d6ae978dbbc47c67d8a0406f4c7eaea "$NATIVE_WORK/source.tgz" | shasum -a 256 -c -
tar -xzf "$NATIVE_WORK/source.tgz" -C "$NATIVE_WORK"
python3 - "$NATIVE_WORK/llama.cpp-b11163/build-xcframework.sh" <<'PY'
import sys
from pathlib import Path
p=Path(sys.argv[1]);s=p.read_text().replace('LLAMA_BUILD_MTMD=ON','LLAMA_BUILD_MTMD=OFF').replace('GGML_METAL=ON','GGML_METAL=OFF').replace('"arm64;x86_64"','"arm64"').replace('JOBS_PER_BUILD=$(( $(sysctl -n hw.logicalcpu) / MAX_PARALLEL_BUILDS ))','JOBS_PER_BUILD=4');p.write_text(s)
PY
(cd "$NATIVE_WORK/llama.cpp-b11163" && bash build-xcframework.sh ios-sim)
mkdir -p "$NATIVE_WORK/sim"
cp -R "$NATIVE_WORK/llama.cpp-b11163/build-apple/llama.xcframework/ios-arm64_x86_64-simulator/llama.framework" "$NATIVE_WORK/sim/llama.framework"
xcrun lipo "$NATIVE_WORK/sim/llama.framework/llama" -thin arm64 -output "$NATIVE_WORK/sim/llama.framework/llama.arm64"
mv "$NATIVE_WORK/sim/llama.framework/llama.arm64" "$NATIVE_WORK/sim/llama.framework/llama"
xcodebuild -create-xcframework -framework "$NATIVE_WORK/release/build-apple/llama.xcframework/ios-arm64/llama.framework" -framework "$NATIVE_WORK/sim/llama.framework" -output "$NATIVE_WORK/llama.xcframework"
mkdir -p "$ROOT/iosApp/Vendor"
# Replace only this generated dependency after the new build succeeds.
rm -rf "$ROOT/iosApp/Vendor/llama.xcframework"
mv "$NATIVE_WORK/llama.xcframework" "$ROOT/iosApp/Vendor/llama.xcframework"
echo 'Native iOS and arm64 simulator runtime ready.'

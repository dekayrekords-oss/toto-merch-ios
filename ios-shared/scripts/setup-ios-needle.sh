#!/bin/sh
# Fetch Cactus Needle 3's official iOS engine (same pinned release as Android) and wrap it as
# iosApp/Vendor/needle.xcframework. Every download is SHA-256 checked; the weights
# (needle3.cact) are downloaded by the app itself on first use.
set -eu
cd "$(dirname "$0")/.."
PIN=c1fc4d4cb32993156a880ceb8ff171b03b1f166a
BASE=https://huggingface.co/Cactus-Compute/needle3/resolve/$PIN
WORK=$(mktemp -d /private/tmp/meowmba-needle-XXXXXX)
fetch() { # folder sha
  mkdir -p "$WORK/$1/include"
  curl --fail --location --retry 2 -s "$BASE/$1/libneedle.a" -o "$WORK/$1/libneedle.a"
  echo "$2  $WORK/$1/libneedle.a" | shasum -a 256 -c - >/dev/null || { echo "Needle $1 failed SHA-256 verification"; exit 1; }
  curl --fail --location --retry 2 -s "$BASE/$1/needle.h" -o "$WORK/$1/include/needle.h"
  printf 'module Needle {\n  header "needle.h"\n  link "c++"\n  export *\n}\n' > "$WORK/$1/include/module.modulemap"
}
fetch ios-arm64 2d30a9fbd13dff5c1cb8844d8ce65e12d7bbdb233a501eea2671cd13d806cf5d
fetch ios-sim-arm64 c4bee4fb7c7ca787799eda2ca353e939add34f9d846e64a60012df9b2d5b0df4
rm -rf iosApp/Vendor/needle.xcframework
xcodebuild -create-xcframework \
  -library "$WORK/ios-arm64/libneedle.a" -headers "$WORK/ios-arm64/include" \
  -library "$WORK/ios-sim-arm64/libneedle.a" -headers "$WORK/ios-sim-arm64/include" \
  -output iosApp/Vendor/needle.xcframework >/dev/null
rm -rf "$WORK"
echo "Needle 3 engine ready: iosApp/Vendor/needle.xcframework"

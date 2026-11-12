#!/bin/sh
# Build Meowmba for a connected iPhone, sign it with your Xcode team, install and launch it.
#
# One-time setup (Apple requires these; they cannot be scripted):
#   1. iPhone: Settings > Privacy & Security > Developer Mode > On (restart, then confirm).
#   2. Xcode > Settings > Accounts > + > Apple ID (a free account works for your own phone).
#   3. Plug the iPhone in with a cable and tap Trust.
#
# Usage: scripts/install-ios-device.sh [TEAM_ID]
#   TEAM_ID defaults to the team of the first "Apple Development" certificate in your keychain.
#   The first build creates that certificate automatically once your Apple ID is in Xcode.
set -eu
cd "$(dirname "$0")/.."

# Fetch gitignored native runtimes if this checkout does not already have them.
[ -d iosApp/Vendor/llama.xcframework ] || scripts/setup-ios-llama.sh
[ -d iosApp/Vendor/needle.xcframework ] || scripts/setup-ios-needle.sh
[ -d iosApp/Vendor/SherpaOnnxC.xcframework ] || scripts/setup-ios-sherpa.sh

DEVICE=$(xcrun devicectl list devices 2>/dev/null | awk '/physical/ && /iPhone/ {for (i = 1; i <= NF; i++) if ($i ~ /^[0-9A-F]{8}-[0-9A-F]{16}$/) { print $i; exit }}')
[ -n "$DEVICE" ] || { echo "No iPhone found. Connect it with a cable, unlock it and tap Trust."; exit 1; }

TEAM=${1:-$(security find-identity -v -p codesigning | sed -n 's/.*Apple Development: .* (\([A-Z0-9]\{10\}\)).*/\1/p' | head -1)}
if [ -z "$TEAM" ]; then
  # A personal team shows up in Xcode's preferences before its first certificate exists.
  TEAM=$(defaults read com.apple.dt.Xcode IDEProvisioningTeamByIdentifier 2>/dev/null | sed -n 's/.*teamID = \([A-Z0-9]\{10\}\);.*/\1/p' | head -1)
fi
[ -n "$TEAM" ] || { echo "No signing team found. Add your Apple ID in Xcode > Settings > Accounts, then run again (or pass the Team ID)."; exit 1; }

echo "Building for iPhone $DEVICE with team $TEAM…"
xcodebuild -project iosApp/Meowmba.xcodeproj -scheme Meowmba -configuration Debug \
  -destination "id=$DEVICE" -derivedDataPath build/ios-device \
  -allowProvisioningUpdates DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic build | tail -3

APP=build/ios-device/Build/Products/Debug-iphoneos/Meowmba.app
xcrun devicectl device install app --device "$DEVICE" "$APP"
xcrun devicectl device process launch --device "$DEVICE" com.meowmba.multi.app || \
  echo "Installed. If iOS says the developer is not trusted: Settings > General > VPN & Device Management > trust your Apple ID, then open Meowmba."

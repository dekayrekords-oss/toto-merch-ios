# Toto iOS avatar target

This isolated Xcode target is the Toto avatar-only iOS surface, version **0.4.0 (6)**, bundle identifier `com.meowmba.toto.avatar` and display name `Toto`.

The source is paired with `mobile-app/ios-shared`, copied from the existing Meowmba-Multi shared implementation. The real Compose avatar renderer and shared AI/provider, on-device model, voice, memory, and tools adapters are retained. The iOS entry point opens the avatar surface and Flow/Desk routes are ignored; avatar setup no longer exposes Flow/Desk navigation. No automatic sensor enable, movement, robot control, or fake canned responses were added. Screen-on behavior remains the existing explicit display driver.

Build the shared framework from `mobile-app/ios-shared` (the Xcode script points there), then build `Meowmba.xcodeproj` for the simulator. Device/archive signing was not attempted without an existing authorized signing identity. No physical phone or robot was used.

Validation: shared framework link and arm64 simulator Xcode build completed successfully. An unsigned arm64 device-family build also completed successfully (no install/sign/publish). The iOS native driver does not install the legacy Cozmo UDP transport, so no robot movement authority is granted. Vendor frameworks/models remain available in the local build tree but are not copied into source archives where excluded.

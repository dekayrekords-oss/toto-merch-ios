import SwiftUI
import UIKit
import SharedKit

@main
struct iOSApp: App {
    private let local = OnDeviceLlama()
    init() {
        OnDeviceModel.shared.driver = local
        NativeDrivers.install()
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--local-smoke") {
            LocalModelSmoke.shared.run { output in
                let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("local-smoke.txt")
                try? output.write(to: url, atomically: true, encoding: .utf8)
            }
        }
#endif
    }
    var body: some Scene {
        WindowGroup {
            LauncherHost()
        }
    }
}

/** Launcher mode: keeps the foreground avatar awake and hides the status bar. */
final class DisplayState: ObservableObject, DisplayDriver {
    static let shared = DisplayState()
    @Published var statusBarHidden = false
    func keepAwake(on: Bool) { DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = on } }
    func hideStatusBar(hidden: Bool) { DispatchQueue.main.async { self.statusBarHidden = hidden } }
    func requestGuidedAccess(on: Bool, onResult: @escaping (KotlinBoolean) -> Void) {
        DispatchQueue.main.async {
            // Only succeeds on supervised iPhones; otherwise the person starts Guided Access themselves.
            UIAccessibility.requestGuidedAccessSession(enabled: on) { ok in
                DispatchQueue.main.async { onResult(KotlinBoolean(bool: ok && on)) }
            }
        }
    }
}

struct LauncherHost: View {
    @ObservedObject private var display = DisplayState.shared
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        ContentView()
            .statusBarHidden(display.statusBarHidden)
            .persistentSystemOverlays(display.statusBarHidden ? .hidden : .automatic)
            .onAppear {
                LauncherMode.shared.display = DisplayState.shared
                display.keepAwake(on: true)
                display.hideStatusBar(hidden: true)
            }
            .onChange(of: scenePhase) { phase in
                display.keepAwake(on: phase == .active)
                display.hideStatusBar(hidden: phase == .active)
                if phase != .active {
                    LooiRobotBridge.shared.emergencyStop(reason: "App left the foreground — STOP sent, Motors OFF")
                    TotoRobotBridge.shared.emergencyStop(reason: "App left the foreground — Toto motion stopped")
                }
            }
            .onDisappear {
                display.keepAwake(on: false)
                display.hideStatusBar(hidden: false)
            }
    }
}


// This avatar-only target does not register donor URL schemes or Shortcuts.

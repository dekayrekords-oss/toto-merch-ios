import SwiftUI
import SharedKit

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}

/// Bridges the shared Compose Multiplatform UI into SwiftUI.
///
/// `MainViewControllerKt` is the Objective-C class Kotlin/Native generates for
/// the top-level declarations in
/// `composeApp/src/iosMain/kotlin/com/meowmba/shared/MainViewController.kt`
/// (`Foo.kt` -> `FooKt`). If this symbol is missing, the framework is stale —
/// rebuild it:
///   ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

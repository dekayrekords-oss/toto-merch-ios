package com.meowmba.shared

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.meowmba.shared.ui.CompanionScreen
import com.meowmba.shared.ui.CompanionViewModel
import platform.UIKit.UIViewController

/**
 * iOS entry point: the shared Compose UI hosted in a UIViewController.
 *
 * The file name is load-bearing. Kotlin/Native exports top-level declarations
 * from `Foo.kt` as the Objective-C class `FooKt`, so the previous
 * `Main.ios.kt` exported an unpredictable `Main_iosKt` symbol. Swift now calls
 * `MainViewControllerKt.MainViewController()`, matching the Compose
 * Multiplatform convention.
 *
 * Storage needs no priming here — the iOS actual resolves the Documents
 * directory on its own.
 */
fun MainViewController(): UIViewController =
    ComposeUIViewController {
        // Remembered so recomposition does not build a fresh ViewModel and
        // throw away the conversation.
        val viewModel = remember { CompanionViewModel() }
        // Toto iOS deliberately opens the avatar surface. The shared engine,
        // provider settings, memory and tools remain behind its compact sheets;
        // Flow and Desk are not entry points for this target.
        CompanionScreen(viewModel, avatarOnly = true)
    }

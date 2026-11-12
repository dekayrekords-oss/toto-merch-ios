package com.meowmba.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Finite max height for a [androidx.compose.foundation.verticalScroll] hosted inside a
 * Compose [androidx.compose.ui.window.Dialog] or AlertDialog.
 *
 * On iOS, those windows measure their children with an infinite maximum height.
 * [androidx.compose.foundation.checkScrollableContainerConstraints] then throws
 * IllegalStateException, which Kotlin/Native aborts as SIGABRT / Abort trap: 6.
 */
@Composable
internal fun dialogScrollMaxHeight(fraction: Float = 0.9f): Dp {
    val size = LocalWindowInfo.current.containerSize
    val height = with(LocalDensity.current) { size.height.toDp() }
    return if (height.value.isFinite() && height > 120.dp) height * fraction else 640.dp
}

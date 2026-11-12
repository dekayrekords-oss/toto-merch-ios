/* Hallmark · pre-emit critique: P4 H4 E4 S4 R5 V4
 * genre: playful · macrostructure: native workbench · design-system: design.md
 * Shared Material controls keep their existing semantics, gestures and callbacks. */
package com.meowmba.shared.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One ink language for the whole app. No continuous animation or extra rendering loop. */
object CartoonTokens {
    val ink = Color(0xFF172D34)
    val outline = 2.dp
    val shadow = 3.dp
}

@Composable
fun cartoonBorder(enabled: Boolean = true): BorderStroke = BorderStroke(
    CartoonTokens.outline,
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else .38f),
)

/** A pressed button settles onto its drawn ink base; focus is immediate, native and visible. */
@Composable
private fun Modifier.inkButton(shape: Shape, source: MutableInteractionSource, enabled: Boolean): Modifier {
    val pressed by source.collectIsPressedAsState()
    return this.graphicsLayer { translationY = if (pressed && enabled) 2.dp.toPx() else 0f }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            onDrawBehind {
                if (enabled) translate(top = if (pressed) 1.dp.toPx() else CartoonTokens.shadow.toPx()) {
                    drawOutline(outline, CartoonTokens.ink)
                }
            }
        }
}

@Composable
fun Button(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = cartoonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    androidx.compose.material3.Button(onClick, modifier.inkButton(shape, source, enabled), enabled, shape,
        colors, elevation, if (focused) BorderStroke(3.dp, colors.contentColor) else border,
        contentPadding, source, content)
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = cartoonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors, elevation,
        border, contentPadding, interactionSource, content)
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
    elevation: ButtonElevation? = null, border: BorderStroke? = cartoonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

@Composable
fun Card(
    modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border: BorderStroke? = cartoonBorder(), content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.Card(modifier, shape, colors, elevation, border, content)

@Composable
fun Card(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border: BorderStroke? = cartoonBorder(enabled), interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.Card(onClick, modifier, enabled, shape, colors, elevation, border, interactionSource, content)

@Composable
fun OutlinedCard(
    modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.large,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    elevation: CardElevation = CardDefaults.outlinedCardElevation(),
    border: BorderStroke = cartoonBorder(), content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.OutlinedCard(modifier, shape, colors, elevation, border, content)

@Composable
fun FilterChip(
    selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.small,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
    elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = cartoonBorder(enabled), interactionSource: MutableInteractionSource? = null,
) = androidx.compose.material3.FilterChip(selected, onClick, label, modifier, enabled, leadingIcon,
    trailingIcon, shape, colors, elevation, border, interactionSource)

/** Full-page surfaces stay borderless. Rounded sheets and panels get a restrained ink edge. */
@Composable
fun Surface(
    modifier: Modifier = Modifier, shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface, contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp, shadowElevation: Dp = 0.dp,
    border: BorderStroke? = if (shape == RectangleShape) null else cartoonBorder(),
    content: @Composable () -> Unit,
) = androidx.compose.material3.Surface(modifier, shape, color, contentColor, tonalElevation, shadowElevation, border, content)

@Composable
fun Surface(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RectangleShape, color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color), tonalElevation: Dp = 0.dp, shadowElevation: Dp = 0.dp,
    border: BorderStroke? = if (shape == RectangleShape) null else cartoonBorder(enabled),
    interactionSource: MutableInteractionSource? = null, content: @Composable () -> Unit,
) = androidx.compose.material3.Surface(onClick, modifier, enabled, shape, color, contentColor,
    tonalElevation, shadowElevation, border, interactionSource, content)

/** Menus and modal sub-pages use the same outlined surfaces as the launcher cards. */
@Composable
fun DropdownMenu(
    expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    offset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero,
    scrollState: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState(),
    properties: androidx.compose.ui.window.PopupProperties = androidx.compose.ui.window.PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.DropdownMenu(
    expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier,
    offset = offset, scrollState = scrollState, properties = properties,
    shape = MaterialTheme.shapes.medium, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 0.dp, border = cartoonBorder(), content = content,
)

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null, shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    iconContentColor: Color = MaterialTheme.colorScheme.secondary,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tonalElevation: Dp = 0.dp,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest, confirmButton = confirmButton,
    modifier = modifier.cartoonOutline(shape),
    dismissButton = dismissButton, icon = icon, title = title, text = text,
    shape = shape, containerColor = containerColor, iconContentColor = iconContentColor,
    titleContentColor = titleContentColor, textContentColor = textContentColor,
    tonalElevation = tonalElevation, properties = properties,
)

@Composable
private fun Modifier.cartoonOutline(shape: Shape): Modifier =
    this.border(cartoonBorder(), shape)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = contentColorFor(containerColor),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier.cartoonOutline(MaterialTheme.shapes.extraLarge),
    sheetState = sheetState, shape = MaterialTheme.shapes.extraLarge,
    containerColor = containerColor, contentColor = contentColor,
    tonalElevation = 0.dp, content = content,
)

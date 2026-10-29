package com.shonkware.droidmodloader.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

private data class InfoPopoverEntry(val identity: Any, val text: String, val anchor: Rect)
private class InfoPopoverState {
    var active by mutableStateOf<InfoPopoverEntry?>(null)
    var hostPosition = Offset.Zero
}
private val LocalInfoPopover = staticCompositionLocalOf<InfoPopoverState?> { null }

/** Screen-local dismissal boundary. Observes gestures without consuming taps or scrolls. */
@Composable
fun InfoPopoverHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { InfoPopoverState() }
    val scrollConnection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available != Offset.Zero) state.active = null
                return Offset.Zero
            }
        }
    }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    CompositionLocalProvider(LocalInfoPopover provides state) {
        Box(modifier.onGloballyPositioned { state.hostPosition = it.positionInWindow() }
            .nestedScroll(scrollConnection)
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val active = state.active
                        if (active != null && event.changes.any {
                            it.changedToDownIgnoreConsumed() &&
                                !active.anchor.contains(it.position + state.hostPosition)
                        }) state.active = null
                    }
                }
            }) {
            content()
            state.active?.let { entry ->
                Popup(
                    popupPositionProvider = remember(entry.anchor, gap) { InfoPopoverPosition(entry.anchor, gap) },
                    // Native input goes directly to the screen, even when it starts over the box.
                    properties = PopupProperties(
                        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        dismissOnBackPress = false, dismissOnClickOutside = false
                    )
                ) {
                    Surface(
                        modifier = Modifier.widthIn(max = 320.dp).padding(8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shadowElevation = 4.dp
                    ) {
                        Text(entry.text, modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Short, noninteractive help. Requires [InfoPopoverHost]. Not saved across recreation. */
@Composable
fun InfoPopover(
    text: String,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    autoDismissMillis: Long? = null
) {
    require(autoDismissMillis == null || autoDismissMillis > 0) { "Popover timeout must be positive or null." }
    val state = checkNotNull(LocalInfoPopover.current) { "InfoPopover requires InfoPopoverHost." }
    val identity = remember { Any() }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val expanded = state.active?.identity === identity
    DisposableEffect(state, identity) {
        onDispose { if (state.active?.identity === identity) state.active = null }
    }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(expanded, autoDismissMillis, text, accessibility) {
        if (expanded && state.active?.identity === identity) {
            if (state.active?.text != text) state.active = state.active?.copy(text = text)
            if (autoDismissMillis != null) {
                delay(accessibility?.calculateRecommendedTimeoutMillis(
                    autoDismissMillis, containsIcons = false, containsText = true, containsControls = false
                ) ?: autoDismissMillis)
                if (state.active?.identity === identity) state.active = null
            }
        }
    }
    BackHandler(expanded) { state.active = null }
    IconButton(
        onClick = { state.active = if (expanded) null else InfoPopoverEntry(identity, text, anchor) },
        modifier = modifier.semantics {
            contentDescription = accessibilityLabel
            stateDescription = if (expanded) "Expanded" else "Collapsed"
        }.onGloballyPositioned { anchor = it.boundsInWindow() }
    ) {
        Text("ⓘ", style = MaterialTheme.typography.titleLarge)
    }
}

private class InfoPopoverPosition(private val anchor: Rect, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchor.right.toInt() - popupContentSize.width
            else anchor.left.toInt()
        val below = anchor.bottom.toInt() + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below
            else anchor.top.toInt() - gap - popupContentSize.height
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

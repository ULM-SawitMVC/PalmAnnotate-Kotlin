package dev.sawitulm.palmannotate.ui.common

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*

/**
 * Keyboard shortcut handler for annotation and dedup surfaces.
 * Port of JS app.js global key handler.
 *
 * Matches the original shortcuts:
 * - [ / ] : previous / next tree (or side)
 * - Q / E : previous / next side
 * - 1-4   : set selected bbox class
 * - Delete/Backspace : delete selected bbox
 * - Esc   : deselect
 * - R     : run suggestions (dedup)
 * - S     : toggle suggestions (dedup)
 * - ← / → : prev/next pair (dedup)
 */
data class KeyboardActions(
    val onPrevTree: (() -> Unit)? = null,
    val onNextTree: (() -> Unit)? = null,
    val onPrevSide: (() -> Unit)? = null,
    val onNextSide: (() -> Unit)? = null,
    val onClass1: (() -> Unit)? = null,
    val onClass2: (() -> Unit)? = null,
    val onClass3: (() -> Unit)? = null,
    val onClass4: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onDeselect: (() -> Unit)? = null,
    val onRunSuggestions: (() -> Unit)? = null,
    val onToggleSuggestions: (() -> Unit)? = null,
    val onPrevPair: (() -> Unit)? = null,
    val onNextPair: (() -> Unit)? = null,
)

@Composable
fun KeyboardShortcutHandler(
    enabled: Boolean = true,
    actions: KeyboardActions,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (enabled) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    if (!enabled) return

    val keyHandler = Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

        // Ignore if Ctrl/Meta/Alt held
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val alt = event.isAltPressed
        if (ctrl || alt) return@onKeyEvent false

        when (event.key) {
            Key.LeftBracket -> { actions.onPrevTree?.invoke(); true }
            Key.RightBracket -> { actions.onNextTree?.invoke(); true }
            Key.Q -> { actions.onPrevSide?.invoke(); true }
            Key.E -> { actions.onNextSide?.invoke(); true }
            Key.One, Key.NumPad1 -> { actions.onClass1?.invoke(); true }
            Key.Two, Key.NumPad2 -> { actions.onClass2?.invoke(); true }
            Key.Three, Key.NumPad3 -> { actions.onClass3?.invoke(); true }
            Key.Four, Key.NumPad4 -> { actions.onClass4?.invoke(); true }
            Key.Delete, Key.Backspace -> { actions.onDelete?.invoke(); true }
            Key.Escape -> { actions.onDeselect?.invoke(); true }
            Key.R -> { actions.onRunSuggestions?.invoke(); true }
            Key.S -> { actions.onToggleSuggestions?.invoke(); true }
            Key.DirectionLeft -> { actions.onPrevPair?.invoke(); true }
            Key.DirectionRight -> { actions.onNextPair?.invoke(); true }
            else -> false
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .then(keyHandler)
            .focusRequester(focusRequester)
            .focusable()
    )
}

package ai.opencode.mobile.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which cards of the chat are expanded (reasoning, tool calls, message actions),
 * by key. Kept in the ViewModel rather than in saved state: rememberSaveable in
 * every list item put one entry per card ever shown into the saved instance
 * state, which grows with the chat and travels through a ~1 MB Binder buffer.
 * The cards stay as they were while the screen lives, across scrolling.
 */
@Stable
class ExpandedItems {
    private val states = HashMap<String, MutableState<Boolean>>()

    fun stateFor(key: String): MutableState<Boolean> = synchronized(states) {
        states.getOrPut(key) { mutableStateOf(false) }
    }
}

internal val LocalExpandedItems = staticCompositionLocalOf { ExpandedItems() }

/** Whether the card [key] is expanded; see [ExpandedItems]. */
@Composable
internal fun rememberExpanded(key: String): MutableState<Boolean> {
    val items = LocalExpandedItems.current
    return remember(items, key) { items.stateFor(key) }
}

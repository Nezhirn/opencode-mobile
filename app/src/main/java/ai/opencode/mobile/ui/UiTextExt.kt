package ai.opencode.mobile.ui

import ai.opencode.mobile.data.UiText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource

/** Resolves the message in the current app language. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> stringResource(id, *args.toTypedArray())
}

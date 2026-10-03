package ai.opencode.mobile.data

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * A user-visible message produced outside the UI. The data layer has no Context
 * and the app language can change at runtime, so messages are kept as a string
 * resource plus arguments and resolved only when shown. Text that comes from the
 * server (or the OS) is passed through as [Raw]: it cannot be translated here.
 */
@Immutable
sealed interface UiText {
    data class Raw(val text: String) : UiText

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
}

internal fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** The error's own message when it has one, otherwise the localised [fallback]. */
internal fun Throwable.toUiText(@StringRes fallback: Int, vararg args: Any): UiText =
    message?.takeIf { it.isNotBlank() }?.let(UiText::Raw) ?: UiText.Res(fallback, args.toList())

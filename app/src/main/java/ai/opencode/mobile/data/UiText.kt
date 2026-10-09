package ai.opencode.mobile.data

import ai.opencode.mobile.R
import ai.opencode.mobile.data.remote.ResponseTooLargeException
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

    /** Several messages shown together, one per line. */
    data class Lines(val lines: List<UiText>) : UiText
}

internal fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** The error's own message when it has one, otherwise the localised [fallback]. */
internal fun Throwable.toUiText(@StringRes fallback: Int, vararg args: Any): UiText {
    if (this is ResponseTooLargeException) {
        return UiText.Res(R.string.error_response_too_large, listOf((limitBytes / (1024 * 1024)).toInt()))
    }
    return message?.takeIf { it.isNotBlank() }?.let(UiText::Raw) ?: UiText.Res(fallback, args.toList())
}

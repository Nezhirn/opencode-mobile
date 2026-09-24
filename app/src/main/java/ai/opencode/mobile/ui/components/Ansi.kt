package ai.opencode.mobile.ui.components

/** CSI sequences (colours, cursor moves) and OSC sequences (titles, hyperlinks). */
private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]")

/**
 * Removes terminal escape sequences from tool output. Coloured output from
 * shells and test runners otherwise shows up as literal `ESC[31m` noise.
 */
internal fun String.stripAnsi(): String = if (indexOf('\u001B') < 0) this else replace(ANSI_ESCAPE, "")

package ai.opencode.mobile.data

import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.PromptPart
import androidx.compose.runtime.Immutable

/**
 * A file sent with a prompt. [url] is a `data:` URL carrying the content (files
 * from the phone) or `file://` plus an absolute path on the server (project
 * files, which the server reads itself).
 */
@Immutable
data class Attachment(
    val id: String,
    val filename: String,
    val mime: String,
    val url: String,
    val sizeBytes: Long = 0,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** A sent message put back into the prompt for editing. */
@Immutable
data class EditDraft(
    val text: String,
    val attachments: List<Attachment>,
)

internal const val FILE_PART_TYPE = "file"

internal fun Attachment.toPromptPart(): PromptPart =
    PromptPart(type = FILE_PART_TYPE, mime = mime, url = url, filename = filename)

/** The attachment a sent file part came from, to put it back into the prompt. */
internal fun Part.toAttachment(): Attachment? {
    if (type != FILE_PART_TYPE) return null
    val url = url?.takeIf { it.isNotBlank() } ?: return null
    return Attachment(
        id = id,
        filename = filename?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/'),
        mime = mime ?: "application/octet-stream",
        url = url,
    )
}

package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.Attachment
import ai.opencode.mobile.data.UiText
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Largest file sent from the phone; it travels base64-encoded inside the prompt. */
internal const val MAX_ATTACHMENT_MB = 10
private const val MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MB * 1024L * 1024L
private const val THUMBNAIL_PX = 160

/** Image types models accept as they are; other images are converted to JPEG. */
private val SENDABLE_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

private val TEXT_MIMES = setOf(
    "application/json", "application/ld+json", "application/xml", "application/yaml", "application/x-yaml",
    "application/toml", "application/x-toml", "application/javascript", "application/x-sh", "application/sql",
)

private val TEXT_EXTENSIONS = setOf(
    "c", "cc", "cjs", "conf", "cpp", "css", "csv", "cts", "env", "go", "gql", "graphql", "h", "hh", "hpp", "htm",
    "html", "ini", "java", "js", "json", "jsx", "kt", "kts", "log", "md", "mdx", "mjs", "mts", "py", "rb", "rs",
    "sass", "scss", "sh", "sql", "toml", "ts", "tsx", "txt", "xml", "yaml", "yml", "zsh", "gradle", "properties",
)

/** MIME types offered by the phone's file picker. */
internal val DEVICE_PICKER_MIMES = arrayOf("image/*", "application/pdf", "text/*", "application/json", "application/xml", "application/*")

internal sealed interface DeviceFileResult {
    data class Ok(val attachment: Attachment, val thumbnail: ImageBitmap?) : DeviceFileResult
    data class Failed(val message: UiText) : DeviceFileResult
}

/**
 * Reads a file picked on the phone into an attachment, like the web client's
 * upload: images and PDFs keep their type, text of any kind is sent as
 * `text/plain` (the only text type opencode inlines for the model).
 */
internal suspend fun readDeviceFile(resolver: ContentResolver, uri: Uri, id: String): DeviceFileResult =
    withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size > MAX_ATTACHMENT_BYTES) {
            return@withContext DeviceFileResult.Failed(UiText.Res(R.string.error_attachment_too_large, listOf(name, MAX_ATTACHMENT_MB)))
        }
        val declared = resolver.getType(uri) ?: mimeFromName(name)
        val bytes = try {
            readLimited(resolver, uri) ?: return@withContext DeviceFileResult.Failed(
                UiText.Res(R.string.error_attachment_too_large, listOf(name, MAX_ATTACHMENT_MB)),
            )
        } catch (error: IOException) {
            return@withContext DeviceFileResult.Failed(UiText.Res(R.string.error_attachment_read, listOf(name)))
        } catch (error: SecurityException) {
            return@withContext DeviceFileResult.Failed(UiText.Res(R.string.error_attachment_read, listOf(name)))
        }
        val kind = sendableMime(declared, name)
        when {
            kind == null -> DeviceFileResult.Failed(UiText.Res(R.string.error_attachment_type, listOf(name)))
            kind.startsWith("image/") && kind !in SENDABLE_IMAGES -> {
                // HEIC and the like: re-encode to JPEG, which every model reads.
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext DeviceFileResult.Failed(UiText.Res(R.string.error_attachment_type, listOf(name)))
                val jpeg = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.toByteArray()
                }
                val jpegName = name.substringBeforeLast('.') + ".jpg"
                DeviceFileResult.Ok(attachment(id, jpegName, "image/jpeg", jpeg), thumbnail(jpeg))
            }
            else -> DeviceFileResult.Ok(attachment(id, name, kind, bytes), if (kind.startsWith("image/")) thumbnail(bytes) else null)
        }
    }

private const val JPEG_QUALITY = 90

private fun attachment(id: String, name: String, mime: String, bytes: ByteArray) = Attachment(
    id = id,
    filename = name,
    mime = mime,
    url = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
    sizeBytes = bytes.size.toLong(),
)

/** Reads at most [MAX_ATTACHMENT_BYTES]; null when the file is larger (its size was unknown up front). */
private fun readLimited(resolver: ContentResolver, uri: Uri): ByteArray? {
    val input = resolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
    return input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ATTACHMENT_BYTES) return null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
}

private fun mimeFromName(name: String): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

/** The type to send [declared] as, or null when models cannot read it. */
internal fun sendableMime(declared: String?, name: String): String? {
    val mime = declared?.lowercase()?.substringBefore(';')?.trim()
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        mime != null && mime.startsWith("image/") -> mime
        mime == "application/pdf" || extension == "pdf" -> "application/pdf"
        mime != null && (mime.startsWith("text/") || mime in TEXT_MIMES) -> "text/plain"
        extension in TEXT_EXTENSIONS -> "text/plain"
        else -> null
    }
}

/** MIME type of a project file sent by path: images and PDFs as such, the rest as text. */
internal fun projectFileMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    else -> "text/plain"
}

/** A small preview of an image, decoded at reduced size. */
internal fun thumbnail(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMBNAIL_PX && bounds.outHeight / (sample * 2) >= THUMBNAIL_PX) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}.getOrNull()

/** The preview of an image attachment carried as a `data:` URL; null for anything else. */
internal fun thumbnailOf(attachment: Attachment): ImageBitmap? {
    if (!attachment.isImage) return null
    val url = attachment.url
    if (!url.startsWith("data:")) return null
    val comma = url.indexOf(',')
    if (comma < 0) return null
    val bytes = runCatching { Base64.decode(url.substring(comma + 1), Base64.DEFAULT) }.getOrNull() ?: return null
    return thumbnail(bytes)
}

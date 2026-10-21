package ai.opencode.mobile.data.remote.v2

import ai.opencode.mobile.data.remote.OpenCodeJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Lenient readers for v2 payloads. The v2 API changes between releases, so
 * nothing here insists on a shape: a field of an unexpected type reads as absent.
 */

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** [value] as JSON with the app's settings, nulls and defaults left out. */
internal fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = OpenCodeJson.encodeToJsonElement(serializer, value)

internal fun <T> JsonElement.decodeOrNull(serializer: KSerializer<T>): T? =
    if (this is JsonNull) null else runCatching { OpenCodeJson.decodeFromJsonElement(serializer, this) }.getOrNull()

/** A path without a trailing slash, so `/a/b/` and `/a/b` compare equal (the root stays "/"). */
internal fun samePath(a: String?, b: String?): Boolean {
    fun norm(path: String) = if (path.length > 1) path.trimEnd('/', '\\') else path
    return a != null && b != null && norm(a) == norm(b)
}

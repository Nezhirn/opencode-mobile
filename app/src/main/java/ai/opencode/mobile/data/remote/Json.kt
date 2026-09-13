package ai.opencode.mobile.data.remote

import kotlinx.serialization.json.Json

/**
 * Single, shared JSON configuration for the whole app. Keeping one instance
 * avoids the client and repository drifting apart on leniency or null handling.
 */
val OpenCodeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}

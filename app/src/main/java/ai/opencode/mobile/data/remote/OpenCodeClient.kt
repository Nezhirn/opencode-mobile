package ai.opencode.mobile.data.remote

import ai.opencode.mobile.BuildConfig
import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Thin HTTP client for the opencode server. Stateless with respect to app
 * configuration: base URL and credentials are fixed per instance, so a new
 * instance is created whenever the connection settings change.
 */
class OpenCodeClient(
    baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    allowInsecureTls: Boolean = false,
) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
        coerceInputValues = true
    }

    private val client: OkHttpClient = buildClient(allowInsecureTls)

    /**
     * Builds the OkHttp client. When [allowInsecureTls] is set, TLS certificate
     * and hostname verification are disabled so servers using self-signed
     * certificates (common for local development) can be reached. This weakens
     * transport security and is an explicit, opt-in user choice.
     */
    @SuppressLint("CustomX509TrustManager")
    private fun buildClient(allowInsecureTls: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
        if (BuildConfig.DEBUG) {
            // BODY logging is development-only; credentials are redacted and it
            // is never registered in release builds.
            builder.addInterceptor(
                HttpLoggingInterceptor { message -> Log.d(TAG, message) }.apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    redactHeader("Authorization")
                },
            )
        }
        if (allowInsecureTls) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    private val authHeader: String? =
        if (!username.isNullOrBlank() && password != null) {
            Credentials.basic(username, password, Charsets.UTF_8)
        } else {
            null
        }

    // --- Core request helpers ---

    private fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = "$baseUrl$path".toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Invalid server URL: $baseUrl$path")
        query.forEach { (key, value) -> if (value != null) builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun newRequest(
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: RequestBody? = null,
        accept: String? = null,
    ): Request {
        val builder = Request.Builder().url(url(path, query))
        authHeader?.let { builder.header("Authorization", it) }
        accept?.let { builder.header("Accept", it) }
        // OkHttp rejects methods that require a body when none is supplied
        // (POST/PUT/PATCH/...), so send an explicit empty body instead.
        val effectiveBody = body ?: if (method in METHODS_REQUIRING_BODY) EMPTY_BODY else null
        builder.method(method, effectiveBody)
        return builder.build()
    }

    private suspend fun <T> execute(
        request: Request,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        client.newCall(request).apply { timeout(requestTimeoutMillis(request)) }.execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OpenCodeException(response.code, extractError(text, response.message))
            }
            if (text.isBlank()) {
                // Some opencode versions answer POST /session (and similar) with
                // an empty 200 body. Report that explicitly instead of leaking a
                // ClassCastException from an unchecked cast to T.
                throw OpenCodeException(
                    response.code,
                    "Empty response body for ${request.url.encodedPath}",
                )
            }
            json.decodeFromString(deserializer, text)
        }
    }

    private suspend fun executeUnit(
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: RequestBody? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val request = newRequest(method, path, query, body)
        client.newCall(request).apply { timeout(requestTimeoutMillis(request)) }.execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OpenCodeException(response.code, extractError(text, response.message))
            }
        }
    }

    private fun requestTimeoutMillis(request: Request): Long =
        if (request.url.encodedPath.endsWith("/event")) 0 else 120_000

    private fun extractError(text: String, fallback: String): String {
        if (text.isBlank()) return fallback
        return runCatching {
            val obj = json.parseToJsonElement(text) as? JsonObject
            // opencode nests the reason in several shapes: {message}, {error},
            // {error:{data:{message}}}, {data:{message}}. Read the text without
            // JsonElement.toString() so it is not wrapped in quotes.
            obj?.stringOrNull("message")
                ?: obj?.stringOrNull("error")
                ?: (obj?.get("error") as? JsonObject)?.stringOrNull("message")
                ?: (obj?.get("error") as? JsonObject)?.get("data")
                    ?.let { it as? JsonObject }?.stringOrNull("message")
                ?: (obj?.get("data") as? JsonObject)?.stringOrNull("message")
                ?: text
        }.getOrDefault(text)
    }

    private fun jsonBody(value: Any): RequestBody {
        val encoded = when (value) {
            is String -> value
            else -> {
                @Suppress("UNCHECKED_CAST")
                json.encodeToString(serializerFor(value) as KSerializer<Any>, value)
            }
        }
        return encoded.toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun serializerFor(value: Any) = when (value) {
        is PromptRequest -> PromptRequest.serializer()
        is CreateSessionRequest -> CreateSessionRequest.serializer()
        is PermissionReplyRequest -> PermissionReplyRequest.serializer()
        is QuestionReplyRequest -> QuestionReplyRequest.serializer()
        else -> error("No serializer registered for ${value::class}")
    }

    // --- Endpoints ---

    suspend fun health(): Health =
        execute(newRequest("GET", "/global/health"), Health.serializer())

    suspend fun listSessions(): List<Session> =
        execute(newRequest("GET", "/session"), ListSerializer(Session.serializer()))

    suspend fun createSession(request: CreateSessionRequest): Session =
        execute(newRequest("POST", "/session", body = jsonBody(request)), Session.serializer())

    suspend fun deleteSession(sessionId: String) =
        executeUnit("DELETE", "/session/$sessionId")

    suspend fun getMessages(sessionId: String): List<MessageWithParts> =
        execute(
            newRequest("GET", "/session/$sessionId/message"),
            ListSerializer(MessageWithParts.serializer()),
        )

    suspend fun prompt(sessionId: String, request: PromptRequest): PromptResponse =
        execute(
            newRequest("POST", "/session/$sessionId/message", body = jsonBody(request)),
            PromptResponse.serializer(),
        )

    suspend fun promptAsync(sessionId: String, request: PromptRequest) =
        executeUnit("POST", "/session/$sessionId/prompt_async", body = jsonBody(request))

    suspend fun abort(sessionId: String) =
        executeUnit("POST", "/session/$sessionId/abort")

    suspend fun listPermissions(): List<PermissionRequest> =
        execute(newRequest("GET", "/permission"), ListSerializer(PermissionRequest.serializer()))

    suspend fun replyPermission(requestId: String, reply: String, message: String? = null) =
        executeUnit(
            "POST",
            "/permission/$requestId/reply",
            body = jsonBody(PermissionReplyRequest(reply = reply, message = message)),
        )

    suspend fun listQuestions(): List<QuestionRequest> =
        execute(newRequest("GET", "/question"), ListSerializer(QuestionRequest.serializer()))

    suspend fun replyQuestion(requestId: String, answers: List<List<String>>) =
        executeUnit(
            "POST",
            "/question/$requestId/reply",
            body = jsonBody(QuestionReplyRequest(answers = answers)),
        )

    suspend fun rejectQuestion(requestId: String) =
        executeUnit("POST", "/question/$requestId/reject")

    suspend fun listProviders(): ProviderList =
        execute(newRequest("GET", "/provider"), ProviderList.serializer())

    suspend fun listAgents(): List<Agent> =
        execute(newRequest("GET", "/agent"), ListSerializer(Agent.serializer()))

    suspend fun listFiles(path: String): List<FileNode> =
        execute(
            newRequest("GET", "/file", query = mapOf("path" to path)),
            ListSerializer(FileNode.serializer()),
        )

    suspend fun readFile(path: String): FileContent =
        execute(
            newRequest("GET", "/file/content", query = mapOf("path" to path)),
            FileContent.serializer(),
        )

    suspend fun vcsInfo(): VcsInfo = execute(newRequest("GET", "/vcs"), VcsInfo.serializer())

    suspend fun vcsStatus(): List<VcsFileStatus> =
        execute(newRequest("GET", "/vcs/status"), ListSerializer(VcsFileStatus.serializer()))

    suspend fun vcsDiff(mode: String): List<VcsFileDiff> =
        execute(
            newRequest("GET", "/vcs/diff", query = mapOf("mode" to mode)),
            ListSerializer(VcsFileDiff.serializer()),
        )

    suspend fun sessionDiff(sessionId: String): List<VcsFileDiff> =
        execute(
            newRequest("GET", "/session/$sessionId/diff"),
            ListSerializer(VcsFileDiff.serializer()),
        )

    suspend fun todos(sessionId: String): List<Todo> =
        execute(
            newRequest("GET", "/session/$sessionId/todo"),
            ListSerializer(Todo.serializer()),
        )

    /**
     * Subscribes to the server-sent event stream. The flow completes when the
     * connection is closed; collect it in a scope that cancels on disconnect.
     */
    fun events(): Flow<EventEnvelope> = callbackFlow {
        val request = newRequest("GET", "/event", accept = "text/event-stream")
        val factory = EventSources.createFactory(client)
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { json.decodeFromString<EventEnvelope>(data) }
                    .onSuccess { trySend(it) }
                    .onFailure { error -> Log.w(TAG, "Failed to parse event: $data", error) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(OpenCodeException(response?.code ?: -1, t?.message ?: "Event stream failed"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }
        val source = factory.newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    private fun Call.timeout(millis: Long) {
        timeout().timeout(if (millis == 0L) 0 else millis, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "OpenCodeClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null, 0, 0)
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

        fun normalizeBaseUrl(input: String): String {
            var value = input.trim()
            if (value.isEmpty()) return value
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "http://$value"
            }
            return value.trimEnd('/')
        }
    }
}

class OpenCodeException(val code: Int, override val message: String) : IOException(message)

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

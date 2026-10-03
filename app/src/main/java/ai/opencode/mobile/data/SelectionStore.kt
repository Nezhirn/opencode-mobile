package ai.opencode.mobile.data

import ai.opencode.mobile.R
import ai.opencode.mobile.data.local.ModelSelection
import ai.opencode.mobile.data.local.SettingsSource
import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.Model
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.ProviderList
import ai.opencode.mobile.data.remote.SessionModel
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Outcome of checking whether a prompt can be sent with the current selection. */
internal sealed interface SendSelection {
    data class Ready(val model: PromptModel, val agent: String?, val variant: String? = null) : SendSelection
    data class Rejected(val message: UiText) : SendSelection
}

/**
 * What the server can run (configured providers, agents) and what the user chose
 * to run it with. Every change to the selected model goes through [lock], so an
 * explicit pick cannot be overwritten by a reconciliation that read the old value.
 */
internal class SelectionStore(
    private val scope: CoroutineScope,
    private val settingsStore: SettingsSource,
) {
    /**
     * Providers the opencode server reports as actually configured. Catalog-only
     * providers are never exposed: offering them would let the user pick a model
     * the server cannot run.
     */
    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    /** False until /provider has been read at least once. */
    private val _providersLoaded = MutableStateFlow(false)
    val providersLoaded: StateFlow<Boolean> = _providersLoaded.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _selectedModel = MutableStateFlow<PromptModel?>(null)
    val selectedModel: StateFlow<PromptModel?> = _selectedModel.asStateFlow()

    private val _selectedAgent = MutableStateFlow<String?>(null)
    val selectedAgent: StateFlow<String?> = _selectedAgent.asStateFlow()

    /** Set when a previously chosen model or agent is no longer available on the server. */
    private val _notice = MutableStateFlow<UiText?>(null)
    val notice: StateFlow<UiText?> = _notice.asStateFlow()

    /**
     * Models the user wants in the picker ([modelKey]s), or null when the list
     * was never customised and every configured model is shown. Purely a display
     * preference: hidden models stay runnable and can be turned back on.
     */
    private val _enabledModels = MutableStateFlow<Set<String>?>(null)
    val enabledModels: StateFlow<Set<String>?> = _enabledModels.asStateFlow()

    /** Variant picked per model ([modelKey] -> variant name). */
    private val variantPicks = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Variants the selected model offers, in server order; empty when it has none. */
    val availableVariants: StateFlow<List<String>> = combine(_selectedModel, _providers) { model, providers ->
        model?.let { providers.variantsOf(it) }.orEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Variant prompts are sent with; null means the model's defaults. */
    val selectedVariant: StateFlow<String?> = combine(_selectedModel, _providers, variantPicks) { model, providers, picks ->
        resolveVariant(model, providers, picks)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    @Volatile
    private var serverUrl: String? = null
    private var visibilityJob: Job? = null
    private val visibilityWriteLock = Mutex()

    private val lock = Any()

    /** The user's stored pick, restored on start and kept in sync with the picker. */
    private var storedSelection = ModelSelection()

    /** True once the user picked explicitly; restoring must not overwrite that. */
    private var userPickedModel = false

    /** Completes once the stored pick has been read (or failed to read). */
    private val restored = CompletableDeferred<Unit>()

    /**
     * Completes once the stored pick of the current server is known: [restored]
     * on start, a fresh read after switching servers (the pick is kept per
     * server, so returning to a server brings its model back).
     */
    @Volatile
    private var selectionReady: CompletableDeferred<Unit> = restored
    private var selectionJob: Job? = null
    private val selectionWriteLock = Mutex()

    fun restore() {
        scope.launch {
            // Restore the explicit pick before providers load; it is validated
            // against the configured list in applyProviders(), which waits on
            // `restored` so a cold start cannot discard the stored choice.
            try {
                // Bounded: a DataStore read that never emits would otherwise hang
                // applyProviders() forever, leaving the app with no models at all.
                runCatching {
                    withTimeout(RESTORE_TIMEOUT_MILLIS) { settingsStore.modelVariants.first() }
                }.getOrNull()?.let { stored ->
                    // Picks made while this read was in flight win.
                    variantPicks.update { picks -> stored + picks }
                }
                val stored = runCatching {
                    withTimeout(RESTORE_TIMEOUT_MILLIS) { settingsStore.modelSelection.first() }
                }.getOrNull() ?: return@launch
                synchronized(lock) {
                    // A pick made while this read was in flight wins; otherwise the
                    // disk value would overwrite it and the next agent change would
                    // persist the stale model back.
                    if (userPickedModel) return@launch
                    storedSelection = stored
                    if (stored.hasModel && _selectedModel.value == null) {
                        _selectedModel.value = PromptModel(stored.providerId, stored.modelId)
                    }
                    if (stored.agent.isNotBlank() && _selectedAgent.value == null) {
                        _selectedAgent.value = stored.agent
                    }
                }
            } finally {
                restored.complete(Unit)
            }
        }
    }

    suspend fun applyProviders(result: ProviderList) {
        // Never reconcile against a selection that has not been read yet;
        // otherwise the first load would replace the stored pick with the server
        // default and then persist that replacement.
        selectionReady.await()
        val configured = configuredProviders(result)
        _providers.value = configured
        // An empty result while the catalogue itself is non-empty means this
        // server does not report `connected`/`default` in a shape we understand.
        // Treating that as "nothing is configured" would wipe the stored pick and
        // block every send with no way back, so the list is left unvalidated.
        val signalReadable = configured.isNotEmpty() || result.all.isEmpty()
        _providersLoaded.value = signalReadable
        if (signalReadable) {
            reconcileModel(result, configured)
        } else {
            Log.w(TAG, "/provider returned ${result.all.size} providers but none configured")
        }
    }

    fun clearProviders() {
        _providers.value = emptyList()
        _providersLoaded.value = false
    }

    /**
     * Keeps the selected model pointing at a model opencode is configured for. A
     * pick that is still valid is left untouched; one that is not (server
     * reconfigured, provider disconnected) is replaced by the server-declared
     * default and reported, so the model a prompt is sent with is never silently
     * different from the one that was chosen.
     */
    private fun reconcileModel(providers: ProviderList, configured: List<Provider>) {
        val forgetStored = synchronized(lock) {
            val current = _selectedModel.value
            if (current != null && configured.offers(current)) return
            val fallback = resolveDefaultModel(providers)
            _selectedModel.value = fallback
            _notice.value = when {
                current == null -> null
                fallback != null -> uiText(R.string.notice_model_replaced, current.label, fallback.label)
                else -> uiText(R.string.notice_model_missing, current.label)
            }
            // Only forget the stored pick when a usable replacement exists. Erasing
            // it because the only configured provider happens to be down right now
            // would lose the choice permanently.
            current != null && fallback != null
        }
        if (forgetStored) updateStoredSelection { it.copy(providerId = "", modelId = "") }
    }

    fun applyAgents(list: List<Agent>) {
        // Name is the LazyColumn key in the picker, so blanks and duplicates go.
        val agents = list.filter { agent -> agent.hidden != true && agent.name.isNotBlank() }
            .distinctBy { agent -> agent.name }
        _agents.value = agents
        // Same rule as for models: an agent that disappeared from the server must
        // not keep being sent with every prompt without the user knowing.
        val current = _selectedAgent.value ?: return
        if (agents.isNotEmpty() && agents.none { it.name == current }) {
            _selectedAgent.value = null
            _notice.value = uiText(R.string.notice_agent_missing, current)
            updateStoredSelection { it.copy(agent = "") }
        }
    }

    /**
     * Seeds the selection from the model the session last ran with, but only when
     * nothing is selected yet. Every prompt carries an explicit model, so the
     * current selection — not the session's history — decides what runs.
     * Models that are not configured are ignored.
     */
    fun adoptSessionModel(model: SessionModel) {
        if (model.providerID.isBlank() || model.id.isBlank()) return
        val candidate = PromptModel(providerID = model.providerID, modelID = model.id)
        synchronized(lock) {
            if (_selectedModel.value != null) return
            if (_providers.value.offers(candidate)) _selectedModel.value = candidate
        }
    }

    /** Records an explicit pick so it survives a restart. */
    fun selectModel(model: PromptModel?) {
        synchronized(lock) {
            _selectedModel.value = model
            _notice.value = null
            userPickedModel = true
        }
        updateStoredSelection {
            it.copy(providerId = model?.providerID.orEmpty(), modelId = model?.modelID.orEmpty())
        }
    }

    /** Picks the variant for the selected model; null goes back to its defaults. */
    fun selectVariant(variant: String?) {
        val model = _selectedModel.value ?: return
        val key = modelKey(model.providerID, model.modelID)
        variantPicks.update { picks -> if (variant == null) picks - key else picks + (key to variant) }
        scope.launch {
            runCatching { settingsStore.saveModelVariant(key, variant) }
                .onFailure { Log.w(TAG, "saveModelVariant failed", it) }
        }
    }

    fun selectAgent(agent: String?) {
        _selectedAgent.value = agent
        updateStoredSelection { it.copy(agent = agent.orEmpty()) }
    }

    fun clearNotice() {
        _notice.value = null
    }

    // --- Picker visibility ---

    /** Loads the visibility preference of the server the app now talks to. */
    fun bindServer(url: String?) {
        synchronized(lock) {
            if (url == serverUrl) return
            val previous = serverUrl
            serverUrl = url
            if (previous != null && url != null) reloadSelectionFor(url)
            visibilityJob?.cancel()
            _enabledModels.value = null
            if (url == null) return
            // Read once: from here on memory is the source of truth, so a toggle
            // is never flipped back by a disk read racing its own write.
            visibilityJob = scope.launch {
                val stored = runCatching {
                    withTimeout(RESTORE_TIMEOUT_MILLIS) { settingsStore.enabledModels(url).first() }
                }.getOrNull()
                if (serverUrl == url) _enabledModels.compareAndSet(null, stored)
            }
        }
    }

    /** Replaces the selection with the one stored for [url]; called under [lock]. */
    private fun reloadSelectionFor(url: String) {
        val ready = CompletableDeferred<Unit>()
        selectionReady = ready
        selectionJob?.cancel()
        userPickedModel = false
        storedSelection = ModelSelection()
        _selectedModel.value = null
        _selectedAgent.value = null
        _notice.value = null
        selectionJob = scope.launch {
            try {
                val stored = runCatching {
                    withTimeout(RESTORE_TIMEOUT_MILLIS) { settingsStore.modelSelection.first() }
                }.getOrNull() ?: return@launch
                synchronized(lock) {
                    if (serverUrl != url || userPickedModel) return@launch
                    storedSelection = stored
                    if (stored.hasModel) _selectedModel.value = PromptModel(stored.providerId, stored.modelId)
                    if (stored.agent.isNotBlank()) _selectedAgent.value = stored.agent
                }
            } finally {
                ready.complete(Unit)
            }
        }
    }

    fun setModelEnabled(model: PromptModel, enabled: Boolean) {
        val key = modelKey(model.providerID, model.modelID)
        updateEnabled { current -> if (enabled) current + key else current - key }
    }

    /** Turns a whole provider on or off, e.g. to hide the OpenRouter catalogue in one go. */
    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        val keys = _providers.value.firstOrNull { it.id == providerId }
            ?.modelIds()
            ?.map { modelKey(providerId, it) }
            .orEmpty()
        updateEnabled { current -> if (enabled) current + keys else current - keys.toSet() }
    }

    /** Back to "show every configured model". */
    fun showAllModels() {
        _enabledModels.value = null
        persistEnabled()
    }

    /**
     * The first change starts from "everything visible", so turning one model
     * off hides just that one; after that, models the server adds later stay
     * hidden until enabled (OpenRouter keeps growing its catalogue).
     */
    private fun updateEnabled(transform: (Set<String>) -> Set<String>) {
        _enabledModels.update { current -> transform(current ?: allModelKeys()) }
        persistEnabled()
    }

    private fun allModelKeys(): Set<String> =
        _providers.value.flatMapTo(LinkedHashSet()) { provider ->
            provider.modelIds().map { modelKey(provider.id, it) }
        }

    private fun persistEnabled() {
        val url = serverUrl ?: return
        scope.launch {
            // Serialised, and always writing the latest value: two quick toggles
            // can never land on disk in the wrong order.
            visibilityWriteLock.withLock {
                if (serverUrl != url) return@withLock
                runCatching { settingsStore.saveEnabledModels(url, _enabledModels.value) }
                    .onFailure { Log.w(TAG, "saveEnabledModels failed", it) }
            }
        }
    }

    fun checkSendable(): SendSelection {
        val model = _selectedModel.value
        if (model == null || model.providerID.isBlank() || model.modelID.isBlank()) {
            // Sending without a valid model is rejected by the server, so surface a
            // clear message instead of a cryptic session.error.
            return SendSelection.Rejected(uiText(R.string.error_no_model))
        }
        // Enforced only against a list we actually managed to read. A failed or
        // unreadable /provider response must not block a setup that was working a
        // minute ago.
        val configured = _providers.value
        if (_providersLoaded.value && configured.isNotEmpty() && !configured.offers(model)) {
            return SendSelection.Rejected(uiText(R.string.error_model_not_configured, model.label))
        }
        return SendSelection.Ready(model, _selectedAgent.value, resolveVariant(model, configured, variantPicks.value))
    }

    /** Serialises read-modify-write on the stored selection and persists the result. */
    private fun updateStoredSelection(transform: (ModelSelection) -> ModelSelection) {
        synchronized(lock) { storedSelection = transform(storedSelection) }
        scope.launch {
            // Serialised, and always writing the latest value: two quick picks
            // can never land on disk in the wrong order.
            selectionWriteLock.withLock {
                val latest = synchronized(lock) { storedSelection }
                runCatching { settingsStore.saveModelSelection(latest) }
                    .onFailure { Log.w(TAG, "saveModelSelection failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "SelectionStore"
        const val RESTORE_TIMEOUT_MILLIS = 5_000L
    }
}

private val PromptModel.label: String get() = "$providerID/$modelID"

/** Stable id of a model across providers. Provider ids never contain '/'. */
internal fun modelKey(providerId: String, modelId: String): String = "$providerId/$modelId"

/**
 * Whether a model belongs in the picker. The selected model is always shown, so
 * the picker never hides what prompts are actually sent with.
 */
internal fun isModelVisible(key: String, enabled: Set<String>?, selectedKey: String?): Boolean =
    enabled == null || key in enabled || key == selectedKey

/**
 * Providers opencode is actually configured for, in the order the server reports
 * them. Only `connected` counts. `all` is the full models.dev catalog, and
 * `default` is no signal either: `/provider` declares a default model for every
 * catalogue provider (224 of them on opencode 1.18), so falling back to it put
 * the whole catalogue into the picker whenever `connected` was missing.
 */
internal fun configuredProviders(providers: ProviderList): List<Provider> {
    val byId = providers.all.associateBy { it.id }
    return providers.connected.distinct()
        .mapNotNull { byId[it] }
        .filter { it.modelIds().isNotEmpty() }
}

/** Model ids of a provider, taken from the map key and falling back to the model itself. */
internal fun Provider.modelIds(): List<String> =
    models.entries
        .mapNotNull { (key, model) -> key.ifBlank { model.id }.takeIf { it.isNotBlank() } }
        .distinct()

/** The model entry for [modelId]; the map key wins, as in [modelIds]. */
private fun Provider.model(modelId: String): Model? =
    models[modelId] ?: models.values.firstOrNull { it.id == modelId }

/** Variant names [model] offers, or null when the model is not in this list. */
internal fun List<Provider>.variantsOf(model: PromptModel): List<String>? =
    firstOrNull { it.id == model.providerID }
        ?.model(model.modelID)
        ?.variants?.keys?.filter { it.isNotBlank() }

/**
 * The stored pick for [model] when the model still offers it. While the model
 * cannot be looked up (providers not loaded) the pick is kept as is: dropping
 * it would silently send the first prompt after a restart without it.
 */
internal fun resolveVariant(model: PromptModel?, providers: List<Provider>, picks: Map<String, String>): String? {
    if (model == null) return null
    val pick = picks[modelKey(model.providerID, model.modelID)] ?: return null
    val offered = providers.variantsOf(model) ?: return pick
    return pick.takeIf { it in offered }
}

/** True when [model] is offered by one of these providers. */
internal fun List<Provider>.offers(model: PromptModel): Boolean =
    any { it.id == model.providerID && it.modelIds().contains(model.modelID) }

/**
 * Picks a default model from the providers opencode is configured for, so a new
 * session can send a prompt without opening the picker first. The server's
 * `default` entry for a configured provider wins; otherwise the first model of
 * the first configured provider is used. Returns null when nothing is
 * configured — sending is then blocked with an explicit message rather than
 * failing server-side.
 */
internal fun resolveDefaultModel(providers: ProviderList): PromptModel? {
    val configured = configuredProviders(providers)
    configured.forEach { provider ->
        val declared = providers.default[provider.id]?.takeIf { it.isNotBlank() } ?: return@forEach
        if (provider.modelIds().contains(declared)) return PromptModel(provider.id, declared)
    }
    configured.forEach { provider ->
        provider.modelIds().firstOrNull()?.let { return PromptModel(provider.id, it) }
    }
    return null
}

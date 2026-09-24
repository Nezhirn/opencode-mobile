package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.isModelVisible
import ai.opencode.mobile.data.modelKey
import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.Provider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** One selectable model, flattened out of the provider map ahead of rendering. */
@Immutable
private data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
) {
    val key: String get() = modelKey(providerId, modelId)
    val model: PromptModel get() = PromptModel(providerID = providerId, modelID = modelId)
}

/**
 * Flattens the configured providers into selectable rows. The map key is the
 * authoritative model id — a custom provider can leave the model's own `id`
 * blank — and blank ids are dropped so no row can produce a duplicate LazyColumn
 * key (which crashes the list) or a prompt with an empty modelID.
 */
private fun buildModelOptions(providers: List<Provider>): List<ModelOption> =
    providers.flatMap { provider ->
        val providerName = provider.name.ifBlank { provider.id }
        provider.models.entries
            .mapNotNull { (key, model) ->
                val id = key.ifBlank { model.id }
                if (id.isBlank()) {
                    null
                } else {
                    ModelOption(provider.id, providerName, id, model.name.ifBlank { id })
                }
            }
            .distinctBy { it.modelId }
            .sortedBy { it.modelName.lowercase() }
    }

private fun List<ModelOption>.matching(query: String): List<ModelOption> =
    if (query.isBlank()) {
        this
    } else {
        filter { option ->
            option.modelName.contains(query, ignoreCase = true) ||
                option.modelId.contains(query, ignoreCase = true) ||
                option.providerName.contains(query, ignoreCase = true)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(
    providers: List<Provider>,
    providersLoaded: Boolean,
    enabledModels: Set<String>?,
    agents: List<Agent>,
    selected: PromptModel?,
    selectedAgent: String?,
    onDismiss: () -> Unit,
    onSelectModel: (PromptModel) -> Unit,
    onSelectAgent: (String?) -> Unit,
    onManageModels: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        var query by rememberSaveable { mutableStateOf("") }
        // Built once per provider list / query instead of on every scroll frame.
        val options = remember(providers) { buildModelOptions(providers) }
        val selectedKey = selected?.let { modelKey(it.providerID, it.modelID) }
        // Only the models the user enabled, like the web client's picker.
        val enabled = remember(options, enabledModels, selectedKey) {
            options.filter { isModelVisible(it.key, enabledModels, selectedKey) }
        }
        val visible = remember(enabled, query) { enabled.matching(query) }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.models_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.models_configured_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.models_search)) },
                singleLine = true,
                enabled = enabled.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }

        // Models and agents share one scrollable list. Previously the agents sat
        // in the sheet's own Column below a fixed-height list, so a server with
        // more than a few agents pushed them past the bottom of the sheet with
        // no way to reach them.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(horizontal = 16.dp),
        ) {
            val emptyText = when {
                options.isEmpty() && providersLoaded -> R.string.models_none_configured
                options.isEmpty() -> R.string.models_loading
                enabled.isEmpty() -> R.string.models_none_enabled
                visible.isEmpty() -> R.string.models_no_match
                else -> null
            }
            if (emptyText != null) {
                item(key = "models-empty") {
                    Text(
                        text = stringResource(emptyText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            itemsIndexed(visible, key = { _, option -> option.key }) { index, option ->
                Column {
                    if (index == 0 || visible[index - 1].providerId != option.providerId) {
                        ProviderHeader(option.providerName)
                    }
                    ModelRow(
                        name = option.modelName,
                        id = option.modelId,
                        selected = option.key == selectedKey,
                        onClick = { onSelectModel(option.model) },
                    )
                }
            }

            if (options.isNotEmpty()) {
                item(key = "models-manage") {
                    Column {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onManageModels)
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Tune, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.models_manage), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (agents.isNotEmpty()) {
                item(key = "agents-header") {
                    Column {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(stringResource(R.string.agents_title), style = MaterialTheme.typography.titleMedium)
                    }
                }
                item(key = "agent-default") {
                    AgentRow(name = stringResource(R.string.agents_default), selected = selectedAgent == null) {
                        onSelectAgent(null)
                    }
                }
                items(agents, key = { "agent-${it.name}" }) { agent ->
                    AgentRow(name = agent.name, selected = selectedAgent == agent.name) {
                        onSelectAgent(agent.name)
                    }
                }
            }
        }
    }
}

/**
 * Chooses which configured models appear in the picker. Hiding a model only
 * declutters the picker; it stays available on the server and can be turned
 * back on here at any time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManageModelsSheet(
    providers: List<Provider>,
    enabledModels: Set<String>?,
    onDismiss: () -> Unit,
    onSetModelEnabled: (PromptModel, Boolean) -> Unit,
    onSetProviderEnabled: (String, Boolean) -> Unit,
    onShowAll: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        var query by rememberSaveable { mutableStateOf("") }
        val options = remember(providers) { buildModelOptions(providers) }
        val visible = remember(options, query) { options.matching(query) }
        // Provider switches reflect every model of the provider, not just the
        // ones matching the search.
        val enabledByProvider = remember(options, enabledModels) {
            options.groupBy { it.providerId }.mapValues { (_, models) ->
                models.count { enabledModels == null || it.key in enabledModels } to models.size
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.models_manage),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (enabledModels != null) {
                    TextButton(onClick = onShowAll) { Text(stringResource(R.string.models_show_all)) }
                }
            }
            Text(
                text = stringResource(R.string.models_manage_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.models_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 16.dp),
        ) {
            if (visible.isEmpty()) {
                item(key = "manage-empty") {
                    Text(
                        text = stringResource(R.string.models_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            itemsIndexed(visible, key = { _, option -> option.key }) { index, option ->
                Column {
                    if (index == 0 || visible[index - 1].providerId != option.providerId) {
                        val (on, total) = enabledByProvider[option.providerId] ?: (0 to 0)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(option.providerName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = stringResource(R.string.models_enabled_count, on, total),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = on == total && total > 0,
                                onCheckedChange = { onSetProviderEnabled(option.providerId, it) },
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    val checked = enabledModels == null || option.key in enabledModels
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetModelEnabled(option.model, !checked) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(option.modelName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                option.modelId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = checked, onCheckedChange = { onSetModelEnabled(option.model, it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderHeader(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun AgentRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModelRow(name: String, id: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.models_selected),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

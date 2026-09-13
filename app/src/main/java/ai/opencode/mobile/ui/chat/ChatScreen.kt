package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.data.ChatMessageUi
import ai.opencode.mobile.data.ChatState
import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.Model
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.QuestionRequest
import ai.opencode.mobile.ui.components.TruncatedText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onBack: () -> Unit,
    onOpenFiles: (String) -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val questions by viewModel.questions.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.open(sessionId) }
    var showModelSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = chat.title.ifBlank { "Chat" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = selectedModel?.let { "${it.providerID}/${it.modelID}" } ?: "default model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showModelSheet = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Model and agent")
                    }
                    IconButton(onClick = { onOpenFiles(sessionId) }) {
                        Icon(Icons.Filled.Folder, contentDescription = "Files and changes")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (chat.todos.isNotEmpty()) {
                TodoStrip(chat.todos)
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                MessageList(chat = chat, modifier = Modifier.fillMaxSize())
            }

            if (questions.isNotEmpty()) {
                QuestionPanel(
                    questions = questions,
                    onReply = viewModel::replyQuestion,
                    onReject = viewModel::rejectQuestion,
                )
            }

            if (permissions.isNotEmpty()) {
                PermissionPanel(
                    permissions = permissions,
                    onReply = viewModel::replyPermission,
                )
            }

            chat.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            InputBar(
                busy = chat.busy,
                onSend = viewModel::send,
                onStop = viewModel::abort,
            )
        }
    }

    if (showModelSheet) {
        ModelSheet(
            providers = providers,
            agents = agents,
            selected = selectedModel,
            onDismiss = { showModelSheet = false },
            onSelectModel = {
                viewModel.selectModel(it)
                showModelSheet = false
            },
            onSelectAgent = {
                viewModel.selectAgent(it)
                showModelSheet = false
            },
        )
    }
}

@Composable
private fun MessageList(chat: ChatState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val lastMessage by rememberUpdatedState(chat.messages.lastOrNull())

    // Follow the newest content only while the user is already near the bottom:
    // scrollToItem (not animate) avoids cancelling/restarting an animation on
    // every streamed token, and scrolling up is never fought.
    LaunchedEffect(listState) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = total > 0 && lastVisible >= total - 2
            val lastTextLength = lastMessage?.parts?.sumOf { it.text?.length ?: 0 } ?: 0
            Triple(nearBottom, total, lastTextLength)
        }.collect { (nearBottom, total, _) ->
            if (nearBottom && total > 0) {
                listState.scrollToItem(total - 1)
            }
        }
    }

    if (chat.loading && chat.messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (chat.messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "Send a message to start the session.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chat.messages, key = { it.info.id }) { message ->
            MessageItem(message)
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessageUi) {
    val isUser = message.info.role == "user"
    if (isUser) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            message.parts.filter { it.type == "text" }.forEach { part ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = part.text.orEmpty(),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            message.parts.forEach { part -> AssistantPart(part) }
            message.info.error?.let { error ->
                val errorText = remember(error) { error.toString() }
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AssistantPart(part: Part) {
    when (part.type) {
        "text" -> {
            if (part.synthetic == true) return
            SelectionContainer {
                Text(text = part.text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        "reasoning" -> ReasoningBlock(part.text.orEmpty())

        "tool" -> ToolCard(part)

        "step-start" -> StepDivider(label = "step")

        "step-finish" -> StepFinish(part)

        "file" -> FileChip(part.filename ?: part.url.orEmpty())

        "patch" -> PatchChip(part.files.orEmpty())

        "retry" -> Text(
            text = "Retrying (attempt ${part.attempt ?: 1})…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        "compaction" -> StepDivider(label = "context compacted")

        "subtask" -> Text(
            text = "Subtask: ${part.description.orEmpty()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> Unit
    }
}

@Composable
private fun ReasoningBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(part: Part) {
    var expanded by remember { mutableStateOf(false) }
    val state = part.state
    val status = state?.status ?: "pending"
    val (statusColor, statusLabel) = when (status) {
        "completed" -> MaterialTheme.colorScheme.primary to "done"
        "error" -> MaterialTheme.colorScheme.error to "error"
        "running" -> MaterialTheme.colorScheme.tertiary to "running"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to status
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = part.tool.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = state?.title ?: statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status == "running") {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                state?.input?.let { input ->
                    MonoBlock(title = "input", text = remember(input) { input.toString() })
                }
                state?.output?.let { output ->
                    MonoBlock(title = "output", text = output)
                }
                state?.error?.let { error ->
                    MonoBlock(title = "error", text = error)
                }
            }
        }
    }
}

@Composable
private fun MonoBlock(title: String, text: String) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = Color.Black.copy(alpha = 0.25f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
        ) {
            SelectionContainer {
                TruncatedText(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun StepDivider(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Text(
            text = "  $label  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

@Composable
private fun StepFinish(part: Part) {
    val tokens = part.tokens
    val info = buildString {
        tokens?.get("input")?.jsonPrimitive?.contentOrNull?.let { append("in $it ") }
        tokens?.get("output")?.jsonPrimitive?.contentOrNull?.let { append("out $it ") }
        part.cost?.takeIf { it > 0 }?.let { append("$" + "%.4f".format(it)) }
    }.trim()
    if (info.isNotEmpty()) {
        Text(
            text = info,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun FileChip(name: String) {
    AssistChip(onClick = {}, label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
}

@Composable
private fun PatchChip(files: List<String>) {
    if (files.isEmpty()) return
    Text(
        text = "Changed: " + files.joinToString(", ") { it.substringAfterLast('/') },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TodoStrip(todos: List<ai.opencode.mobile.data.remote.Todo>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        todos.forEach { todo ->
            val color = when (todo.status) {
                "completed" -> MaterialTheme.colorScheme.primaryContainer
                "in_progress" -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(color = color, shape = RoundedCornerShape(50)) {
                Text(
                    text = todo.content,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PermissionPanel(
    permissions: List<PermissionRequest>,
    onReply: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        permissions.forEach { request ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Permission: ${request.permission}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (request.patterns.isNotEmpty()) {
                        Text(
                            text = request.patterns.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { onReply(request.id, "once") }) { Text("Allow once") }
                        OutlinedButton(onClick = { onReply(request.id, "always") }) { Text("Always") }
                        TextButton(onClick = { onReply(request.id, "reject") }) { Text("Reject") }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionPanel(
    questions: List<QuestionRequest>,
    onReply: (String, List<List<String>>) -> Unit,
    onReject: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        questions.forEach { request ->
            QuestionCard(request = request, onReply = onReply, onReject = onReject)
        }
    }
}

@Composable
private fun QuestionCard(
    request: QuestionRequest,
    onReply: (String, List<List<String>>) -> Unit,
    onReject: (String) -> Unit,
) {
    val selections = remember(request.id) {
        mutableStateOf(List(request.questions.size) { emptySet<String>() })
    }
    val customs = remember(request.id) {
        mutableStateOf(List(request.questions.size) { "" })
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(modifier = Modifier.padding(12.dp)) {
            request.questions.forEachIndexed { qIndex, question ->
                Text(
                    text = question.header.ifBlank { "Question" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    question.options.forEach { option ->
                        val selected = selections.value[qIndex].contains(option.label)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val current = selections.value.toMutableList()
                                val set = current[qIndex].toMutableSet()
                                if (question.multiple == true) {
                                    if (selected) set.remove(option.label) else set.add(option.label)
                                } else {
                                    if (selected) set.clear() else { set.clear(); set.add(option.label) }
                                }
                                current[qIndex] = set
                                selections.value = current
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
                if (question.custom == true) {
                    OutlinedTextField(
                        value = customs.value[qIndex],
                        onValueChange = { value ->
                            val current = customs.value.toMutableList()
                            current[qIndex] = value
                            customs.value = current
                        },
                        placeholder = { Text("Custom answer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                if (qIndex != request.questions.lastIndex) {
                    Spacer(Modifier.height(8.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    val answers = request.questions.mapIndexed { index, _ ->
                        val selected = selections.value[index].toMutableList()
                        customs.value[index].takeIf { it.isNotBlank() }?.let { selected.add(it) }
                        selected.toList()
                    }
                    onReply(request.id, answers)
                }) { Text("Send") }
                TextButton(onClick = { onReject(request.id) }) { Text("Reject") }
            }
        }
    }
}

@Composable
private fun InputBar(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message opencode") },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                maxLines = 6,
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(
    providers: List<Provider>,
    agents: List<Agent>,
    selected: PromptModel?,
    onDismiss: () -> Unit,
    onSelectModel: (PromptModel) -> Unit,
    onSelectAgent: (String) -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        var query by remember { mutableStateOf("") }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Model", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search models") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                providers.forEach { provider ->
                    val models = provider.models.values.filter { model ->
                        query.isBlank() ||
                            model.name.contains(query, ignoreCase = true) ||
                            model.id.contains(query, ignoreCase = true)
                    }
                    if (models.isEmpty()) return@forEach
                    item(key = "provider-${provider.id}") {
                        Text(
                            text = provider.name.ifBlank { provider.id },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    items(models, key = { "${provider.id}/${it.id}" }) { model ->
                        ModelRow(
                            model = model,
                            selected = selected?.providerID == provider.id && selected.modelID == model.id,
                            onClick = { onSelectModel(PromptModel(providerID = provider.id, modelID = model.id)) },
                        )
                    }
                }
            }

            if (agents.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Agent", style = MaterialTheme.typography.titleMedium)
                agents.forEach { agent ->
                    DropdownMenuItem(
                        text = { Text(agent.name) },
                        onClick = { onSelectAgent(agent.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: Model, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name.ifBlank { model.id }, style = MaterialTheme.typography.bodyMedium)
            Text(
                model.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

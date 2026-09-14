package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.ChatMessageUi
import ai.opencode.mobile.data.ChatState
import ai.opencode.mobile.data.remote.Agent
import ai.opencode.mobile.data.remote.Part
import ai.opencode.mobile.data.remote.PermissionRequest
import ai.opencode.mobile.data.remote.PromptModel
import ai.opencode.mobile.data.remote.Provider
import ai.opencode.mobile.data.remote.QuestionRequest
import ai.opencode.mobile.ui.components.TruncatedText
import ai.opencode.mobile.ui.theme.LocalGnomeAccents
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    val providersLoaded by viewModel.providersLoaded.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val selectedAgent by viewModel.selectedAgent.collectAsStateWithLifecycle()
    val modelNotice by viewModel.modelNotice.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.open(sessionId) }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // The subtitle names the model every prompt will actually be
                    // sent with, and opens the picker, so the choice is never
                    // something the user has to infer.
                    Column(modifier = Modifier.clickable { showModelSheet = true }) {
                        Text(
                            text = chat.title.ifBlank { stringResource(R.string.chat_title) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val model = selectedModel
                        val modelLabel = if (model == null) {
                            stringResource(R.string.chat_no_model)
                        } else {
                            stringResource(R.string.chat_model_subtitle, model.providerID, model.modelID)
                        }
                        Text(
                            text = modelLabel + (selectedAgent?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (model == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showModelSheet = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.chat_model_and_agent))
                    }
                    IconButton(onClick = { onOpenFiles(sessionId) }) {
                        Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.sessions_open_files))
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

            modelNotice?.let { notice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = notice,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearModelNotice) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
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
            providersLoaded = providersLoaded,
            agents = agents,
            selected = selectedModel,
            selectedAgent = selectedAgent,
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

    // Follow the newest content only while the user is already near the bottom,
    // and only when the content itself changed. Driving this off layoutInfo
    // instead made every scrollToItem produce a new layout, which produced a new
    // emission — a scroll/layout loop that showed up as jank while streaming.
    val nearBottom = remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            total == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= total - 2
        }
    }
    // Growth also happens in tool output and in new parts, not just in `text`:
    // keying on text length alone froze the scroll for the whole duration of a
    // tool call. Computed directly rather than through remember — summing a few
    // lengths is cheaper than the deep equals a ChatMessageUi key would cost,
    // and LaunchedEffect only compares the resulting ints.
    val lastParts = chat.messages.lastOrNull()?.parts
    val contentSignature = Triple(
        chat.messages.size,
        lastParts?.size ?: 0,
        lastParts?.sumOf { part ->
            (part.text?.length ?: 0) + (part.state?.output?.length ?: 0) + (part.state?.status?.length ?: 0)
        } ?: 0,
    )
    LaunchedEffect(contentSignature) {
        // Reading .value outside composition keeps this off the recomposition path.
        if (nearBottom.value && chat.messages.isNotEmpty()) {
            listState.scrollToItem(chat.messages.lastIndex)
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
            message.parts.filter { it.type == "file" }.forEach { part ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(
                        text = part.filename ?: part.url.orEmpty(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

        "step-start" -> StepDivider(label = stringResource(R.string.chat_step))

        "step-finish" -> StepFinish(part)

        "file" -> FileChip(part.filename ?: part.url.orEmpty())

        "patch" -> PatchChip(part.files.orEmpty())

        "retry" -> Text(
            text = stringResource(R.string.chat_retrying, part.attempt ?: 1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        "compaction" -> StepDivider(label = stringResource(R.string.chat_context_compacted))

        "subtask" -> Text(
            text = stringResource(R.string.chat_subtask, part.description.orEmpty()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        "snapshot" -> Unit

        "agent" -> part.name?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                text = stringResource(R.string.chat_agent, name),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Never swallow a part silently: show its type so new server part kinds
        // are at least visible instead of disappearing.
        else -> {
            if (part.type.isNotBlank()) {
                Text(
                    text = part.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                    text = stringResource(R.string.chat_thinking),
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
    val accents = LocalGnomeAccents.current
    val (statusColor, statusLabel) = when (status) {
        "completed" -> accents.success to "done"
        "error" -> MaterialTheme.colorScheme.error to "error"
        "running" -> MaterialTheme.colorScheme.primary to "running"
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
                    // Keyed on the part id, not on the JsonObject: JsonObject.equals
                    // deep-compares the whole tree on every recomposition, and
                    // toString() on a write/edit input serialises a file's contents.
                    MonoBlock(
                        title = "input",
                        text = remember(part.id, status) { input.toString() },
                        stateKey = "${part.id}-input",
                    )
                }
                state?.output?.let { output ->
                    MonoBlock(title = "output", text = output, stateKey = "${part.id}-output")
                }
                state?.error?.let { error ->
                    MonoBlock(title = "error", text = error, stateKey = "${part.id}-error")
                }
            }
        }
    }
}

@Composable
private fun MonoBlock(title: String, text: String, stateKey: Any? = Unit) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            // Theme-derived, so the block does not turn into a black slab in the
            // light scheme the way a hardcoded translucent black did.
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
        ) {
            SelectionContainer {
                TruncatedText(
                    text = text,
                    stateKey = stateKey,
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
        // Safe cast, not `.jsonPrimitive`: that accessor throws when the server
        // sends an object (opencode already does for `tokens.cache`), and the
        // crash repeated on every recomposition because the part is kept in
        // repository state.
        tokens?.tokenCount("input")?.let { append("in $it ") }
        tokens?.tokenCount("output")?.let { append("out $it ") }
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

private fun JsonObject.tokenCount(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

@Composable
private fun FileChip(name: String) {
    // Not clickable: this is a label for an attached file, not an action.
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PatchChip(files: List<String>) {
    if (files.isEmpty()) return
    Text(
        text = stringResource(R.string.chat_changed_files, files.joinToString(", ") { it.substringAfterLast('/') }),
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
    // Capped and scrollable: as an unbounded sibling of the weighted message
    // list this squeezed the list to zero height and pushed the input bar off
    // screen, leaving no way to answer or type.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PANEL_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        permissions.forEach { request ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.chat_permission, request.permission),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (request.patterns.isNotEmpty()) {
                        Text(
                            text = request.patterns.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { onReply(request.id, "once") }) { Text(stringResource(R.string.chat_allow_once)) }
                        OutlinedButton(onClick = { onReply(request.id, "always") }) { Text(stringResource(R.string.chat_always)) }
                        TextButton(onClick = { onReply(request.id, "reject") }) { Text(stringResource(R.string.chat_reject)) }
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
    // See PermissionPanel: a multi-question card is easily taller than the
    // screen and must not push the input bar out of the layout.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PANEL_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
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
                    text = question.header.ifBlank { stringResource(R.string.chat_question) },
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
                        placeholder = { Text(stringResource(R.string.chat_custom_answer)) },
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
                }) { Text(stringResource(R.string.chat_send)) }
                TextButton(onClick = { onReject(request.id) }) { Text(stringResource(R.string.chat_reject)) }
            }
        }
    }
}

@Composable
private fun InputBar(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    // Saveable: a half-written prompt must survive rotation and process death.
    var text by rememberSaveable { mutableStateOf("") }
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
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                maxLines = 6,
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop), tint = MaterialTheme.colorScheme.error)
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
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                }
            }
        }
    }
}

/** Ceiling for the permission/question panels so the input bar always fits. */
private val PANEL_MAX_HEIGHT = 260.dp

/** One selectable model, flattened out of the provider map ahead of rendering. */
@Immutable
private data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
) {
    val key: String get() = "$providerId/$modelId"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(
    providers: List<Provider>,
    providersLoaded: Boolean,
    agents: List<Agent>,
    selected: PromptModel?,
    selectedAgent: String?,
    onDismiss: () -> Unit,
    onSelectModel: (PromptModel) -> Unit,
    onSelectAgent: (String?) -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        var query by rememberSaveable { mutableStateOf("") }
        // Built once per provider list / query instead of on every scroll frame.
        val options = remember(providers) { buildModelOptions(providers) }
        val visible = remember(options, query) {
            if (query.isBlank()) {
                options
            } else {
                options.filter { option ->
                    option.modelName.contains(query, ignoreCase = true) ||
                        option.modelId.contains(query, ignoreCase = true) ||
                        option.providerName.contains(query, ignoreCase = true)
                }
            }
        }

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
                enabled = options.isNotEmpty(),
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
            if (options.isEmpty()) {
                item(key = "models-empty") {
                    Text(
                        text = if (providersLoaded) {
                            stringResource(R.string.models_none_configured)
                        } else {
                            stringResource(R.string.models_loading)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else if (visible.isEmpty()) {
                item(key = "models-no-match") {
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
                        Text(
                            text = option.providerName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    ModelRow(
                        name = option.modelName,
                        id = option.modelId,
                        selected = selected?.providerID == option.providerId && selected.modelID == option.modelId,
                        onClick = {
                            onSelectModel(PromptModel(providerID = option.providerId, modelID = option.modelId))
                        },
                    )
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

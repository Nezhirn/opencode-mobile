package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.ChatMessageUi
import ai.opencode.mobile.data.ChatState
import ai.opencode.mobile.ui.components.TruncatedText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.filter

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
    val replying by viewModel.replying.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val providersLoaded by viewModel.providersLoaded.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val selectedAgent by viewModel.selectedAgent.collectAsStateWithLifecycle()
    val modelNotice by viewModel.modelNotice.collectAsStateWithLifecycle()
    val enabledModels by viewModel.enabledModels.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.open(sessionId) }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }
    var showManageModels by rememberSaveable { mutableStateOf(false) }

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
                    replying = replying,
                    onReply = viewModel::replyQuestion,
                    onReject = viewModel::rejectQuestion,
                )
            }

            if (permissions.isNotEmpty()) {
                PermissionPanel(
                    permissions = permissions,
                    replying = replying,
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
                text = draft,
                onTextChange = viewModel::onDraftChange,
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
            enabledModels = enabledModels,
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
            onManageModels = {
                showModelSheet = false
                showManageModels = true
            },
        )
    }

    if (showManageModels) {
        ManageModelsSheet(
            providers = providers,
            enabledModels = enabledModels,
            // Back to the picker, where the result of the changes is visible.
            onDismiss = {
                showManageModels = false
                showModelSheet = true
            },
            onSetModelEnabled = viewModel::setModelEnabled,
            onSetProviderEnabled = viewModel::setProviderEnabled,
            onShowAll = viewModel::showAllModels,
        )
    }
}

/** How close to the end still counts as "at the bottom" for following. */
private val FOLLOW_TOLERANCE = 48.dp

@Composable
private fun MessageList(chat: ChatState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val tolerancePx = with(LocalDensity.current) { FOLLOW_TOLERANCE.roundToPx() }

    // Whether new content should keep the end in view. It is decided where the
    // user leaves the list after a scroll, not re-derived after every layout:
    // growth alone would otherwise turn it off, and an index-based check stayed
    // true while the user read the top of a long last message, yanking them back
    // down on every streamed token.
    var follow by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { scrolling -> !scrolling }
            .collect { follow = listState.isAtBottom(tolerancePx) }
    }

    // Growth also happens in tool output and in new parts, not just in `text`:
    // keying on text length alone froze the scroll for the whole duration of a
    // tool call. Computed directly rather than through remember — summing a few
    // lengths is cheaper than the deep equals a ChatMessageUi key would cost,
    // and LaunchedEffect only compares the resulting ints.
    val lastMessage = chat.messages.lastOrNull()
    val lastParts = lastMessage?.parts
    val contentSignature = Triple(
        chat.messages.size,
        lastParts?.size ?: 0,
        lastParts?.sumOf { part ->
            (part.text?.length ?: 0) + (part.state?.output?.length ?: 0) + (part.state?.status?.length ?: 0)
        } ?: 0,
    )
    LaunchedEffect(contentSignature) {
        if (lastMessage == null) return@LaunchedEffect
        // Sending a prompt brings the conversation back into view.
        if (lastMessage.isLocalEcho) follow = true
        // Never fight a finger that is on the list.
        if (!follow || listState.isScrollInProgress) return@LaunchedEffect
        listState.scrollToEnd()
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
                stringResource(R.string.chat_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chat.messages, key = { it.info.id }) { message ->
            MessageItem(message)
        }
    }
}

private fun LazyListState.isAtBottom(tolerancePx: Int): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size <= info.viewportEndOffset + tolerancePx
}

/**
 * Scrolls to the end of the last item. scrollToItem() alone aligns an item's
 * top, which parked the view at the start of a reply taller than the screen
 * while its tail kept growing out of sight.
 */
private suspend fun LazyListState.scrollToEnd() {
    // Effects start right after composition, before the frame's layout: without
    // waiting, the item count and canScrollForward would describe the content
    // as it was one update ago and the newest text would stay below the fold.
    withFrameNanos { }
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if ((layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) < lastIndex) scrollToItem(lastIndex)
    // scrollBy consumes only what is left, so this stops exactly at the end.
    repeat(MAX_END_SCROLL_STEPS) {
        if (!canScrollForward) return
        scrollBy(END_SCROLL_STEP_PX)
    }
}

private const val END_SCROLL_STEP_PX = 100_000f
private const val MAX_END_SCROLL_STEPS = 10

@Composable
private fun MessageItem(message: ChatMessageUi) {
    if (message.info.role == "user") {
        UserMessage(message)
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Keyed so per-part UI state (expanded cards) follows its part when
            // parts are inserted or removed, instead of sticking to a position.
            message.parts.forEach { part ->
                key(part.id) { AssistantPart(part) }
            }
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
private fun UserMessage(message: ChatMessageUi) {
    val (texts, files) = remember(message.parts) {
        message.parts.filter { it.type == "text" && it.synthetic != true } to message.parts.filter { it.type == "file" }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        texts.forEach { part ->
            key(part.id) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    // What the user typed is shown as typed (no Markdown), but
                    // bounded: a pasted log can be megabytes.
                    SelectionContainer {
                        TruncatedText(
                            text = part.text.orEmpty(),
                            stateKey = part.id,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
        files.forEach { part ->
            key(part.id) { FileChip(part.filename ?: part.url.orEmpty()) }
        }
    }
}

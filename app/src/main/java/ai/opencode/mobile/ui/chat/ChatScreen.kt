package ai.opencode.mobile.ui.chat

import ai.opencode.mobile.R
import ai.opencode.mobile.data.ChatMessageUi
import ai.opencode.mobile.data.ChatState
import ai.opencode.mobile.data.userText
import ai.opencode.mobile.ui.asString
import ai.opencode.mobile.ui.components.TruncatedText
import ai.opencode.mobile.ui.mcp.McpButton
import ai.opencode.mobile.ui.mcp.McpSheet
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
    val availableVariants by viewModel.availableVariants.collectAsStateWithLifecycle()
    val selectedVariant by viewModel.selectedVariant.collectAsStateWithLifecycle()
    val modelNotice by viewModel.modelNotice.collectAsStateWithLifecycle()
    val enabledModels by viewModel.enabledModels.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    val attachError by viewModel.attachError.collectAsStateWithLifecycle()
    val contextUsage by viewModel.contextUsage.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.open(sessionId) }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }
    var showManageModels by rememberSaveable { mutableStateOf(false) }
    var showMcp by rememberSaveable { mutableStateOf(false) }
    var showContext by rememberSaveable { mutableStateOf(false) }
    var showFilePicker by rememberSaveable { mutableStateOf(false) }
    val pickDeviceFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addDeviceFiles(uris)
    }
    val promptFocus = remember { FocusRequester() }
    LaunchedEffect(viewModel) {
        viewModel.focusPrompt.collect { runCatching { promptFocus.requestFocus() } }
    }

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
                            text = listOfNotNull(modelLabel, selectedVariant?.let(::variantLabel), selectedAgent)
                                .joinToString(" · "),
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
                    ContextButton(usagePercent = contextUsage, onClick = { showContext = true })
                    IconButton(onClick = { showModelSheet = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.chat_model_and_agent))
                    }
                    McpButton(servers = mcpServers, onClick = { showMcp = true })
                    IconButton(onClick = { onOpenFiles(sessionId) }) {
                        Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.sessions_open_files))
                    }
                },
            )
        },
    ) { padding ->
        // Measured inside imePadding, so the budget shrinks while the keyboard is
        // open. A fixed 260dp panel left the message list a sliver there. The
        // Scaffold padding already holds the navigation bar, which the IME inset
        // covers too: consumed here, or the bar was counted twice above the
        // keyboard.
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        ) {
            val density = LocalDensity.current
            // The input bar is measured after the panels and got whatever they
            // left; the budget is what remains once it has its own height.
            var inputBarHeight by remember { mutableStateOf(0.dp) }
            val panelCount = (if (questions.isNotEmpty()) 1 else 0) + (if (permissions.isNotEmpty()) 1 else 0)
            val panelBudget = (maxHeight - inputBarHeight).coerceAtLeast(0.dp)
            val panelMaxHeight = minOf(panelBudget * PANEL_SCREEN_FRACTION, PANEL_MAX_HEIGHT) / panelCount.coerceAtLeast(1)
            Column(modifier = Modifier.fillMaxSize()) {
                if (chat.todos.isNotEmpty()) {
                    TodoStrip(chat.todos)
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    MessageList(
                        chat = chat,
                        onEdit = viewModel::edit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (questions.isNotEmpty()) {
                    QuestionPanel(
                        questions = questions,
                        replying = replying,
                        maxHeight = panelMaxHeight,
                        onReply = viewModel::replyQuestion,
                        onReject = viewModel::rejectQuestion,
                    )
                }

                if (permissions.isNotEmpty()) {
                    PermissionPanel(
                        permissions = permissions,
                        replying = replying,
                        maxHeight = panelMaxHeight,
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
                            text = notice.asString(),
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error.asString(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearError) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    }
                }

                attachError?.let { error ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error.asString(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearAttachError) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    }
                }

                RevertDock(
                    messages = chat.revertedUserMessages,
                    reverting = chat.reverting,
                    onRestore = viewModel::restore,
                )

                InputBar(
                    text = draft,
                    onTextChange = viewModel::onDraftChange,
                    busy = chat.busy,
                    onSend = viewModel::send,
                    onStop = viewModel::abort,
                    variants = availableVariants,
                    selectedVariant = selectedVariant,
                    onSelectVariant = viewModel::selectVariant,
                    attachments = attachments,
                    thumbnails = thumbnails,
                    onAttachFromDevice = { pickDeviceFiles.launch(DEVICE_PICKER_MIMES) },
                    onAttachFromProject = { showFilePicker = true },
                    onRemoveAttachment = viewModel::removeAttachment,
                    focusRequester = promptFocus,
                    modifier = Modifier.onSizeChanged { size -> inputBarHeight = with(density) { size.height.toDp() } },
                )
            }
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

    if (showMcp) {
        val mcpToggling by viewModel.mcpToggling.collectAsStateWithLifecycle()
        val mcpError by viewModel.mcpError.collectAsStateWithLifecycle()
        McpSheet(
            servers = mcpServers,
            toggling = mcpToggling,
            error = mcpError,
            onOpen = viewModel::refreshMcp,
            onToggle = viewModel::setMcpEnabled,
            onDismissError = viewModel::clearMcpError,
            onDismiss = { showMcp = false },
        )
    }

    if (showContext) {
        val stats by viewModel.contextStats.collectAsStateWithLifecycle()
        ContextSheet(stats = stats, sessionTitle = chat.title, onDismiss = { showContext = false })
    }

    if (showFilePicker) {
        val picker by viewModel.filePicker.collectAsStateWithLifecycle()
        ProjectFilePickerSheet(
            state = picker,
            onOpen = viewModel::openFilePicker,
            onQueryChange = viewModel::onFileQueryChange,
            onBrowse = viewModel::browseFiles,
            onUp = viewModel::browseFilesUp,
            onPick = { path ->
                showFilePicker = false
                viewModel.addProjectFile(path)
            },
            onDismiss = { showFilePicker = false },
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

/** Share of the chat area the permission/question panels may take together. */
private const val PANEL_SCREEN_FRACTION = 0.4f

/** Absolute ceiling for the panels on tall screens. */
private val PANEL_MAX_HEIGHT = 320.dp

/** How close to the end still counts as "at the bottom" for following. */
private val FOLLOW_TOLERANCE = 48.dp

@Composable
private fun MessageList(chat: ChatState, onEdit: (String) -> Unit, modifier: Modifier = Modifier) {
    val messages = chat.visibleMessages
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
            // The first value is the current state, not the end of a scroll:
            // evaluated against a list not laid out yet it reset a restored
            // "not following" and yanked the user back to the end.
            .drop(1)
            .filter { scrolling -> !scrolling }
            .collect { follow = listState.isAtBottom(tolerancePx) }
    }
    // The list also loses room without new content: the keyboard opens, a
    // permission panel appears. Keep the end in view then too, or the card the
    // panel asks about slides under it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .drop(1)
            .collect { if (follow && !listState.isScrollInProgress) listState.scrollToEnd() }
    }

    // Growth also happens in tool output and in new parts, not just in `text`:
    // keying on text length alone froze the scroll for the whole duration of a
    // tool call. Computed directly rather than through remember — summing a few
    // lengths is cheaper than the deep equals a ChatMessageUi key would cost,
    // and LaunchedEffect only compares the resulting ints.
    val lastMessage = messages.lastOrNull()
    val lastParts = lastMessage?.parts
    val contentSignature = Triple(
        messages.size,
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

    if (chat.loading && messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (messages.isEmpty()) {
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
        items(messages, key = { it.info.id }, contentType = { it.info.role }) { message ->
            MessageItem(message, editEnabled = !chat.reverting, onEdit = onEdit)
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
private fun MessageItem(message: ChatMessageUi, editEnabled: Boolean, onEdit: (String) -> Unit) {
    if (message.info.role == "user") {
        UserMessage(message, editEnabled, onEdit)
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

/**
 * A sent prompt. A tap shows its actions (copy, edit) below it, like the web
 * client's hover row; a long press stays with text selection.
 */
@Composable
private fun UserMessage(message: ChatMessageUi, editEnabled: Boolean, onEdit: (String) -> Unit) {
    val (texts, files) = remember(message.parts) {
        message.parts.filter { it.type == "text" && it.synthetic != true } to message.parts.filter { it.type == "file" }
    }
    var showActions by rememberSaveable(message.info.id) { mutableStateOf(false) }
    val actionsAvailable = !message.isLocalEcho
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
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = actionsAvailable) { showActions = !showActions },
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
            key(part.id) {
                FileChip(
                    name = part.filename ?: part.url.orEmpty(),
                    icon = attachmentIcon(part.mime),
                    onClick = if (actionsAvailable) ({ showActions = !showActions }) else null,
                )
            }
        }
        if (showActions && actionsAvailable) {
            MessageActions(message = message, editEnabled = editEnabled, onEdit = {
                showActions = false
                onEdit(message.info.id)
            })
        }
    }
}

@Composable
private fun MessageActions(message: ChatMessageUi, editEnabled: Boolean, onEdit: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.message_copied)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(message.userText()))
            // Android 13+ confirms copies itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
        }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.message_copy))
        }
        TextButton(onClick = onEdit, enabled = editEnabled) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.message_edit))
        }
    }
}

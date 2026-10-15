package io.github.openfinalshell.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.openfinalshell.android.CommandDispatchRequest
import io.github.openfinalshell.android.CommandTargetRoster
import io.github.openfinalshell.android.CommandWorkspaceReport
import io.github.openfinalshell.android.MainViewModel
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.core.ai.AiClient
import io.github.openfinalshell.android.core.workspace.CommandBody
import io.github.openfinalshell.android.core.workspace.CommandTarget
import io.github.openfinalshell.android.storage.CommandSnippetEntity
import io.github.openfinalshell.android.tools.AssistantViewModel

private const val HISTORY_PREVIEW = 20
private const val TAB_LIBRARY = 0
private const val TAB_EDITOR = 1
private const val TAB_AI = 2

private val fencedCommand = Regex(
    "```(?:bash|sh|shell|zsh|console|powershell|cmd)?[^\\n]*\\n([\\s\\S]*?)```",
    RegexOption.IGNORE_CASE
)

@Composable
fun CommandLibraryPage(sessionId: String?, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val assistant: AssistantViewModel = viewModel()
    val library by assistant.state.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.commandHistoryRecords.collectAsStateWithLifecycle()
    val report by viewModel.commandWorkspaceReport.collectAsStateWithLifecycle()
    val busy by viewModel.commandSendBusy.collectAsStateWithLifecycle()
    val selectedId = sessionId ?: ui.selectedSessionId
    var section by rememberSaveable { mutableIntStateOf(TAB_LIBRARY) }
    var body by rememberSaveable { mutableStateOf("") }
    var placeholders by rememberSaveable { mutableStateOf(true) }
    var autoEnter by rememberSaveable { mutableStateOf(true) }
    var allTargets by rememberSaveable { mutableStateOf(false) }
    var shortcutId by rememberSaveable { mutableStateOf("") }
    var shortcutName by rememberSaveable { mutableStateOf("") }
    var shortcutGroup by rememberSaveable { mutableStateOf("") }
    var saveOpen by rememberSaveable { mutableStateOf(false) }
    var appliedReport by rememberSaveable { mutableLongStateOf(0L) }
    var pending by remember { mutableStateOf<CommandDispatchRequest?>(null) }
    val roster = viewModel.commandTargetRoster()

    LaunchedEffect(Unit) {
        assistant.refresh()
        viewModel.refreshCommandHistory()
    }
    LaunchedEffect(report?.id) {
        val current = report ?: return@LaunchedEffect
        if (current.id == appliedReport) return@LaunchedEffect
        appliedReport = current.id
        if (current.clearEditor && body == current.editorRaw) {
            body = ""
            shortcutId = ""
        }
    }

    fun dispatch(request: CommandDispatchRequest) {
        if (CommandBody.normalize(request.raw).isEmpty() || request.targets.isEmpty()) return
        if (viewModel.commandBodyNeedsConfirm(request.raw)) pending = request
        else viewModel.sendCommandWorkspace(request)
    }

    fun send(raw: String, fromEditor: Boolean) {
        val targets = lockedTargets(roster, selectedId, allTargets)
        if (targets.isEmpty()) {
            val blocked = !allTargets && selectedId != null && roster.rootBlocked.any { it.target.id == selectedId }
            if (blocked) viewModel.noteLocalRootCommandBlocked()
            return
        }
        dispatch(CommandDispatchRequest(raw, placeholders, autoEnter, targets, fromEditor))
    }

    Column(modifier.fillMaxSize().imePadding()) {
        ScrollableTabRow(selectedTabIndex = section, edgePadding = 0.dp) {
            Tab(selected = section == TAB_LIBRARY, onClick = { section = TAB_LIBRARY }, text = { Text(stringResource(R.string.command_tab_library)) })
            Tab(selected = section == TAB_EDITOR, onClick = { section = TAB_EDITOR }, text = { Text(stringResource(R.string.command_tab_editor)) })
            Tab(selected = section == TAB_AI, onClick = { section = TAB_AI }, text = { Text(stringResource(R.string.command_tab_ai)) })
        }
        when (section) {
            TAB_LIBRARY -> CommandLibraryTab(
                snippets = library.snippets,
                groups = library.groups,
                roster = roster,
                selectedId = selectedId,
                allTargets = allTargets,
                onAllTargets = { allTargets = it },
                placeholders = placeholders,
                onPlaceholders = { placeholders = it },
                autoEnter = autoEnter,
                onAutoEnter = { autoEnter = it },
                busy = busy,
                report = report,
                onSend = { send(it, fromEditor = false) },
                onRetry = ::dispatch,
                onEdit = { snippet ->
                    shortcutId = snippet.id
                    shortcutName = snippet.name
                    shortcutGroup = snippet.groupId.orEmpty()
                    body = snippet.content
                    section = TAB_EDITOR
                },
                onDelete = assistant::deleteSnippet,
                onDeleteGroup = assistant::deleteGroup,
                viewModel = viewModel
            )
            TAB_EDITOR -> CommandEditorTab(
                body = body,
                onBody = { body = it },
                roster = roster,
                selectedId = selectedId,
                allTargets = allTargets,
                onAllTargets = { allTargets = it },
                placeholders = placeholders,
                onPlaceholders = { placeholders = it },
                autoEnter = autoEnter,
                onAutoEnter = { autoEnter = it },
                busy = busy,
                canSend = CommandBody.normalize(body).isNotEmpty() && lockedTargets(roster, selectedId, allTargets).isNotEmpty(),
                report = report,
                history = history.take(HISTORY_PREVIEW),
                onSend = { send(body, fromEditor = true) },
                onRetry = ::dispatch,
                onSave = { saveOpen = true },
                onHistory = { text ->
                    body = text
                    shortcutId = ""
                },
                viewModel = viewModel
            )
            else -> CommandAiTab(
                assistant = assistant,
                onUse = { text ->
                    body = text
                    shortcutId = ""
                    section = TAB_EDITOR
                }
            )
        }
    }

    if (saveOpen) {
        SaveShortcutDialog(
            name = shortcutName,
            onName = { shortcutName = it },
            groupId = shortcutGroup.ifBlank { null },
            onGroup = { shortcutGroup = it.orEmpty() },
            groups = library.groups,
            canSave = shortcutName.isNotBlank() && CommandBody.normalize(body).isNotEmpty(),
            onSaveGroup = { assistant.saveGroup(null, it) },
            onSave = {
                assistant.saveSnippet(shortcutId.ifBlank { null }, shortcutName, CommandBody.normalize(body), shortcutGroup.ifBlank { null })
                saveOpen = false
            },
            onDismiss = { saveOpen = false }
        )
    }
    pending?.let { request ->
        val prepared = CommandBody.prepare(request.raw, request.placeholdersEnabled, request.autoEnter, request.targets)
        val labels = request.targets.joinToString("\n") { viewModel.commandSessionLabel(it.id) }
        val content = prepared.targets.joinToString("\n\n") { send ->
            viewModel.commandSessionLabel(send.targetId) + "\n" + send.expanded.orEmpty()
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.command_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.command_confirm_body, labels, content))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        viewModel.sendCommandWorkspace(request)
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.command_send)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pending = null },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private fun lockedTargets(roster: CommandTargetRoster, selectedId: String?, allTargets: Boolean): List<CommandTarget> {
    val rows = if (allTargets) roster.eligible else roster.eligible.filter { it.target.id == selectedId }
    return rows.map { it.target }
}

@Composable
private fun CommandLibraryTab(
    snippets: List<CommandSnippetEntity>,
    groups: List<io.github.openfinalshell.android.storage.CommandGroupEntity>,
    roster: CommandTargetRoster,
    selectedId: String?,
    allTargets: Boolean,
    onAllTargets: (Boolean) -> Unit,
    placeholders: Boolean,
    onPlaceholders: (Boolean) -> Unit,
    autoEnter: Boolean,
    onAutoEnter: (Boolean) -> Unit,
    busy: Boolean,
    report: CommandWorkspaceReport?,
    onSend: (String) -> Unit,
    onRetry: (CommandDispatchRequest) -> Unit,
    onEdit: (CommandSnippetEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    viewModel: MainViewModel
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = snippets.filter { it.name.contains(query, true) || it.content.contains(query, true) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(OpenFinalShellSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
            CommandTargetHeader(roster, selectedId, allTargets, onAllTargets, viewModel)
            CommandSendSwitches(placeholders, onPlaceholders, autoEnter, onAutoEnter)
            CommandReport(report, busy, onRetry)
            OutlinedTextField(
                query,
                { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.command_search)) },
                singleLine = true
            )
            }
        }
        if (visible.isEmpty()) {
            item { Text(stringResource(R.string.commands_empty)) }
        }
        val grouped = visible.groupBy { it.groupId }
        val order = groups.map { it.id } + listOf(null)
        for (groupId in order.distinct()) {
            val rows = grouped[groupId].orEmpty()
            if (rows.isEmpty()) continue
            val currentGroup = groupId
            item(key = "group-$currentGroup") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        groups.find { it.id == currentGroup }?.name ?: stringResource(R.string.command_group_none),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (currentGroup != null) {
                        TextButton(
                            onClick = { onDeleteGroup(currentGroup) },
                            modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                        ) { Text(stringResource(R.string.tools_delete)) }
                    }
                }
            }
            items(rows, key = { it.id }) { snippet ->
                Column(Modifier.fillMaxWidth().padding(bottom = OpenFinalShellSpacing.Small)) {
                    Text(snippet.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(snippet.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row {
                        TextButton(
                            onClick = { onSend(snippet.content) },
                            enabled = !busy && lockedTargets(roster, selectedId, allTargets).isNotEmpty(),
                            modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                        ) { Text(stringResource(R.string.command_send)) }
                        TextButton(
                            onClick = { onEdit(snippet) },
                            modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                        ) { Text(stringResource(R.string.command_fill_editor)) }
                        TextButton(
                            onClick = { onDelete(snippet.id) },
                            modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                        ) { Text(stringResource(R.string.tools_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandEditorTab(
    body: String,
    onBody: (String) -> Unit,
    roster: CommandTargetRoster,
    selectedId: String?,
    allTargets: Boolean,
    onAllTargets: (Boolean) -> Unit,
    placeholders: Boolean,
    onPlaceholders: (Boolean) -> Unit,
    autoEnter: Boolean,
    onAutoEnter: (Boolean) -> Unit,
    busy: Boolean,
    canSend: Boolean,
    report: CommandWorkspaceReport?,
    history: List<io.github.openfinalshell.android.storage.CommandHistoryRecord>,
    onSend: () -> Unit,
    onRetry: (CommandDispatchRequest) -> Unit,
    onSave: () -> Unit,
    onHistory: (String) -> Unit,
    viewModel: MainViewModel
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenFinalShellSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
    ) {
        CommandTargetHeader(roster, selectedId, allTargets, onAllTargets, viewModel)
        CommandSendSwitches(placeholders, onPlaceholders, autoEnter, onAutoEnter)
        OutlinedTextField(
            body,
            onBody,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text(stringResource(R.string.command_body)) },
            minLines = 4
        )
        Row {
            TextButton(
                onClick = onSend,
                enabled = canSend && !busy,
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) { Text(stringResource(R.string.command_send)) }
            TextButton(
                onClick = onSave,
                enabled = CommandBody.normalize(body).isNotEmpty(),
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) { Text(stringResource(R.string.command_save_shortcut)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        CommandReport(report, busy, onRetry)
        Text(stringResource(R.string.command_history_recent), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.command_history_note), style = MaterialTheme.typography.labelSmall)
        if (history.isEmpty()) {
            Text(stringResource(R.string.terminal_history_empty))
        }
        history.forEach { record ->
            TextButton(
                onClick = { onHistory(record.text) },
                modifier = Modifier.fillMaxWidth().heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(record.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.terminal_history_count, record.useCount), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CommandAiTab(assistant: AssistantViewModel, onUse: (String) -> Unit) {
    val state by assistant.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var stream by rememberSaveable { mutableStateOf(true) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenFinalShellSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
    ) {
        Text(stringResource(R.string.command_ai_in_settings), style = MaterialTheme.typography.bodyMedium)
        if (state.profiles.isEmpty()) {
            Text(stringResource(R.string.tools_configure_first))
            return@Column
        }
        state.profiles.forEach { profile ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.selected?.id == profile.id, onClick = { assistant.select(profile) })
                Text(profile.name + " · " + profile.model, modifier = Modifier.weight(1f))
            }
        }
        OutlinedTextField(
            prompt,
            { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.tools_question)) },
            minLines = 3
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(stream, { stream = it })
            Text(stringResource(R.string.tools_stream))
        }
        Row {
            TextButton(
                onClick = { assistant.ask(prompt, "", stream) },
                enabled = state.selected?.hasToken == true && prompt.isNotBlank() && !state.busy && prompt.length <= AiClient.CONTEXT_LIMIT,
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) { Text(stringResource(R.string.tools_send)) }
            TextButton(
                onClick = assistant::stop,
                enabled = state.busy,
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) { Text(stringResource(R.string.tools_stop)) }
            TextButton(
                onClick = assistant::clear,
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) { Text(stringResource(R.string.tools_clear)) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        CommandAiError(state.error)
        if (state.answer.isNotEmpty()) {
            Text(state.answer)
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(state.answer)) },
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) { Text(stringResource(R.string.tools_copy)) }
            fencedCommand.findAll(state.answer).map { it.groupValues[1].trimEnd('\n', '\r') }.filter { it.isNotBlank() }.forEach { command ->
                Text(command, maxLines = 6, overflow = TextOverflow.Ellipsis)
                TextButton(
                    onClick = { onUse(command) },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.command_ai_insert)) }
            }
        }
    }
}

@Composable
private fun CommandTargetHeader(
    roster: CommandTargetRoster,
    selectedId: String?,
    allTargets: Boolean,
    onAllTargets: (Boolean) -> Unit,
    viewModel: MainViewModel
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.XSmall)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = !allTargets, onClick = { onAllTargets(false) })
        Text(stringResource(R.string.command_target_current), modifier = Modifier.weight(1f))
        RadioButton(selected = allTargets, onClick = { onAllTargets(true) })
        Text(stringResource(R.string.command_target_all), modifier = Modifier.weight(1f))
    }
    if (allTargets) {
        val names = roster.eligible.joinToString("\n") { it.label }
        Text(stringResource(R.string.command_all_targets, names.ifEmpty { stringResource(R.string.command_no_targets) }))
    } else {
        val label = selectedId?.let(viewModel::commandSessionLabel) ?: stringResource(R.string.command_no_targets)
        Text(stringResource(R.string.command_current_target, label))
        val eligible = selectedId != null && roster.eligible.any { it.target.id == selectedId }
        val blocked = selectedId != null && roster.rootBlocked.any { it.target.id == selectedId }
        if (blocked) Text(stringResource(R.string.command_root_blocked))
        else if (selectedId != null && !eligible) Text(stringResource(R.string.command_target_unavailable))
    }
    if (roster.rootBlocked.isNotEmpty() && (allTargets || roster.rootBlocked.none { it.target.id == selectedId })) {
        Text(stringResource(R.string.command_root_blocked))
        Text(roster.rootBlocked.joinToString("\n") { it.label }, style = MaterialTheme.typography.labelMedium)
    }
    }
}

@Composable
private fun CommandSendSwitches(
    placeholders: Boolean,
    onPlaceholders: (Boolean) -> Unit,
    autoEnter: Boolean,
    onAutoEnter: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(placeholders, onPlaceholders)
        Text(stringResource(R.string.command_placeholders), modifier = Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(autoEnter, onAutoEnter)
        Text(stringResource(R.string.command_auto_enter), modifier = Modifier.weight(1f))
    }
    Text(
        stringResource(if (autoEnter) R.string.command_auto_enter_note else R.string.command_insert_note),
        style = MaterialTheme.typography.labelSmall
    )
    }
}

@Composable
private fun CommandReport(report: CommandWorkspaceReport?, busy: Boolean, onRetry: (CommandDispatchRequest) -> Unit) {
    if (report == null) return
    if (report.succeeded.isNotEmpty()) {
        Text(stringResource(R.string.command_result_ok, report.succeeded.joinToString("\n")))
    }
    if (report.failed.isNotEmpty()) {
        Text(stringResource(R.string.command_result_failed, report.failed.joinToString("\n")), color = MaterialTheme.colorScheme.error)
    }
    if (report.skipped.isNotEmpty()) {
        Text(stringResource(R.string.command_result_skipped, report.skipped.joinToString("\n")))
    }
    val retry = report.retry
    if (retry != null) {
        TextButton(
            onClick = { onRetry(retry) },
            enabled = !busy,
            modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
        ) { Text(stringResource(R.string.command_retry_failed)) }
    }
}

@Composable
private fun SaveShortcutDialog(
    name: String,
    onName: (String) -> Unit,
    groupId: String?,
    onGroup: (String?) -> Unit,
    groups: List<io.github.openfinalshell.android.storage.CommandGroupEntity>,
    canSave: Boolean,
    onSaveGroup: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var groupName by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.command_save_shortcut)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                OutlinedTextField(name, onName, label = { Text(stringResource(R.string.command_shortcut_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = groupId == null, onClick = { onGroup(null) })
                    Text(stringResource(R.string.command_group_none))
                }
                groups.forEach { group ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = groupId == group.id, onClick = { onGroup(group.id) })
                        Text(group.name)
                    }
                }
                OutlinedTextField(groupName, { groupName = it }, label = { Text(stringResource(R.string.command_group_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                TextButton(
                    onClick = {
                        onSaveGroup(groupName)
                        groupName = ""
                    },
                    enabled = groupName.isNotBlank(),
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.tools_save_group)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = canSave, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.tools_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun CommandAiError(code: String?) {
    if (code == null) return
    val key = when (code) {
        "unauthorized" -> R.string.tools_unauthorized
        "forbidden" -> R.string.tools_forbidden
        "not_found" -> R.string.tools_not_found
        "rate_limit" -> R.string.tools_rate_limit
        "missing_token" -> R.string.tools_missing_token
        "invalid_url" -> R.string.tools_invalid_url
        "context_limit" -> R.string.tools_context_limit
        "cancelled" -> R.string.tools_cancelled
        "conflict" -> R.string.tools_conflict
        "timeout" -> R.string.tools_timeout
        else -> R.string.tools_failed
    }
    Text(stringResource(key), color = MaterialTheme.colorScheme.error)
}

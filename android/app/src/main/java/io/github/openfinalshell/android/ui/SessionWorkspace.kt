package io.github.openfinalshell.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.openfinalshell.android.AndroidUiState
import io.github.openfinalshell.android.MainViewModel
import io.github.openfinalshell.android.MonitorScreen
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.SettingsViewModel
import io.github.openfinalshell.android.SftpScreen
import io.github.openfinalshell.android.TerminalScreen
import io.github.openfinalshell.android.core.model.SessionState
import io.github.openfinalshell.android.core.ssh.SessionSnapshot

@Composable
fun SessionWorkspace(
    state: AndroidUiState,
    viewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    chrome: SessionChrome,
    wide: Boolean,
    onMonitorWidth: (Int) -> Unit,
    onFileHeight: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val sessions = viewModel.visibleSessions()
    val selectedId = state.selectedSessionId
    var menuSessionId by remember { mutableStateOf<String?>(null) }
    var renameSessionId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingClose by remember { mutableStateOf<List<String>?>(null) }
    val filesDescription = stringResource(R.string.cd_session_files)
    val monitorDescription = stringResource(R.string.cd_session_monitor)
    val reconnectDescription = stringResource(R.string.cd_session_reconnect)
    val clearDescription = stringResource(R.string.action_clear)
    val disconnectDescription = stringResource(R.string.action_disconnect)
    Column(modifier.fillMaxSize()) {
        SessionTabRow(
            state = state,
            sessions = sessions,
            menuSessionId = menuSessionId,
            onMenu = { menuSessionId = it },
            onSelect = viewModel::selectSession,
            onNew = {
                val profile = selectedId?.let { state.sessions[it]?.profile }
                    ?: state.profiles.firstOrNull { it.id == state.selectedProfileId }
                if (profile != null) viewModel.connectNew(profile)
            },
            onRename = { id ->
                renameSessionId = id
                renameText = sessionLabel(state, state.sessions[id])
                menuSessionId = null
            },
            onDuplicate = { id ->
                menuSessionId = null
                viewModel.duplicateSession(id)
            },
            onReconnect = { id ->
                menuSessionId = null
                viewModel.reconnectSession(id)
            },
            onClose = { ids ->
                menuSessionId = null
                requestClose(state, ids, viewModel) { pendingClose = it }
            }
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = SessionLayoutLimits.TOOLBAR_DP.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SessionToolButton(Icons.AutoMirrored.Filled.List, filesDescription, selectedId != null) {
                selectedId?.let { viewModel.toggleSessionFiles(it, compact = !wide) }
            }
            SessionToolButton(Icons.Filled.Info, monitorDescription, selectedId != null) {
                selectedId?.let { viewModel.toggleSessionMonitor(it, compact = !wide) }
            }
            SessionToolButton(Icons.Filled.Refresh, reconnectDescription, selectedId != null) {
                selectedId?.let(viewModel::reconnectSession)
            }
            SessionToolButton(Icons.Filled.Clear, clearDescription, selectedId != null) {
                selectedId?.let(viewModel::clearTerminal)
            }
            SessionToolButton(Icons.Filled.Close, disconnectDescription, selectedId != null) {
                selectedId?.let { requestClose(state, listOf(it), viewModel) { pending -> pendingClose = pending } }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (selectedId == null) {
                Column(Modifier.padding(OpenFinalShellSpacing.Medium), verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    Text(stringResource(R.string.session_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.session_empty_body))
                }
            } else {
                SessionPanes(
                    state = state,
                    viewModel = viewModel,
                    settingsViewModel = settingsViewModel,
                    chrome = chrome,
                    onMonitorWidth = onMonitorWidth,
                    onFileHeight = onFileHeight,
                    onCloseFiles = { viewModel.closeSessionFiles(selectedId) },
                    onCloseMonitor = { viewModel.closeSessionMonitor(selectedId) },
                    onCloseTab = { requestClose(state, listOf(selectedId), viewModel) { pendingClose = it } }
                )
            }
        }
    }
    val renaming = renameSessionId
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renameSessionId = null },
            title = { Text(stringResource(R.string.session_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSession(renaming, renameText)
                        renameSessionId = null
                    },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { renameSessionId = null },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    pendingClose?.let { ids ->
        val names = ids.mapNotNull { id -> state.sessions[id]?.takeIf { it.state != SessionState.CLOSED } }
            .joinToString("\n") { sessionLabel(state, it) }
        AlertDialog(
            onDismissRequest = { pendingClose = null },
            title = { Text(stringResource(R.string.session_close_connected_title)) },
            text = { Text(stringResource(R.string.session_close_connected_message, names)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        ids.forEach(viewModel::disconnect)
                        pendingClose = null
                    },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_close_session)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingClose = null },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun SessionPanes(
    state: AndroidUiState,
    viewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    chrome: SessionChrome,
    onMonitorWidth: (Int) -> Unit,
    onFileHeight: (Int) -> Unit,
    onCloseFiles: () -> Unit,
    onCloseMonitor: () -> Unit,
    onCloseTab: () -> Unit
) {
    val density = LocalDensity.current.density
    val fileFullscreen = chrome.fileMode == SessionPanelMode.FULLSCREEN
    val monitorFullscreen = chrome.monitorMode == SessionPanelMode.FULLSCREEN
    BackHandler(enabled = fileFullscreen || monitorFullscreen) {
        if (fileFullscreen) onCloseFiles() else onCloseMonitor()
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val terminalSettings by settingsViewModel.state.collectAsStateWithLifecycle()
                TerminalScreen(state, viewModel, terminalSettings.settings, onCloseTab)
                if (fileFullscreen) {
                    PanelPage(stringResource(R.string.cd_close_panel), onCloseFiles) {
                        FilePane(state, viewModel, settingsViewModel)
                    }
                }
                if (monitorFullscreen || chrome.monitorMode == SessionPanelMode.OVERLAY) {
                    val overlayWidth = if (chrome.monitorMode == SessionPanelMode.OVERLAY) {
                        Modifier.width(chrome.monitorWidthDp.dp).align(Alignment.CenterEnd)
                    } else {
                        Modifier.fillMaxSize()
                    }
                    Box(overlayWidth) {
                        PanelPage(stringResource(R.string.cd_close_panel), onCloseMonitor) {
                            MonitorScreen(state, viewModel)
                        }
                    }
                }
            }
            if (chrome.fileMode == SessionPanelMode.BOTTOM) {
                HorizontalPaneHandle(stringResource(R.string.cd_resize_files)) { delta ->
                    onFileHeight((chrome.fileHeightDp - delta / density).toInt())
                }
                Box(Modifier.fillMaxWidth().height(chrome.fileHeightDp.dp)) {
                    FilePane(state, viewModel, settingsViewModel)
                }
            }
            if (chrome.monitorMode == SessionPanelMode.BOTTOM) {
                HorizontalPaneHandle(stringResource(R.string.cd_resize_files)) { delta ->
                    onFileHeight((chrome.fileHeightDp - delta / density).toInt())
                }
                Box(Modifier.fillMaxWidth().height(chrome.fileHeightDp.dp.coerceAtLeast(SessionLayoutLimits.FILE_MIN_HEIGHT_DP.dp))) {
                    MonitorScreen(state, viewModel)
                }
            }
        }
        if (chrome.monitorMode == SessionPanelMode.END) {
            VerticalPaneHandle(stringResource(R.string.cd_resize_monitor)) { delta ->
                onMonitorWidth((chrome.monitorWidthDp - delta / density).toInt())
            }
            Box(Modifier.width(chrome.monitorWidthDp.dp).fillMaxHeight()) {
                MonitorScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun FilePane(state: AndroidUiState, viewModel: MainViewModel, settingsViewModel: SettingsViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
            TextButton(onClick = viewModel::sftpBack, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.action_history_back))
            }
            TextButton(onClick = viewModel::sftpForward, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.action_history_forward))
            }
        }
        Box(Modifier.weight(1f)) { SftpScreen(state, viewModel, settingsViewModel) }
    }
}

@Composable
private fun PanelPage(closeDescription: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onClose, modifier = Modifier.size(SessionLayoutLimits.TOOLBAR_DP.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = closeDescription)
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun SessionTabRow(
    state: AndroidUiState,
    sessions: List<SessionSnapshot>,
    menuSessionId: String?,
    onMenu: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onClose: (List<String>) -> Unit
) {
    val actionsDescription = stringResource(R.string.cd_session_actions)
    val newDescription = stringResource(R.string.action_new_session)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((index, session) in sessions.withIndex()) {
            val selected = session.sessionId == state.selectedSessionId
            TextButton(
                onClick = { onSelect(session.sessionId) },
                modifier = Modifier.heightIn(min = SessionLayoutLimits.TOOLBAR_DP.dp)
            ) {
                Text(
                    sessionLabel(state, session),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            Box {
                IconButton(
                    onClick = { onMenu(session.sessionId) },
                    modifier = Modifier.size(SessionLayoutLimits.TOOLBAR_DP.dp)
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = actionsDescription)
                }
                DropdownMenu(expanded = menuSessionId == session.sessionId, onDismissRequest = { onMenu(null) }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_rename_session)) }, onClick = { onRename(session.sessionId) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate_session)) }, onClick = { onDuplicate(session.sessionId) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_reconnect)) }, onClick = { onReconnect(session.sessionId) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_close_session)) }, onClick = { onClose(listOf(session.sessionId)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_close_others)) }, onClick = {
                        onClose(sessions.map { it.sessionId }.filter { it != session.sessionId })
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_close_right)) }, onClick = {
                        onClose(sessions.drop(index + 1).map { it.sessionId })
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_close_all)) }, onClick = {
                        onClose(sessions.map { it.sessionId })
                    })
                }
            }
        }
        IconButton(onClick = onNew, modifier = Modifier.size(SessionLayoutLimits.TOOLBAR_DP.dp)) {
            Icon(Icons.Filled.Add, contentDescription = newDescription)
        }
    }
}

@Composable
private fun SessionToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(SessionLayoutLimits.TOOLBAR_DP.dp)) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun VerticalPaneHandle(description: String, onDrag: (Float) -> Unit) {
    Box(
        Modifier
            .width(SessionLayoutLimits.SPLITTER_DP.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            }
            .semantics { contentDescription = description }
    )
}

@Composable
private fun HorizontalPaneHandle(description: String, onDrag: (Float) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(SessionLayoutLimits.SPLITTER_DP.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            }
            .semantics { contentDescription = description }
    )
}

@Composable
fun TransferQueuePane(state: AndroidUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = OpenFinalShellSpacing.Medium, vertical = OpenFinalShellSpacing.Small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.tab_transfers), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = viewModel::clearFinishedTransfers) { Text(stringResource(R.string.file_transfer_clear)) }
        }
        if (state.transfers.isEmpty()) {
            Text(stringResource(R.string.file_transfer_empty), modifier = Modifier.padding(OpenFinalShellSpacing.Medium))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(OpenFinalShellSpacing.Medium)) {
            items(state.transfers, key = { it.id }) { transfer ->
                val sessionId = state.transferSessions[transfer.id]
                val sessionName = sessionId?.let { id ->
                    state.sessionPanels[id]?.title?.takeIf { it.isNotBlank() } ?: state.sessions[id]?.profile?.name ?: id
                }
                val active = transfer.state == io.github.openfinalshell.android.core.sftp.TransferState.QUEUED ||
                    transfer.state == io.github.openfinalshell.android.core.sftp.TransferState.RUNNING ||
                    transfer.state == io.github.openfinalshell.android.core.sftp.TransferState.PAUSED
                val fraction = if (transfer.bytesTotal > 0) (transfer.bytesTransferred.toFloat() / transfer.bytesTotal.toFloat()).coerceIn(0f, 1f) else null
                Column(Modifier.fillMaxWidth().padding(vertical = OpenFinalShellSpacing.Small)) {
                    Text(transfer.remotePath, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    sessionName?.let { Text(stringResource(R.string.file_transfer_session, it), style = MaterialTheme.typography.labelSmall) }
                    Text(transferStateLabel(transfer.state), style = MaterialTheme.typography.labelSmall)
                    if (fraction != null) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else if (transfer.state == io.github.openfinalshell.android.core.sftp.TransferState.RUNNING) {
                        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (transfer.speedBps > 0) Text(stringResource(R.string.file_transfer_speed, formatRate(transfer.speedBps)), style = MaterialTheme.typography.labelSmall)
                    remainingText(transfer)?.let { Text(stringResource(R.string.file_transfer_remaining, it), style = MaterialTheme.typography.labelSmall) }
                    if (!transfer.supportsResume && active) Text(stringResource(R.string.port_resume_note), style = MaterialTheme.typography.labelSmall)
                    transfer.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                    Row {
                        if (active) TextButton(onClick = { viewModel.pauseTransfer(transfer.id) }, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.action_pause)) }
                        if (transfer.state == io.github.openfinalshell.android.core.sftp.TransferState.PAUSED) TextButton(onClick = { viewModel.resumeTransfer(transfer.id) }, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.action_resume)) }
                        if (transfer.state == io.github.openfinalshell.android.core.sftp.TransferState.FAILED || transfer.state == io.github.openfinalshell.android.core.sftp.TransferState.CANCELED) {
                            TextButton(onClick = { viewModel.retryTransfer(transfer.id) }, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.action_retry)) }
                        }
                        if (active) TextButton(onClick = { viewModel.cancelTransfer(transfer.id) }, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun transferStateLabel(state: io.github.openfinalshell.android.core.sftp.TransferState): String = stringResource(
    when (state) {
        io.github.openfinalshell.android.core.sftp.TransferState.QUEUED -> R.string.transfer_state_queued
        io.github.openfinalshell.android.core.sftp.TransferState.RUNNING -> R.string.transfer_state_running
        io.github.openfinalshell.android.core.sftp.TransferState.PAUSED -> R.string.transfer_state_paused
        io.github.openfinalshell.android.core.sftp.TransferState.COMPLETED -> R.string.transfer_state_completed
        io.github.openfinalshell.android.core.sftp.TransferState.FAILED -> R.string.transfer_state_failed
        io.github.openfinalshell.android.core.sftp.TransferState.CANCELED -> R.string.transfer_state_canceled
    }
)

private fun formatRate(bytesPerSecond: Long): String {
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSecond.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytesPerSecond} ${units[index]}" else String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[index])
}

private fun remainingText(transfer: io.github.openfinalshell.android.core.sftp.TransferTask): String? {
    if (transfer.state != io.github.openfinalshell.android.core.sftp.TransferState.RUNNING) return null
    if (transfer.bytesTotal <= 0 || transfer.speedBps <= 0) return null
    val left = transfer.bytesTotal - transfer.bytesTransferred
    if (left <= 0) return null
    val seconds = left / transfer.speedBps
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

@Composable
fun SessionVerticalSplitter(description: String, onDragDp: (Float) -> Unit) {
    val density = LocalDensity.current.density
    VerticalPaneHandle(description) { pixels -> onDragDp(pixels / density) }
}

private fun sessionLabel(state: AndroidUiState, snapshot: SessionSnapshot?): String {
    if (snapshot == null) return ""
    return state.sessionPanels[snapshot.sessionId]?.title?.takeIf { it.isNotBlank() } ?: snapshot.profile.name
}

private fun requestClose(
    state: AndroidUiState,
    ids: List<String>,
    viewModel: MainViewModel,
    onConfirm: (List<String>) -> Unit
) {
    if (ids.isEmpty()) return
    val live = ids.any { id -> state.sessions[id]?.state != SessionState.CLOSED }
    if (live) onConfirm(ids) else ids.forEach(viewModel::disconnect)
}

package io.github.openfinalshell.android.files

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.openfinalshell.android.AndroidUiState
import io.github.openfinalshell.android.MainViewModel
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.core.filesync.CdFollow
import io.github.openfinalshell.android.core.filesync.FastDeletePlan
import io.github.openfinalshell.android.core.filesync.ListedEntry
import io.github.openfinalshell.android.core.filesync.ListedKind
import io.github.openfinalshell.android.core.filesync.PermissionBits
import io.github.openfinalshell.android.core.filesync.RemoteName
import io.github.openfinalshell.android.core.session.FileSortKey
import io.github.openfinalshell.android.core.session.SessionFileState
import io.github.openfinalshell.android.core.ssh.SftpEntry
import io.github.openfinalshell.android.storage.AndroidSettings
import java.text.DateFormat
import java.util.Date

@Composable
fun FileBrowser(
    state: AndroidUiState,
    viewModel: MainViewModel,
    settings: AndroidSettings,
    onToggleHidden: (Boolean) -> Unit,
    onDownloadDirectory: (Uri) -> Unit,
) {
    val sessionId = state.selectedSessionId
    val files = sessionId?.let { state.sessionFiles[it] } ?: SessionFileState(confirmedPath = state.sftpPath)
    val entries = sessionId?.let { state.sessionEntries[it] }.orEmpty().ifEmpty { if (sessionId == null) state.sftpEntries else emptyList() }
    val pending = sessionId?.let { state.sessionPendingDirs[it] }
    val nav = CdFollow.navView(files.confirmedPath, pending)
    var pathText by remember(nav.displayDir) { mutableStateOf(nav.displayDir) }
    var directoryName by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var showDirectory by remember { mutableStateOf(false) }
    var showFile by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SftpEntry?>(null) }
    var renameName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<List<SftpEntry>>(emptyList()) }
    var permissionTarget by remember { mutableStateOf<SftpEntry?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val unavailable = stringResource(R.string.file_unavailable)
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (uris.isNotEmpty()) viewModel.uploadDocuments(uris)
    }
    val uploadTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.uploadDocuments(listOf(it), directory = true)
        }
    }
    val packed = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.uploadPackedDirectory(it)
        }
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { if (viewModel.setTransferDownloadTree(it)) onDownloadDirectory(it) }
    }

    if (showDirectory) NameDialog(
        title = stringResource(R.string.file_new_folder),
        value = directoryName,
        onValue = { directoryName = it },
        onDismiss = { showDirectory = false },
        onConfirm = {
            viewModel.createSftpDirectory(directoryName)
            directoryName = ""
            showDirectory = false
        },
    )
    if (showFile) NameDialog(
        title = stringResource(R.string.file_new_file),
        value = fileName,
        onValue = { fileName = it },
        onDismiss = { showFile = false },
        onConfirm = {
            viewModel.beginNewRemoteFile(fileName)
            fileName = ""
            showFile = false
        },
    )
    renameTarget?.let { entry ->
        NameDialog(
            title = stringResource(R.string.action_rename),
            value = renameName,
            onValue = { renameName = it },
            onDismiss = { renameTarget = null },
            onConfirm = {
                viewModel.renameSftp(entry.path, renameName)
                renameTarget = null
            },
        )
    }
    if (pendingDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(pendingDelete.joinToString("\n") { it.path }, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = {
                    if (pendingDelete.size == 1) {
                        val entry = pendingDelete.first()
                        viewModel.deleteSftp(entry.path, entry.type == SftpEntry.Type.DIRECTORY)
                    } else {
                        viewModel.deleteSelectedSftp()
                    }
                    pendingDelete = emptyList()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = emptyList() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    permissionTarget?.let { entry ->
        PermissionDialog(entry, onDismiss = { permissionTarget = null }) { mode ->
            viewModel.applyRemotePermissions(entry.path, mode)
            permissionTarget = null
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sessionId == null) {
            Text(stringResource(R.string.sftp_connect_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sftp_connect_body))
            return@Column
        }
        OutlinedTextField(
            value = pathText,
            onValueChange = { pathText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.file_path)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { viewModel.browseSftp(pathText) }),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = viewModel::sftpBack, enabled = files.backStack.isNotEmpty()) { Text(stringResource(R.string.action_history_back)) }
            TextButton(onClick = viewModel::sftpForward, enabled = files.forwardStack.isNotEmpty()) { Text(stringResource(R.string.action_history_forward)) }
            TextButton(onClick = viewModel::goToSftpParent, enabled = files.confirmedPath != "/") { Text(stringResource(R.string.action_parent)) }
            TextButton(onClick = { viewModel.browseSftp() }) { Text(stringResource(R.string.action_refresh)) }
            TextButton(onClick = { onToggleHidden(!settings.sftpShowHiddenFiles) }) {
                Text(stringResource(if (settings.sftpShowHiddenFiles) R.string.file_hidden_shown else R.string.file_hidden))
            }
            TextButton(onClick = { upload.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.port_upload_files)) }
            TextButton(onClick = { uploadTree.launch(null) }) { Text(stringResource(R.string.port_upload_directory)) }
            if (viewModel.packedTransferAvailable()) {
                TextButton(onClick = { packed.launch(null) }) { Text(stringResource(R.string.port_pack_upload)) }
            }
            TextButton(onClick = { destination.launch(viewModel.configuredDownloadTree()) }) { Text(stringResource(R.string.action_download)) }
            TextButton(onClick = { showFile = true }) { Text(stringResource(R.string.file_new_file)) }
            TextButton(onClick = { showDirectory = true }) { Text(stringResource(R.string.file_new_folder)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SortButton(R.string.file_sort_name, files, FileSortKey.NAME, viewModel)
            SortButton(R.string.file_sort_size, files, FileSortKey.SIZE, viewModel)
            SortButton(R.string.file_sort_modified, files, FileSortKey.MODIFIED, viewModel)
            SortButton(R.string.file_sort_owner, files, FileSortKey.OWNER, viewModel)
        }
        val selected = entries.filter { it.path in files.selectedPaths && RemoteName.canOperate(it) }
        if (selected.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.file_selected, selected.size), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = viewModel::downloadSelected) { Text(stringResource(R.string.action_download)) }
                TextButton(onClick = { pendingDelete = selected }) { Text(stringResource(R.string.action_delete)) }
                val fast = fastDeleteReady(selected)
                if (selected.all { it.type == SftpEntry.Type.DIRECTORY }) {
                    TextButton(onClick = { viewModel.requestFastDelete(selected.map { it.path }) }, enabled = fast) {
                        Text(stringResource(R.string.file_fast_delete))
                    }
                }
                TextButton(onClick = viewModel::clearSftpSelection) { Text(stringResource(R.string.action_clear)) }
            }
        }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.sftp_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sftp_empty_body))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(entries, key = { index, entry ->
                    if (entry.nameInvalid) "invalid:$index" else entry.path
                }) { _, entry ->
                    FileRow(
                        entry = entry,
                        selected = entry.path in files.selectedPaths,
                        unavailable = unavailable,
                        fileTap = settings.sftpFileTap,
                        packed = viewModel.packedTransferAvailable(),
                        onToggle = { viewModel.toggleSftpSelection(entry.path) },
                        onOpen = { viewModel.browseSftp(entry.path) },
                        onEdit = { viewModel.openRemoteEditor(entry.path) },
                        onDownload = { viewModel.downloadDocument(entry.path) },
                        onPack = { viewModel.downloadPackedDirectory(entry.path) },
                        onRename = { renameTarget = entry; renameName = entry.name },
                        onDelete = { pendingDelete = listOf(entry) },
                        onPermissions = { permissionTarget = entry },
                        onFastDelete = { viewModel.requestFastDelete(listOf(entry.path)) },
                        onCopy = { clipboard.setText(AnnotatedString(entry.path)) },
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun SortButton(label: Int, files: SessionFileState, key: FileSortKey, viewModel: MainViewModel) {
    val mark = if (files.sortKey != key) "" else if (files.sortAscending) " ↑" else " ↓"
    TextButton(onClick = { viewModel.setFileSort(key) }) { Text(stringResource(label) + mark) }
}

@Composable
private fun FileRow(
    entry: SftpEntry,
    selected: Boolean,
    unavailable: String,
    fileTap: String,
    packed: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDownload: () -> Unit,
    onPack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPermissions: () -> Unit,
    onFastDelete: () -> Unit,
    onCopy: () -> Unit,
    viewModel: MainViewModel,
) {
    val operable = RemoteName.canOperate(entry)
    val directory = entry.type == SftpEntry.Type.DIRECTORY
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = { if (operable) onToggle() }, enabled = operable)
        Column(
            Modifier.weight(1f).clickable(enabled = operable) {
                if (directory) onOpen()
                else if (fileTap == "edit") onEdit()
                else viewModel.downloadDocument(entry.path)
            }
        ) {
            Text(
                if (directory) stringResource(R.string.sftp_directory_entry, entry.name) else entry.name,
                maxLines = 2,
            )
            if (!operable) Text(stringResource(R.string.file_inoperable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            Text(metaLine(entry, unavailable), style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        if (operable) {
            TextButton(onClick = { menu = true }) { Text(stringResource(R.string.file_menu)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (directory) DropdownMenuItem(text = { Text(stringResource(R.string.action_open)) }, onClick = { menu = false; onOpen() })
                if (!directory) DropdownMenuItem(text = { Text(stringResource(R.string.port_edit)) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.file_copy_path)) }, onClick = { menu = false; onCopy() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_download)) }, onClick = { menu = false; onDownload() })
                if (directory && packed) DropdownMenuItem(text = { Text(stringResource(R.string.port_pack_download)) }, onClick = { menu = false; onPack() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                if (directory) DropdownMenuItem(text = { Text(stringResource(R.string.file_fast_delete)) }, onClick = { menu = false; onFastDelete() })
                DropdownMenuItem(text = { Text(stringResource(R.string.file_permissions)) }, onClick = { menu = false; onPermissions() }, enabled = entry.permissions != null)
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, value: String, onValue: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = onValue, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PermissionDialog(entry: SftpEntry, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val existing = entry.permissions
    if (existing == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.file_permissions)) },
            text = { Text(stringResource(R.string.file_unavailable)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        )
        return
    }
    var octal by remember(entry.path) { mutableStateOf(PermissionBits.formatOctal(existing)) }
    var grid by remember(entry.path) { mutableStateOf(PermissionBits.toGrid(existing)) }
    val parsed = runCatching { PermissionBits.parseOctal(octal) }.getOrNull()
    val labels = listOf(
        R.string.file_permission_owner to listOf(R.string.file_permission_read, R.string.file_permission_write, R.string.file_permission_exec),
        R.string.file_permission_group to listOf(R.string.file_permission_read, R.string.file_permission_write, R.string.file_permission_exec),
        R.string.file_permission_other to listOf(R.string.file_permission_read, R.string.file_permission_write, R.string.file_permission_exec),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_permissions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.name, maxLines = 1)
                OutlinedTextField(
                    value = octal,
                    onValueChange = { text ->
                        octal = text
                        runCatching { PermissionBits.parseOctal(text) }.getOrNull()?.let { grid = PermissionBits.toGrid(it) }
                    },
                    label = { Text(stringResource(R.string.file_permission_octal)) },
                    singleLine = true,
                )
                labels.forEachIndexed { row, (title, cells) ->
                    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
                    Row {
                        cells.forEachIndexed { column, cell ->
                            val index = row * 3 + column
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = grid[index], onCheckedChange = { checked ->
                                    val next = grid.copyOf()
                                    next[index] = checked
                                    grid = next
                                    octal = PermissionBits.formatOctal(PermissionBits.fromGrid(next))
                                })
                                Text(stringResource(cell))
                            }
                        }
                    }
                }
                Text(stringResource(R.string.file_permission_special), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun metaLine(entry: SftpEntry, unavailable: String): String {
    val size = entry.size?.toString() ?: unavailable
    val whenText = entry.modifiedAtMillis?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: unavailable
    val owner = entry.owner?.takeIf { it.isNotBlank() }
    val group = entry.group?.takeIf { it.isNotBlank() }
    val who = when {
        owner == null && group == null -> unavailable
        group == null -> owner
        owner == null -> group
        else -> "$owner:$group"
    }
    return "$size · $whenText · $who"
}

private fun fastDeleteReady(entries: List<SftpEntry>): Boolean {
    val listed = entries.map {
        ListedEntry(
            it.path,
            if (it.type == SftpEntry.Type.DIRECTORY) ListedKind.DIRECTORY else ListedKind.FILE,
            it.nameInvalid,
        )
    }
    return try {
        FastDeletePlan.plan(listed) is FastDeletePlan.Result.Ready
    } catch (_: IllegalArgumentException) {
        false
    }
}

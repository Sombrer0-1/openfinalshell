package io.github.openfinalshell.android.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.openfinalshell.android.AndroidUiState
import io.github.openfinalshell.android.BuildConfig
import io.github.openfinalshell.android.ConnectionForm
import io.github.openfinalshell.android.MainViewModel
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.StatusKey
import io.github.openfinalshell.android.UiStatus
import io.github.openfinalshell.android.core.connection.GroupNode
import io.github.openfinalshell.android.core.connection.SshCommandNotice
import io.github.openfinalshell.android.core.connection.allowedParentIds
import io.github.openfinalshell.android.core.connection.formatSshCommand
import io.github.openfinalshell.android.core.connection.maskHost
import io.github.openfinalshell.android.core.connection.parseQuickConnect
import io.github.openfinalshell.android.core.connection.recentProfiles
import io.github.openfinalshell.android.core.model.ConnectionProfile
import io.github.openfinalshell.android.core.model.LOCAL_SHELL_PROTOCOL
import io.github.openfinalshell.android.core.model.LocalShellTier
import io.github.openfinalshell.android.core.model.SessionState
import io.github.openfinalshell.android.local.LocalTierBlocker
import io.github.openfinalshell.android.local.LocalTierResolution
import io.github.openfinalshell.android.storage.ConnectionGroupEntity
import io.github.openfinalshell.android.uiStatusText
import kotlinx.coroutines.launch

private data class SshFormState(
    val profileId: String? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val clearPassword: Boolean = false,
    val showClearPassword: Boolean = false,
    val authMethod: String = "password",
    val privateKeyId: String? = null,
    val groupId: String? = null,
    val note: String = "",
    val charset: String = "utf-8",
    val termType: String = "xterm-256color",
    val startupCommand: String = "",
    val keepalive: String = "30",
    val readyTimeout: String = "20",
    val legacyAlgorithms: Boolean = false,
    val autoReconnect: Boolean = true,
    val monitorEnabled: Boolean = false,
    val compress: Boolean = false,
    val proxyId: String? = null,
    val color: String? = null,
    val flag: String = "",
    val connectOnSave: Boolean = false
)

private data class LocalFormState(
    val profileId: String? = null,
    val name: String = "",
    val tier: String = LocalShellTier.AUTO,
    val startDirectory: String = ""
)

private data class GroupFormState(
    val id: String? = null,
    val name: String = "",
    val parentId: String? = null
)

private data class PendingConnect(
    val knownIds: Set<String>,
    val host: String,
    val port: Int,
    val username: String
)

private sealed interface WorkspaceEntry {
    data object RecentHeader : WorkspaceEntry
    data object UngroupedHeader : WorkspaceEntry
    data class GroupRow(val group: ConnectionGroupEntity, val depth: Int) : WorkspaceEntry
    data class ProfileRow(val profile: ConnectionProfile, val depth: Int, val bucket: String) : WorkspaceEntry
}

private data class SshCopyNotice(val command: String, val notices: List<SshCommandNotice>)

@Composable
fun ConnectionWorkspace(
    state: AndroidUiState,
    viewModel: MainViewModel,
    maskHosts: Boolean,
    snackbarHost: SnackbarHostState
) {
    var query by remember { mutableStateOf("") }
    var quickInput by remember { mutableStateOf("") }
    var sshForm by remember { mutableStateOf<SshFormState?>(null) }
    var localForm by remember { mutableStateOf<LocalFormState?>(null) }
    var groupForm by remember { mutableStateOf<GroupFormState?>(null) }
    var pendingDelete by remember { mutableStateOf<ConnectionProfile?>(null) }
    var pendingGroupDelete by remember { mutableStateOf<ConnectionGroupEntity?>(null) }
    var pendingConnect by remember { mutableStateOf<PendingConnect?>(null) }
    var sshNotice by remember { mutableStateOf<SshCopyNotice?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.ssh_command_copied_title)
    val localEnabled = BuildConfig.LOCAL_SHELL_ENABLED

    LaunchedEffect(state.quickConnectDraft) {
        val draft = state.quickConnectDraft ?: return@LaunchedEffect
        sshForm = SshFormState(
            name = draft.host,
            host = draft.host,
            port = draft.port.toString(),
            username = draft.username.orEmpty(),
            connectOnSave = true
        )
    }
    LaunchedEffect(state.profiles) {
        val wait = pendingConnect ?: return@LaunchedEffect
        val match = state.profiles.firstOrNull {
            it.id !in wait.knownIds &&
                it.protocol != LOCAL_SHELL_PROTOCOL &&
                it.host == wait.host &&
                it.port == wait.port &&
                it.username == wait.username
        } ?: return@LaunchedEffect
        pendingConnect = null
        viewModel.connect(match)
    }

    val entries = remember(state.profiles, state.groups, query) {
        workspaceEntries(state.profiles, state.groups, query)
    }
    val filteredEmpty = query.isNotBlank() && state.profiles.none { profileMatches(it, query) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = OpenFinalShellSpacing.Small,
            vertical = OpenFinalShellSpacing.Small
        ),
        verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.XSmall)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.saved_connections),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { sshForm = SshFormState() },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_add_connection)) }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.label_search)) },
                singleLine = true
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = quickInput,
                    onValueChange = { quickInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.label_quick_connect)) },
                    placeholder = { Text(stringResource(R.string.quick_connect_placeholder)) },
                    singleLine = true
                )
                Button(
                    onClick = {
                        val parsed = parseQuickConnect(quickInput)
                        viewModel.quickConnect(quickInput)
                        if (parsed != null) quickInput = ""
                    },
                    enabled = quickInput.isNotBlank(),
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_connect)) }
            }
        }
        if (state.profiles.isEmpty() && state.groups.isEmpty() && query.isBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = OpenFinalShellSpacing.XLarge, horizontal = OpenFinalShellSpacing.Large),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
                    ) {
                        Text(stringResource(R.string.connections_empty_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.connections_empty_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        if (filteredEmpty) {
            item { Text(stringResource(R.string.search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val recentEntries = entries.filter { entry ->
            entry is WorkspaceEntry.RecentHeader || (entry is WorkspaceEntry.ProfileRow && entry.bucket == "recent")
        }
        val groupedEntries = entries.filter { entry ->
            entry !is WorkspaceEntry.RecentHeader && (entry !is WorkspaceEntry.ProfileRow || entry.bucket != "recent")
        }
        items(recentEntries, key = { entryKey(it) }) { entry ->
            WorkspaceLine(
                entry = entry,
                state = state,
                viewModel = viewModel,
                maskHosts = maskHosts,
                localEnabled = localEnabled,
                onGroupForm = { groupForm = it },
                onDeleteGroup = { pendingGroupDelete = it },
                onSshForm = { sshForm = it },
                onLocalForm = { localForm = it },
                onDeleteProfile = { pendingDelete = it },
                onCopySsh = { profile ->
                    val line = runCatching { formatSshCommand(profile) }.getOrNull() ?: return@WorkspaceLine
                    clipboard.setText(AnnotatedString(line.command))
                    if (line.notices.isEmpty()) {
                        scope.launch { snackbarHost.showSnackbar(copiedText) }
                    } else {
                        sshNotice = SshCopyNotice(line.command, line.notices)
                    }
                }
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.groups_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { groupForm = GroupFormState() },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_add_group)) }
            }
        }
        items(groupedEntries, key = { entryKey(it) }) { entry ->
            WorkspaceLine(
                entry = entry,
                state = state,
                viewModel = viewModel,
                maskHosts = maskHosts,
                localEnabled = localEnabled,
                onGroupForm = { groupForm = it },
                onDeleteGroup = { pendingGroupDelete = it },
                onSshForm = { sshForm = it },
                onLocalForm = { localForm = it },
                onDeleteProfile = { pendingDelete = it },
                onCopySsh = { profile ->
                    val line = runCatching { formatSshCommand(profile) }.getOrNull() ?: return@WorkspaceLine
                    clipboard.setText(AnnotatedString(line.command))
                    if (line.notices.isEmpty()) {
                        scope.launch { snackbarHost.showSnackbar(copiedText) }
                    } else {
                        sshNotice = SshCopyNotice(line.command, line.notices)
                    }
                }
            )
        }
        if (localEnabled) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = OpenFinalShellSpacing.Small))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.local_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { localForm = LocalFormState() },
                        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                    ) { Text(stringResource(R.string.action_add_local_session)) }
                }
            }
            item {
                Text(stringResource(R.string.local_shell_note), style = MaterialTheme.typography.bodySmall)
            }
            item {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !rememberAllFilesAccess()) {
                    val context = LocalContext.current
                    TextButton(
                        onClick = {
                            if (!io.github.openfinalshell.android.local.openAllFilesAccessSettings(context)) {
                                viewModel.setStatus(UiStatus(StatusKey.LOCAL_ALL_FILES_UNAVAILABLE))
                            }
                        },
                        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                    ) { Text(stringResource(R.string.action_grant_all_files)) }
                }
            }
        }
    }

    sshForm?.let { form ->
        SshConnectionDialog(
            form = form,
            state = state,
            onChange = { sshForm = it },
            onDismiss = {
                sshForm = null
                if (state.quickConnectDraft != null) viewModel.clearQuickConnectDraft()
            },
            onSave = { built ->
                if (form.connectOnSave && form.profileId == null) {
                    pendingConnect = PendingConnect(
                        knownIds = state.profiles.map { it.id }.toSet(),
                        host = built.host.trim(),
                        port = built.port,
                        username = built.username.trim()
                    )
                }
                viewModel.saveConnectionForm(built)
                sshForm = null
            }
        )
    }
    if (localEnabled) {
        localForm?.let { form ->
            LocalShellDialog(
                form = form,
                onChange = { localForm = it },
                onDismiss = { localForm = null },
                onSave = {
                    viewModel.saveLocalProfile(form.name, form.tier, form.startDirectory, form.profileId)
                    localForm = null
                }
            )
        }
    }
    groupForm?.let { form ->
        GroupEditorDialog(
            form = form,
            groups = state.groups,
            onChange = { groupForm = it },
            onDismiss = { groupForm = null },
            onSave = {
                val order = state.groups.firstOrNull { it.id == form.id }?.sortOrder
                    ?: ((state.groups.maxOfOrNull { it.sortOrder } ?: 0.0) + 1.0)
                viewModel.saveGroup(form.name.trim(), form.parentId, order, form.id)
                groupForm = null
            }
        )
    }
    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_connection_title)) },
            text = { Text(stringResource(R.string.delete_connection_message, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        pendingDelete = null
                    },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    pendingGroupDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingGroupDelete = null },
            title = { Text(stringResource(R.string.delete_group_title)) },
            text = { Text(stringResource(R.string.delete_group_message, group.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteGroup(group)
                        pendingGroupDelete = null
                    },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingGroupDelete = null },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    sshNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { sshNotice = null },
            title = { Text(stringResource(R.string.ssh_command_copied_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    Text(notice.command, fontFamily = FontFamily.Monospace)
                    notice.notices.forEach { item ->
                        val res = when (item) {
                            SshCommandNotice.PRIVATE_KEY -> R.string.ssh_notice_private_key
                            SshCommandNotice.PROXY -> R.string.ssh_notice_proxy
                        }
                        Text(stringResource(res))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { sshNotice = null },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }
}

@Composable
private fun WorkspaceLine(
    entry: WorkspaceEntry,
    state: AndroidUiState,
    viewModel: MainViewModel,
    maskHosts: Boolean,
    localEnabled: Boolean,
    onGroupForm: (GroupFormState) -> Unit,
    onDeleteGroup: (ConnectionGroupEntity) -> Unit,
    onSshForm: (SshFormState) -> Unit,
    onLocalForm: (LocalFormState) -> Unit,
    onDeleteProfile: (ConnectionProfile) -> Unit,
    onCopySsh: (ConnectionProfile) -> Unit
) {
    when (entry) {
        WorkspaceEntry.RecentHeader -> Text(stringResource(R.string.section_recent), style = MaterialTheme.typography.titleSmall)
        WorkspaceEntry.UngroupedHeader -> Text(stringResource(R.string.section_ungrouped), style = MaterialTheme.typography.titleSmall)
        is WorkspaceEntry.GroupRow -> GroupHeader(
            group = entry.group,
            depth = entry.depth,
            onRename = { onGroupForm(GroupFormState(entry.group.id, entry.group.name, entry.group.parentId)) },
            onDelete = { onDeleteGroup(entry.group) }
        )
        is WorkspaceEntry.ProfileRow -> ConnectionRow(
            profile = entry.profile,
            depth = entry.depth,
            selected = entry.profile.id == state.selectedProfileId,
            session = state.sessions.values.firstOrNull { it.profile.id == entry.profile.id && it.state != SessionState.CLOSED },
            resolution = state.localTiers[entry.profile.id],
            maskHosts = maskHosts,
            localEnabled = localEnabled,
            viewModel = viewModel,
            onSelect = { viewModel.selectProfile(entry.profile) },
            onConnect = { viewModel.connect(entry.profile) },
            onEdit = {
                if (entry.profile.protocol == LOCAL_SHELL_PROTOCOL) {
                    if (localEnabled) {
                        onLocalForm(
                            LocalFormState(
                                profileId = entry.profile.id,
                                name = entry.profile.name,
                                tier = entry.profile.local?.tier ?: LocalShellTier.AUTO,
                                startDirectory = entry.profile.local?.startDirectory.orEmpty()
                            )
                        )
                    }
                } else {
                    onSshForm(entry.profile.toSshForm())
                }
            },
            onDuplicate = { viewModel.duplicateProfile(entry.profile) },
            onCopySsh = { onCopySsh(entry.profile) },
            onDelete = { onDeleteProfile(entry.profile) }
        )
    }
}

@Composable
private fun ConnectionRow(
    profile: ConnectionProfile,
    depth: Int,
    selected: Boolean,
    session: io.github.openfinalshell.android.core.ssh.SessionSnapshot?,
    resolution: LocalTierResolution?,
    maskHosts: Boolean,
    localEnabled: Boolean,
    viewModel: MainViewModel,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onCopySsh: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val local = profile.protocol == LOCAL_SHELL_PROTOCOL
    val sessionDescription = session?.let { sessionStateText(it.state) }
    Column(Modifier.fillMaxWidth().padding(start = (12 * depth).dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(role = Role.Button, onClick = {
                    onSelect()
                    onConnect()
                })
                .padding(horizontal = OpenFinalShellSpacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            profile.color?.let { hex ->
                parseHexColor(hex)?.let { color ->
                    val description = accentColorLabel(hex)
                    Box(
                        Modifier
                            .padding(end = OpenFinalShellSpacing.Small)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color)
                            .semantics { contentDescription = description }
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.XSmall)) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    val flag = profile.flag
                    if (!flag.isNullOrBlank()) Text(flag, style = MaterialTheme.typography.labelSmall)
                }
                if (local) {
                    Text(stringResource(R.string.local_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                } else {
                    val hostText = if (maskHosts) maskHost(profile.host) else profile.host
                    Text(
                        "${profile.username}@$hostText:${profile.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            session?.let {
                Box(
                    Modifier
                        .padding(horizontal = OpenFinalShellSpacing.Small)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (it.state == SessionState.READY) OpenFinalShellColors.Success else MaterialTheme.colorScheme.outline)
                        .semantics { contentDescription = sessionDescription.orEmpty() }
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(OpenFinalShellSpacing.MinimumTouchTarget)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_connection_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuAction(R.string.action_connect) { menu = false; onConnect() }
                    if (!local || localEnabled) MenuAction(R.string.action_edit) { menu = false; onEdit() }
                    MenuAction(R.string.action_duplicate_connection) { menu = false; onDuplicate() }
                    if (!local) MenuAction(R.string.action_copy_ssh) { menu = false; onCopySsh() }
                    MenuAction(R.string.action_delete, destructive = true) { menu = false; onDelete() }
                }
            }
        }
        if (local) {
            LocalTierChip(resolution)
            LocalBlockerNote(resolution, viewModel)
        }
    }
}

@Composable
private fun MenuAction(label: Int, destructive: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(label),
                color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified
            )
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
    )
}

@Composable
private fun GroupHeader(
    group: ConnectionGroupEntity,
    depth: Int,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = OpenFinalShellSpacing.Large * depth)
            .heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(group.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_group_actions))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuAction(R.string.action_rename) { menu = false; onRename() }
                MenuAction(R.string.action_delete, destructive = true) { menu = false; onDelete() }
            }
        }
    }
}

@Composable
private fun SshConnectionDialog(
    form: SshFormState,
    state: AndroidUiState,
    onChange: (SshFormState) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ConnectionForm) -> Unit
) {
    val built = form.toConnectionForm()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(OpenFinalShellSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
            ) {
                Text(
                    stringResource(if (form.profileId == null) R.string.add_connection_title else R.string.edit_connection_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(stringResource(R.string.form_basics), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(form.name, { onChange(form.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_name)) }, singleLine = true)
                OutlinedTextField(form.host, { onChange(form.copy(host = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_host)) }, singleLine = true)
                OutlinedTextField(
                    form.port,
                    { onChange(form.copy(port = it.filter(Char::isDigit).take(5))) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(form.username, { onChange(form.copy(username = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_username)) }, singleLine = true)
                IdDropdown(
                    label = stringResource(R.string.label_group),
                    selectedId = form.groupId,
                    options = listOf(null to stringResource(R.string.group_none)) + state.groups.sortedBy { it.name }.map { it.id to it.name },
                    onSelected = { onChange(form.copy(groupId = it)) }
                )
                OutlinedTextField(form.note, { onChange(form.copy(note = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_note)) }, minLines = 2)
                Text(stringResource(R.string.label_auth), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    FilterChip(
                        selected = form.authMethod != "privateKey",
                        onClick = { onChange(form.copy(authMethod = "password")) },
                        label = { Text(stringResource(R.string.auth_method_password)) },
                        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                    )
                    FilterChip(
                        selected = form.authMethod == "privateKey",
                        onClick = {
                            onChange(form.copy(authMethod = "privateKey", password = "", clearPassword = false))
                        },
                        label = { Text(stringResource(R.string.auth_method_private_key)) },
                        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                    )
                }
                if (form.authMethod == "privateKey") {
                    Text(stringResource(R.string.auth_private_key_drops_password), style = MaterialTheme.typography.bodySmall)
                    if (state.privateKeys.isEmpty()) {
                        Text(stringResource(R.string.private_key_required), color = MaterialTheme.colorScheme.error)
                    } else {
                        IdDropdown(
                            label = stringResource(R.string.label_private_key),
                            selectedId = form.privateKeyId,
                            options = state.privateKeys.map { it.id to it.name },
                            onSelected = { onChange(form.copy(privateKeyId = it)) }
                        )
                    }
                } else {
                    OutlinedTextField(
                        form.password,
                        { onChange(form.copy(password = it, clearPassword = false)) },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.label_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    if (form.showClearPassword) {
                        FormSwitch(stringResource(R.string.action_clear_saved_password), form.clearPassword) {
                            onChange(form.copy(clearPassword = it, password = if (it) "" else form.password))
                        }
                    }
                }
                Text(stringResource(R.string.form_terminal), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(form.charset, { onChange(form.copy(charset = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_charset)) }, singleLine = true)
                OutlinedTextField(form.termType, { onChange(form.copy(termType = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_terminal_type)) }, singleLine = true)
                OutlinedTextField(form.startupCommand, { onChange(form.copy(startupCommand = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_startup_command)) }, singleLine = true)
                Text(stringResource(R.string.form_options), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    form.keepalive,
                    { onChange(form.copy(keepalive = it.filter(Char::isDigit).take(5))) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_keepalive)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    form.readyTimeout,
                    { onChange(form.copy(readyTimeout = it.filter(Char::isDigit).take(5))) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_timeout)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                FormSwitch(stringResource(R.string.option_compress), form.compress) { onChange(form.copy(compress = it)) }
                FormSwitch(stringResource(R.string.option_legacy_algorithms), form.legacyAlgorithms) { onChange(form.copy(legacyAlgorithms = it)) }
                FormSwitch(stringResource(R.string.option_auto_reconnect), form.autoReconnect) { onChange(form.copy(autoReconnect = it)) }
                FormSwitch(stringResource(R.string.option_monitor), form.monitorEnabled) { onChange(form.copy(monitorEnabled = it)) }
                IdDropdown(
                    label = stringResource(R.string.label_proxy),
                    selectedId = form.proxyId,
                    options = listOf(null to stringResource(R.string.proxy_none)) + state.savedProxies.map { it.id to it.name },
                    onSelected = { onChange(form.copy(proxyId = it)) }
                )
                Text(stringResource(R.string.form_appearance), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.label_tag_color), style = MaterialTheme.typography.labelLarge)
                AccentSwatches(selected = form.color, allowClear = true) { onChange(form.copy(color = it)) }
                OutlinedTextField(form.flag, { onChange(form.copy(flag = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_location)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = { built?.let(onSave) },
                        enabled = built != null,
                        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                    ) {
                        Text(stringResource(if (form.connectOnSave && form.profileId == null) R.string.action_save_and_connect else R.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalShellDialog(
    form: LocalFormState,
    onChange: (LocalFormState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.padding(OpenFinalShellSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
            ) {
                Text(
                    stringResource(if (form.profileId == null) R.string.local_section_title else R.string.edit_local_session),
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    form.name,
                    { onChange(form.copy(name = it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    form.startDirectory,
                    { onChange(form.copy(startDirectory = it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.local_start_directory)) },
                    singleLine = true
                )
                Text(stringResource(R.string.local_tier_label), style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
                ) {
                    listOf(
                        LocalShellTier.AUTO to R.string.local_tier_auto,
                        LocalShellTier.APP to R.string.local_tier_app,
                        LocalShellTier.ADB to R.string.local_tier_adb,
                        LocalShellTier.ROOT to R.string.local_tier_root
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = form.tier == value,
                            onClick = { onChange(form.copy(tier = value)) },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = onSave,
                        enabled = form.name.isNotBlank(),
                        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                    ) { Text(stringResource(if (form.profileId == null) R.string.action_add_local_session else R.string.action_save)) }
                }
            }
        }
    }
}

@Composable
private fun GroupEditorDialog(
    form: GroupFormState,
    groups: List<ConnectionGroupEntity>,
    onChange: (GroupFormState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val allowed = allowedParentIds(form.id, groups.map { GroupNode(it.id, it.parentId) })
    val options = allowed.map { id ->
        id to (groups.firstOrNull { it.id == id }?.name ?: stringResource(R.string.group_parent_none))
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.padding(OpenFinalShellSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
            ) {
                Text(
                    stringResource(if (form.id == null) R.string.action_add_group else R.string.action_rename),
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    form.name,
                    { onChange(form.copy(name = it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_group_name)) },
                    singleLine = true
                )
                IdDropdown(
                    label = stringResource(R.string.label_parent_group),
                    selectedId = form.parentId,
                    options = options,
                    onSelected = { onChange(form.copy(parentId = it)) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = onSave,
                        enabled = form.name.isNotBlank(),
                        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdDropdown(
    label: String,
    selectedId: String?,
    options: List<Pair<String?, String>>,
    onSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedId }?.second.orEmpty()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = label) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelected(id)
                        expanded = false
                    },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                )
            }
        }
    }
}

@Composable
private fun FormSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun ConnectionProfile.toSshForm() = SshFormState(
    profileId = id,
    name = name,
    host = host,
    port = port.toString(),
    username = username,
    showClearPassword = auth.passwordRef != null,
    authMethod = if (auth.method == "privateKey" || auth.privateKeyId != null) "privateKey" else "password",
    privateKeyId = auth.privateKeyId,
    groupId = groupId,
    note = note.orEmpty(),
    charset = terminal.charset,
    termType = terminal.termType,
    startupCommand = terminal.startupCommand.orEmpty(),
    keepalive = options.keepaliveInterval.toString(),
    readyTimeout = options.readyTimeout.toString(),
    legacyAlgorithms = options.legacyAlgorithms,
    autoReconnect = options.autoReconnect,
    monitorEnabled = options.monitorEnabled,
    compress = options.compress,
    proxyId = proxyId,
    color = color,
    flag = flag.orEmpty()
)

private fun SshFormState.toConnectionForm(): ConnectionForm? {
    val portNumber = port.toIntOrNull() ?: return null
    if (name.isBlank() || host.isBlank() || username.isBlank() || portNumber !in 1..65535) return null
    val method = if (authMethod == "privateKey") "privateKey" else "password"
    if (method == "privateKey" && privateKeyId.isNullOrBlank()) return null
    return ConnectionForm(
        profileId = profileId,
        name = name,
        host = host,
        port = portNumber,
        username = username,
        password = if (method == "privateKey") "" else password,
        clearPassword = method == "password" && clearPassword,
        authMethod = method,
        privateKeyId = if (method == "privateKey") privateKeyId else null,
        groupId = groupId,
        note = note,
        charset = charset.ifBlank { "utf-8" },
        termType = termType.ifBlank { "xterm-256color" },
        startupCommand = startupCommand,
        keepaliveInterval = keepalive.toIntOrNull()?.coerceIn(0, 3600) ?: 30,
        readyTimeout = readyTimeout.toIntOrNull()?.coerceIn(1, 300) ?: 20,
        legacyAlgorithms = legacyAlgorithms,
        autoReconnect = autoReconnect,
        monitorEnabled = monitorEnabled,
        compress = compress,
        proxyId = proxyId,
        color = color,
        flag = flag
    )
}

private fun profileMatches(profile: ConnectionProfile, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return profile.name.contains(needle, ignoreCase = true) ||
        profile.host.contains(needle, ignoreCase = true) ||
        profile.username.contains(needle, ignoreCase = true)
}

private fun workspaceEntries(
    profiles: List<ConnectionProfile>,
    groups: List<ConnectionGroupEntity>,
    query: String
): List<WorkspaceEntry> {
    val filtered = profiles.filter { profileMatches(it, query) }
    val recent = recentProfiles(profiles).filter { profileMatches(it, query) }
    val groupIds = groups.map { it.id }.toSet()
    val result = mutableListOf<WorkspaceEntry>()
    if (recent.isNotEmpty()) {
        result += WorkspaceEntry.RecentHeader
        recent.forEach { result += WorkspaceEntry.ProfileRow(it, 0, "recent") }
    }
    val ordered = flattenGroupTree(groups)
    val visible = if (query.isBlank()) ordered else ordered.filter { (group, _) ->
        groupShowsMatch(group.id, groups, filtered, mutableSetOf())
    }
    visible.forEach { (group, depth) ->
        result += WorkspaceEntry.GroupRow(group, depth)
        filtered.filter { it.groupId == group.id }
            .sortedBy { it.name.lowercase() }
            .forEach { result += WorkspaceEntry.ProfileRow(it, depth + 1, "list") }
    }
    val ungrouped = filtered
        .filter { it.groupId == null || it.groupId !in groupIds }
        .sortedBy { it.name.lowercase() }
    if (ungrouped.isNotEmpty()) {
        if (groups.isNotEmpty()) result += WorkspaceEntry.UngroupedHeader
        ungrouped.forEach { result += WorkspaceEntry.ProfileRow(it, 0, "list") }
    }
    return result
}

private fun groupShowsMatch(
    groupId: String,
    groups: List<ConnectionGroupEntity>,
    filtered: List<ConnectionProfile>,
    seen: MutableSet<String>
): Boolean {
    if (!seen.add(groupId)) return false
    if (filtered.any { it.groupId == groupId }) return true
    return groups.any { it.parentId == groupId && groupShowsMatch(it.id, groups, filtered, seen) }
}

private fun flattenGroupTree(groups: List<ConnectionGroupEntity>): List<Pair<ConnectionGroupEntity, Int>> {
    val sorted = groups.sortedWith(compareBy<ConnectionGroupEntity> { it.sortOrder }.thenBy { it.name.lowercase() })
    val byParent = sorted.groupBy { it.parentId }
    val result = mutableListOf<Pair<ConnectionGroupEntity, Int>>()
    val seen = mutableSetOf<String>()
    fun walk(parentId: String?, depth: Int) {
        byParent[parentId].orEmpty().forEach { group ->
            if (seen.add(group.id)) {
                result += group to depth
                walk(group.id, depth + 1)
            }
        }
    }
    walk(null, 0)
    var remaining = sorted.filter { it.id !in seen }
    while (remaining.isNotEmpty()) {
        val next = remaining.firstOrNull { group -> group.parentId == null || remaining.none { it.id == group.parentId } } ?: remaining.first()
        val parentDepth = result.firstOrNull { it.first.id == next.parentId }?.second
        val depth = if (parentDepth == null) 0 else parentDepth + 1
        if (seen.add(next.id)) {
            result += next to depth
            walk(next.id, depth + 1)
        }
        remaining = sorted.filter { it.id !in seen }
    }
    return result
}

private fun entryKey(entry: WorkspaceEntry): String = when (entry) {
    WorkspaceEntry.RecentHeader -> "recent-header"
    WorkspaceEntry.UngroupedHeader -> "ungrouped-header"
    is WorkspaceEntry.GroupRow -> "group-${entry.group.id}"
    is WorkspaceEntry.ProfileRow -> "${entry.bucket}-${entry.profile.id}"
}

@Composable
private fun LocalTierChip(resolution: LocalTierResolution?) {
    val tier = resolution?.tier ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            localTierLabel(tier.profileValue),
            Modifier.padding(horizontal = OpenFinalShellSpacing.Small, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun localTierLabel(profileValue: String): String {
    val id = when (profileValue) {
        LocalShellTier.AUTO -> R.string.local_tier_auto
        LocalShellTier.APP -> R.string.local_tier_app
        LocalShellTier.ADB -> R.string.local_tier_adb
        LocalShellTier.ROOT -> R.string.local_tier_root
        else -> null
    }
    return id?.let { stringResource(it) } ?: profileValue
}

@Composable
private fun LocalBlockerNote(resolution: LocalTierResolution?, viewModel: MainViewModel) {
    val blocker = resolution?.blocker ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (resolution.downgraded) {
            Text(
                stringResource(
                    R.string.local_tier_downgraded,
                    localTierLabel(resolution.requested),
                    localTierLabel(resolution.tier.profileValue)
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (blocker == LocalTierBlocker.SHIZUKU_NO_PERMISSION) {
            TextButton(
                onClick = viewModel::requestShizukuPermission,
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) {
                Text(stringResource(R.string.local_blocker_shizuku_no_permission))
            }
        } else {
            Text(
                stringResource(
                    when (blocker) {
                        LocalTierBlocker.SHIZUKU_NOT_INSTALLED -> R.string.local_blocker_shizuku_not_installed
                        LocalTierBlocker.SHIZUKU_NOT_RUNNING -> R.string.local_blocker_shizuku_not_running
                        LocalTierBlocker.SHIZUKU_TOO_OLD -> R.string.local_blocker_shizuku_too_old
                        LocalTierBlocker.NO_ROOT_BINARY -> R.string.local_blocker_no_root_binary
                        LocalTierBlocker.ROOT_DISABLED_BY_SETTINGS -> R.string.local_blocker_root_disabled
                        LocalTierBlocker.HELPER_MISSING -> R.string.local_blocker_helper_missing
                        LocalTierBlocker.SHIZUKU_NO_PERMISSION -> R.string.local_blocker_shizuku_no_permission
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun rememberAllFilesAccess(): Boolean {
    var granted by remember { mutableStateOf(io.github.openfinalshell.android.local.hasAllFilesAccess()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = io.github.openfinalshell.android.local.hasAllFilesAccess()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}

@Composable
private fun sessionStateText(state: SessionState): String = uiStatusText(
    UiStatus(
        when (state) {
            SessionState.CONNECTING -> StatusKey.CONNECTING
            SessionState.AUTHENTICATING -> StatusKey.AUTHENTICATING
            SessionState.READY -> StatusKey.CONNECTED
            SessionState.RECONNECTING -> StatusKey.RECONNECTING
            SessionState.CLOSED -> StatusKey.DISCONNECTED
        }
    )
)

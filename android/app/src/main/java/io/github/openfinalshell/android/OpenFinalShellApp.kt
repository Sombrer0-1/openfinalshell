package io.github.openfinalshell.android

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import io.github.openfinalshell.android.storage.AndroidSettings
import io.github.openfinalshell.android.core.model.ConnectionProfile
import io.github.openfinalshell.android.core.model.LOCAL_SHELL_PROTOCOL
import io.github.openfinalshell.android.core.model.LocalShellTier
import io.github.openfinalshell.android.core.model.SessionState
import io.github.openfinalshell.android.local.LocalTier
import io.github.openfinalshell.android.local.LocalTierBlocker
import io.github.openfinalshell.android.local.LocalTierResolution
import io.github.openfinalshell.android.core.ssh.SftpEntry
import io.github.openfinalshell.android.core.terminal.TerminalCursorStyle
import io.github.openfinalshell.android.update.UpdateState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.openfinalshell.android.core.connection.maskHost
import io.github.openfinalshell.android.core.connection.profilesUsingPrivateKey
import io.github.openfinalshell.android.ui.AccentSwatches
import io.github.openfinalshell.android.ui.CommandLibraryPage
import io.github.openfinalshell.android.ui.ConnectionWorkspace
import io.github.openfinalshell.android.ui.MonitorPanel
import io.github.openfinalshell.android.ui.SessionLayoutLimits
import io.github.openfinalshell.android.ui.SessionPanelMode
import io.github.openfinalshell.android.ui.SessionVerticalSplitter
import io.github.openfinalshell.android.ui.SessionWorkspace
import io.github.openfinalshell.android.ui.TransferQueuePane
import io.github.openfinalshell.android.ui.resolveSessionChrome
import io.github.openfinalshell.android.ui.KnownHostSettingsContent
import io.github.openfinalshell.android.ui.OpenFinalShellTheme
import io.github.openfinalshell.android.ui.PrivateKeyReferenceText
import io.github.openfinalshell.android.ui.ProxySettingsContent
import io.github.openfinalshell.android.ui.SidePanelHeading
import io.github.openfinalshell.android.ui.WorkbenchActivityBar
import io.github.openfinalshell.android.ui.WorkbenchDialog
import io.github.openfinalshell.android.ui.WorkbenchStatusBar
import io.github.openfinalshell.android.ui.WorkbenchTheme
import io.github.openfinalshell.android.ui.WorkbenchTitleStrip
import io.github.openfinalshell.android.ui.OpenFinalShellSpacing
import io.github.openfinalshell.android.ui.OpenFinalShellShapes
import io.github.openfinalshell.android.ui.OpenFinalShellTerminalTokens
import io.github.openfinalshell.android.terminal.SshTerminalView
import kotlinx.coroutines.launch

private enum class AppDestination(
    val key: String,
    val titleRes: Int
) {
    CONNECTIONS("connections", R.string.tab_connections),
    SESSION("session", R.string.tab_session),
    COMMANDS("commands", R.string.tab_commands),
    FORWARDS("forwards", R.string.tab_forwards),
    TRANSFERS("transfers", R.string.tab_transfers),
    SYNC("sync", R.string.tab_sync),
    SETTINGS("settings", R.string.tab_settings);

    companion object {
        fun fromKey(key: String): AppDestination = entries.firstOrNull { it.key == key } ?: CONNECTIONS
        val sideTools = setOf(CONNECTIONS, COMMANDS, FORWARDS, TRANSFERS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenFinalShellApp(
    viewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    lanSyncViewModel: LanSyncViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(settingsState.settings) { viewModel.applyTransferSettings(settingsState.settings) }
    val lanSyncState by lanSyncViewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val statusText = uiStatusText(state.status)
    var destinationKey by rememberSaveable { mutableStateOf(AppDestination.CONNECTIONS.key) }
    var sideCollapsed by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var deferredUpdateTag by rememberSaveable { mutableStateOf<String?>(null) }
    var installAfterPermission by rememberSaveable { mutableStateOf(false) }
    val updatePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (installAfterPermission && settingsViewModel.canInstallPackages()) {
            settingsViewModel.installUpdate()
        }
        installAfterPermission = false
    }
    val requestUpdateInstall = {
        if (settingsViewModel.canInstallPackages()) {
            settingsViewModel.installUpdate()
        } else {
            installAfterPermission = true
            updatePermissionLauncher.launch(settingsViewModel.installPermissionIntent())
        }
    }

    LaunchedEffect(settingsState.update) {
        if (settingsState.update !is UpdateState.Ready) deferredUpdateTag = null
    }
    LaunchedEffect(state.status) {
        if (state.status.key != StatusKey.READY) snackbarHost.showSnackbar(statusText)
    }

    state.hostKeyPrompt?.let { prompt ->
        val previousFingerprint = prompt.previousFingerprint
        val keyChanged = !previousFingerprint.isNullOrEmpty()
        val previousText = previousFingerprint.orEmpty()
        AlertDialog(
            onDismissRequest = viewModel::rejectHostKey,
            title = {
                Text(
                    androidx.compose.ui.res.stringResource(
                        if (keyChanged) R.string.host_key_changed_title else R.string.host_key_title
                    )
                )
            },
            text = {
                Text(
                    if (keyChanged) {
                        androidx.compose.ui.res.stringResource(
                            R.string.host_key_changed_message,
                            prompt.host,
                            prompt.port,
                            prompt.keyType,
                            previousText,
                            prompt.fingerprint
                        )
                    } else {
                        androidx.compose.ui.res.stringResource(
                            R.string.host_key_message,
                            prompt.host,
                            prompt.port,
                            prompt.keyType,
                            prompt.fingerprint
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::acceptHostKey,
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(
                            if (keyChanged) R.string.action_revoke_old_trust else R.string.action_trust
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::rejectHostKey,
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_reject))
                }
            }
        )
    }

    val darkTheme = when (settingsState.settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val primary = runCatching { Color(android.graphics.Color.parseColor(settingsState.settings.accentColor)) }
        .getOrDefault(Color(0xFF1677FF))
    OpenFinalShellTheme(
        darkTheme = darkTheme,
        // Desktop paints neutral surfaces and reserves the accent for selection.
        // Material You would retint those surfaces per device and break that hierarchy.
        dynamicColor = false,
        accentColor = primary
    ) {
        io.github.openfinalshell.android.transfer.PortTransferDialogs(viewModel)
        LocalRootConfirmDialog(state, viewModel)
        val readyUpdate = settingsState.update as? UpdateState.Ready
        if (readyUpdate != null && deferredUpdateTag != readyUpdate.release.tagName) {
            UpdateReadyDialog(
                versionName = readyUpdate.release.versionName,
                onDefer = { deferredUpdateTag = readyUpdate.release.tagName },
                onInstall = {
                    deferredUpdateTag = readyUpdate.release.tagName
                    requestUpdateInstall()
                }
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wideLayout = maxWidth >= SessionLayoutLimits.NAV_BREAK_DP.dp
            val destination = AppDestination.fromKey(destinationKey)
            val showPhoneTitle = !wideLayout && destination != AppDestination.SESSION
            val moreSelected = destination == AppDestination.SYNC || destination == AppDestination.SETTINGS ||
                (!wideLayout && (destination == AppDestination.FORWARDS || destination == AppDestination.TRANSFERS))
            val selectSide: (AppDestination) -> Unit = { next ->
                if (destination == next) sideCollapsed = !sideCollapsed
                else {
                    destinationKey = next.key
                    sideCollapsed = false
                }
            }
            var seenSession by remember { mutableStateOf(state.selectedSessionId) }
            LaunchedEffect(state.selectedSessionId, wideLayout) {
                val id = state.selectedSessionId
                if (!wideLayout && id != null && id != seenSession && destination == AppDestination.CONNECTIONS) {
                    destinationKey = AppDestination.SESSION.key
                }
                seenSession = id
            }
            LaunchedEffect(state.quickConnectDraft) {
                if (state.quickConnectDraft != null) {
                    destinationKey = AppDestination.CONNECTIONS.key
                    sideCollapsed = false
                }
            }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                // The title strip consumes the status bar when it is visible.
                // Otherwise the content keeps that inset. The bottom bar owns
                // the navigation inset so lists can add IME padding safely.
                contentWindowInsets = if (showPhoneTitle) {
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                } else {
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                },
                topBar = {
                    if (showPhoneTitle) {
                        WorkbenchTitleStrip(androidx.compose.ui.res.stringResource(destination.titleRes))
                    }
                },
                bottomBar = {
                    Column(Modifier.navigationBarsPadding()) {
                        WorkbenchStatusBar(
                            state = state,
                            maskHosts = settingsState.settings.maskHosts,
                            onOpenTransfers = {
                                destinationKey = AppDestination.TRANSFERS.key
                                sideCollapsed = false
                            }
                        )
                        if (!wideLayout) {
                            MainNavigationBar(
                                destination = destination,
                                moreSelected = moreSelected,
                                transferCount = activeTransferCount(state),
                                onDestination = { destinationKey = it.key },
                                onMore = { showMore = true }
                            )
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHost) }
            ) { padding ->
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                ) {
                    if (wideLayout) {
                        WorkbenchActivityBar(
                            connectionsSelected = !sideCollapsed && destination == AppDestination.CONNECTIONS,
                            commandsSelected = !sideCollapsed && destination == AppDestination.COMMANDS,
                            forwardsSelected = !sideCollapsed && destination == AppDestination.FORWARDS,
                            transfersSelected = !sideCollapsed && destination == AppDestination.TRANSFERS,
                            transferCount = activeTransferCount(state),
                            onConnections = { selectSide(AppDestination.CONNECTIONS) },
                            onCommands = { selectSide(AppDestination.COMMANDS) },
                            onForwards = { selectSide(AppDestination.FORWARDS) },
                            onTransfers = { selectSide(AppDestination.TRANSFERS) },
                            onToggleTheme = {
                                settingsViewModel.setTheme(
                                    WorkbenchTheme.next(settingsState.settings.theme, darkTheme)
                                )
                            },
                            onSettings = { settingsOpen = true }
                        )
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        )
                    }
                    AppDestinationBody(
                        modifier = Modifier.fillMaxSize().weight(1f).imePadding(),
                        wideLayout = wideLayout,
                        sideCollapsed = sideCollapsed,
                        destination = destination,
                        state = state,
                        viewModel = viewModel,
                        settingsViewModel = settingsViewModel,
                        settingsState = settingsState,
                        lanSyncState = lanSyncState,
                        lanSyncViewModel = lanSyncViewModel,
                        snackbarHost = snackbarHost,
                        requestUpdateInstall = requestUpdateInstall,
                        onCollapseSide = { sideCollapsed = true },
                        onShowConnections = {
                            destinationKey = AppDestination.CONNECTIONS.key
                            sideCollapsed = false
                        }
                    )
                }
            }
            if (wideLayout && (settingsOpen || destination == AppDestination.SETTINGS)) {
                WorkbenchDialog(onDismiss = {
                    settingsOpen = false
                    if (destination == AppDestination.SETTINGS) destinationKey = AppDestination.SESSION.key
                }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            settingsOpen = false
                            if (destination == AppDestination.SETTINGS) destinationKey = AppDestination.SESSION.key
                        }) { Text(androidx.compose.ui.res.stringResource(R.string.cd_close_panel)) }
                    }
                    SettingsScreen(
                        settingsViewModel, settingsState, viewModel, state, lanSyncState, lanSyncViewModel, requestUpdateInstall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (wideLayout && destination == AppDestination.SYNC) {
                WorkbenchDialog(onDismiss = { destinationKey = AppDestination.SESSION.key }) {
                    LanSyncScreen(lanSyncState, lanSyncViewModel)
                }
            }
        }
        if (showMore) {
            ModalBottomSheet(onDismissRequest = { showMore = false }) {
                MoreNavigationItem(R.string.tab_forwards, Icons.AutoMirrored.Filled.ArrowForward) {
                    destinationKey = AppDestination.FORWARDS.key
                    showMore = false
                }
                MoreNavigationItem(R.string.tab_transfers, Icons.Filled.Share, badge = activeTransferCount(state)) {
                    destinationKey = AppDestination.TRANSFERS.key
                    showMore = false
                }
                MoreNavigationItem(R.string.tab_sync, Icons.Filled.Refresh) {
                    destinationKey = AppDestination.SYNC.key
                    showMore = false
                }
                MoreNavigationItem(R.string.tab_settings, Icons.Filled.Settings) {
                    destinationKey = AppDestination.SETTINGS.key
                    showMore = false
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun MainNavigationBar(
    destination: AppDestination,
    moreSelected: Boolean,
    transferCount: Int,
    onDestination: (AppDestination) -> Unit,
    onMore: () -> Unit
) {
    val moreLabel = androidx.compose.ui.res.stringResource(R.string.nav_more)
    NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
        NavigationDestinationItem(destination == AppDestination.CONNECTIONS, R.string.tab_connections, Icons.Filled.Home) {
            onDestination(AppDestination.CONNECTIONS)
        }
        NavigationDestinationItem(destination == AppDestination.SESSION, R.string.tab_session, Icons.Filled.Edit) {
            onDestination(AppDestination.SESSION)
        }
        NavigationDestinationItem(destination == AppDestination.COMMANDS, R.string.tab_commands, Icons.Filled.Menu) {
            onDestination(AppDestination.COMMANDS)
        }
        NavigationBarItem(
            selected = moreSelected,
            onClick = onMore,
            icon = { TransferBadge(Icons.Filled.MoreVert, transferCount) },
            label = { Text(moreLabel) },
            modifier = Modifier.semantics { contentDescription = moreLabel }
        )
    }
}

@Composable
private fun RowScope.NavigationDestinationItem(
    selected: Boolean,
    labelRes: Int,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val label = androidx.compose.ui.res.stringResource(labelRes)
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, maxLines = 1) },
        modifier = Modifier.semantics { contentDescription = label }
    )
}

@Composable
private fun AppDestinationBody(
    modifier: Modifier,
    wideLayout: Boolean,
    sideCollapsed: Boolean,
    destination: AppDestination,
    state: AndroidUiState,
    viewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    settingsState: SettingsUiState,
    lanSyncState: LanSyncUiState,
    lanSyncViewModel: LanSyncViewModel,
    snackbarHost: SnackbarHostState,
    requestUpdateInstall: () -> Unit,
    onCollapseSide: () -> Unit,
    onShowConnections: () -> Unit
) {
    val settings = settingsState.settings
    val panels = state.selectedSessionId?.let { state.sessionPanels[it] }
    val fullPage = !wideLayout && destination != AppDestination.SESSION
    BoxWithConstraints(modifier) {
        val chrome = resolveSessionChrome(
            widthDp = if (wideLayout) maxWidth.value.toInt() + SessionLayoutLimits.RAIL_DP else maxWidth.value.toInt(),
            heightDp = maxHeight.value.toInt(),
            connectionWidthDp = settings.sessionConnectionWidthDp,
            monitorWidthDp = settings.sessionMonitorWidthDp,
            fileHeightDp = settings.sessionFileHeightDp,
            filesOpen = panels?.filesOpen == true,
            monitorOpen = panels?.monitorOpen == true,
            sideRequested = wideLayout && !sideCollapsed && destination in AppDestination.sideTools,
            wide = wideLayout
        )
        LaunchedEffect(chrome.showSidePane, chrome.sideWidthDp, settings.sessionConnectionWidthDp) {
            if (chrome.showSidePane && chrome.sideWidthDp != settings.sessionConnectionWidthDp) {
                settingsViewModel.setSessionConnectionWidth(chrome.sideWidthDp)
            }
        }
        LaunchedEffect(chrome.monitorMode, chrome.monitorWidthDp, settings.sessionMonitorWidthDp) {
            if (chrome.monitorMode == SessionPanelMode.END && chrome.monitorWidthDp != settings.sessionMonitorWidthDp) {
                settingsViewModel.setSessionMonitorWidth(chrome.monitorWidthDp)
            }
        }
        LaunchedEffect(chrome.fileMode, chrome.fileHeightDp, settings.sessionFileHeightDp) {
            if (chrome.fileMode == SessionPanelMode.BOTTOM && chrome.fileHeightDp != settings.sessionFileHeightDp) {
                settingsViewModel.setSessionFileHeight(chrome.fileHeightDp)
            }
        }
        if (fullPage) {
            StandaloneDestination(
                destination, state, viewModel, settingsViewModel, settingsState, lanSyncState, lanSyncViewModel, snackbarHost, requestUpdateInstall
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    if (chrome.showSidePane) {
                        Column(
                            Modifier
                                .width(chrome.sideWidthDp.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            SidePanelHeading(androidx.compose.ui.res.stringResource(destination.titleRes), onCollapseSide)
                            Box(Modifier.weight(1f)) {
                                SideDestination(destination, state, viewModel, settingsState.settings.maskHosts, snackbarHost)
                            }
                        }
                        SessionVerticalSplitter(androidx.compose.ui.res.stringResource(R.string.cd_resize_connection)) { delta ->
                            settingsViewModel.setSessionConnectionWidth((settings.sessionConnectionWidthDp + delta).toInt())
                        }
                    }
                    SessionWorkspace(
                        state = state,
                        viewModel = viewModel,
                        settingsViewModel = settingsViewModel,
                        chrome = chrome,
                        wide = wideLayout,
                        maskHosts = settings.maskHosts,
                        onShowConnections = onShowConnections,
                        onMonitorWidth = settingsViewModel::setSessionMonitorWidth,
                        onFileHeight = settingsViewModel::setSessionFileHeight,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!chrome.showSidePane && !sideCollapsed && wideLayout && destination in AppDestination.sideTools) {
                    BackHandler { onCollapseSide() }
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = onCollapseSide, modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)) {
                                    Text(androidx.compose.ui.res.stringResource(R.string.cd_close_panel))
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                SideDestination(destination, state, viewModel, settingsState.settings.maskHosts, snackbarHost)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandaloneDestination(
    destination: AppDestination,
    state: AndroidUiState,
    viewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    settingsState: SettingsUiState,
    lanSyncState: LanSyncUiState,
    lanSyncViewModel: LanSyncViewModel,
    snackbarHost: SnackbarHostState,
    requestUpdateInstall: () -> Unit
) {
    when (destination) {
        AppDestination.CONNECTIONS -> ConnectionWorkspace(state, viewModel, settingsState.settings.maskHosts, snackbarHost)
        AppDestination.COMMANDS -> CommandLibraryPage(state.selectedSessionId, viewModel, Modifier.fillMaxSize())
        AppDestination.FORWARDS -> ForwardScreen(state, viewModel)
        AppDestination.TRANSFERS -> TransferQueuePane(state, viewModel, Modifier.fillMaxSize())
        AppDestination.SYNC -> LanSyncScreen(lanSyncState, lanSyncViewModel)
        AppDestination.SETTINGS -> SettingsScreen(settingsViewModel, settingsState, viewModel, state, lanSyncState, lanSyncViewModel, requestUpdateInstall)
        AppDestination.SESSION -> Unit
    }
}

@Composable
private fun SideDestination(
    destination: AppDestination,
    state: AndroidUiState,
    viewModel: MainViewModel,
    maskHosts: Boolean,
    snackbarHost: SnackbarHostState
) {
    when (destination) {
        AppDestination.CONNECTIONS -> ConnectionWorkspace(state, viewModel, maskHosts, snackbarHost)
        AppDestination.COMMANDS -> CommandLibraryPage(state.selectedSessionId, viewModel, Modifier.fillMaxSize())
        AppDestination.FORWARDS -> ForwardScreen(state, viewModel)
        AppDestination.TRANSFERS -> TransferQueuePane(state, viewModel, Modifier.fillMaxSize())
        else -> Unit
    }
}

private fun activeTransferCount(state: AndroidUiState): Int = state.transfers.count {
    it.state == io.github.openfinalshell.android.core.sftp.TransferState.QUEUED ||
        it.state == io.github.openfinalshell.android.core.sftp.TransferState.RUNNING ||
        it.state == io.github.openfinalshell.android.core.sftp.TransferState.PAUSED
}

@Composable
private fun TransferBadge(icon: ImageVector, count: Int) {
    if (count <= 0) {
        Icon(icon, contentDescription = null)
        return
    }
    androidx.compose.material3.BadgedBox(badge = {
        androidx.compose.material3.Badge { Text(if (count > 99) "99+" else count.toString()) }
    }) {
        Icon(icon, contentDescription = androidx.compose.ui.res.stringResource(R.string.file_transfer_count, count))
    }
}

@Composable
private fun MoreNavigationItem(label: Int, icon: ImageVector, badge: Int = 0, onClick: () -> Unit) {
    val labelText = androidx.compose.ui.res.stringResource(label)
    ListItem(
        headlineContent = { Text(labelText) },
        leadingContent = { TransferBadge(icon, badge) },
        modifier = Modifier
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = labelText
                role = Role.Button
            }
    )
}

/**
 * The root-tier confirmation.
 *
 * Shown before any host process exists, so declining costs nothing. It states the consequence in
 * plain terms rather than as a generic "are you sure": at uid 0 the shell can read this app's own
 * database and attach to this process, so every saved connection and the AI token have to be treated
 * as readable by anything else running as root on the device.
 */
@Composable
private fun LocalRootConfirmDialog(state: AndroidUiState, viewModel: MainViewModel) {
    val pending = state.rootConfirmation ?: return
    AlertDialog(
        onDismissRequest = viewModel::cancelRootTier,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.local_root_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                Text(androidx.compose.ui.res.stringResource(R.string.local_root_confirm_body))
                Text(pending.profileName, style = MaterialTheme.typography.labelLarge)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmRootTier) {
                Text(androidx.compose.ui.res.stringResource(R.string.local_root_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelRootTier) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun SftpScreen(state: AndroidUiState, viewModel: MainViewModel, settingsViewModel: SettingsViewModel) {
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    io.github.openfinalshell.android.files.FileBrowser(
        state = state,
        viewModel = viewModel,
        settings = settings.settings,
        onToggleHidden = settingsViewModel::setSftpShowHiddenFiles,
        onDownloadDirectory = { settingsViewModel.setDownloadDirectoryUri(it.toString()) },
    )
}

@Composable
internal fun TerminalScreen(
    state: AndroidUiState,
    viewModel: MainViewModel,
    settings: io.github.openfinalshell.android.storage.AndroidSettings,
    onCloseTab: () -> Unit = {}
) {
    val terminalFontSize = settings.terminalFontSize
    val terminalCursorStyle = settings.terminalCursorStyle
    val darkTheme = when (settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val palette = io.github.openfinalshell.android.core.termops.TerminalPalettes.resolve(
        io.github.openfinalshell.android.core.termops.TerminalPaletteId.fromStorageKey(settings.terminalPalette),
        if (darkTheme) io.github.openfinalshell.android.core.termops.TerminalUiMode.DARK else io.github.openfinalshell.android.core.termops.TerminalUiMode.LIGHT
    )
    val lineHeight = settings.terminalLineHeight.toFloatOrNull() ?: 1f
    val sessionId = state.selectedSessionId
    val session = sessionId?.let { state.sessions[it] }
    val controller = sessionId
        ?.takeIf { it in state.terminalSessionIds }
        ?.let(viewModel::terminalController)
    val terminalDescription = androidx.compose.ui.res.stringResource(R.string.tab_terminal)
    var selectedText by remember(sessionId) { mutableStateOf("") }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var searchOpen by remember(sessionId) { mutableStateOf(false) }
    var searchQuery by remember(sessionId) { mutableStateOf("") }
    var searchCase by remember(sessionId) { mutableStateOf(false) }
    var searchRegex by remember(sessionId) { mutableStateOf(false) }
    var searchMessage by remember(sessionId) { mutableStateOf<String?>(null) }
    var searchMatch by remember(sessionId) { mutableStateOf<io.github.openfinalshell.android.core.termops.TerminalMatch?>(null) }
    var historyOpen by remember(sessionId) { mutableStateOf(false) }
    var historyFilter by remember(sessionId) { mutableStateOf("") }
    var historyView by remember(sessionId) { mutableStateOf<String?>(null) }
    var menuOpen by remember(sessionId) { mutableStateOf(false) }
    var ctrlHeld by remember(sessionId) { mutableStateOf(false) }
    var pendingPaste by remember(sessionId) { mutableStateOf<String?>(null) }
    var terminalView by remember(sessionId) { mutableStateOf<SshTerminalView?>(null) }
    val commandHistory by viewModel.commandHistoryRecords.collectAsStateWithLifecycle()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val invalidRegex = androidx.compose.ui.res.stringResource(R.string.terminal_find_invalid)
    val noMatches = androidx.compose.ui.res.stringResource(R.string.terminal_find_none)
    fun runSearch(next: Boolean) {
        val host = sessionId?.let(viewModel::terminalController) ?: return
        val emulator = host.emulator ?: return
        val lines = io.github.openfinalshell.android.terminal.TerminalBufferReader.searchLines(emulator)
        val transcript = io.github.openfinalshell.android.terminal.TerminalBufferReader.transcriptRows(emulator)
        val origin = searchMatch
        val fromLine = if (origin == null) -1 else origin.line
        val fromColumn = if (origin == null) -1 else if (next) origin.startColumn else origin.startColumn
        val result = if (next) {
            io.github.openfinalshell.android.core.termops.TerminalSearch.findNext(
                lines, searchQuery, searchCase, searchRegex, fromLine, fromColumn
            )
        } else {
            io.github.openfinalshell.android.core.termops.TerminalSearch.findPrevious(
                lines, searchQuery, searchCase, searchRegex, fromLine, fromColumn
            )
        }
        when (result) {
            is io.github.openfinalshell.android.core.termops.TerminalSearchResult.Found -> {
                val active = result.active
                searchMatch = active
                searchMessage = null
                val line = lines.getOrNull(active.line).orEmpty()
                val start = io.github.openfinalshell.android.terminal.TerminalBufferReader.cellColumn(line, active.startColumn)
                val end = io.github.openfinalshell.android.terminal.TerminalBufferReader.cellColumn(line, active.endColumn)
                terminalView?.showMatch(active.line - transcript, start, end)
                terminalView?.scrollToExternalRow(active.line - transcript)
            }
            is io.github.openfinalshell.android.core.termops.TerminalSearchResult.InvalidRegex -> {
                searchMatch = null
                searchMessage = invalidRegex
                terminalView?.clearMatch()
            }
            io.github.openfinalshell.android.core.termops.TerminalSearchResult.None -> {
                searchMatch = null
                searchMessage = if (searchQuery.isEmpty()) null else noMatches
                terminalView?.clearMatch()
            }
        }
    }
    fun requestPaste(text: String) {
        if (text.isEmpty() || sessionId == null) return
        val plan = io.github.openfinalshell.android.core.termops.TerminalPastePlan.of(
            text,
            bracketedPaste = false,
            confirmMultiline = settings.terminalConfirmMultilinePaste,
            confirmed = false
        )
        if (plan.needsConfirmation) pendingPaste = text
        else viewModel.pasteTerminalText(sessionId, text, confirmed = true)
    }
    androidx.activity.compose.BackHandler(enabled = searchOpen || historyOpen || menuOpen) {
        when {
            menuOpen -> menuOpen = false
            historyOpen -> historyOpen = false
            else -> {
                searchOpen = false
                searchMatch = null
                terminalView?.clearMatch()
            }
        }
    }
    LaunchedEffect(sessionId, session?.state) {
        if (sessionId != null && session?.state == SessionState.READY && viewModel.terminalController(sessionId) == null) {
            // The Android view immediately replaces this initial size with its measured rows/columns.
            viewModel.openShell(sessionId, 80, 24)
        }
    }
    if (confirmDisconnect && sessionId != null) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.action_disconnect)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.terminal_disconnect_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        viewModel.disconnect(sessionId)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(androidx.compose.ui.res.stringResource(R.string.action_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
                }
            }
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Landscape with the IME open can leave less height than the fixed furniture needs. If the
        // furniture kept its place, the weighted terminal measures to zero and the view the user is
        // typing into becomes invisible. Furniture yields first, and in the tightest tier the
        // terminal stops being weighted at all: it gets an explicit share of the budget, because a
        // weighted child measured against no space collapses even inside a min-height floor.
        // Portrait heights clear both thresholds and keep the roomy layout below.
        val hideAssistantTools = maxHeight < 220.dp
        val hideSessionHeader = maxHeight < 140.dp
        // Tight tier: the action row is the one furniture kept, shrunk to a text line. The terminal
        // takes what is left, floored at one terminal row.
        val tightActionRowHeight = 28.dp
        val terminalHeight = (maxHeight - tightActionRowHeight - 4.dp).coerceAtLeast(24.dp)
        val tightTerminalHeight = if (maxHeight < 220.dp) maxHeight.coerceAtLeast(24.dp) else terminalHeight
        Column(
            Modifier.fillMaxSize()
                .padding(horizontal = 12.dp, vertical = if (hideSessionHeader) 0.dp else 10.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(if (hideSessionHeader) 4.dp else 8.dp)
        ) {
            if (!hideAssistantTools) {
                io.github.openfinalshell.android.tools.AssistantTools(
                    sessionId = sessionId?.takeIf { session?.state == SessionState.READY },
                    selectedText = selectedText,
                    onInsert = viewModel::insertAssistantCommand,
                    onExecute = viewModel::executeAssistantCommand
                )
            }
            if (!hideSessionHeader) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(session?.profile?.name ?: androidx.compose.ui.res.stringResource(R.string.tab_terminal), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(
                        session?.state?.let { sessionStateText(it) }
                            ?: androidx.compose.ui.res.stringResource(R.string.terminal_no_session),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (sessionId == null) {
                EmptyState(
                    title = androidx.compose.ui.res.stringResource(R.string.terminal_select_connection),
                    body = androidx.compose.ui.res.stringResource(R.string.terminal_select_body)
                )
                return@Column
            }
            if (searchOpen) {
                io.github.openfinalshell.android.terminal.TerminalSearchBar(
                    query = searchQuery,
                    onQuery = {
                        searchQuery = it
                        searchMatch = null
                        runSearch(next = true)
                    },
                    caseSensitive = searchCase,
                    onCase = {
                        searchCase = it
                        searchMatch = null
                        runSearch(next = true)
                    },
                    regex = searchRegex,
                    onRegex = {
                        searchRegex = it
                        searchMatch = null
                        runSearch(next = true)
                    },
                    message = searchMessage,
                    onPrevious = { runSearch(next = false) },
                    onNext = { runSearch(next = true) },
                    onClose = {
                        searchOpen = false
                        searchMatch = null
                        terminalView?.clearMatch()
                    }
                )
            }
            val hideScreenKeys = hideAssistantTools
            Surface(
                modifier = if (hideSessionHeader) {
                    Modifier.fillMaxWidth().height(tightTerminalHeight)
                } else {
                    Modifier.fillMaxWidth().weight(1f)
                },
                color = androidx.compose.ui.graphics.Color(palette.background),
                shape = OpenFinalShellShapes.large
            ) {
                Box(Modifier.fillMaxSize()) {
                    val ordered = state.terminalSessionIds.filter { it != sessionId } + sessionId
                    for (id in ordered) {
                        key(id) {
                            val host = viewModel.terminalController(id)
                            if (host != null) {
                            val snapshot = host.snapshot.collectAsStateWithLifecycle().value
                            AndroidView(
                                factory = { context -> SshTerminalView(context) },
                                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (id == sessionId) 1f else 0f },
                                update = { view ->
                                    if (id == sessionId) {
                                        terminalView = view
                                        view.onTextSelected = { selectedText = it }
                                        view.onContextMenu = { menuOpen = true }
                                        view.onPasteText = { requestPaste(it) }
                                        view.onOpenLink = { url ->
                                            runCatching {
                                                view.context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                                )
                                            }
                                        }
                                        view.onEscape = {
                                            searchOpen = false
                                            searchMatch = null
                                            view.clearMatch()
                                        }
                                        view.onFind = { searchOpen = true }
                                        view.onHistory = {
                                            historyOpen = true
                                            viewModel.refreshCommandHistory()
                                        }
                                        view.onPasteShortcut = {
                                            requestPaste(clipboard.getText()?.text.orEmpty())
                                        }
                                        view.onCloseTab = onCloseTab
                                        view.closeSearchOnEscape = searchOpen
                                        if (!searchOpen && !historyOpen && !menuOpen && pendingPaste == null && !view.hasFocus()) {
                                            view.requestFocus()
                                        }
                                        view.copyOnSelect = settings.terminalCopyOnSelect
                                        view.setPalette(palette)
                                        view.setCursorBlink(settings.terminalCursorBlink)
                                        view.setLineHeightMultiplier(lineHeight)
                                        view.setStickyCtrl(ctrlHeld)
                                        view.onStickyCtrlConsumed = { ctrlHeld = false }
                                    }
                                    view.contentDescription = terminalDescription
                                    view.bind(
                                        nextController = host,
                                        fontSizeSp = terminalFontSize,
                                        cursorStyle = when (terminalCursorStyle) {
                                            "line" -> TerminalCursorStyle.BAR
                                            "underline" -> TerminalCursorStyle.UNDERLINE
                                            else -> TerminalCursorStyle.BLOCK
                                        },
                                        input = { bytes -> viewModel.sendTerminalInput(id, bytes) },
                                        resize = { cols, rows -> viewModel.resizeTerminal(id, cols, rows) }
                                    )
                                    snapshot
                                    view.refresh()
                                }
                            )
                            }
                        }
                    }
                    if (controller == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    R.string.terminal_connecting,
                                    session?.profile?.host ?: androidx.compose.ui.res.stringResource(R.string.terminal_unknown_host)
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    io.github.openfinalshell.android.terminal.TerminalContextMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        onCopy = { terminalView?.copySelection() },
                        onPaste = {
                            val text = clipboard.getText()?.text.orEmpty()
                            requestPaste(text)
                        },
                        onSelectAll = { terminalView?.selectAll() },
                        onClear = { viewModel.clearTerminal(sessionId) },
                        onFind = { searchOpen = true },
                        onHistory = {
                            historyOpen = true
                            viewModel.refreshCommandHistory()
                        },
                        onDisconnect = { confirmDisconnect = true }
                    )
                    if (historyOpen) {
                        io.github.openfinalshell.android.terminal.TerminalHistoryOverlay(
                            records = commandHistory,
                            filter = historyFilter,
                            onFilter = { historyFilter = it },
                            onInsert = { line ->
                                if (!line.contains('\n') && !line.contains('\r')) viewModel.insertAssistantCommand(sessionId, line)
                            },
                            onView = { historyView = it },
                            onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
                            onClear = { viewModel.clearCommandHistory() },
                            onClose = { historyOpen = false },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
            if (!hideScreenKeys) {
                io.github.openfinalshell.android.terminal.TerminalKeyRow(
                    ctrl = ctrlHeld,
                    onCtrl = { ctrlHeld = !ctrlHeld },
                    onKey = { key ->
                        viewModel.sendScreenKey(sessionId, key, ctrlHeld)
                        ctrlHeld = false
                    }
                )
            }
            pendingPaste?.let { text ->
                io.github.openfinalshell.android.terminal.TerminalPasteDialog(
                    sessionName = session?.profile?.name ?: sessionId,
                    text = text,
                    onConfirm = {
                        viewModel.pasteTerminalText(sessionId, text, confirmed = true)
                        pendingPaste = null
                    },
                    onDismiss = { pendingPaste = null }
                )
            }
            historyView?.let { text ->
                io.github.openfinalshell.android.terminal.TerminalHistoryViewDialog(
                    text = text,
                    onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) },
                    onDismiss = { historyView = null }
                )
            }
        }
    }
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

@Composable
private fun forwardTypeText(type: String): String = androidx.compose.ui.res.stringResource(
    when (type) {
        "remote" -> R.string.forward_type_remote
        "dynamic" -> R.string.forward_type_dynamic
        else -> R.string.forward_type_local
    }
)

@Composable
private fun forwardRuntimeStateText(state: String?): String = androidx.compose.ui.res.stringResource(
    when (state) {
        "active" -> R.string.forward_state_active
        "error" -> R.string.forward_state_error
        else -> R.string.forward_state_stopped
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardScreen(state: AndroidUiState, viewModel: MainViewModel) {
    var type by remember { mutableStateOf("local") }
    var label by remember { mutableStateOf("") }
    var bindAddr by remember { mutableStateOf("127.0.0.1") }
    var bindPort by remember { mutableStateOf("0") }
    var dstHost by remember { mutableStateOf("127.0.0.1") }
    var dstPort by remember { mutableStateOf("22") }
    val sshProfiles = state.profiles.filter { it.protocol != LOCAL_SHELL_PROTOCOL }
    var profileId by remember(sshProfiles, state.selectedProfileId) {
        val preferred = state.selectedProfileId?.takeIf { id -> sshProfiles.any { it.id == id } }
        mutableStateOf(preferred ?: sshProfiles.firstOrNull()?.id.orEmpty())
    }
    var autoStart by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val selectedProfile = state.profiles.firstOrNull { it.id == profileId }
    val port = bindPort.toIntOrNull()
    val destinationPort = dstPort.toIntOrNull()
    val valid = selectedProfile != null && bindAddr.isNotBlank() && port?.let { it in 1..65535 } == true &&
        (type == "dynamic" || (dstHost.isNotBlank() && destinationPort?.let { it in 1..65535 } == true))

    pendingDeleteId?.let { targetId ->
        val target = state.forwards.firstOrNull { it.id == targetId }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.action_delete)) },
            text = { Text(target?.label ?: targetId) },
            confirmButton = {
                TextButton(
                    onClick = {
                        target?.let(viewModel::deleteForward)
                        pendingDeleteId = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(androidx.compose.ui.res.stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
                }
            }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(androidx.compose.ui.res.stringResource(R.string.forward_title), style = MaterialTheme.typography.titleMedium)
            Text(androidx.compose.ui.res.stringResource(R.string.forward_description), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (state.forwards.isEmpty()) {
            item { EmptyState(androidx.compose.ui.res.stringResource(R.string.forward_empty_title), androidx.compose.ui.res.stringResource(R.string.forward_empty_body)) }
        } else {
            items(state.forwards, key = { it.id }) { rule ->
                val runtime = state.forwardStates[rule.id]
                val profileName = state.profiles.firstOrNull { it.id == rule.profileId }?.name ?: rule.profileId
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.label, style = MaterialTheme.typography.titleSmall)
                                Text("${forwardTypeText(rule.type)} ${rule.bindAddr}:${rule.bindPort} -> ${rule.dstHost ?: "SOCKS5"}:${rule.dstPort ?: "-"}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    profileName + " | " + forwardRuntimeStateText(runtime?.state),
                                    color = if (runtime?.state == "error") MaterialTheme.colorScheme.error else Color.Unspecified,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (runtime?.state == "active") {
                                TextButton(onClick = { viewModel.stopForward(rule.id) }) { Text(androidx.compose.ui.res.stringResource(R.string.action_stop)) }
                            } else {
                                TextButton(onClick = { viewModel.startForward(rule) }) { Text(androidx.compose.ui.res.stringResource(R.string.action_start)) }
                            }
                        }
                        runtime?.error?.let { ErrorState(it) }
                        TextButton(
                            onClick = { pendingDeleteId = rule.id },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(androidx.compose.ui.res.stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
        item { Text(androidx.compose.ui.res.stringResource(R.string.forward_add_title), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(androidx.compose.ui.res.stringResource(R.string.forward_profile), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sshProfiles.forEach { profile ->
                    FilterChip(selected = profile.id == profileId, onClick = { profileId = profile.id }, label = { Text(profile.name) })
                }
            }
            if (sshProfiles.isEmpty()) {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.forward_ssh_only),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            ChoiceRow(
                androidx.compose.ui.res.stringResource(R.string.forward_type), type,
                listOf("local" to null, "remote" to null, "dynamic" to null)
            ) { type = it }
        }
        item { OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text(androidx.compose.ui.res.stringResource(R.string.forward_label)) }, singleLine = true) }
        item { OutlinedTextField(bindAddr, { bindAddr = it }, Modifier.fillMaxWidth(), label = { Text(androidx.compose.ui.res.stringResource(R.string.forward_bind_address)) }, singleLine = true) }
        item { OutlinedTextField(bindPort, { bindPort = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text(androidx.compose.ui.res.stringResource(R.string.forward_bind_port)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }
        if (type != "dynamic") {
            item { OutlinedTextField(dstHost, { dstHost = it }, Modifier.fillMaxWidth(), label = { Text(androidx.compose.ui.res.stringResource(R.string.forward_destination_host)) }, singleLine = true) }
            item { OutlinedTextField(dstPort, { dstPort = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text(androidx.compose.ui.res.stringResource(R.string.forward_destination_port)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }
        }
        item { SettingsSwitchRow(androidx.compose.ui.res.stringResource(R.string.forward_auto_start), autoStart) { autoStart = it } }
        item {
            Button(
                onClick = {
                    viewModel.saveForward(profileId, type, label, bindAddr, port ?: 0, dstHost, destinationPort, autoStart)
                    label = ""
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth()
            ) { Text(androidx.compose.ui.res.stringResource(R.string.forward_save)) }
        }
    }
}

@Composable
private fun LanSyncScreen(state: LanSyncUiState, viewModel: LanSyncViewModel, embedded: Boolean = false) {
    var pairingCode by remember { mutableStateOf("") }
    val localizedMessage = state.message?.let { uiStatusText(it) }
    Column(
        if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!embedded) {
            Text(androidx.compose.ui.res.stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
        }
        Text(androidx.compose.ui.res.stringResource(R.string.sync_description), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::scan, enabled = state.phase != "scanning") {
                Text(androidx.compose.ui.res.stringResource(R.string.sync_scan))
            }
            if (state.receiver == null) {
                Button(onClick = viewModel::startReceiver, enabled = state.phase != "starting") {
                    Text(androidx.compose.ui.res.stringResource(R.string.sync_start_receiver))
                }
            } else {
                Button(onClick = viewModel::stopReceiver) {
                    Text(androidx.compose.ui.res.stringResource(R.string.sync_stop_receiver))
                }
            }
        }
        state.receiver?.let { receiver ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.sync_receiver_ready), style = MaterialTheme.typography.titleSmall)
                    Text(androidx.compose.ui.res.stringResource(R.string.sync_receiver_port, receiver.tcpPort))
                    Text(androidx.compose.ui.res.stringResource(R.string.sync_pairing_code, receiver.pairingCode), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        if (state.phase == "incoming") {
            Card(Modifier.fillMaxWidth(), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(androidx.compose.ui.res.stringResource(R.string.sync_confirmation_required), style = MaterialTheme.typography.titleSmall)
                    Text(androidx.compose.ui.res.stringResource(R.string.sync_confirmation_body))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::acceptIncoming) { Text(androidx.compose.ui.res.stringResource(R.string.sync_accept)) }
                        TextButton(onClick = viewModel::rejectIncoming) { Text(androidx.compose.ui.res.stringResource(R.string.sync_reject)) }
                    }
                }
            }
        }
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(androidx.compose.ui.res.stringResource(R.string.sync_pairing_code_input)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        if (state.peers.isEmpty()) {
            EmptyState(androidx.compose.ui.res.stringResource(R.string.sync_no_devices), androidx.compose.ui.res.stringResource(R.string.sync_scan_hint))
        } else {
            Text(androidx.compose.ui.res.stringResource(R.string.sync_devices), style = MaterialTheme.typography.titleSmall)
            state.peers.forEach { peer ->
                val selected = state.selectedPeer?.deviceId == peer.deviceId && state.selectedPeer?.address == peer.address
                Card(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                        .clickable(role = Role.Button) { viewModel.selectPeer(peer) },
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(peer.deviceName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    R.string.sync_peer_metadata,
                                    peer.address,
                                    peer.tcpPort,
                                    peer.appVersion
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = { viewModel.send(peer, pairingCode) }, enabled = pairingCode.length == 6 && state.phase != "sending") {
                            Text(androidx.compose.ui.res.stringResource(R.string.sync_send))
                        }
                    }
                }
            }
        }
        localizedMessage?.let { message ->
            if (state.phase == "error") ErrorState(message)
            else Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.lastResult?.let { result ->
            Text(androidx.compose.ui.res.stringResource(R.string.sync_last_result, result.profiles, result.forwards, result.secrets, result.skipped))
        }
    }
}

@Composable
internal fun MonitorScreen(state: AndroidUiState, viewModel: MainViewModel) {
    MonitorPanel(state, viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    mainViewModel: MainViewModel,
    mainState: AndroidUiState,
    lanSyncState: LanSyncUiState,
    lanSyncViewModel: LanSyncViewModel,
    onRequestUpdateInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    val context = LocalContext.current
    val defaultPrivateKeyName = androidx.compose.ui.res.stringResource(R.string.private_key_default_name)
    var transferPassphrase by remember { mutableStateOf("") }
    var includeSecrets by remember { mutableStateOf(true) }
    var encryptAll by remember { mutableStateOf(true) }
    var pendingDeleteKeyId by remember { mutableStateOf<String?>(null) }
    var bindKeyId by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val portableStage by mainViewModel.portableImport.collectAsStateWithLifecycle()
    val finalShellStage by mainViewModel.finalShellImport.collectAsStateWithLifecycle()
    val needsExportPassphrase = includeSecrets || encryptAll
    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val existing = bindKeyId?.let { id -> mainState.privateKeys.firstOrNull { key -> key.id == id } }
            val displayName = existing?.name ?: it.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?.ifBlank { defaultPrivateKeyName }
                ?: defaultPrivateKeyName
            mainViewModel.importPrivateKey(it, displayName, existingId = existing?.id)
            bindKeyId = null
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { mainViewModel.exportPortable(it, transferPassphrase, includeSecrets, encryptAll) }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            importUri = it
            mainViewModel.stagePortableImport(it, transferPassphrase)
        }
    }
    val finalShellPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { mainViewModel.stageFinalShell(it) }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setDownloadDirectoryUri(uri.toString())
        }
    }
    pendingDeleteKeyId?.let { keyId ->
        val key = mainState.privateKeys.firstOrNull { it.id == keyId }
        val usedNames = key?.let { profilesUsingPrivateKey(mainState.profiles, it.id).map { profile -> profile.name } }.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingDeleteKeyId = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.action_delete)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    Text(key?.name.orEmpty())
                    PrivateKeyReferenceText(usedNames)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        key?.let(mainViewModel::deletePrivateKey)
                        pendingDeleteKeyId = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(androidx.compose.ui.res.stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteKeyId = null }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
                }
            }
        )
    }
    var settingsSection by rememberSaveable { mutableStateOf(SettingsPane.GENERAL.id) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wideSettings = maxWidth >= 560.dp
        Row(Modifier.fillMaxSize()) {
            if (wideSettings) {
                SettingsPaneList(settingsSection, vertical = true) { settingsSection = it }
            }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .imePadding()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = OpenFinalShellSpacing.Medium,
                    top = OpenFinalShellSpacing.Small,
                    end = OpenFinalShellSpacing.Medium,
                    bottom = OpenFinalShellSpacing.XXLarge
                ),
                verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
            ) {
                if (!wideSettings) {
                    item { SettingsPaneList(settingsSection, vertical = false) { settingsSection = it } }
                }
        if (settingsSection == SettingsPane.GENERAL.id) item {
            SettingsSection(androidx.compose.ui.res.stringResource(R.string.settings_general)) {
                ChoiceRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_language),
                    settings.language,
                    AndroidLocales.options.map { it.tag to it.labelRes }
                ) { viewModel.setLanguage(it) }
                SettingsSwitchRow(androidx.compose.ui.res.stringResource(R.string.settings_auto_updates), settings.autoCheckUpdates, viewModel::setAutoCheckUpdates)
                Text(androidx.compose.ui.res.stringResource(R.string.keepalive_title), style = MaterialTheme.typography.titleSmall)
                KeepAliveSettings()
            }
        }
        if (settingsSection == SettingsPane.APPEARANCE.id) item {
            SettingsSection(androidx.compose.ui.res.stringResource(R.string.settings_appearance)) {
                ChoiceRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_theme),
                    settings.theme,
                    listOf("system" to R.string.settings_theme_system, "light" to R.string.settings_theme_light, "dark" to R.string.settings_theme_dark)
                ) { viewModel.setTheme(it) }
                Text(androidx.compose.ui.res.stringResource(R.string.settings_accent_color), style = MaterialTheme.typography.labelLarge)
                AccentSwatches(selected = settings.accentColor, allowClear = false) { color ->
                    if (color != null) viewModel.setAccentColor(color)
                }
                SettingsSwitchRow(androidx.compose.ui.res.stringResource(R.string.settings_mask_hosts), settings.maskHosts, viewModel::setMaskHosts)
            }
        }
        if (settingsSection == SettingsPane.TERMINAL.id) item {
            SettingsSection(androidx.compose.ui.res.stringResource(R.string.settings_terminal)) {
                NumberSetting(
                    label = androidx.compose.ui.res.stringResource(R.string.settings_terminal_font_size),
                    value = settings.terminalFontSize,
                    range = 10..32,
                    onChange = viewModel::setTerminalFontSize
                )
                ChoiceRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_cursor),
                    settings.terminalCursorStyle,
                    listOf("block" to R.string.settings_cursor_block, "line" to R.string.settings_cursor_line, "underline" to R.string.settings_cursor_underline)
                ) { viewModel.setTerminalCursorStyle(it) }
                NumberSetting(
                    label = androidx.compose.ui.res.stringResource(R.string.settings_scrollback),
                    value = settings.terminalScrollbackLines,
                    range = io.github.openfinalshell.android.core.termops.ScrollbackLimits.MIN..io.github.openfinalshell.android.core.termops.ScrollbackLimits.MAX,
                    onChange = viewModel::setTerminalScrollbackLines
                )
                Text(
                    androidx.compose.ui.res.stringResource(R.string.settings_scrollback_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChoiceRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_palette),
                    settings.terminalPalette,
                    listOf(
                        "one-dark" to R.string.settings_palette_one_dark,
                        "dracula" to R.string.settings_palette_dracula,
                        "nord" to R.string.settings_palette_nord,
                        "solarized-dark" to R.string.settings_palette_solarized_dark,
                        "github-light" to R.string.settings_palette_github_light,
                        "solarized-light" to R.string.settings_palette_solarized_light,
                        "auto" to R.string.settings_palette_auto
                    )
                ) { viewModel.setTerminalPalette(it) }
                val previewDark = when (settings.theme) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkTheme()
                }
                io.github.openfinalshell.android.terminal.TerminalPalettePreview(
                    io.github.openfinalshell.android.core.termops.TerminalPalettes.resolve(
                        io.github.openfinalshell.android.core.termops.TerminalPaletteId.fromStorageKey(settings.terminalPalette),
                        if (previewDark) io.github.openfinalshell.android.core.termops.TerminalUiMode.DARK else io.github.openfinalshell.android.core.termops.TerminalUiMode.LIGHT
                    )
                )
                ChoiceRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_line_height),
                    settings.terminalLineHeight,
                    io.github.openfinalshell.android.storage.AndroidSettings.LINE_HEIGHTS.map { height -> height to null as Int? }
                ) { viewModel.setTerminalLineHeight(it) }
                SettingsSwitchRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_cursor_blink),
                    settings.terminalCursorBlink,
                    viewModel::setTerminalCursorBlink
                )
                SettingsSwitchRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_copy_on_select),
                    settings.terminalCopyOnSelect,
                    viewModel::setTerminalCopyOnSelect
                )
                SettingsSwitchRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_confirm_multiline_paste),
                    settings.terminalConfirmMultilinePaste,
                    viewModel::setTerminalConfirmMultilinePaste
                )
                val assistant = androidx.lifecycle.viewmodel.compose.viewModel<io.github.openfinalshell.android.tools.AssistantViewModel>()
                val assistantState by assistant.state.collectAsStateWithLifecycle()
                SettingsSwitchRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_record_commands),
                    assistantState.saveHistory,
                    assistant::historyEnabled
                )
                Text(androidx.compose.ui.res.stringResource(R.string.settings_keyboard_title), style = MaterialTheme.typography.labelLarge)
                Text(
                    androidx.compose.ui.res.stringResource(R.string.settings_keyboard_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (settingsSection == SettingsPane.TRANSFER.id) item {
            SettingsSection(androidx.compose.ui.res.stringResource(R.string.settings_transfer_monitor)) {
                ChoiceRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_sftp_concurrency),
                    settings.sftpConcurrency.toString(),
                    (1..8).map { it.toString() to null }
                ) { viewModel.setSftpConcurrency(it.toInt()) }
                ChoiceRow(
                    androidx.compose.ui.res.stringResource(R.string.settings_sftp_conflict),
                    settings.sftpConflictPolicy,
                    listOf(
                        "ask" to R.string.settings_conflict_ask,
                        "overwrite" to R.string.settings_conflict_overwrite,
                        "skip" to R.string.settings_conflict_skip,
                        "rename" to R.string.file_conflict_rename,
                    )
                ) { viewModel.setSftpConflictPolicy(it) }
                ChoiceRow(
                    androidx.compose.ui.res.stringResource(R.string.file_tap_action),
                    settings.sftpFileTap,
                    listOf("download" to R.string.file_tap_download, "edit" to R.string.file_tap_edit)
                ) { viewModel.setSftpFileTap(it) }
                SettingsSwitchRow(androidx.compose.ui.res.stringResource(R.string.settings_sftp_hidden), settings.sftpShowHiddenFiles, viewModel::setSftpShowHiddenFiles)
                NumberSetting(
                    label = androidx.compose.ui.res.stringResource(R.string.settings_monitor_interval),
                    value = settings.monitorIntervalSeconds,
                    range = 2..60,
                    onChange = viewModel::setMonitorIntervalSeconds
                )
                Text(androidx.compose.ui.res.stringResource(R.string.settings_download_directory), style = MaterialTheme.typography.labelLarge)
                Text(
                    settings.downloadDirectoryUri ?: androidx.compose.ui.res.stringResource(R.string.settings_download_directory_default),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { directoryPicker.launch(settings.downloadDirectoryUri?.let(Uri::parse)) },
                        shape = OpenFinalShellShapes.medium
                    ) {
                        Text(androidx.compose.ui.res.stringResource(R.string.action_choose_directory))
                    }
                    if (settings.downloadDirectoryUri != null) {
                        TextButton(onClick = { viewModel.setDownloadDirectoryUri(null) }) {
                            Text(androidx.compose.ui.res.stringResource(R.string.action_clear))
                        }
                    }
                }
            }
        }
        if (settingsSection == SettingsPane.PROXY.id) item {
            SettingsSection(androidx.compose.ui.res.stringResource(R.string.settings_proxy_and_keys)) {
                Text(androidx.compose.ui.res.stringResource(R.string.settings_private_keys), style = MaterialTheme.typography.labelLarge)
                if (mainState.privateKeys.isEmpty()) {
                    Text(androidx.compose.ui.res.stringResource(R.string.settings_private_keys_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    mainState.privateKeys.forEach { key ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(key.name)
                                if (key.materialRef == null) {
                                    Text(androidx.compose.ui.res.stringResource(R.string.private_key_material_missing), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = {
                                        bindKeyId = key.id
                                        privateKeyPicker.launch(arrayOf("*/*"))
                                    }) { Text(androidx.compose.ui.res.stringResource(R.string.private_key_bind_file)) }
                                } else {
                                    Text(key.originalPath.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                            }
                            TextButton(
                                onClick = { pendingDeleteKeyId = key.id },
                                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(androidx.compose.ui.res.stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        bindKeyId = null
                        privateKeyPicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
                    shape = OpenFinalShellShapes.medium
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_import_private_key))
                }
                Text(androidx.compose.ui.res.stringResource(R.string.settings_proxies), style = MaterialTheme.typography.titleSmall)
                ProxySettingsContent(mainState, mainViewModel)
                Text(androidx.compose.ui.res.stringResource(R.string.settings_known_hosts), style = MaterialTheme.typography.titleSmall)
                KnownHostSettingsContent(mainState, mainViewModel)
            }
        }
        if (settingsSection == SettingsPane.SECURITY.id) item {
            SettingsSection(androidx.compose.ui.res.stringResource(R.string.settings_security_data)) {
                if (BuildConfig.LOCAL_SHELL_ENABLED) {
                    SettingsSwitchRow(
                        androidx.compose.ui.res.stringResource(R.string.settings_local_allow_root),
                        settings.localAllowRoot,
                        viewModel::setLocalAllowRoot
                    )
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.settings_local_allow_root_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                io.github.openfinalshell.android.tools.AiSettingsSection()
                SettingsSwitchRow(androidx.compose.ui.res.stringResource(R.string.settings_export_include_secrets), includeSecrets) { includeSecrets = it }
                Text(androidx.compose.ui.res.stringResource(R.string.settings_export_include_secrets_body), style = MaterialTheme.typography.bodySmall)
                SettingsSwitchRow(androidx.compose.ui.res.stringResource(R.string.settings_export_encrypt_all), encryptAll) { encryptAll = it }
                Text(androidx.compose.ui.res.stringResource(R.string.settings_export_encrypt_all_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = transferPassphrase,
                    onValueChange = { transferPassphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.settings_backup_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { exportPicker.launch("openfinalshell-export.json") },
                        enabled = !needsExportPassphrase || transferPassphrase.length >= 8,
                        shape = OpenFinalShellShapes.medium
                    ) { Text(androidx.compose.ui.res.stringResource(R.string.action_export)) }
                    Button(
                        onClick = { importPicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        shape = OpenFinalShellShapes.medium
                    ) { Text(androidx.compose.ui.res.stringResource(R.string.action_import)) }
                }
                Text(androidx.compose.ui.res.stringResource(R.string.finalshell_import), style = MaterialTheme.typography.titleSmall)
                Text(androidx.compose.ui.res.stringResource(R.string.finalshell_import_body), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { finalShellPicker.launch(null) }, shape = OpenFinalShellShapes.medium) {
                    Text(androidx.compose.ui.res.stringResource(R.string.finalshell_choose_directory))
                }
            }
        }
        if (settingsSection == SettingsPane.SYNC.id) item {
            SettingsSection(androidx.compose.ui.res.stringResource(R.string.settings_lan_sync)) {
                LanSyncScreen(lanSyncState, lanSyncViewModel, embedded = true)
            }
        }
        if (settingsSection == SettingsPane.ABOUT.id) item {
            SettingsSection(androidx.compose.ui.res.stringResource(R.string.settings_about)) {
                Text(androidx.compose.ui.res.stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
                Text(androidx.compose.ui.res.stringResource(R.string.settings_about_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                UpdatePanel(state, viewModel, onRequestUpdateInstall)
            }
        }
            }
        }
    }
    if (importUri != null && portableStage !is PortableImportStage.Idle) {
        io.github.openfinalshell.android.ui.PortableImportDialog(
            stage = portableStage,
            passphrase = transferPassphrase,
            onPassphrase = { transferPassphrase = it },
            onRead = { importUri?.let { mainViewModel.stagePortableImport(it, transferPassphrase) } },
            onApply = { plan -> mainViewModel.applyPortableImport(plan) },
            onDismiss = {
                importUri = null
                mainViewModel.clearPortableImport()
            }
        )
    }
    if (finalShellStage !is FinalShellImportStage.Idle) {
        io.github.openfinalshell.android.ui.FinalShellImportDialog(
            stage = finalShellStage,
            onApply = mainViewModel::applyFinalShell,
            onDismiss = mainViewModel::clearFinalShellImport
        )
    }
}

private enum class SettingsPane(val id: String, val label: Int) {
    GENERAL("general", R.string.settings_general),
    APPEARANCE("appearance", R.string.settings_appearance),
    TERMINAL("terminal", R.string.settings_terminal),
    TRANSFER("transfer", R.string.settings_transfer_monitor),
    PROXY("proxy", R.string.settings_proxy_and_keys),
    SECURITY("security", R.string.settings_security_data),
    SYNC("sync", R.string.settings_lan_sync),
    ABOUT("about", R.string.settings_about)
}

@Composable
private fun SettingsPaneList(selected: String, vertical: Boolean, onSelect: (String) -> Unit) {
    if (vertical) {
        Column(
            Modifier
                .width(200.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = OpenFinalShellSpacing.Small)
        ) {
            SettingsPane.entries.forEach { pane ->
                val label = androidx.compose.ui.res.stringResource(pane.label)
                TextButton(
                    onClick = { onSelect(pane.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected == pane.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(label, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.XSmall)
        ) {
            SettingsPane.entries.forEach { pane ->
                FilterChip(
                    selected = selected == pane.id,
                    onClick = { onSelect(pane.id) },
                    label = { Text(androidx.compose.ui.res.stringResource(pane.label), maxLines = 1) }
                )
            }
        }
    }
}

@Composable
private fun KeepAliveSettings() {
    val context = LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                exempt = power.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Text(
        androidx.compose.ui.res.stringResource(R.string.keepalive_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        androidx.compose.ui.res.stringResource(if (exempt) R.string.keepalive_battery_exempt else R.string.keepalive_battery),
        style = MaterialTheme.typography.labelLarge
    )
    if (!exempt) {
        Button(
            onClick = {
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
        ) { Text(androidx.compose.ui.res.stringResource(R.string.keepalive_battery_action)) }
    }
    Text(
        androidx.compose.ui.res.stringResource(R.string.keepalive_oem),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(OpenFinalShellSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(label: String, selected: String, options: List<Pair<String, Int?>>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }
        ?.second
        ?.let { androidx.compose.ui.res.stringResource(it) }
        ?: selected
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(
                MenuAnchorType.PrimaryNotEditable,
                enabled = true
            ),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, resource) ->
                DropdownMenuItem(
                    text = { Text(resource?.let { androidx.compose.ui.res.stringResource(it) } ?: value) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun NumberSetting(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value, focused) {
        if (!focused) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            val filtered = next.filter(Char::isDigit).take(5)
            text = filtered
            filtered.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        label = { Text(label) },
        supportingText = { Text(range.first.toString() + " - " + range.last.toString()) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@Composable
private fun UpdatePanel(state: SettingsUiState, viewModel: SettingsViewModel, onRequestUpdateInstall: () -> Unit) {
    when (val update = state.update) {
        UpdateState.Idle -> Text(androidx.compose.ui.res.stringResource(R.string.update_not_checked))
        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val checking = androidx.compose.ui.res.stringResource(R.string.update_checking)
            CircularProgressIndicator(
                modifier = Modifier.width(20.dp).heightIn(min = 20.dp).semantics { contentDescription = checking },
                strokeWidth = 2.dp
            )
            Text(checking)
        }
        is UpdateState.UpToDate -> {
            Text(androidx.compose.ui.res.stringResource(R.string.update_up_to_date, update.currentVersion))
            Button(
                onClick = viewModel::checkForUpdates,
                shape = OpenFinalShellShapes.medium
            ) { Text(androidx.compose.ui.res.stringResource(R.string.action_check_updates)) }
        }
        is UpdateState.Available -> {
            Text(androidx.compose.ui.res.stringResource(R.string.update_available, update.release.versionName, update.apk.asset.name))
            Button(
                onClick = viewModel::downloadUpdate,
                shape = OpenFinalShellShapes.medium
            ) { Text(androidx.compose.ui.res.stringResource(R.string.action_download_update)) }
        }
        is UpdateState.Downloading -> {
            val ratio = if (update.totalBytes > 0) update.downloadedBytes.toFloat() / update.totalBytes else 0f
            val downloading = androidx.compose.ui.res.stringResource(R.string.update_downloading, formatBytes(update.downloadedBytes), formatBytes(update.totalBytes))
            Text(downloading)
            LinearProgressIndicator(
                progress = { ratio.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = downloading
                    progressBarRangeInfo = ProgressBarRangeInfo(current = ratio.coerceIn(0f, 1f), range = 0f..1f)
                }
            )
            TextButton(onClick = viewModel::cancelDownload) { Text(androidx.compose.ui.res.stringResource(R.string.action_cancel)) }
        }
        is UpdateState.Ready -> {
            Text(androidx.compose.ui.res.stringResource(R.string.update_ready, update.release.versionName))
            if (viewModel.canInstallPackages()) {
                Button(
                    onClick = onRequestUpdateInstall,
                    shape = OpenFinalShellShapes.medium
                ) { Text(androidx.compose.ui.res.stringResource(R.string.action_install_update)) }
            } else {
                Button(
                    onClick = onRequestUpdateInstall,
                    shape = OpenFinalShellShapes.medium
                ) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_allow_install))
                }
            }
        }
        UpdateState.Canceled -> {
            Text(androidx.compose.ui.res.stringResource(R.string.update_canceled))
            Button(
                onClick = viewModel::checkForUpdates,
                shape = OpenFinalShellShapes.medium
            ) { Text(androidx.compose.ui.res.stringResource(R.string.action_check_updates)) }
        }
        is UpdateState.Installing -> Text(androidx.compose.ui.res.stringResource(R.string.update_installing, update.versionName))
        is UpdateState.Installed -> Text(androidx.compose.ui.res.stringResource(R.string.update_installed, update.versionName))
        is UpdateState.Failed -> {
            Text(androidx.compose.ui.res.stringResource(R.string.update_failed), color = MaterialTheme.colorScheme.error)
            if (update.retryable) {
                Button(
                    onClick = viewModel::checkForUpdates,
                    shape = OpenFinalShellShapes.medium
                ) { Text(androidx.compose.ui.res.stringResource(R.string.action_retry)) }
            }
        }
    }
    if (state.saving) Text(androidx.compose.ui.res.stringResource(R.string.settings_saving), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun UpdateReadyDialog(versionName: String, onDefer: () -> Unit, onInstall: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDefer,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.update_ready_dialog_title)) },
        text = { Text(androidx.compose.ui.res.stringResource(R.string.update_ready_dialog_message, versionName)) },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_install_and_restart))
            }
        },
        dismissButton = {
            TextButton(onClick = onDefer) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_later))
            }
        }
    )
}

@Composable
private fun EmptyState(title: String, body: String) {
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
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorState(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(OpenFinalShellSpacing.Medium),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var amount = value.toDouble()
    var index = -1
    while (amount >= 1024 && index < units.lastIndex) {
        amount /= 1024
        index++
    }
    return "%.1f ${units[index]}".format(amount)
}

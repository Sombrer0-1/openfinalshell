package io.github.openfinalshell.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.openfinalshell.android.core.connection.GroupNode
import io.github.openfinalshell.android.core.connection.QuickConnectTarget
import io.github.openfinalshell.android.core.connection.isAllowedParent
import io.github.openfinalshell.android.core.connection.parseQuickConnect
import io.github.openfinalshell.android.core.connection.profilesUsingPrivateKey
import io.github.openfinalshell.android.core.connection.profilesUsingProxy
import io.github.openfinalshell.android.core.model.ConnectionAuth
import io.github.openfinalshell.android.core.model.ConnectionLocal
import io.github.openfinalshell.android.core.model.ConnectionProfile
import io.github.openfinalshell.android.core.model.ConnectionOptions
import io.github.openfinalshell.android.core.model.ConnectionProxy
import io.github.openfinalshell.android.core.model.ConnectionTerminal
import io.github.openfinalshell.android.core.model.ForwardRule
import io.github.openfinalshell.android.core.model.LOCAL_SHELL_PROTOCOL
import io.github.openfinalshell.android.core.model.LocalShellTier
import io.github.openfinalshell.android.core.model.SessionState
import io.github.openfinalshell.android.core.workspace.EndpointKey
import io.github.openfinalshell.android.core.workspace.FinalShellPlan
import io.github.openfinalshell.android.core.workspace.ImportCatalog
import io.github.openfinalshell.android.core.workspace.ImportPlanResult
import io.github.openfinalshell.android.core.workspace.LocalIndex
import io.github.openfinalshell.android.core.workspace.CommandBatch
import io.github.openfinalshell.android.core.workspace.CommandBody
import io.github.openfinalshell.android.core.workspace.CommandTarget
import io.github.openfinalshell.android.core.workspace.ForwardBind
import io.github.openfinalshell.android.core.workspace.ForwardBindingPolicy
import io.github.openfinalshell.android.core.workspace.ForwardRunLatch
import io.github.openfinalshell.android.core.workspace.ForwardSessionRef
import io.github.openfinalshell.android.core.workspace.LatchState
import io.github.openfinalshell.android.core.workspace.MonitorCollectionPolicy
import io.github.openfinalshell.android.core.workspace.ReadyDecision
import io.github.openfinalshell.android.core.workspace.RuleSession
import io.github.openfinalshell.android.core.workspace.RunningForward
import io.github.openfinalshell.android.core.forward.ForwardRuntimeState
import io.github.openfinalshell.android.core.monitor.LatencyProbe
import io.github.openfinalshell.android.core.monitor.MonitorSession
import io.github.openfinalshell.android.core.monitor.MonitorState
import io.github.openfinalshell.android.core.ssh.Credentials
import io.github.openfinalshell.android.core.ssh.CredentialsResolver
import io.github.openfinalshell.android.core.ssh.MinaSshTransport
import io.github.openfinalshell.android.core.ssh.HostKeyFingerprint
import io.github.openfinalshell.android.core.ssh.SessionEvent
import io.github.openfinalshell.android.core.ssh.SessionSnapshot
import io.github.openfinalshell.android.core.filesync.AcknowledgedRemote
import io.github.openfinalshell.android.core.filesync.BatchChoice
import io.github.openfinalshell.android.core.filesync.CdFollow
import io.github.openfinalshell.android.core.filesync.ConflictPolicy
import io.github.openfinalshell.android.core.filesync.FastDeletePlan
import io.github.openfinalshell.android.core.filesync.ListedEntry
import io.github.openfinalshell.android.core.filesync.ListedKind
import io.github.openfinalshell.android.core.filesync.NodeKind
import io.github.openfinalshell.android.core.filesync.PermissionBits
import io.github.openfinalshell.android.core.filesync.RemoteName
import io.github.openfinalshell.android.core.filesync.SaveRelease
import io.github.openfinalshell.android.core.filesync.TransferConflictPlan
import io.github.openfinalshell.android.core.filesync.TransferNode
import io.github.openfinalshell.android.core.session.FileSortKey
import io.github.openfinalshell.android.core.session.NavigationRequest
import io.github.openfinalshell.android.core.session.SessionFileState
import io.github.openfinalshell.android.core.session.SessionPanels
import io.github.openfinalshell.android.core.session.applyListing
import io.github.openfinalshell.android.core.session.beginNavigation
import io.github.openfinalshell.android.core.session.goBack
import io.github.openfinalshell.android.core.session.goForward
import io.github.openfinalshell.android.core.sftp.RemoteTextDocument
import io.github.openfinalshell.android.core.sftp.RemoteTextRetargeted
import io.github.openfinalshell.android.core.sftp.RemoteTextShrink
import io.github.openfinalshell.android.core.sftp.SftpSortDirection
import io.github.openfinalshell.android.core.sftp.SftpSortKey
import io.github.openfinalshell.android.core.ssh.ShellChannel
import io.github.openfinalshell.android.core.ssh.ShellCloseReason
import io.github.openfinalshell.android.core.ssh.SftpEntry
import io.github.openfinalshell.android.core.ssh.SshSessionManager
import io.github.openfinalshell.android.core.terminal.TerminalSnapshot
import io.github.openfinalshell.android.core.sftp.TransferQueue
import io.github.openfinalshell.android.core.sftp.orderedSftpEntries
import io.github.openfinalshell.android.core.sftp.TransferSink
import io.github.openfinalshell.android.core.sftp.TransferSource
import io.github.openfinalshell.android.core.sftp.TransferTask
import java.io.File
import java.io.RandomAccessFile
import io.github.openfinalshell.android.service.ConnectionForegroundService
import io.github.openfinalshell.android.service.SessionKeepAlivePolicy
import io.github.openfinalshell.android.core.local.LocalHostConfig
import io.github.openfinalshell.android.core.local.LocalHostTransport
import io.github.openfinalshell.android.core.ssh.SshTransport
import io.github.openfinalshell.android.local.LocalMounts
import io.github.openfinalshell.android.local.LocalPrivilege
import io.github.openfinalshell.android.local.LocalShellChannel
import io.github.openfinalshell.android.local.LocalShellSpec
import io.github.openfinalshell.android.local.LocalShellTransport
import io.github.openfinalshell.android.local.LocalTier
import io.github.openfinalshell.android.local.LocalTierAvailability
import io.github.openfinalshell.android.local.LocalTierResolution
import io.github.openfinalshell.android.local.RootConfirmation
import io.github.openfinalshell.android.local.appTierRoots
import io.github.openfinalshell.android.local.privilegedTierRoots
import io.github.openfinalshell.android.core.termops.ScrollbackLimits
import io.github.openfinalshell.android.core.termops.TerminalPastePlan
import io.github.openfinalshell.android.terminal.LocalTerminalController
import io.github.openfinalshell.android.terminal.ScreenKey
import io.github.openfinalshell.android.terminal.SshTerminalController
import io.github.openfinalshell.android.terminal.TerminalCommandCapture
import io.github.openfinalshell.android.terminal.TerminalHostController
import io.github.openfinalshell.android.terminal.TerminalModes
import io.github.openfinalshell.android.terminal.TerminalSizeTracker
import io.github.openfinalshell.android.terminal.screenKeyBytes
import io.github.openfinalshell.android.storage.AndroidCredentialStore
import io.github.openfinalshell.android.storage.ConnectionGroupMaintenance
import io.github.openfinalshell.android.storage.AppDatabase
import io.github.openfinalshell.android.storage.ProfileRepository
import io.github.openfinalshell.android.storage.ConnectionGroupEntity
import io.github.openfinalshell.android.storage.ConnectionGroupRepository
import io.github.openfinalshell.android.storage.PrivateKeyEntity
import io.github.openfinalshell.android.storage.PrivateKeyRepository
import io.github.openfinalshell.android.storage.SavedProxyEntity
import io.github.openfinalshell.android.storage.SavedProxyRepository
import io.github.openfinalshell.android.storage.PortableExport
import io.github.openfinalshell.android.storage.PortableSource
import io.github.openfinalshell.android.storage.PortableImportResult
import io.github.openfinalshell.android.storage.KnownHostEntity
import io.github.openfinalshell.android.storage.KnownHostRepository
import java.security.PublicKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.openfinalshell.android.storage.AndroidSettings
import io.github.openfinalshell.android.storage.CommandHistoryRecord
import io.github.openfinalshell.android.storage.ToolRepository
import io.github.openfinalshell.android.transfer.SafDocuments
import io.github.openfinalshell.android.transfer.SafEntry
import io.github.openfinalshell.android.transfer.PortTransferState
import io.github.openfinalshell.android.core.sftp.RemoteTextEditor
import io.github.openfinalshell.android.core.sftp.RemoteTextBinary
import io.github.openfinalshell.android.core.sftp.RemoteTextConflict
import io.github.openfinalshell.android.core.sftp.RemoteTextEncoding
import io.github.openfinalshell.android.core.sftp.RemoteTextEncodingFailure
import io.github.openfinalshell.android.core.sftp.RemoteTextEncodingUnavailable
import io.github.openfinalshell.android.core.sftp.SafeTar
import io.github.openfinalshell.android.core.ssh.AtomicReplaceUnavailable

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    /** Set when a stored key changed. Trusting the new key from this prompt is not allowed. */
    val previousFingerprint: String? = null,
    val recordKey: String = ""
)

data class AndroidUiState(
    val profiles: List<ConnectionProfile> = emptyList(),
    val groups: List<ConnectionGroupEntity> = emptyList(),
    val savedProxies: List<SavedProxyEntity> = emptyList(),
    val privateKeys: List<PrivateKeyEntity> = emptyList(),
    val knownHosts: List<KnownHostEntity> = emptyList(),
    val selectedProfileId: String? = null,
    val selectedSessionId: String? = null,
    val sessions: Map<String, SessionSnapshot> = emptyMap(),
    val terminalSessionIds: Set<String> = emptySet(),
    val monitor: MonitorState = MonitorState(),
    val sftpPath: String = "/",
    val sftpEntries: List<SftpEntry> = emptyList(),
    val sessionFiles: Map<String, SessionFileState> = emptyMap(),
    val sessionEntries: Map<String, List<SftpEntry>> = emptyMap(),
    val sessionPendingDirs: Map<String, String> = emptyMap(),
    val transferSessions: Map<String, String> = emptyMap(),
    val sessionPanels: Map<String, SessionPanels> = emptyMap(),
    val sessionMonitors: Map<String, MonitorState> = emptyMap(),
    val transfers: List<TransferTask> = emptyList(),
    val forwards: List<ForwardRule> = emptyList(),
    val forwardStates: Map<String, ForwardRuntimeState> = emptyMap(),
    val hostKeyPrompt: HostKeyPrompt? = null,
    /** The tier each local profile last resolved to, keyed by profile id, for the badge. */
    val localTiers: Map<String, LocalTierResolution> = emptyMap(),
    /** Set while a root-tier session is waiting for the user to confirm it. */
    val rootConfirmation: RootConfirmation? = null,
    val quickConnectDraft: QuickConnectTarget? = null,
    val status: UiStatus = UiStatus()
)

/** Full connection form. Blank password keeps the stored secret unless [clearPassword] is set. */
data class ConnectionForm(
    val profileId: String? = null,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val clearPassword: Boolean = false,
    val authMethod: String = "password",
    val privateKeyId: String? = null,
    val groupId: String? = null,
    val note: String? = null,
    val charset: String = "utf-8",
    val termType: String = "xterm-256color",
    val startupCommand: String? = null,
    val keepaliveInterval: Int = 30,
    val readyTimeout: Int = 20,
    val legacyAlgorithms: Boolean = false,
    val autoReconnect: Boolean = true,
    val monitorEnabled: Boolean = false,
    val compress: Boolean = false,
    val proxyId: String? = null,
    val color: String? = null,
    val flag: String? = null
)

/** One click of Send or Retry. [targets] is the set captured at that click. */
data class CommandDispatchRequest(
    val raw: String,
    val placeholdersEnabled: Boolean,
    val autoEnter: Boolean,
    val targets: List<CommandTarget>,
    val fromEditor: Boolean
)

data class CommandWorkspaceTarget(val target: CommandTarget, val label: String)

data class CommandTargetRoster(
    val eligible: List<CommandWorkspaceTarget>,
    val rootBlocked: List<CommandWorkspaceTarget>
)

/** Labels are resolved when the batch finishes. [retry] contains only failed targets. */
data class CommandWorkspaceReport(
    val id: Long,
    val clearEditor: Boolean,
    /** Editor text at send time, so a later draft is not wiped. */
    val editorRaw: String,
    val succeeded: List<String>,
    val failed: List<String>,
    val skipped: List<String>,
    val retry: CommandDispatchRequest?
)

enum class ImportFailure { Passphrase, Read }

sealed interface PortableImportStage {
    data object Idle : PortableImportStage
    data object Reading : PortableImportStage
    data class Failed(val kind: ImportFailure) : PortableImportStage
    data class Ready(
        val catalog: ImportCatalog,
        val local: LocalIndex,
        val invalid: Int,
        val rdpSkipped: Int,
        val materialRefsIgnored: Int,
        val secretsLocked: Boolean,
        val openedAt: Long
    ) : PortableImportStage
}

sealed interface FinalShellImportStage {
    data object Idle : FinalShellImportStage
    data object Reading : FinalShellImportStage
    data object Failed : FinalShellImportStage
    data class Ready(
        val parsed: io.github.openfinalshell.android.core.workspace.ParsedFinalShell,
        val existing: Set<EndpointKey>,
        val files: Int,
        val openedAt: Long
    ) : FinalShellImportStage
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(AndroidUiState())
    val state: StateFlow<AndroidUiState> = mutableState.asStateFlow()
    private val portableImportState = MutableStateFlow<PortableImportStage>(PortableImportStage.Idle)
    val portableImport: StateFlow<PortableImportStage> = portableImportState.asStateFlow()
    private val finalShellImportState = MutableStateFlow<FinalShellImportStage>(FinalShellImportStage.Idle)
    val finalShellImport: StateFlow<FinalShellImportStage> = finalShellImportState.asStateFlow()
    private var stagedSource: PortableSource? = null
    private var importGeneration = 0
    private var finalShellGeneration = 0
    private val database = AppDatabase.create(application)
    private val profiles = ProfileRepository(database.profiles())
    private val knownHosts = KnownHostRepository(database.knownHosts())
    private val groups = ConnectionGroupRepository(database.groups())
    private val savedProxies = SavedProxyRepository(database.proxies())
    private val credentialStore = AndroidCredentialStore(application, database)
    private val commandHistory = ToolRepository(database, credentialStore)
    private val commandCaptures = mutableMapOf<String, TerminalCommandCapture>()
    private val mutableCommandHistory = MutableStateFlow<List<CommandHistoryRecord>>(emptyList())
    val commandHistoryRecords: StateFlow<List<CommandHistoryRecord>> = mutableCommandHistory.asStateFlow()
    private val mutableCommandReport = MutableStateFlow<CommandWorkspaceReport?>(null)
    val commandWorkspaceReport: StateFlow<CommandWorkspaceReport?> = mutableCommandReport.asStateFlow()
    private val mutableCommandSendBusy = MutableStateFlow(false)
    val commandSendBusy: StateFlow<Boolean> = mutableCommandSendBusy.asStateFlow()
    private val privateKeys = PrivateKeyRepository(database.privateKeys(), credentialStore)
    private val shells = mutableMapOf<String, ShellChannel>()
    private val shellOutputJobs = mutableMapOf<String, Job>()
    /** One size tracker per session so an in-flight open keeps the latest measurement. */
    private val terminalSizes = mutableMapOf<String, TerminalSizeTracker>()
    private val terminalResizeLocks = mutableMapOf<String, Mutex>()
    /** Terminal controllers keep VT parsing and rendering state out of Compose text nodes. */
    private val terminalControllers = mutableMapOf<String, TerminalHostController>()
    private val monitors = mutableMapOf<String, MonitorSession>()
    private val monitorJobs = mutableMapOf<String, Job>()
    /** Bumped per session so a superseded loop cannot set running back to true. */
    private val monitorTokens = mutableMapOf<String, Int>()
    private val transferQueue = TransferQueue(viewModelScope)
    private var transferSettings = AndroidSettings()
    private val documents = SafDocuments(application.contentResolver)
    private val mutablePortTransfer = MutableStateFlow(PortTransferState())
    val portTransfer: StateFlow<PortTransferState> = mutablePortTransfer.asStateFlow()
    private val transferSessions = mutableMapOf<String, String>()
    private val preparationJobs = mutableMapOf<String, MutableList<Job>>()
    private val archiveRetries = mutableMapOf<String, () -> Unit>()
    private val conflictMutex = Mutex()
    private var conflictDecision: CompletableDeferred<BatchChoice>? = null
    private var editorSessionId: String? = null
    private var editorRelease = SaveRelease(overwriteRemoteChanges = false, allowNonAtomic = false, allowShrink = false)
    private val cdAnchors = mutableMapOf<String, CdAnchor>()
    private val forwarding = io.github.openfinalshell.android.storage.ForwardRepository(database.forwards())
    private val forwardingHandles = mutableMapOf<String, AutoCloseable>()
    private val forwardingSessions = mutableMapOf<String, String>()
    private var forwardLatch = LatchState()
    private val sessionReadyAt = mutableMapOf<String, Long>()
    /** Sessions whose next READY is a reconnect, so the monitor delta baseline is reset. */
    private val monitorReconnect = mutableSetOf<String>()
    @Volatile private var pendingHostKey: CompletableDeferred<Boolean>? = null

    private val sessions = SshSessionManager(
        // The factory takes the profile so one registry can hold SSH sessions and local on-device
        // shells side by side; that is what keeps every open* call site transport-agnostic.
        transportFactory = { profile ->
            if (profile.protocol == LOCAL_SHELL_PROTOCOL) {
                // Failing loudly matters here: quietly falling through would build an SSH transport
                // and try to reach localhost:22, which reads as a network fault rather than as a
                // disabled feature.
                check(BuildConfig.LOCAL_SHELL_ENABLED) { "local sessions are disabled in this build" }
                createLocalTransport(profile)
            } else {
                MinaSshTransport(hostKeyVerifier = io.github.openfinalshell.android.core.ssh.HostKeyVerifier { host, port, key -> verifyHostKey(host, port, key) },
                    proxyPasswordResolver = { credentialStore.getText(it) })
            }
        },
        scope = viewModelScope,
        credentialsResolver = CredentialsResolver { profile, supplied ->
            if (supplied.password != null && profile.auth.privateKeyId == null && profile.auth.passphraseRef == null) {
                supplied
            } else {
                val stored = credentialStore.resolve(profile.auth)
                Credentials(
                    password = supplied.password ?: stored.password,
                    privateKey = supplied.privateKey ?: stored.privateKey,
                    passphrase = supplied.passphrase ?: stored.passphrase
                )
            }
        },
        shouldReconnect = { profile -> profile.options.autoReconnect }
    )

    private fun monitorFor(sessionId: String): MonitorSession =
        monitors.getOrPut(sessionId) { MonitorSession(sessions) { id -> sessions.frameSource(id) } }

    private val localPrivilege by lazy { LocalPrivilege(getApplication()) }

    /**
     * Root sessions the user has confirmed, per profile.
     *
     * In memory only, and deliberately so: a reboot or a process death is exactly when the device's
     * root state may have changed, so the confirmation must not survive either.
     */
    private val confirmedRootProfiles = mutableSetOf<String>()

    /**
     * The tier resolution `connect()` already computed, handed to the session factory.
     *
     * The confirmation gate and the transport must be decided by the same probe — resolving twice
     * could ask the user to confirm root and then connect at the ADB tier, or the reverse.
     */
    @Volatile private var pendingLocalTier: PendingLocalTier? = null

    private data class PendingLocalTier(
        val profileId: String,
        val availability: LocalTierAvailability,
        val resolution: LocalTierResolution
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val staleBefore = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            getApplication<Application>().cacheDir.listFiles()?.filter {
                it.isFile && it.name.startsWith("ofs-pack-") && it.name.endsWith(".tar") && it.lastModified() < staleBefore
            }?.forEach { it.delete() }
        }
        viewModelScope.launch {
            runCatching { refreshStorage() }
                .onFailure { setStatus(readableError(it, StatusKey.LOCAL_STORAGE_UNAVAILABLE)) }
        }
        viewModelScope.launch {
            sessions.sessions.collect { snapshot ->
                updateState { it.copy(sessions = snapshot) }
                updateBackgroundService()
            }
        }
        viewModelScope.launch {
            sessions.events.collect { event ->
                when (event) {
                    is SessionEvent.StateChanged -> {
                        updateState { current ->
                            val nextStatus = when (event.state) {
                                SessionState.CONNECTING -> UiStatus(StatusKey.CONNECTING)
                                SessionState.AUTHENTICATING -> UiStatus(StatusKey.AUTHENTICATING)
                                SessionState.READY -> UiStatus(StatusKey.CONNECTED)
                                SessionState.RECONNECTING -> UiStatus(StatusKey.RECONNECTING)
                                SessionState.CLOSED -> {
                                    // Do not wipe a concrete connect failure (e.g. timeout while the
                                    // host-key dialog was open) with a generic "disconnected".
                                    val keep = current.status.key == StatusKey.CONNECTION_FAILED ||
                                        current.status.key == StatusKey.SSH_COMPONENTS_UNAVAILABLE
                                    if (keep) current.status else UiStatus(StatusKey.DISCONNECTED)
                                }
                            }
                            current.copy(status = nextStatus)
                        }
                        when (event.state) {
                            SessionState.CLOSED -> {
                                cancelSessionTransfers(event.sessionId)
                                monitorReconnect.remove(event.sessionId)
                                applyMonitorDecision(event.sessionId, reconnected = false)
                                detachForwardHandles(event.sessionId, event.error ?: "SSH session disconnected")
                            }
                            SessionState.RECONNECTING -> {
                                monitorReconnect.add(event.sessionId)
                                applyMonitorDecision(event.sessionId, reconnected = false)
                                detachForwardHandles(event.sessionId, event.error ?: "SSH session reconnecting")
                            }
                            SessionState.READY -> {
                                val reconnected = monitorReconnect.remove(event.sessionId)
                                noteSessionReady(event.sessionId)
                                applyMonitorDecision(event.sessionId, reconnected)
                                restoreAutoForwardings(event.sessionId)
                            }
                            SessionState.CONNECTING, SessionState.AUTHENTICATING ->
                                applyMonitorDecision(event.sessionId, reconnected = false)
                        }
                    }
                    is SessionEvent.Reconnected -> {
                        updateState { it.copy(status = UiStatus(StatusKey.RECONNECTED)) }
                        val profileId = state.value.sessions[event.sessionId]?.profile?.id
                        if (profileId != null) launch {
                            profiles.touchLastUsed(profileId)
                            refreshStorage()
                        }
                        rebindShell(event.sessionId)
                    }
                    is SessionEvent.ShellOpened -> updateState { it.copy(status = UiStatus(StatusKey.TERMINAL_READY)) }
                    is SessionEvent.ShellClosed -> when (event.reason) {
                        ShellCloseReason.DISCONNECTED -> {
                            shells.remove(event.sessionId)
                            shellOutputJobs.remove(event.sessionId)?.cancel()
                            (terminalControllers[event.sessionId] as? SshTerminalController)?.setInputEnabled(false)
                        }
                        ShellCloseReason.CLOSED, ShellCloseReason.ERROR -> {
                            shells.remove(event.sessionId)
                            shellOutputJobs.remove(event.sessionId)?.cancel()
                            terminalControllers.remove(event.sessionId)?.let { controller ->
                                viewModelScope.launch { controller.closeSession() }
                            }
                            updateState {
                                it.copy(
                                    terminalSessionIds = it.terminalSessionIds - event.sessionId,
                                    status = if (it.selectedSessionId == event.sessionId) {
                                        UiStatus(StatusKey.TERMINAL_CLOSED)
                                    } else {
                                        it.status
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            transferQueue.tasks.collect { tasks ->
                val live = tasks.map { it.id }.toSet()
                transferSessions.keys.retainAll(live)
                updateState { it.copy(transfers = tasks, transferSessions = transferSessions.toMap()) }
                updateBackgroundService()
            }
        }
    }

    /** Called when the activity stops or the FGS sticky-restarts; force republish past dedupe. */
    fun ensureBackgroundKeepAlive() {
        // Clear lastPublished so sticky restart / onStop re-assert always reaches startForeground.
        lastPublishedKeepAlive = null
        updateBackgroundService()
    }

    /** True when this process still has sockets or a connect attempt. Empty after process death. */
    fun hasBackgroundWork(): Boolean {
        val activeSessions = state.value.sessions.values.count { it.state != SessionState.CLOSED }
        val activeTransfers = state.value.transfers.count { task ->
            task.state == io.github.openfinalshell.android.core.sftp.TransferState.RUNNING ||
                task.state == io.github.openfinalshell.android.core.sftp.TransferState.QUEUED ||
                task.state == io.github.openfinalshell.android.core.sftp.TransferState.PAUSED
        }
        return SessionKeepAlivePolicy.shouldHoldIncludingConnects(
            activeSessions,
            activeTransfers,
            forwardingHandles.size,
            connectsInFlight
        )
    }

    private suspend fun refreshProfiles() {
        val loaded = profiles.list()
        updateState { it.copy(profiles = loaded) }
    }

    private suspend fun refreshStorage() {
        val loadedProfiles = profiles.list()
        val loadedGroups = groups.list()
        val loadedProxies = savedProxies.list()
        val loadedPrivateKeys = privateKeys.list()
        val loadedKnownHosts = knownHosts.list()
        val loadedForwards = forwarding.list()
        updateState {
            it.copy(
                profiles = loadedProfiles,
                groups = loadedGroups,
                savedProxies = loadedProxies,
                privateKeys = loadedPrivateKeys,
                knownHosts = loadedKnownHosts,
                forwards = loadedForwards
            )
        }
        // Fill in tiers for local profiles that have none yet, so a card can show what connecting
        // would actually do rather than only revealing it afterwards. Guarded so the probe runs once
        // per storage change rather than on every reload.
        if (loadedProfiles.any { it.protocol == LOCAL_SHELL_PROTOCOL && it.id !in state.value.localTiers }) {
            refreshLocalTiers()
        }
    }

    fun saveForward(
        profileId: String,
        type: String,
        label: String,
        bindAddr: String,
        bindPort: Int,
        dstHost: String?,
        dstPort: Int?,
        autoStart: Boolean,
        id: String? = null
    ) {
        viewModelScope.launch {
            try {
                require(profileId.isNotBlank()) { "forwarding connection is required" }
                require(type.lowercase() in setOf("local", "remote", "dynamic")) { "unsupported forwarding type" }
                require(bindAddr.isNotBlank() && bindPort in 1..65535) { "invalid forwarding bind address or port" }
                if (type.lowercase() != "dynamic") {
                    require(!dstHost.isNullOrBlank() && dstPort != null && dstPort in 1..65535) {
                        "forwarding destination is required"
                    }
                }
                forwarding.upsert(
                    ForwardRule(
                        id = id ?: UUID.randomUUID().toString(),
                        profileId = profileId,
                        type = type.lowercase(),
                        label = label.trim().ifEmpty { "${type.lowercase()} $bindPort" },
                        bindAddr = bindAddr.trim(),
                        bindPort = bindPort,
                        dstHost = dstHost?.trim()?.ifEmpty { null },
                        dstPort = dstPort,
                        autoStart = autoStart
                    )
                )
                refreshStorage()
                setStatus(StatusKey.FORWARDING_RULE_SAVED)
            } catch (error: Throwable) {
                setStatus(readableError(error, StatusKey.FORWARDING_RULE_SAVE_FAILED))
            }
        }
    }

    fun deleteForward(rule: ForwardRule) {
        viewModelScope.launch {
            closeForwardHandle(rule.id)
            forwardingSessions.remove(rule.id)
            forwardLatch = ForwardRunLatch.ruleDeleted(forwardLatch, rule.id)
            updateState { it.copy(forwardStates = it.forwardStates - rule.id) }
            updateBackgroundService()
            runCatching { forwarding.delete(rule.id); refreshStorage() }
                .onFailure { setStatus(readableError(it, StatusKey.FORWARDING_RULE_DELETE_FAILED)) }
        }
    }

    fun startForward(rule: ForwardRule) {
        viewModelScope.launch {
            try {
                val ruleProfile = state.value.profiles.firstOrNull { it.id == rule.profileId }
                if (ruleProfile?.protocol == LOCAL_SHELL_PROTOCOL) {
                    rejectLocalForward(rule.id)
                    return@launch
                }
                val bound = forwardingSessions[rule.id]
                val running = if (bound != null && forwardingHandles.containsKey(rule.id)) RunningForward(bound) else null
                when (val choice = ForwardBindingPolicy.choose(rule.profileId, forwardRefs(), running)) {
                    is ForwardBind.Keep -> Unit
                    is ForwardBind.Start -> beginForward(rule, choice.sessionId, userAction = true)
                    ForwardBind.RejectLocalShell -> rejectLocalForward(rule.id)
                    ForwardBind.NoReadySession -> {
                        val message = getApplication<Application>().getString(StatusKey.FORWARDING_START_FAILED.resourceId)
                        updateForwardState(rule.id, ForwardRuntimeState(rule.id, "error", error = message))
                        setStatus(UiStatus(StatusKey.FORWARDING_START_FAILED))
                    }
                }
            } catch (error: Throwable) {
                updateForwardState(rule.id, ForwardRuntimeState(rule.id, "error", error = readableErrorText(error, StatusKey.FORWARDING_START_FAILED)))
                setStatus(readableError(error, StatusKey.FORWARDING_START_FAILED))
            }
        }
    }

    fun stopForward(id: String) {
        val bound = forwardingSessions[id]
        closeForwardHandle(id)
        if (bound != null) forwardLatch = ForwardRunLatch.manualStop(forwardLatch, id, bound)
        if (state.value.forwardStates.containsKey(id)) {
            updateForwardState(id, ForwardRuntimeState(id, "stopped"))
        }
        updateBackgroundService()
    }

    fun stopAllForwards() {
        forwardingHandles.keys.toList().forEach(::stopForward)
    }

    private fun updateForwardState(id: String, value: ForwardRuntimeState) {
        updateState { it.copy(forwardStates = it.forwardStates + (id to value)) }
    }

    private fun closeForwardHandle(id: String) {
        forwardingHandles.remove(id)?.let { runCatching { it.close() } }
    }

    private fun rejectLocalForward(ruleId: String) {
        val message = getApplication<Application>().getString(R.string.status_forwarding_requires_ssh)
        updateForwardState(ruleId, ForwardRuntimeState(ruleId, "error", error = message))
        setStatus(UiStatus(StatusKey.FORWARDING_REQUIRES_SSH))
    }

    private fun noteSessionReady(sessionId: String) {
        if (sessionId !in sessionReadyAt) sessionReadyAt[sessionId] = System.currentTimeMillis()
    }

    private fun liveSession(sessionId: String) =
        sessions.sessions.value[sessionId] ?: state.value.sessions[sessionId]

    private fun forwardRefs(): List<ForwardSessionRef> {
        val selected = state.value.selectedSessionId
        sessions.sessions.value.values.filter { it.state == SessionState.READY }.forEach { noteSessionReady(it.sessionId) }
        return sessions.sessions.value.values.map { session ->
            ForwardSessionRef(
                sessionId = session.sessionId,
                profileId = session.profile.id,
                state = session.state,
                readyAt = sessionReadyAt[session.sessionId] ?: Long.MAX_VALUE,
                selected = session.sessionId == selected,
                localShell = session.profile.protocol == LOCAL_SHELL_PROTOCOL
            )
        }
    }

    /** Drops a stale listener but keeps the binding and any manual-stop latch. */
    private fun detachForwardHandles(sessionId: String, message: String) {
        forwardingSessions.filterValues { it == sessionId }.keys.toList().forEach { id ->
            closeForwardHandle(id)
            val bound = forwardingSessions[id] ?: return@forEach
            if (RuleSession(id, bound) !in forwardLatch.suppressed) {
                updateForwardState(id, ForwardRuntimeState(id, "error", error = message))
            }
        }
        updateBackgroundService()
    }

    private suspend fun beginForward(rule: io.github.openfinalshell.android.core.model.ForwardRule, sessionId: String, userAction: Boolean) {
        val session = liveSession(sessionId) ?: return
        if (session.profile.protocol == LOCAL_SHELL_PROTOCOL) {
            rejectLocalForward(rule.id)
            return
        }
        if (session.state != SessionState.READY) return
        if (userAction) {
            val previous = forwardingSessions[rule.id]
            forwardLatch = ForwardRunLatch.manualStart(forwardLatch, rule.id, sessionId)
            if (previous != null && previous != sessionId) {
                forwardLatch = ForwardRunLatch.manualStart(forwardLatch, rule.id, previous)
            }
        }
        closeForwardHandle(rule.id)
        try {
            val handle = sessions.startForwarding(sessionId, rule)
            forwardingHandles[rule.id] = handle
            forwardingSessions[rule.id] = sessionId
            updateForwardState(rule.id, ForwardRuntimeState(rule.id, "active"))
            setStatus(StatusKey.FORWARDING_STARTED)
            updateBackgroundService()
        } catch (error: Throwable) {
            updateForwardState(rule.id, ForwardRuntimeState(rule.id, "error", error = readableErrorText(error, StatusKey.FORWARDING_START_FAILED)))
            setStatus(readableError(error, StatusKey.FORWARDING_START_FAILED))
        }
    }

    private fun restoreAutoForwardings(sessionId: String) {
        viewModelScope.launch {
            val session = liveSession(sessionId) ?: return@launch
            if (session.state != SessionState.READY) return@launch
            noteSessionReady(sessionId)
            val rules = forwarding.list().filter { it.profileId == session.profile.id }
            for (rule in rules) {
                val bound = forwardingSessions[rule.id]
                if (bound != null) {
                    when (ForwardRunLatch.onReady(forwardLatch, rule.id, bound, sessionId)) {
                        ReadyDecision.IgnoreOtherTab, ReadyDecision.StayStopped -> Unit
                        ReadyDecision.Restore -> beginForward(rule, bound, userAction = false)
                    }
                } else if (rule.autoStart) {
                    when (val choice = ForwardBindingPolicy.choose(rule.profileId, forwardRefs(), running = null)) {
                        is ForwardBind.Start -> if (choice.sessionId == sessionId) beginForward(rule, choice.sessionId, userAction = false)
                        ForwardBind.RejectLocalShell -> rejectLocalForward(rule.id)
                        is ForwardBind.Keep, ForwardBind.NoReadySession -> Unit
                    }
                }
            }
        }
    }

    private var keepAliveStarted = false
    /** Last counts sent to the FGS; skip identical UPDATEs to avoid transfer IPC storms. */
    private var lastPublishedKeepAlive: SessionKeepAlivePolicy.PublishedCounts? = null
    private var connectsInFlight = 0
    private var notificationPermissionPrompted = false
    private val _requestNotificationPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** One-shot signal so the UI can request POST_NOTIFICATIONS before/while FGS runs (API 33+). */
    val requestNotificationPermission: SharedFlow<Unit> = _requestNotificationPermission.asSharedFlow()

    private fun promptNotificationPermissionOnce() {
        if (notificationPermissionPrompted) return
        notificationPermissionPrompted = true
        _requestNotificationPermission.tryEmit(Unit)
    }

    private fun updateBackgroundService() {
        val activeSessions = state.value.sessions.values.count { it.state != SessionState.CLOSED }
        val activeTransfers = state.value.transfers.count { task ->
            task.state == io.github.openfinalshell.android.core.sftp.TransferState.RUNNING ||
                task.state == io.github.openfinalshell.android.core.sftp.TransferState.QUEUED ||
                task.state == io.github.openfinalshell.android.core.sftp.TransferState.PAUSED
        }
        val activeForwards = forwardingHandles.size
        val connecting = connectsInFlight > 0 && activeSessions == 0
        val hold = SessionKeepAlivePolicy.shouldHoldIncludingConnects(
            activeSessions,
            activeTransfers,
            activeForwards,
            connectsInFlight
        )
        if (hold) {
            keepAliveStarted = true
            promptNotificationPermissionOnce()
            val next = SessionKeepAlivePolicy.PublishedCounts(
                sessions = activeSessions,
                transfers = activeTransfers,
                forwards = activeForwards,
                connecting = connecting
            )
            // Counts unchanged → no startForegroundService / prefs write / notification rebuild.
            // Transfer chunk progress fires often but does not change these counts.
            if (!SessionKeepAlivePolicy.shouldRepublish(lastPublishedKeepAlive, next)) {
                return
            }
            lastPublishedKeepAlive = next
            ConnectionForegroundService.update(
                getApplication(),
                activeSessions,
                activeTransfers,
                activeForwards,
                connecting = connecting
            )
        } else if (keepAliveStarted) {
            keepAliveStarted = false
            lastPublishedKeepAlive = null
            ConnectionForegroundService.stop(getApplication())
        }
    }

    private fun holdProcessForConnect() {
        connectsInFlight++
        keepAliveStarted = true
        promptNotificationPermissionOnce()
        ConnectionForegroundService.begin(getApplication())
    }

    private fun finishConnectAttempt() {
        connectsInFlight = (connectsInFlight - 1).coerceAtLeast(0)
        updateBackgroundService()
    }

    fun addProfile(name: String, host: String, port: Int, username: String, password: String = "") {
        saveProfile(name, host, port, username, password)
    }

    fun saveProfile(
        name: String,
        host: String,
        port: Int,
        username: String,
        password: String = "",
        profileId: String? = null,
        privateKeyId: String? = null,
        proxyId: String? = null,
        groupId: String? = null,
        note: String? = null,
        autoReconnect: Boolean = true,
        keepaliveInterval: Int = 30,
        readyTimeout: Int = 20,
        startupCommand: String? = null,
        clearPassword: Boolean = false
    ) {
        viewModelScope.launch {
            val previous = profileId?.let { profiles.find(it) }
            saveConnectionForm(
                ConnectionForm(
                    profileId = profileId,
                    name = name,
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    clearPassword = clearPassword,
                    authMethod = if (privateKeyId != null) "privateKey" else "password",
                    privateKeyId = privateKeyId,
                    groupId = groupId,
                    note = note,
                    charset = previous?.terminal?.charset ?: "utf-8",
                    termType = previous?.terminal?.termType ?: "xterm-256color",
                    startupCommand = startupCommand,
                    keepaliveInterval = keepaliveInterval,
                    readyTimeout = readyTimeout,
                    legacyAlgorithms = previous?.options?.legacyAlgorithms ?: false,
                    autoReconnect = autoReconnect,
                    monitorEnabled = previous?.options?.monitorEnabled ?: false,
                    compress = previous?.options?.compress ?: false,
                    proxyId = proxyId,
                    color = previous?.color,
                    flag = previous?.flag
                ),
                launch = false
            )
        }
    }

    fun saveConnectionForm(form: ConnectionForm) {
        viewModelScope.launch { saveConnectionForm(form, launch = false) }
    }

    private suspend fun saveConnectionForm(form: ConnectionForm, launch: Boolean) {
        if (launch) return
        try {
            require(form.name.isNotBlank() && form.host.isNotBlank() && form.username.isNotBlank()) { "name, host and username are required" }
            require(form.port in 1..65535) { "invalid SSH port" }
            val previous = form.profileId?.let { profiles.find(it) }
            val method = if (form.authMethod == "privateKey") "privateKey" else "password"
            val keyId = if (method == "privateKey") form.privateKeyId else null
            require(method != "privateKey" || !keyId.isNullOrBlank()) { "private key is required" }
            val oldPasswordRef = previous?.auth?.passwordRef
            val passwordRef = when {
                method == "privateKey" -> null
                form.password.isNotEmpty() -> credentialStore.put(form.password, oldPasswordRef ?: UUID.randomUUID().toString())
                form.clearPassword -> null
                else -> oldPasswordRef
            }
            if (oldPasswordRef != null && passwordRef != oldPasswordRef) credentialStore.delete(oldPasswordRef)
            val passphraseRef = if (method == "privateKey" && keyId == previous?.auth?.privateKeyId) {
                previous?.auth?.passphraseRef
            } else {
                null
            }
            val base = previous ?: ConnectionProfile(
                id = UUID.randomUUID().toString(),
                name = form.name.trim(),
                host = form.host.trim(),
                port = form.port,
                username = form.username.trim(),
                auth = ConnectionAuth(method)
            )
            val proxy = form.proxyId?.let { id ->
                savedProxies.find(id)?.let { saved ->
                    ConnectionProxy(saved.type, saved.host, saved.port, saved.username, saved.passwordRef)
                }
            }
            val profile = base.copy(
                id = previous?.id ?: base.id,
                name = form.name.trim(),
                host = form.host.trim(),
                port = form.port,
                username = form.username.trim(),
                auth = ConnectionAuth(method, passwordRef, keyId, passphraseRef),
                proxy = proxy,
                protocol = if (previous?.protocol == LOCAL_SHELL_PROTOCOL) previous.protocol else "ssh",
                groupId = form.groupId,
                note = form.note?.trim()?.ifEmpty { null },
                terminal = base.terminal.copy(
                    charset = form.charset.ifBlank { "utf-8" },
                    termType = form.termType.ifBlank { "xterm-256color" },
                    startupCommand = form.startupCommand?.trim()?.ifEmpty { null }
                ),
                options = base.options.copy(
                    keepaliveInterval = form.keepaliveInterval.coerceIn(0, 3600),
                    readyTimeout = form.readyTimeout.coerceIn(1, 300),
                    legacyAlgorithms = form.legacyAlgorithms,
                    autoReconnect = form.autoReconnect,
                    monitorEnabled = form.monitorEnabled,
                    compress = form.compress
                ),
                proxyMode = when {
                    form.proxyId != null -> "custom"
                    previous?.proxyId != null -> "direct"
                    else -> previous?.proxyMode
                },
                proxyId = form.proxyId,
                color = form.color?.trim()?.ifEmpty { null },
                flag = form.flag?.trim()?.ifEmpty { null },
                lastUsedAt = previous?.lastUsedAt
            )
            profiles.savePreserving(previous?.id, profile)
            refreshStorage()
            updateState { it.copy(selectedProfileId = profile.id, quickConnectDraft = null, status = UiStatus(StatusKey.PROFILE_SAVED)) }
            sessions.sessions.value.values.filter { it.profile.id == profile.id }.forEach { session ->
                applyMonitorDecision(session.sessionId, reconnected = false)
            }
        } catch (error: Throwable) {
            setStatus(readableError(error, StatusKey.PROFILE_SAVE_FAILED))
        }
    }

    /**
     * Creates a local on-device session.
     *
     * Unlike [saveProfile] this validates nothing about a host, because there is no host: the shell
     * runs on the phone. `host` and `username` are NOT NULL columns and carry placeholders so the
     * row satisfies the schema without pretending the values mean anything.
     *
     * `autoReconnect` is off deliberately. A local session ends when its shell exits, and
     * reconnecting would respawn the shell in a loop the moment a user typed `exit`.
     */
    fun saveLocalProfile(name: String, tier: String = LocalShellTier.AUTO, startDirectory: String? = null, profileId: String? = null) {
        viewModelScope.launch {
            try {
                require(name.isNotBlank()) { "local session name is required" }
                require(tier in LocalShellTier.all) { "unknown local tier: $tier" }
                val previous = profileId?.let { profiles.find(it) }
                require(previous == null || previous.protocol == LOCAL_SHELL_PROTOCOL) { "not a local shell profile" }
                val profile = (previous ?: ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    host = LOCAL_HOST_PLACEHOLDER,
                    port = 22,
                    username = LOCAL_USERNAME_PLACEHOLDER,
                    auth = ConnectionAuth(method = "none"),
                    protocol = LOCAL_SHELL_PROTOCOL,
                    options = ConnectionOptions(autoReconnect = false)
                )).copy(
                    name = name.trim(),
                    protocol = LOCAL_SHELL_PROTOCOL,
                    local = ConnectionLocal(
                        tier = tier,
                        userland = previous?.local?.userland ?: "system",
                        startDirectory = startDirectory?.trim()?.ifEmpty { null }
                    ),
                    options = (previous?.options ?: ConnectionOptions()).copy(autoReconnect = false)
                )
                profiles.savePreserving(previous?.id, profile)
                refreshStorage()
                updateState { it.copy(selectedProfileId = profile.id, status = UiStatus(StatusKey.PROFILE_SAVED)) }
            } catch (error: Throwable) {
                setStatus(readableError(error, StatusKey.PROFILE_SAVE_FAILED))
            }
        }
    }

    fun deleteProfile(profile: ConnectionProfile) {
        viewModelScope.launch {
            try {
                state.value.sessions.values.filter { it.profile.id == profile.id }.forEach { sessions.disconnect(it.sessionId) }
                profiles.delete(profile.id)
                refreshStorage()
                updateState { it.copy(selectedProfileId = null, status = UiStatus(StatusKey.PROFILE_DELETED)) }
            } catch (error: Throwable) { setStatus(readableError(error, StatusKey.PROFILE_DELETE_FAILED)) }
        }
    }

    fun duplicateProfile(profile: ConnectionProfile) {
        viewModelScope.launch {
            try {
                val copiedPassword = profile.auth.passwordRef?.let { reference ->
                    credentialStore.get(reference)?.let { credentialStore.put(it) }
                }
                val copy = profile.copy(
                    id = UUID.randomUUID().toString(),
                    name = "${profile.name} copy",
                    auth = profile.auth.copy(passwordRef = copiedPassword),
                    lastUsedAt = null
                )
                profiles.savePreserving(profile.id, copy)
                refreshStorage()
                updateState { it.copy(selectedProfileId = copy.id, status = UiStatus(StatusKey.PROFILE_SAVED)) }
            } catch (error: Throwable) {
                setStatus(readableError(error, StatusKey.PROFILE_SAVE_FAILED))
            }
        }
    }

    fun saveGroup(name: String, parentId: String? = null, order: Double = 0.0, id: String? = null) {
        viewModelScope.launch {
            runCatching {
                val nodes = groups.list().map { GroupNode(it.id, it.parentId) }
                if (!isAllowedParent(id, parentId, nodes)) {
                    setStatus(StatusKey.GROUP_PARENT_INVALID)
                    return@runCatching
                }
                groups.save(name, parentId, order, id)
                refreshStorage()
            }.onFailure { setStatus(readableError(it, StatusKey.GROUP_SAVE_FAILED)) }
        }
    }

    fun deleteGroup(group: ConnectionGroupEntity) {
        viewModelScope.launch {
            runCatching {
                ConnectionGroupMaintenance(database).deleteAndLift(group.id)
                refreshStorage()
            }.onFailure { setStatus(readableError(it, StatusKey.GROUP_DELETE_FAILED)) }
        }
    }

    fun saveProxy(name: String, type: String, host: String, port: Int, username: String?, password: String, id: String? = null) {
        viewModelScope.launch {
            try {
                val old = id?.let { savedProxies.find(it) }
                val passwordRef = when {
                    password.isNotEmpty() -> credentialStore.put(password, old?.passwordRef ?: UUID.randomUUID().toString())
                    else -> old?.passwordRef
                }
                savedProxies.save(name, type, host, port, username, passwordRef, id)
                refreshStorage()
                setStatus(StatusKey.PROXY_SAVED_UNAVAILABLE)
            } catch (error: Throwable) { setStatus(readableError(error, StatusKey.PROXY_SAVE_FAILED)) }
        }
    }

    fun deleteProxy(proxy: SavedProxyEntity) {
        viewModelScope.launch {
            val used = profilesUsingProxy(state.value.profiles, proxy.id)
            if (used.isNotEmpty()) {
                setStatus(StatusKey.PROXY_IN_USE, used.joinToString { it.name })
                return@launch
            }
            runCatching { savedProxies.delete(proxy.id); refreshStorage() }
                .onFailure { setStatus(readableError(it, StatusKey.PROXY_DELETE_FAILED)) }
        }
    }

    fun importPrivateKey(uri: android.net.Uri, name: String, passphrase: String = "", existingId: String? = null) {
        viewModelScope.launch {
            try {
                privateKeys.saveFromUri(
                    getApplication<Application>().contentResolver,
                    uri,
                    name,
                    passphrase.takeIf { it.isNotEmpty() }?.toCharArray(),
                    existingId
                )
                refreshStorage()
                setStatus(StatusKey.PRIVATE_KEY_IMPORTED)
            } catch (error: Throwable) { setStatus(readableError(error, StatusKey.PRIVATE_KEY_IMPORT_FAILED)) }
        }
    }

    fun deletePrivateKey(key: PrivateKeyEntity) {
        viewModelScope.launch {
            val used = profilesUsingPrivateKey(state.value.profiles, key.id)
            if (used.isNotEmpty()) {
                setStatus(StatusKey.PRIVATE_KEY_IN_USE, used.joinToString { it.name })
                return@launch
            }
            runCatching { privateKeys.delete(key.id); refreshStorage() }
                .onFailure { setStatus(readableError(it, StatusKey.PRIVATE_KEY_DELETE_FAILED)) }
        }
    }

    /** Parses a quick-connect string. A missing username opens the form instead of connecting. */
    fun quickConnect(input: String) {
        val target = parseQuickConnect(input)
        if (target == null) {
            setStatus(StatusKey.QUICK_CONNECT_INVALID)
            return
        }
        val username = target.username
        if (username.isNullOrBlank()) {
            updateState { it.copy(quickConnectDraft = target, status = UiStatus()) }
            return
        }
        viewModelScope.launch {
            try {
                val profile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = target.host,
                    host = target.host,
                    port = target.port,
                    username = username,
                    auth = ConnectionAuth("password")
                )
                profiles.savePreserving(null, profile)
                refreshStorage()
                connect(profile)
            } catch (error: Throwable) {
                setStatus(readableError(error, StatusKey.PROFILE_SAVE_FAILED))
            }
        }
    }

    fun clearQuickConnectDraft() {
        updateState { it.copy(quickConnectDraft = null) }
    }

    fun revokeKnownHost(key: String) {
        viewModelScope.launch {
            runCatching { knownHosts.remove(key); refreshStorage(); setStatus(StatusKey.HOST_TRUST_REVOKED) }
                .onFailure { setStatus(readableError(it, StatusKey.HOST_TRUST_REVOKE_FAILED)) }
        }
    }

    fun exportPortable(uri: android.net.Uri, passphrase: String, includeSecrets: Boolean = true, encryptAll: Boolean = true) {
        viewModelScope.launch {
            try {
                val protects = includeSecrets || encryptAll
                require(!protects || passphrase.length >= 8) { "export passphrase must be at least 8 characters" }
                val result = if (encryptAll) {
                    PortableExport.buildV2FromStorage(profiles, io.github.openfinalshell.android.storage.ForwardRepository(database.forwards()), groups, savedProxies, privateKeys, knownHosts, credentialStore, passphrase.toCharArray(), includeSecrets, tools = database.tools())
                } else {
                    PortableExport.buildV1FromStorage(profiles, io.github.openfinalshell.android.storage.ForwardRepository(database.forwards()), groups, savedProxies, privateKeys, knownHosts, credentialStore, includeSecrets, if (includeSecrets) passphrase.toCharArray() else null, tools = database.tools())
                }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(result.text.toByteArray(Charsets.UTF_8)) }
                    ?: error("unable to create export file")
                // Status is a single slot, so the withheld-session message states that the export
                // succeeded rather than replacing that confirmation with a caveat.
                if (result.localSessionsExcluded > 0) {
                    setStatus(StatusKey.LOCAL_SESSIONS_EXCLUDED, result.localSessionsExcluded)
                } else {
                    setStatus(StatusKey.EXPORT_COMPLETED)
                }
            } catch (error: Throwable) { setStatus(readableError(error, StatusKey.EXPORT_FAILED)) }
        }
    }

    fun stagePortableImport(uri: android.net.Uri, passphrase: String) {
        val generation = ++importGeneration
        portableImportState.value = PortableImportStage.Reading
        viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("unable to read import file")
                    PortableExport.readSource(PortableExport.parse(text), passphrase.takeIf { it.isNotEmpty() }?.toCharArray())
                }
                if (generation != importGeneration) return@launch
                val local = PortableExport.localIndex(
                    profiles, io.github.openfinalshell.android.storage.ForwardRepository(database.forwards()),
                    groups, savedProxies, privateKeys, knownHosts, database.tools()
                )
                stagedSource = source
                portableImportState.value = PortableImportStage.Ready(
                    source.catalog, local, source.invalid, source.rdpSkipped, source.materialRefsIgnored, source.secretsLocked, generation.toLong()
                )
            } catch (error: Throwable) {
                if (generation != importGeneration) return@launch
                stagedSource = null
                val passphraseFailure = generateSequence(error) { it.cause }.any { throwable ->
                    val message = throwable.message.orEmpty()
                    message.contains("passphrase", ignoreCase = true) || message.contains("decrypt", ignoreCase = true) || message.contains("mac", ignoreCase = true)
                }
                portableImportState.value = PortableImportStage.Failed(if (passphraseFailure) ImportFailure.Passphrase else ImportFailure.Read)
            }
        }
    }

    fun applyPortableImport(plan: ImportPlanResult) {
        val source = stagedSource ?: return
        viewModelScope.launch {
            try {
                val result = PortableExport.applyPlan(
                    source, plan, profiles, io.github.openfinalshell.android.storage.ForwardRepository(database.forwards()),
                    groups, savedProxies, privateKeys, knownHosts, credentialStore, database.tools()
                )
                clearPortableImport()
                refreshStorage()
                setStatus(StatusKey.IMPORT_COMPLETED, result.profiles, result.groups, result.proxies, result.notes.size)
            } catch (error: Throwable) { setStatus(readableError(error, StatusKey.IMPORT_FAILED)) }
        }
    }

    fun clearPortableImport() {
        importGeneration++
        stagedSource = null
        portableImportState.value = PortableImportStage.Idle
    }

    fun stageFinalShell(uri: android.net.Uri) {
        val generation = ++finalShellGeneration
        finalShellImportState.value = FinalShellImportStage.Reading
        viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { FinalShellScanner.scan(getApplication<Application>().contentResolver, uri) }
                if (generation != finalShellGeneration) return@launch
                val parsed = io.github.openfinalshell.android.core.workspace.FinalShellCatalog.parse(batch.records)
                val existing = profiles.list().filter { it.protocol != LOCAL_SHELL_PROTOCOL }.map { EndpointKey(it.host, it.port, it.username) }.toSet()
                finalShellImportState.value = if (batch.files == 0) FinalShellImportStage.Failed else FinalShellImportStage.Ready(parsed, existing, batch.files, generation.toLong())
            } catch (error: Throwable) {
                if (generation != finalShellGeneration) return@launch
                finalShellImportState.value = FinalShellImportStage.Failed
                setStatus(readableError(error, StatusKey.IMPORT_FAILED))
            }
        }
    }

    fun applyFinalShell(plan: FinalShellPlan) {
        viewModelScope.launch {
            try {
                orderedFinalShellGroups(plan.groups).forEach { row ->
                    groups.upsert(io.github.openfinalshell.android.storage.ConnectionGroupEntity(row.targetId, row.name, row.parentId, 0.0))
                }
                plan.connections.forEach { row ->
                    profiles.upsert(
                        ConnectionProfile(
                            id = row.targetId,
                            name = row.name,
                            host = row.host,
                            port = row.port,
                            username = row.username,
                            auth = ConnectionAuth("password"),
                            protocol = "ssh",
                            groupId = row.groupId,
                            note = row.note,
                            terminal = ConnectionTerminal(charset = row.charset),
                            options = ConnectionOptions(monitorEnabled = false)
                        )
                    )
                }
                clearFinalShellImport()
                refreshStorage()
                setStatus(StatusKey.IMPORT_COMPLETED, plan.connections.size, plan.groups.size, 0, plan.skipped.size)
            } catch (error: Throwable) { setStatus(readableError(error, StatusKey.IMPORT_FAILED)) }
        }
    }

    fun clearFinalShellImport() {
        finalShellGeneration++
        finalShellImportState.value = FinalShellImportStage.Idle
    }

    private fun orderedFinalShellGroups(rows: List<io.github.openfinalshell.android.core.workspace.PlannedFinalShellGroup>): List<io.github.openfinalshell.android.core.workspace.PlannedFinalShellGroup> {
        val byTarget = rows.associateBy { it.targetId }
        val emitted = mutableSetOf<String>()
        val ordered = mutableListOf<io.github.openfinalshell.android.core.workspace.PlannedFinalShellGroup>()
        fun visit(row: io.github.openfinalshell.android.core.workspace.PlannedFinalShellGroup) {
            if (!emitted.add(row.targetId)) return
            row.parentId?.let { byTarget[it] }?.let(::visit)
            if (row.targetId !in ordered.map { it.targetId }) ordered += row
        }
        rows.forEach(::visit)
        return ordered
    }

    fun selectProfile(profile: ConnectionProfile) {
        val existing = state.value.sessions.values
            .filter { it.profile.id == profile.id && it.state != SessionState.CLOSED }
            .maxByOrNull { it.sessionId }
        sessions.select(existing?.sessionId)
        updateState {
            it.copy(
                selectedProfileId = profile.id,
                selectedSessionId = existing?.sessionId,
                    status = existing?.let { item -> sessionStatus(item.state) }
                    ?: it.status
            )
        }
    }

    /** Selects an unclosed session for this connection. Does not open another session or touch last-used. */
    fun connect(profile: ConnectionProfile, password: String = "") {
        viewModelScope.launch {
            val existing = state.value.sessions.values
                .filter { it.profile.id == profile.id && it.state != SessionState.CLOSED }
                .maxByOrNull { it.sessionId }
            if (existing != null) {
                selectSession(existing.sessionId)
                return@launch
            }
            openNewSession(profile, password)
        }
    }

    /** Always opens a new session id. Last-used is updated only after the connection succeeds. */
    fun connectNew(profile: ConnectionProfile, password: String = "") {
        viewModelScope.launch { openNewSession(profile, password) }
    }

    fun duplicateSession(sessionId: String) {
        val profile = state.value.sessions[sessionId]?.profile ?: return
        connectNew(profile)
    }

    fun selectSession(sessionId: String) {
        val snapshot = state.value.sessions[sessionId] ?: return
        sessions.select(sessionId)
        val files = state.value.sessionFiles[sessionId]
        updateState {
            it.copy(
                selectedProfileId = snapshot.profile.id,
                selectedSessionId = sessionId,
                sftpPath = files?.confirmedPath ?: it.sftpPath,
                sftpEntries = it.sessionEntries[sessionId].orEmpty(),
                monitor = it.sessionMonitors[sessionId] ?: MonitorState(),
                status = sessionStatus(snapshot.state)
            )
        }
    }

    fun renameSession(sessionId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        updateState {
            val panels = it.sessionPanels[sessionId] ?: SessionPanels()
            it.copy(sessionPanels = it.sessionPanels + (sessionId to panels.copy(title = trimmed)))
        }
    }

    fun toggleSessionFiles(sessionId: String, compact: Boolean) {
        updateState {
            val panels = it.sessionPanels[sessionId] ?: SessionPanels()
            val open = !panels.filesOpen
            it.copy(sessionPanels = it.sessionPanels + (sessionId to panels.copy(
                filesOpen = open,
                monitorOpen = if (compact && open) false else panels.monitorOpen
            )))
        }
    }

    fun toggleSessionMonitor(sessionId: String, compact: Boolean) {
        updateState {
            val panels = it.sessionPanels[sessionId] ?: SessionPanels()
            val open = !panels.monitorOpen
            it.copy(sessionPanels = it.sessionPanels + (sessionId to panels.copy(
                monitorOpen = open,
                filesOpen = if (compact && open) false else panels.filesOpen
            )))
        }
    }

    fun closeSessionFiles(sessionId: String) {
        updateState {
            val panels = it.sessionPanels[sessionId] ?: SessionPanels()
            it.copy(sessionPanels = it.sessionPanels + (sessionId to panels.copy(filesOpen = false)))
        }
    }

    fun closeSessionMonitor(sessionId: String) {
        updateState {
            val panels = it.sessionPanels[sessionId] ?: SessionPanels()
            it.copy(sessionPanels = it.sessionPanels + (sessionId to panels.copy(monitorOpen = false)))
        }
    }

    fun reconnectSession(sessionId: String) {
        viewModelScope.launch {
            runCatching { sessions.reconnect(sessionId) }
                .onFailure { setStatus(readableError(it, StatusKey.CONNECTION_FAILED)) }
        }
    }

    private suspend fun openNewSession(profile: ConnectionProfile, password: String) {
        holdProcessForConnect()
        try {
            val effectiveProfile = when {
                profile.proxyMode == "direct" || profile.proxyMode == "none" -> profile.copy(proxy = null, proxyId = null)
                profile.proxyId != null -> {
                    val saved = requireNotNull(savedProxies.find(requireNotNull(profile.proxyId)))
                    profile.copy(proxy = ConnectionProxy(saved.type, saved.host, saved.port, saved.username, saved.passwordRef))
                }
                else -> profile
            }
            if (profile.proxyMode == "custom") require(effectiveProfile.proxy?.type in setOf("http", "socks5"))
            if (profile.protocol == LOCAL_SHELL_PROTOCOL) {
                val pending = probeLocalTier(profile)
                if (pending.resolution.tier == LocalTier.ROOT && profile.id !in confirmedRootProfiles) {
                    updateState {
                        it.copy(
                            localTiers = it.localTiers + (profile.id to pending.resolution),
                            rootConfirmation = RootConfirmation(profile.id, profile.name),
                            status = UiStatus(StatusKey.LOCAL_ROOT_CONFIRM)
                        )
                    }
                    return
                }
            }
            val sessionId = sessions.connect(
                effectiveProfile,
                Credentials(password = password.takeIf { it.isNotEmpty() }?.toCharArray())
            )
            profiles.touchLastUsed(profile.id)
            val initialPath = initialFilePath(profile)
            sessions.select(sessionId)
            updateState {
                it.copy(
                    selectedProfileId = profile.id,
                    selectedSessionId = sessionId,
                    sessionFiles = it.sessionFiles + (sessionId to SessionFileState(confirmedPath = initialPath)),
                    sessionEntries = it.sessionEntries + (sessionId to emptyList()),
                    sessionPanels = it.sessionPanels + (sessionId to SessionPanels()),
                    sftpPath = initialPath,
                    sftpEntries = emptyList(),
                    monitor = it.sessionMonitors[sessionId] ?: MonitorState(),
                    status = UiStatus(StatusKey.CONNECTED)
                )
            }
            refreshStorage()
            openShell(sessionId, 80, 24)
            probeDirectLatency(profile, sessionId)
            collectStaticInfo(sessionId)
        } catch (error: Throwable) {
            // Drop a stuck host-key dialog if the handshake already died (timeout / negotiate fail).
            pendingHostKey?.complete(false)
            setStatus(readableError(error, StatusKey.CONNECTION_FAILED))
        } finally {
            finishConnectAttempt()
        }
    }

    private fun initialFilePath(profile: ConnectionProfile): String =
        if (profile.protocol == LOCAL_SHELL_PROTOCOL) {
            LocalShellSpec.homeDirectory(getApplication()).path
        } else {
            "/"
        }

    fun startMonitoring(intervalSeconds: Int = transferSettings.monitorIntervalSeconds) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        startSessionMonitoring(sessionId, intervalSeconds)
    }

    /**
     * Starts, stops, or rebuilds this session's collector from [MonitorCollectionPolicy].
     * Panel collapse is passed through and does not change the result.
     */
    private fun applyMonitorDecision(sessionId: String, reconnected: Boolean) {
        val session = liveSession(sessionId) ?: return
        if (session.state == SessionState.CLOSED) {
            if (sessionId in monitors) stopSessionMonitor(sessionId)
            return
        }
        val enabled = state.value.profiles.firstOrNull { it.id == session.profile.id }?.options?.monitorEnabled
            ?: session.profile.options.monitorEnabled
        val collapsed = state.value.sessionPanels[sessionId]?.monitorOpen != true
        val decision = MonitorCollectionPolicy.decide(session.state, enabled, collapsed, reconnected)
        val monitor = monitorFor(sessionId)
        monitor.noteSource(sessionId)
        publishMonitor(sessionId, monitor.state.value)
        if (decision.stop) stopSessionMonitor(sessionId)
        if (decision.resetDelta) monitor.reset(sessionId)
        if (decision.start) {
            val keepRunning = !decision.resetDelta && state.value.sessionMonitors[sessionId]?.running == true
            if (!keepRunning) startSessionMonitoring(sessionId, transferSettings.monitorIntervalSeconds)
        }
    }

    private fun startSessionMonitoring(sessionId: String, intervalSeconds: Int) {
        val monitor = monitorFor(sessionId)
        val token = (monitorTokens[sessionId] ?: 0) + 1
        monitorTokens[sessionId] = token
        val interval = intervalSeconds.coerceIn(2, 60)
        monitorJobs.remove(sessionId)?.cancel()
        monitorJobs[sessionId] = viewModelScope.launch {
            val collectJob = launch {
                monitor.state.collect { value -> publishMonitor(sessionId, value) }
            }
            try {
                yield()
                if (token != monitorTokens[sessionId] || !isActive) return@launch
                monitor.start(sessionId, interval) { token == monitorTokens[sessionId] && isActive }
            } finally {
                collectJob.cancel()
            }
        }
    }

    private fun publishMonitor(sessionId: String, value: MonitorState) {
        updateState { current ->
            current.copy(
                sessionMonitors = current.sessionMonitors + (sessionId to value),
                monitor = if (current.selectedSessionId == sessionId) value else current.monitor
            )
        }
    }

    private fun collectStaticInfo(sessionId: String) {
        viewModelScope.launch {
            val monitor = monitorFor(sessionId)
            runCatching { monitor.collectStaticInfo(sessionId) }
                .onSuccess { publishMonitor(sessionId, monitor.state.value) }
                .onFailure { setStatus(readableError(it, StatusKey.SERVER_INFO_UNAVAILABLE)) }
        }
    }

    fun stopMonitoring() {
        val sessionId = state.value.selectedSessionId ?: return
        stopSessionMonitor(sessionId)
    }

    private fun stopSessionMonitor(sessionId: String) {
        monitorTokens[sessionId] = (monitorTokens[sessionId] ?: 0) + 1
        monitors[sessionId]?.stop()
        monitorJobs.remove(sessionId)?.cancel()
        val current = state.value.sessionMonitors[sessionId] ?: return
        publishMonitor(sessionId, current.copy(running = false))
    }

    private fun releaseSessionMonitor(sessionId: String) {
        stopSessionMonitor(sessionId)
        monitors.remove(sessionId)?.reset(sessionId)
        updateState { current ->
            current.copy(
                sessionMonitors = current.sessionMonitors - sessionId,
                monitor = if (current.selectedSessionId == sessionId) MonitorState() else current.monitor
            )
        }
    }

    fun refreshPortTraffic() {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        viewModelScope.launch {
            val monitor = monitorFor(sessionId)
            runCatching { monitor.collectPortTraffic(sessionId) }
                .onSuccess { publishMonitor(sessionId, monitor.state.value) }
                .onFailure { setStatus(readableError(it, StatusKey.PORT_TRAFFIC_FAILED)) }
        }
    }

    fun browseSftp(path: String = confirmedDirectory(state.value.selectedSessionId)) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        browseSftp(sessionId, path)
    }

    private fun browseSftp(sessionId: String, path: String) {
        if (sessionId !in state.value.sessionFiles) return
        val target = normalizeBrowse(confirmedDirectory(sessionId), path)
        val (pending, generation) = beginNavigation(fileState(sessionId), target)
        updateSessionFiles(sessionId, pending)
        setPendingDir(sessionId, target)
        loadListing(sessionId, target, generation, history = false, follow = false)
    }

    fun sftpBack() {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        val step = goBack(fileState(sessionId)) ?: return
        updateSessionFiles(sessionId, step.first)
        setPendingDir(sessionId, step.second)
        loadListing(sessionId, step.second, step.first.generation, history = true, follow = false)
    }

    fun sftpForward() {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        val step = goForward(fileState(sessionId)) ?: return
        updateSessionFiles(sessionId, step.first)
        setPendingDir(sessionId, step.second)
        loadListing(sessionId, step.second, step.first.generation, history = true, follow = false)
    }

    fun setFileSort(key: FileSortKey) {
        val sessionId = state.value.selectedSessionId ?: return
        val files = fileState(sessionId)
        val ascending = if (files.sortKey == key) !files.sortAscending else true
        val next = files.copy(sortKey = key, sortAscending = ascending)
        val entries = sortEntries(state.value.sessionEntries[sessionId].orEmpty(), next)
        updateSessionFiles(sessionId, next, entries)
    }

    fun toggleSftpSelection(path: String) {
        val sessionId = state.value.selectedSessionId ?: return
        val entry = state.value.sessionEntries[sessionId].orEmpty().firstOrNull { it.path == path } ?: return
        if (!RemoteName.canOperate(entry)) return
        val files = fileState(sessionId)
        val selected = if (path in files.selectedPaths) files.selectedPaths - path else files.selectedPaths + path
        updateSessionFiles(sessionId, files.copy(selectedPaths = selected))
    }

    fun clearSftpSelection() {
        val sessionId = state.value.selectedSessionId ?: return
        val files = fileState(sessionId)
        if (files.selectedPaths.isEmpty()) return
        updateSessionFiles(sessionId, files.copy(selectedPaths = emptySet()))
    }

    private fun loadListing(sessionId: String, path: String, generation: Long, history: Boolean, follow: Boolean) {
        val request = NavigationRequest(sessionId, path, generation)
        viewModelScope.launch {
            var channel: io.github.openfinalshell.android.core.ssh.SftpChannel? = null
            try {
                channel = sessions.openSftp(sessionId)
                val listed = channel.list(path).filterNot {
                    it.name == "." || it.name == ".." || (!transferSettings.sftpShowHiddenFiles && it.name.startsWith('.'))
                }
                val entries = sortEntries(listed, fileState(sessionId))
                val latest = fileState(sessionId)
                val applied = if (history) {
                    // goBack/goForward already moved the stacks. applyListing would push another
                    // entry and clear the opposite stack, so a matching generation only confirms the path.
                    if (latest.generation != request.generation) null
                    else latest.copy(confirmedPath = path, selectedPaths = emptySet())
                } else {
                    applyListing(latest, request, path)
                } ?: return@launch
                updateSessionFiles(sessionId, applied, entries)
                setPendingDir(sessionId, null)
                rememberAnchor(sessionId, channel, path)
                if (state.value.selectedSessionId == sessionId) {
                    setStatus(if (follow) UiStatus(StatusKey.FILE_CD_FOLLOWED, listOf(path)) else UiStatus(StatusKey.SFTP_READY))
                }
            } catch (error: Throwable) {
                if (fileState(sessionId).generation == request.generation) {
                    setPendingDir(sessionId, null)
                    if (state.value.selectedSessionId == sessionId) {
                        setStatus(if (follow) UiStatus(StatusKey.FILE_CD_LIST_FAILED) else readableError(error, StatusKey.SFTP_BROWSE_FAILED))
                    }
                }
            } finally {
                try { channel?.close() } catch (_: Throwable) { }
            }
        }
    }

    private fun fileState(sessionId: String): SessionFileState =
        state.value.sessionFiles[sessionId] ?: SessionFileState()

    private fun confirmedDirectory(sessionId: String?): String =
        sessionId?.let { fileState(it).confirmedPath }?.ifBlank { null }
            ?: state.value.sftpPath.ifBlank { "/" }

    private fun updateSessionFiles(sessionId: String, files: SessionFileState, entries: List<SftpEntry>? = null) {
        updateState { current ->
            val nextEntries = if (entries == null) current.sessionEntries else current.sessionEntries + (sessionId to entries)
            val selected = current.selectedSessionId == sessionId
            current.copy(
                sessionFiles = current.sessionFiles + (sessionId to files),
                sessionEntries = nextEntries,
                sftpPath = if (selected) files.confirmedPath else current.sftpPath,
                sftpEntries = if (selected) nextEntries[sessionId].orEmpty() else current.sftpEntries
            )
        }
    }

    fun deleteSftp(path: String, recursive: Boolean = false) {
        deleteSftpPaths(listOf(path), recursiveFor = mapOf(path to recursive))
    }

    fun deleteSelectedSftp() {
        val sessionId = state.value.selectedSessionId ?: return
        val selected = fileState(sessionId).selectedPaths
        val entries = state.value.sessionEntries[sessionId].orEmpty().filter { it.path in selected && RemoteName.canOperate(it) }
        if (entries.isEmpty()) return
        deleteSftpPaths(entries.map { it.path }, entries.associate { it.path to (it.type == SftpEntry.Type.DIRECTORY) })
    }

    private fun deleteSftpPaths(paths: List<String>, recursiveFor: Map<String, Boolean>) {
        val sessionId = state.value.selectedSessionId ?: return
        if (paths.any { !pathOperable(sessionId, it) }) return
        viewModelScope.launch {
            var channel: io.github.openfinalshell.android.core.ssh.SftpChannel? = null
            try {
                channel = sessions.openSftp(sessionId)
                for (path in paths) channel.delete(path, recursiveFor[path] == true)
                browseSftp(sessionId, confirmedDirectory(sessionId))
            } catch (error: Throwable) {
                setStatus(readableError(error, StatusKey.SFTP_DELETE_FAILED))
            } finally {
                try { channel?.close() } catch (_: Throwable) { }
            }
        }
    }

    fun requestFastDelete(paths: List<String>) {
        val sessionId = state.value.selectedSessionId ?: return
        val listed = directoryListing(sessionId, paths) ?: run {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.file_fast_delete_unavailable, fastDeleteCommand = null)
            return
        }
        val plan = try {
            FastDeletePlan.plan(listed)
        } catch (_: IllegalArgumentException) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.file_fast_delete_rejected, fastDeleteCommand = null)
            return
        }
        when (plan) {
            FastDeletePlan.Result.Unavailable ->
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.file_fast_delete_unavailable, fastDeleteCommand = null)
            is FastDeletePlan.Result.Ready ->
                mutablePortTransfer.value = mutablePortTransfer.value.copy(
                    fastDeleteCommand = plan.command,
                    fastDeletePaths = paths,
                    messageRes = null,
                )
        }
    }

    fun cancelFastDelete() {
        mutablePortTransfer.value = mutablePortTransfer.value.copy(fastDeleteCommand = null, fastDeletePaths = emptyList(), fastDeleteReport = null)
    }

    fun confirmFastDelete() {
        val sessionId = state.value.selectedSessionId ?: return
        val preview = mutablePortTransfer.value.fastDeleteCommand ?: return
        val paths = mutablePortTransfer.value.fastDeletePaths
        val listed = directoryListing(sessionId, paths) ?: run {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.file_fast_delete_unavailable, fastDeleteCommand = null)
            return
        }
        val command = try {
            FastDeletePlan.executeCommand(listed)
        } catch (_: IllegalArgumentException) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.file_fast_delete_rejected, fastDeleteCommand = null)
            return
        }
        if (command != preview) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.file_fast_delete_rejected, fastDeleteCommand = null)
            return
        }
        mutablePortTransfer.value = mutablePortTransfer.value.copy(fastDeleteCommand = null)
        viewModelScope.launch {
            var channel: io.github.openfinalshell.android.core.ssh.SftpChannel? = null
            try {
                val (code, output) = execCapture(sessionId, command)
                channel = sessions.openSftp(sessionId)
                val remaining = paths.filter { channel.lstat(it) != null }
                mutablePortTransfer.value = mutablePortTransfer.value.copy(
                    fastDeleteReport = io.github.openfinalshell.android.transfer.FastDeleteReport(code, output, remaining)
                )
                browseSftp(sessionId, confirmedDirectory(sessionId))
            } catch (error: Throwable) {
                setStatus(readableError(error, StatusKey.SFTP_DELETE_FAILED))
            } finally {
                try { channel?.close() } catch (_: Throwable) { }
            }
        }
    }

    fun applyRemotePermissions(path: String, requested: Int) {
        val sessionId = state.value.selectedSessionId ?: return
        val entry = state.value.sessionEntries[sessionId].orEmpty().firstOrNull { it.path == path } ?: return
        if (!RemoteName.canOperate(entry)) return
        val existing = entry.permissions ?: run {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.file_unavailable)
            return
        }
        val mode = PermissionBits.merge(existing, requested)
        mutateSftp(StatusKey.FILE_PERMISSIONS_UPDATED) { channel -> channel.permissions(path, mode) }
    }

    fun applyTransferSettings(settings: AndroidSettings) {
        val refresh = transferSettings.sftpShowHiddenFiles != settings.sftpShowHiddenFiles
        val rootGateChanged = transferSettings.localAllowRoot != settings.localAllowRoot
        val intervalChanged = transferSettings.monitorIntervalSeconds != settings.monitorIntervalSeconds
        transferSettings = settings
        transferQueue.setConcurrency(settings.sftpConcurrency)
        if (refresh && state.value.selectedSessionId != null) browseSftp()
        if (intervalChanged) {
            state.value.sessionMonitors.filterValues { it.running }.keys.toList().forEach { id ->
                startSessionMonitoring(id, settings.monitorIntervalSeconds)
            }
        }
        // The root gate changes what a card may offer, so the tiers have to be re-resolved.
        if (rootGateChanged) viewModelScope.launch { refreshLocalTiers() }
    }

    fun clearTransferMessage() { mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = null) }

    fun configuredDownloadTree(): Uri? = transferSettings.downloadDirectoryUri?.let(Uri::parse)

    fun setTransferDownloadTree(uri: Uri): Boolean = try {
        getApplication<Application>().contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        transferSettings = transferSettings.copy(downloadDirectoryUri = uri.toString())
        true
    } catch (_: Exception) {
        mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_permission_lost)
        false
    }

    fun resolveTransferConflict(policy: ConflictPolicy, applyToRest: Boolean) {
        conflictDecision?.complete(BatchChoice(policy, applyToRest))
    }

    private suspend fun askConflict(name: String): BatchChoice = conflictMutex.withLock {
        val decision = CompletableDeferred<BatchChoice>()
        conflictDecision = decision
        mutablePortTransfer.value = mutablePortTransfer.value.copy(conflictPath = name)
        try {
            decision.await()
        } finally {
            conflictDecision = null
            mutablePortTransfer.value = mutablePortTransfer.value.copy(conflictPath = null)
        }
    }

    private fun transferPreparation(sessionId: String, block: suspend () -> Unit) {
        val job = viewModelScope.launch {
            try { block() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: SecurityException) { mutablePortTransfer.value = mutablePortTransfer.value.copy(editorBusy = false, messageRes = R.string.port_permission_lost) }
            catch (_: Exception) { mutablePortTransfer.value = mutablePortTransfer.value.copy(editorBusy = false, messageRes = R.string.port_operation_failed) }
        }
        preparationJobs.getOrPut(sessionId) { mutableListOf() }.apply { removeAll { it.isCompleted }; add(job) }
    }

    private fun cancelSessionTransfers(sessionId: String) {
        preparationJobs.remove(sessionId)?.forEach(Job::cancel)
        transferSessions.filterValues { it == sessionId }.keys.toList().forEach { transferQueue.cancel(it); transferSessions.remove(it); archiveRetries.remove(it) }
        if (editorSessionId == sessionId) {
            editorSessionId = null
            mutablePortTransfer.value = mutablePortTransfer.value.copy(editorBusy = false, messageRes = R.string.port_session_closed)
        }
    }

    fun uploadDocuments(uris: List<Uri>, directory: Boolean = false) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        val remoteDirectory = confirmedDirectory(sessionId)
        val batch = TransferBatch()
        transferPreparation(sessionId) {
            val channel = sessions.openSftp(sessionId)
            var count = 0
            suspend fun upload(entry: SafEntry, parent: String, depth: Int) {
                check(++count <= 10_000 && depth <= 64)
                val name = SafDocuments.safeName(entry.name)
                val listing = channel.list(parent).map { it.name to nodeKind(it) }
                val sourceKind = if (entry.directory) NodeKind.DIRECTORY else NodeKind.FILE
                when (val resolution = resolvePlacement(batch, parent, name, sourceKind, listing)) {
                    TransferConflictPlan.Resolution.Skip,
                    is TransferConflictPlan.Resolution.Ask -> return
                    is TransferConflictPlan.Resolution.TypeConflict -> {
                        setStatus(StatusKey.FILE_TYPE_CONFLICT, name)
                        return
                    }
                    is TransferConflictPlan.Resolution.Use,
                    is TransferConflictPlan.Resolution.Overwrite,
                    is TransferConflictPlan.Resolution.Rename -> {
                        val finalName = if (resolution is TransferConflictPlan.Resolution.Rename) resolution.name else name
                        val target = resolveChild(parent, finalName)
                        if (entry.directory) {
                            if (resolution !is TransferConflictPlan.Resolution.Overwrite) channel.mkdir(target)
                            for (child in withContext(Dispatchers.IO) { documents.children(entry.uri) }) upload(child, target, depth + 1)
                        } else {
                            val source = withContext(Dispatchers.IO) { documents.source(entry) }
                            val id = transferQueue.enqueueUpload(target, source, channelProvider = { sessions.openSftp(sessionId) }, localPath = entry.uri.toString())
                            rememberTransfer(id, sessionId)
                        }
                    }
                }
            }
            try {
                for (uri in uris) {
                    val actual = if (directory) documents.root(uri) else uri
                    val entry = withContext(Dispatchers.IO) { documents.info(actual) }
                    upload(entry, remoteDirectory, 0)
                }
                setStatus(StatusKey.UPLOAD_QUEUED)
            } finally { channel.close() }
        }
    }

    fun downloadDocument(remotePath: String, treeUri: Uri? = null) = downloadDocuments(listOf(remotePath), treeUri)

    fun downloadDocuments(remotePaths: List<String>, treeUri: Uri? = null) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        val tree = treeUri ?: transferSettings.downloadDirectoryUri?.let(Uri::parse)
        if (tree == null) { mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_choose_download); return }
        val operable = remotePaths.filter { pathOperable(sessionId, it) }
        if (operable.isEmpty()) return
        val batch = TransferBatch()
        transferPreparation(sessionId) {
            val channel = sessions.openSftp(sessionId)
            var count = 0
            suspend fun download(entry: SftpEntry, parent: Uri, depth: Int) {
                check(++count <= 10_000 && depth <= 64)
                if (!RemoteName.canOperate(entry)) return
                if (entry.type != SftpEntry.Type.FILE && entry.type != SftpEntry.Type.DIRECTORY) return
                val name = SafDocuments.safeName(entry.name)
                val isDirectory = entry.type == SftpEntry.Type.DIRECTORY
                val children = withContext(Dispatchers.IO) { documents.children(parent) }
                val listing = children.map { it.name to if (it.directory) NodeKind.DIRECTORY else NodeKind.FILE }
                when (val resolution = resolvePlacement(batch, parent.toString(), name, if (isDirectory) NodeKind.DIRECTORY else NodeKind.FILE, listing)) {
                    TransferConflictPlan.Resolution.Skip,
                    is TransferConflictPlan.Resolution.Ask -> return
                    is TransferConflictPlan.Resolution.TypeConflict -> {
                        setStatus(StatusKey.FILE_TYPE_CONFLICT, name)
                        return
                    }
                    is TransferConflictPlan.Resolution.Use,
                    is TransferConflictPlan.Resolution.Overwrite,
                    is TransferConflictPlan.Resolution.Rename -> {
                        val finalName = if (resolution is TransferConflictPlan.Resolution.Rename) resolution.name else name
                        val existing = children.firstOrNull { it.name == finalName }
                        val destination = existing?.uri ?: withContext(Dispatchers.IO) { documents.create(parent, finalName, isDirectory) }
                        if (isDirectory) {
                            for (child in channel.list(entry.path).filterNot { it.name == "." || it.name == ".." }) download(child, destination, depth + 1)
                        } else {
                            val sink = withContext(Dispatchers.IO) { documents.sink(destination) }
                            val id = transferQueue.enqueueDownload(entry.path, sink, channelProvider = { sessions.openSftp(sessionId) },
                                bytesTotal = entry.size ?: -1, localPath = destination.toString())
                            rememberTransfer(id, sessionId)
                        }
                    }
                }
            }
            try {
                for (remotePath in operable) {
                    val entry = channel.list(parentPath(remotePath)).first { it.path == remotePath }
                    download(entry, documents.root(tree), 0)
                }
                setStatus(StatusKey.DOWNLOAD_QUEUED)
            } finally { channel.close() }
        }
    }

    fun downloadSelected() {
        val sessionId = state.value.selectedSessionId ?: return
        val selected = fileState(sessionId).selectedPaths
        val paths = state.value.sessionEntries[sessionId].orEmpty()
            .filter { it.path in selected && RemoteName.canOperate(it) }
            .map { it.path }
        if (paths.isEmpty()) return
        downloadDocuments(paths)
    }

    private suspend fun execTransfer(sessionId: String, command: String): String = kotlinx.coroutines.withTimeout(120_000L) {
        val channel = sessions.openExec(sessionId, command)
        try {
            val output = StringBuilder()
            channel.output.collect { bytes -> check(output.length + bytes.size <= 16 * 1024); output.append(bytes.toString(Charsets.UTF_8)) }
            check(channel.exitCode.value == 0)
            output.toString().trim()
        } finally { channel.close() }
    }

    /** Pack a SAF directory without following links; existing remote roots use normal conflict-aware SFTP. */
    /**
     * Probes what this device offers and picks the tier for a profile's request.
     *
     * Not cached across connects: root disappears when the device reboots, Shizuku stops with it, and
     * a permission can be revoked — so a cached answer would mean the tier badge lying about what
     * the shell actually is.
     */
    private suspend fun probeLocalTier(profile: ConnectionProfile): PendingLocalTier {
        val availability = localPrivilege.probe(transferSettings.localAllowRoot)
        val resolution = localPrivilege.resolve(profile.local?.tier ?: LocalShellTier.AUTO, availability)
        return PendingLocalTier(profile.id, availability, resolution).also { pendingLocalTier = it }
    }

    private suspend fun createLocalTransport(profile: ConnectionProfile): SshTransport {
        val pending = pendingLocalTier?.takeIf { it.profileId == profile.id }?.also { pendingLocalTier = null }
            ?: probeLocalTier(profile)
        updateState { it.copy(localTiers = it.localTiers + (profile.id to pending.resolution)) }
        val settings = transferSettings
        val spec = LocalShellSpec.forProfile(
            context = getApplication(),
            startDirectory = profile.local?.startDirectory,
            transcriptRows = ScrollbackLimits.forEmulator(settings.terminalScrollbackLines),
            termType = profile.terminal.termType
        )
        return when (pending.resolution.tier) {
            // The app tier runs in this process: no host, no socket, and Termux's session owns both
            // the PTY and the emulator.
            LocalTier.APP -> LocalShellTransport(spec, appTierRoots(getApplication()), viewModelScope)
            LocalTier.ADB, LocalTier.ROOT -> LocalHostTransport(
                launcher = localPrivilege.launcherFor(pending.resolution.tier, pending.availability),
                config = LocalHostConfig(
                    shellPath = spec.shellPath,
                    workingDirectory = spec.workingDirectory,
                    environment = spec.environment,
                    roots = privilegedTierRoots(pending.resolution.tier),
                    mounts = withContext(Dispatchers.IO) { LocalMounts.read() }
                ),
                scope = viewModelScope
            )
        }
    }

    /**
     * Asks Shizuku to grant its API permission.
     *
     * Checking for the grant is not enough to use it: Shizuku shows its own dialog, so without a
     * request the tier can never become available.
     */
    fun requestShizukuPermission() {
        runCatching {
            localPrivilege.requestShizukuPermission { granted ->
                viewModelScope.launch {
                    refreshLocalTiers()
                    // On a grant the card's blocked note simply disappears, which is the feedback.
                    if (!granted) setStatus(StatusKey.SHIZUKU_NO_PERMISSION)
                }
            }
        }.onFailure { setStatus(readableError(it, StatusKey.CONNECTION_FAILED)) }
    }

    /**
     * Re-resolves the tier for every local profile.
     *
     * Not cached across calls on purpose: root disappears when the device reboots, Shizuku stops
     * with it, and a permission can be revoked — a stale answer would mean the card lying about
     * what connecting would actually do.
     */
    private suspend fun refreshLocalTiers() {
        val locals = state.value.profiles.filter { it.protocol == LOCAL_SHELL_PROTOCOL }
        if (locals.isEmpty()) return
        val availability = localPrivilege.probe(transferSettings.localAllowRoot)
        val updated = state.value.localTiers.toMutableMap()
        locals.forEach { profile ->
            updated[profile.id] = localPrivilege.resolve(profile.local?.tier ?: LocalShellTier.AUTO, availability)
        }
        updateState { it.copy(localTiers = updated) }
    }

    /** Declines a pending root confirmation. Nothing was launched, so there is nothing to tear down. */
    fun cancelRootTier() {
        updateState { it.copy(rootConfirmation = null) }
    }

    /** Records the confirmation and retries the connection that was held back for it. */
    fun confirmRootTier() {
        val pending = state.value.rootConfirmation ?: return
        confirmedRootProfiles += pending.profileId
        updateState { it.copy(rootConfirmation = null) }
        state.value.profiles.firstOrNull { it.id == pending.profileId }?.let { connect(it) }
    }

    /**
     * True when a session would write into a root shell whose confirmation has gone away.
     *
     * Checked inside the coroutine that performs the write, so a confirmation withdrawn between the
     * tap and the write cannot be bypassed — the same shape as the existing re-check for a replaced
     * terminal controller.
     */
    private fun isRootTierUnconfirmed(sessionId: String): Boolean {
        val profile = state.value.sessions[sessionId]?.profile ?: return false
        if (profile.protocol != LOCAL_SHELL_PROTOCOL) return false
        if (state.value.localTiers[profile.id]?.tier != LocalTier.ROOT) return false
        return profile.id !in confirmedRootProfiles
    }

    /**
     * True when the selected session runs on this device rather than on a server.
     *
     * Used for the capabilities that only make sense across a link — packed transfer, latency
     * probing — rather than for anything the transport itself already answers.
     */
    private fun selectedSessionIsLocal(): Boolean =
        state.value.selectedSessionId
            ?.let { state.value.sessions[it]?.profile?.protocol }
            ?.let { it == LOCAL_SHELL_PROTOCOL } == true

    /**
     * Whether the packed-transfer actions should be offered for the selected session.
     *
     * Packed transfer collapses round-trips on a high-latency link and needs a remote `/tmp` plus
     * `mktemp -d`; a local session is not a link, and Android has no `/tmp`. The UI hides the
     * actions and this class refuses them, so neither is the only line of defence.
     */
    fun packedTransferAvailable(): Boolean = !selectedSessionIsLocal()

    fun uploadPackedDirectory(tree: Uri) {
        val sessionId = state.value.selectedSessionId ?: return
        if (selectedSessionIsLocal()) return setStatus(StatusKey.PACKED_TRANSFER_UNAVAILABLE)
        val remoteDirectory = confirmedDirectory(sessionId)
        transferPreparation(sessionId) {
            var remoteTemp: String? = null
            val cache = withContext(Dispatchers.IO) { File.createTempFile("ofs-pack-", ".tar", getApplication<Application>().cacheDir) }
            var queued = false
            suspend fun cleanup() = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                cache.delete()
                remoteTemp?.let { path -> runCatching {
                    val channel = sessions.openSftp(sessionId)
                    try { channel.delete(path, recursive = true) } finally { channel.close() }
                } }
            }
            try {
                val root = withContext(Dispatchers.IO) { documents.info(documents.root(tree)) }
                require(root.directory)
                val entries = mutableListOf<Pair<String, SafEntry>>()
                var bytesTotal = 1024L
                suspend fun enumerate(path: String, entry: SafEntry, depth: Int) {
                    require(depth < 64 && entries.size < 10_000)
                    SafeTar.header(path, entry.size.coerceAtLeast(0), entry.directory)
                    if (!entry.directory) require(entry.size >= 0)
                    entries += path to entry
                    bytesTotal += 512 + (if (entry.directory) 0 else ((entry.size + 511) / 512) * 512)
                    require(bytesTotal <= 32L * 1024 * 1024 * 1024)
                    if (entry.directory) for (child in withContext(Dispatchers.IO) { documents.children(entry.uri) }) enumerate("$path/${child.name}", child, depth + 1)
                }
                enumerate(root.name, root, 0)
                require(cache.parentFile!!.usableSpace > bytesTotal + 64L * 1024 * 1024)
                val checkChannel = sessions.openSftp(sessionId)
                try { require(checkChannel.list(remoteDirectory).none { it.name == root.name }) }
                finally { checkChannel.close() }
                check(execTransfer(sessionId, "command -v tar >/dev/null && command -v mktemp >/dev/null && printf OFS_OK") == "OFS_OK")
                val freeKb = execTransfer(sessionId, "df -Pk ${SafeTar.quote(remoteDirectory)} | awk 'NR==2 {print \$4}'").toLong()
                require(freeKb * 1024 > bytesTotal * 2 + 64L * 1024 * 1024)
                val temp = execTransfer(sessionId, "mktemp -d ${SafeTar.quote(resolveChild(remoteDirectory, ".ofs-pack-XXXXXXXX"))}")
                require(parentPath(temp) == remoteDirectory.trimEnd('/').ifEmpty { "/" } && temp.substringAfterLast('/').startsWith(".ofs-pack-"))
                remoteTemp = temp
                withContext(Dispatchers.IO) {
                    cache.outputStream().buffered().use { output ->
                        for ((path, entry) in entries) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            output.write(SafeTar.header(path, entry.size.coerceAtLeast(0), entry.directory))
                            if (!entry.directory) {
                                val source = documents.source(entry)
                                try {
                                    var offset = 0L
                                    while (offset < entry.size) {
                                        val data = source.read(offset, minOf(32 * 1024L, entry.size - offset).toInt())
                                        require(data.isNotEmpty())
                                        output.write(data); offset += data.size
                                    }
                                    output.write(ByteArray(((512 - entry.size % 512) % 512).toInt()))
                                } finally { source.close() }
                            }
                        }
                        output.write(ByteArray(1024))
                    }
                    SafeTar.entries(cache)
                }
                val source = object : TransferSource {
                    private val file = FileTransferSource(cache)
                    override val size = cache.length()
                    override suspend fun read(offset: Long, maxBytes: Int) = file.read(offset, maxBytes)
                    override suspend fun abort() { cleanup() }
                    override suspend fun complete() {
                        try {
                            execTransfer(sessionId, "mkdir ${SafeTar.quote("$temp/payload")} && tar -xf ${SafeTar.quote("$temp/files.tar")} -C ${SafeTar.quote("$temp/payload")}")
                            val channel = sessions.openSftp(sessionId)
                            try {
                                require(channel.list(remoteDirectory).none { it.name == root.name })
                                channel.rename("$temp/payload/${root.name}", resolveChild(remoteDirectory, root.name))
                            } finally { channel.close() }
                        } finally { cleanup() }
                    }
                }
                val id = transferQueue.enqueueUpload("$temp/files.tar", source, channelProvider = { sessions.openSftp(sessionId) }, localPath = tree.toString())
                rememberTransfer(id, sessionId)
                archiveRetries[id] = { if (state.value.selectedSessionId == sessionId) uploadPackedDirectory(tree) }
                queued = true
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_pack_fallback) }
            finally { if (!queued) cleanup() }
        }
    }

    /** Transfer the archive through the same retryable queue, validate it fully, then materialize SAF files. */
    fun downloadPackedDirectory(remotePath: String) {
        val sessionId = state.value.selectedSessionId ?: return
        if (!pathOperable(sessionId, remotePath)) return
        if (selectedSessionIsLocal()) return setStatus(StatusKey.PACKED_TRANSFER_UNAVAILABLE)
        val tree = configuredDownloadTree() ?: run {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_choose_download); return
        }
        val batch = TransferBatch()
        transferPreparation(sessionId) {
            var remoteTemp: String? = null
            val cache = withContext(Dispatchers.IO) { File.createTempFile("ofs-pack-", ".tar", getApplication<Application>().cacheDir) }
            var queued = false
            suspend fun cleanup() = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                cache.delete()
                remoteTemp?.let { path -> runCatching {
                    val channel = sessions.openSftp(sessionId)
                    try { channel.delete(path, recursive = true) } finally { channel.close() }
                } }
            }
            suspend fun exec(command: String): String = kotlinx.coroutines.withTimeout(120_000L) {
                val channel = sessions.openExec(sessionId, command)
                try {
                    val output = StringBuilder()
                    channel.output.collect { bytes -> check(output.length + bytes.size <= 16 * 1024); output.append(bytes.toString(Charsets.UTF_8)) }
                    check(channel.exitCode.value == 0)
                    output.toString().trim()
                } finally { channel.close() }
            }
            try {
                require(remotePath.startsWith('/') && remotePath != "/")
                val channel = sessions.openSftp(sessionId)
                var size = 1024L
                var count = 0
                suspend fun inspect(path: String, depth: Int) {
                    require(depth <= 64)
                    for (entry in channel.list(path).filterNot { it.name == "." || it.name == ".." }) {
                        require(++count <= 10_000 && entry.type in setOf(SftpEntry.Type.FILE, SftpEntry.Type.DIRECTORY))
                        SafeTar.safePath(entry.name)
                        size += (((entry.size ?: 0) + 511) / 512) * 512 + 512
                        require(size in 0..(32L * 1024 * 1024 * 1024))
                        if (entry.type == SftpEntry.Type.DIRECTORY) inspect(entry.path, depth + 1)
                    }
                }
                try { inspect(remotePath, 0) } finally { channel.close() }
                check(cache.parentFile!!.usableSpace > size + 64L * 1024 * 1024)
                check(exec("command -v tar >/dev/null && command -v mktemp >/dev/null && printf OFS_OK") == "OFS_OK")
                val freeKb = exec("df -Pk /tmp | awk 'NR==2 {print \$4}'").toLong()
                check(freeKb * 1024 > size + 64L * 1024 * 1024)
                val directory = exec("mktemp -d /tmp/ofs-pack.XXXXXXXX")
                check(Regex("^/tmp/ofs-pack\\.[A-Za-z0-9]+$").matches(directory))
                remoteTemp = directory
                val archive = "$directory/files.tar"
                exec("tar --format=ustar -cf ${SafeTar.quote(archive)} -C ${SafeTar.quote(parentPath(remotePath))} -- ${SafeTar.quote(remotePath.substringAfterLast('/'))}")
                val metadata = sessions.openSftp(sessionId)
                val archiveSize = try { metadata.list(directory).first { it.name == "files.tar" }.size!! } finally { metadata.close() }
                require(archiveSize <= cache.parentFile!!.usableSpace - 64L * 1024 * 1024)
                val fileSink = FileTransferSink(cache)
                val sink = object : TransferSink {
                    override suspend fun reset() = fileSink.reset()
                    override suspend fun write(offset: Long, data: ByteArray) = fileSink.write(offset, data)
                    override suspend fun abort() { fileSink.abort(); cleanup() }
                    override suspend fun complete(totalBytes: Long) {
                        fileSink.complete(totalBytes)
                        val entries = withContext(Dispatchers.IO) { SafeTar.entries(cache) }
                        val directories = mutableMapOf("" to documents.root(tree))
                        try {
                            for (entry in entries.sortedWith(compareBy({ it.path.count { c -> c == '/' } }, { !it.directory }))) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val parent = directories[entry.path.substringBeforeLast('/', "")] ?: error("archive parent is absent")
                                val name = entry.path.substringAfterLast('/')
                                val children = withContext(Dispatchers.IO) { documents.children(parent) }
                                val listing = children.map { child -> child.name to if (child.directory) NodeKind.DIRECTORY else NodeKind.FILE }
                                val sourceKind = if (entry.directory) NodeKind.DIRECTORY else NodeKind.FILE
                                val resolution = resolvePlacement(batch, parent.toString(), name, sourceKind, listing)
                                val finalName = when (resolution) {
                                    TransferConflictPlan.Resolution.Skip,
                                    is TransferConflictPlan.Resolution.Ask,
                                    is TransferConflictPlan.Resolution.TypeConflict -> {
                                        if (resolution is TransferConflictPlan.Resolution.TypeConflict) setStatus(StatusKey.FILE_TYPE_CONFLICT, name)
                                        continue
                                    }
                                    is TransferConflictPlan.Resolution.Rename -> resolution.name
                                    else -> name
                                }
                                val previous = children.firstOrNull { it.name == finalName }
                                val uri = previous?.uri ?: withContext(Dispatchers.IO) { documents.create(parent, finalName, entry.directory) }
                                if (entry.directory) directories[entry.path] = uri else {
                                    val destination = withContext(Dispatchers.IO) { documents.sink(uri) }
                                    try {
                                        destination.reset()
                                        var offset = 0L
                                        while (offset < entry.size) {
                                            val data = withContext(Dispatchers.IO) {
                                                RandomAccessFile(cache, "r").use { input -> input.seek(entry.offset + offset)
                                                    ByteArray(minOf(32 * 1024L, entry.size - offset).toInt()).also(input::readFully) }
                                            }
                                            destination.write(offset, data)
                                            offset += data.size
                                        }
                                        destination.complete(entry.size)
                                    } catch (error: Throwable) { destination.abort(); throw error }
                                }
                            }
                        } finally { cleanup() }
                    }
                }
                val id = transferQueue.enqueueDownload(archive, sink, channelProvider = { sessions.openSftp(sessionId) }, bytesTotal = archiveSize, localPath = tree.toString())
                rememberTransfer(id, sessionId)
                archiveRetries[id] = { if (state.value.selectedSessionId == sessionId) downloadPackedDirectory(remotePath) }
                queued = true
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_pack_fallback) }
            finally { if (!queued) cleanup() }
        }
    }

    fun chooseRemoteEditorEncoding(id: String) {
        val encoding = RemoteTextEncoding.entries.firstOrNull { it.id == id }
        if (encoding == null || !RemoteTextEditor.isEncodingAvailable(encoding)) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_encoding_unavailable)
            return
        }
        val editor = mutablePortTransfer.value.editor
        if (editor == null) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(editorEncoding = id)
            return
        }
        if (mutablePortTransfer.value.creating) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(editor = editor.copy(encoding = id), editorEncoding = id)
            return
        }
        if (id == editor.encoding) return
        if (mutablePortTransfer.value.editorText != editor.text) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(pendingEncoding = id)
            return
        }
        rereadRemoteEditor(id)
    }

    fun openRemoteEditor(path: String, encoding: String = mutablePortTransfer.value.editorEncoding, force: Boolean = false) {
        if (mutablePortTransfer.value.editorBusy) return
        val current = mutablePortTransfer.value.editor
        val source = if (force && editorSessionId != null && current?.path == path) editorSessionId else state.value.selectedSessionId
        if (source == null) return
        if (!force && current != null && mutablePortTransfer.value.editorText != current.text &&
            (current.path != path || editorSessionId != source)
        ) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(pendingOpenPath = path, pendingOpenEncoding = encoding)
            return
        }
        if (!pathOperable(source, path) && state.value.sessionEntries[source].orEmpty().any { it.path == path }) return
        editorSessionId = source
        resetEditorRelease()
        mutablePortTransfer.value = mutablePortTransfer.value.copy(
            editorBusy = true, editorConflict = false, conflictDeleted = false, conflictFingerprint = null,
            creating = false, alreadyExists = false, retargeted = false, editorSessionId = source, messageRes = null,
        )
        transferPreparation(source) {
            val channel = sessions.openSftp(source)
            try {
                val document = RemoteTextEditor.load(channel, path, encoding)
                if (editorSessionId == source) {
                    mutablePortTransfer.value = mutablePortTransfer.value.copy(
                        editor = document, editorText = document.text, editorEncoding = document.encoding, creating = false,
                    )
                }
            } catch (_: RemoteTextEncodingUnavailable) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_encoding_unavailable)
            } catch (_: RemoteTextEncodingFailure) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_encoding_lossless)
            } catch (_: RemoteTextBinary) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_editor_binary)
            } finally { channel.close(); mutablePortTransfer.value = mutablePortTransfer.value.copy(editorBusy = false) }
        }
    }

    fun beginNewRemoteFile(name: String) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        val path = try {
            resolveChild(confirmedDirectory(sessionId), name)
        } catch (_: IllegalArgumentException) {
            setStatus(StatusKey.SFTP_OPERATION_FAILED)
            return
        }
        val current = mutablePortTransfer.value.editor
        if (current != null && mutablePortTransfer.value.editorText != current.text) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(pendingNewFileName = name)
            return
        }
        openNewDraft(sessionId, path)
    }

    fun renameRemoteDraft(name: String) {
        val sessionId = editorSessionId ?: return
        val current = mutablePortTransfer.value.editor ?: return
        if (!mutablePortTransfer.value.creating) return
        val path = try {
            resolveChild(parentPath(current.path), name)
        } catch (_: IllegalArgumentException) {
            setStatus(StatusKey.SFTP_OPERATION_FAILED)
            return
        }
        mutablePortTransfer.value = mutablePortTransfer.value.copy(
            editor = current.copy(path = path, resolvedPath = path),
            alreadyExists = false,
            editorSessionId = sessionId,
        )
    }

    fun openExistingDraftTarget() {
        val path = mutablePortTransfer.value.editor?.path ?: return
        val encoding = mutablePortTransfer.value.editor?.encoding ?: mutablePortTransfer.value.editorEncoding
        mutablePortTransfer.value = mutablePortTransfer.value.copy(alreadyExists = false, creating = false)
        openRemoteEditor(path, encoding, force = true)
    }

    fun dismissAlreadyExists() {
        mutablePortTransfer.value = mutablePortTransfer.value.copy(alreadyExists = false)
    }

    fun editRemoteText(text: String) { mutablePortTransfer.value = mutablePortTransfer.value.copy(editorText = text) }

    fun closeRemoteEditor() {
        if (mutablePortTransfer.value.editorBusy) return
        editorSessionId = null
        resetEditorRelease()
        mutablePortTransfer.value = mutablePortTransfer.value.copy(
            editor = null, editorText = "", editorSessionId = null, creating = false,
            nonAtomicConfirmation = false, editorConflict = false, conflictDeleted = false,
            conflictFingerprint = null, shrinkRemoteBytes = null, shrinkLocalBytes = null,
            alreadyExists = false, retargeted = false, pendingEncoding = null,
            pendingOpenPath = null, pendingOpenEncoding = null, pendingNewFileName = null,
        )
    }

    fun confirmEditorDiscard() {
        val pendingEncoding = mutablePortTransfer.value.pendingEncoding
        val pendingPath = mutablePortTransfer.value.pendingOpenPath
        val pendingOpenEncoding = mutablePortTransfer.value.pendingOpenEncoding ?: mutablePortTransfer.value.editorEncoding
        val pendingName = mutablePortTransfer.value.pendingNewFileName
        mutablePortTransfer.value = mutablePortTransfer.value.copy(
            pendingEncoding = null, pendingOpenPath = null, pendingOpenEncoding = null, pendingNewFileName = null,
        )
        when {
            pendingEncoding != null -> rereadRemoteEditor(pendingEncoding)
            pendingPath != null -> openRemoteEditor(pendingPath, pendingOpenEncoding, force = true)
            pendingName != null -> {
                val sessionId = state.value.selectedSessionId ?: return
                val path = try {
                    resolveChild(confirmedDirectory(sessionId), pendingName)
                } catch (_: IllegalArgumentException) {
                    setStatus(StatusKey.SFTP_OPERATION_FAILED)
                    return
                }
                openNewDraft(sessionId, path)
            }
        }
    }

    fun cancelEditorDiscard() {
        mutablePortTransfer.value = mutablePortTransfer.value.copy(
            pendingEncoding = null, pendingOpenPath = null, pendingOpenEncoding = null, pendingNewFileName = null,
        )
    }

    fun cancelNonAtomicSave() { mutablePortTransfer.value = mutablePortTransfer.value.copy(nonAtomicConfirmation = false) }

    fun dismissEditorConflict() {
        mutablePortTransfer.value = mutablePortTransfer.value.copy(editorConflict = false, conflictDeleted = false, conflictFingerprint = null)
    }

    fun confirmEditorOverwrite() {
        val acknowledged = if (mutablePortTransfer.value.conflictDeleted) {
            AcknowledgedRemote.Absent
        } else {
            mutablePortTransfer.value.conflictFingerprint?.let(AcknowledgedRemote::Content) ?: return
        }
        editorRelease = editorRelease.copy(overwriteRemoteChanges = true, acknowledged = acknowledged)
        mutablePortTransfer.value = mutablePortTransfer.value.copy(editorConflict = false)
        saveRemoteEditor()
    }

    fun confirmEditorShrink() {
        editorRelease = editorRelease.copy(allowShrink = true)
        mutablePortTransfer.value = mutablePortTransfer.value.copy(shrinkRemoteBytes = null, shrinkLocalBytes = null)
        saveRemoteEditor()
    }

    fun cancelEditorShrink() {
        mutablePortTransfer.value = mutablePortTransfer.value.copy(shrinkRemoteBytes = null, shrinkLocalBytes = null)
    }

    fun confirmEditorNonAtomic() {
        editorRelease = editorRelease.copy(allowNonAtomic = true)
        mutablePortTransfer.value = mutablePortTransfer.value.copy(nonAtomicConfirmation = false)
        saveRemoteEditor()
    }

    fun dismissRetarget() {
        mutablePortTransfer.value = mutablePortTransfer.value.copy(retargeted = false)
    }

    fun saveRemoteEditor() {
        val sessionId = editorSessionId ?: return
        val original = mutablePortTransfer.value.editor ?: return
        if (mutablePortTransfer.value.editorBusy) return
        val text = mutablePortTransfer.value.editorText
        val creating = mutablePortTransfer.value.creating
        val release = editorRelease
        mutablePortTransfer.value = mutablePortTransfer.value.copy(editorBusy = true, nonAtomicConfirmation = false)
        transferPreparation(sessionId) {
            val channel = sessions.openSftp(sessionId)
            try {
                val saved = if (creating) {
                    if (channel.lstat(original.path) != null) {
                        mutablePortTransfer.value = mutablePortTransfer.value.copy(alreadyExists = true)
                        return@transferPreparation
                    }
                    try {
                        RemoteTextEditor.create(channel, original.path, text, original.encoding, original.eol, original.hasBom)
                    } catch (error: Throwable) {
                        if (channel.lstat(original.path) != null) {
                            mutablePortTransfer.value = mutablePortTransfer.value.copy(alreadyExists = true)
                            return@transferPreparation
                        }
                        throw error
                    }
                } else {
                    RemoteTextEditor.save(channel, original, text, release = release)
                }
                val permissions = channel.lstat(saved.path)?.permissions ?: saved.permissions
                val document = saved.copy(permissions = permissions)
                if (editorSessionId == sessionId) {
                    resetEditorRelease()
                    mutablePortTransfer.value = mutablePortTransfer.value.copy(
                        editor = document, editorText = document.text, creating = false,
                        editorConflict = false, alreadyExists = false, messageRes = R.string.port_saved,
                    )
                }
            } catch (_: RemoteTextEncodingUnavailable) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_encoding_unavailable)
            } catch (_: RemoteTextEncodingFailure) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_encoding_lossless)
            } catch (_: IllegalArgumentException) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.file_save_rejected)
            } catch (_: AtomicReplaceUnavailable) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(nonAtomicConfirmation = true)
            } catch (error: RemoteTextShrink) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(
                    shrinkRemoteBytes = error.remoteBytes, shrinkLocalBytes = error.localBytes,
                )
            } catch (_: RemoteTextRetargeted) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(retargeted = true)
            } catch (_: RemoteTextConflict) {
                val probe = runCatching { RemoteTextEditor.load(channel, original.path, original.encoding) }.getOrNull()
                mutablePortTransfer.value = when {
                    probe == null -> mutablePortTransfer.value.copy(editorConflict = true, conflictDeleted = true, conflictFingerprint = null)
                    probe.resolvedPath != original.resolvedPath -> mutablePortTransfer.value.copy(retargeted = true, editorConflict = false)
                    else -> mutablePortTransfer.value.copy(editorConflict = true, conflictDeleted = false, conflictFingerprint = probe.fingerprint)
                }
            } finally { channel.close(); mutablePortTransfer.value = mutablePortTransfer.value.copy(editorBusy = false) }
        }
    }

    /** Opens a fresh SFTP channel for the queue; the queue closes it after the operation. */
    fun uploadSftp(localPath: String, remotePath: String) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        viewModelScope.launch {
            try {
                val file = File(localPath)
                require(file.isFile) { "local upload file does not exist" }
                val id = transferQueue.enqueueUpload(
                    remotePath,
                    FileTransferSource(file),
                    channelProvider = { sessions.openSftp(sessionId) },
                    localPath = localPath
                )
                rememberTransfer(id, sessionId)
                setStatus(StatusKey.UPLOAD_QUEUED)
            } catch (error: Throwable) {
                setStatus(readableError(error, StatusKey.SFTP_UPLOAD_FAILED))
            }
        }
    }

    fun downloadSftp(remotePath: String, localPath: String, bytesTotal: Long = -1L) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        viewModelScope.launch {
            try {
                val file = File(localPath)
                file.parentFile?.mkdirs()
                val id = transferQueue.enqueueDownload(
                    remotePath,
                    FileTransferSink(file),
                    channelProvider = { sessions.openSftp(sessionId) },
                    bytesTotal = bytesTotal,
                    localPath = localPath
                )
                rememberTransfer(id, sessionId)
                setStatus(StatusKey.DOWNLOAD_QUEUED)
            } catch (error: Throwable) {
                setStatus(readableError(error, StatusKey.SFTP_DOWNLOAD_FAILED))
            }
        }
    }

    fun createSftpDirectory(name: String) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        val path = resolveChild(confirmedDirectory(sessionId), name)
        mutateSftp(StatusKey.SFTP_DIRECTORY_CREATED) { channel -> channel.mkdir(path) }
    }

    fun renameSftp(from: String, name: String) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        if (!pathOperable(sessionId, from)) return
        val target = resolveChild(parentPath(from), name)
        mutateSftp(StatusKey.SFTP_ITEM_RENAMED) { channel -> channel.rename(from, target) }
    }

    fun goToSftpParent() {
        val current = confirmedDirectory(state.value.selectedSessionId).trim().ifEmpty { "/" }
        browseSftp(parentPath(current))
    }

    fun cancelTransfer(id: String) = transferQueue.cancel(id)
    fun pauseTransfer(id: String) = transferQueue.pause(id)
    fun resumeTransfer(id: String) = transferQueue.resume(id)
    fun retryTransfer(id: String) { archiveRetries[id]?.invoke() ?: transferQueue.retry(id) }
    fun clearFinishedTransfers() = transferQueue.clearFinished()

    fun acceptHostKey() {
        val prompt = state.value.hostKeyPrompt ?: return
        if (prompt.previousFingerprint != null) {
            viewModelScope.launch {
                if (prompt.recordKey.isNotEmpty()) knownHosts.remove(prompt.recordKey)
                refreshStorage()
                setStatus(StatusKey.HOST_TRUST_REVOKED)
            }
            pendingHostKey?.complete(false)
            return
        }
        // Connect/KEX uses a long host-key decision timeout so Trust can complete the same
        // handshake. A timed-out attempt dismisses the dialog in openNewSession and surfaces
        // CONNECTION_FAILED; the user taps the host again (trust already saved if they accepted).
        pendingHostKey?.complete(true)
    }

    fun rejectHostKey() {
        pendingHostKey?.complete(false)
    }

    private fun openNewDraft(sessionId: String, path: String) {
        editorSessionId = sessionId
        resetEditorRelease()
        val encoding = mutablePortTransfer.value.editorEncoding
        val document = RemoteTextDocument(
            path = path, text = "", fingerprint = "", permissions = null, encoding = encoding,
            hasBom = false, eol = "lf", resolvedPath = path, byteSize = 0,
        )
        mutablePortTransfer.value = mutablePortTransfer.value.copy(
            editor = document, editorText = "", editorEncoding = encoding, editorSessionId = sessionId,
            creating = true, alreadyExists = false, editorConflict = false, retargeted = false, messageRes = null,
        )
    }

    private fun rereadRemoteEditor(encoding: String) {
        val sessionId = editorSessionId ?: return
        val path = mutablePortTransfer.value.editor?.path ?: return
        mutablePortTransfer.value = mutablePortTransfer.value.copy(editorBusy = true, pendingEncoding = null)
        transferPreparation(sessionId) {
            val channel = sessions.openSftp(sessionId)
            try {
                val document = RemoteTextEditor.load(channel, path, encoding)
                if (editorSessionId == sessionId) {
                    resetEditorRelease()
                    mutablePortTransfer.value = mutablePortTransfer.value.copy(
                        editor = document, editorText = document.text, editorEncoding = document.encoding, creating = false,
                    )
                }
            } catch (_: RemoteTextEncodingUnavailable) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_encoding_unavailable)
            } catch (_: RemoteTextEncodingFailure) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_encoding_lossless)
            } catch (_: RemoteTextBinary) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_editor_binary)
            } catch (_: Throwable) {
                mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_operation_failed)
            } finally { channel.close(); mutablePortTransfer.value = mutablePortTransfer.value.copy(editorBusy = false) }
        }
    }

    private fun resetEditorRelease() {
        editorRelease = SaveRelease(overwriteRemoteChanges = false, allowNonAtomic = false, allowShrink = false)
    }

    private fun rememberTransfer(id: String, sessionId: String) {
        transferSessions[id] = sessionId
        updateState { it.copy(transferSessions = transferSessions.toMap()) }
    }

    private fun setPendingDir(sessionId: String, path: String?) {
        updateState { current ->
            current.copy(sessionPendingDirs = if (path == null) current.sessionPendingDirs - sessionId else current.sessionPendingDirs + (sessionId to path))
        }
    }

    private fun sortEntries(entries: List<SftpEntry>, files: SessionFileState): List<SftpEntry> {
        val key = when (files.sortKey) {
            FileSortKey.NAME -> SftpSortKey.NAME
            FileSortKey.SIZE -> SftpSortKey.SIZE
            FileSortKey.MODIFIED -> SftpSortKey.MODIFIED
            FileSortKey.OWNER -> SftpSortKey.OWNER
        }
        val direction = if (files.sortAscending) SftpSortDirection.ASC else SftpSortDirection.DESC
        return orderedSftpEntries(entries, key, direction)
    }

    private fun normalizeBrowse(base: String, path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return base.ifBlank { "/" }
        val absolute = if (trimmed.startsWith("/")) trimmed else {
            val root = base.trimEnd('/').ifEmpty { "" }
            "$root/$trimmed"
        }
        return CdFollow.normalizePosix(absolute)
    }

    private fun pathOperable(sessionId: String, path: String): Boolean {
        val entry = state.value.sessionEntries[sessionId].orEmpty().firstOrNull { it.path == path } ?: return true
        return RemoteName.canOperate(entry)
    }

    private fun directoryListing(sessionId: String, paths: List<String>): List<ListedEntry>? {
        val entries = state.value.sessionEntries[sessionId].orEmpty()
        val matched = paths.map { path -> entries.firstOrNull { it.path == path } ?: return null }
        return matched.map { entry ->
            ListedEntry(
                path = entry.path,
                kind = when (entry.type) {
                    SftpEntry.Type.DIRECTORY -> ListedKind.DIRECTORY
                    SftpEntry.Type.FILE -> ListedKind.FILE
                    SftpEntry.Type.SYMLINK -> ListedKind.SYMLINK
                    SftpEntry.Type.OTHER -> ListedKind.OTHER
                },
                nameInvalid = entry.nameInvalid,
            )
        }
    }

    private fun followCapturedDirectory(sessionId: String, command: String) {
        val target = CdFollow.parseCdTarget(command)
        if (target == null) {
            if (looksLikeCd(command) && state.value.selectedSessionId == sessionId) setStatus(StatusKey.FILE_CD_KEPT)
            return
        }
        viewModelScope.launch {
            var channel: io.github.openfinalshell.android.core.ssh.SftpChannel? = null
            try {
                channel = sessions.openSftp(sessionId)
                val anchor = anchorFor(sessionId, channel)
                val next = CdFollow.applyCd(anchor.cwd, target, anchor.home)
                if (next == null) {
                    if (state.value.selectedSessionId == sessionId) setStatus(StatusKey.FILE_CD_KEPT)
                    return@launch
                }
                val (pending, generation) = beginNavigation(fileState(sessionId), next)
                updateSessionFiles(sessionId, pending)
                setPendingDir(sessionId, next)
                loadListing(sessionId, next, generation, history = false, follow = true)
            } catch (_: Throwable) {
                if (state.value.selectedSessionId == sessionId) setStatus(StatusKey.FILE_CD_KEPT)
            } finally {
                try { channel?.close() } catch (_: Throwable) { }
            }
        }
    }

    private suspend fun anchorFor(
        sessionId: String,
        channel: io.github.openfinalshell.android.core.ssh.SftpChannel,
    ): CdAnchor {
        cdAnchors[sessionId]?.let { return it }
        val cwd = resolvedDirectory(channel, confirmedDirectory(sessionId))
        val home = resolvedHome(channel)
        return CdAnchor(cwd, home).also { cdAnchors[sessionId] = it }
    }

    private suspend fun rememberAnchor(
        sessionId: String,
        channel: io.github.openfinalshell.android.core.ssh.SftpChannel,
        path: String,
    ) {
        val cwd = resolvedDirectory(channel, path)
        val home = cdAnchors[sessionId]?.home ?: resolvedHome(channel)
        cdAnchors[sessionId] = CdAnchor(cwd, home)
    }

    private suspend fun resolvedDirectory(channel: io.github.openfinalshell.android.core.ssh.SftpChannel, path: String): String {
        val resolved = runCatching { channel.realpath(path) }.getOrNull()
        return if (resolved != null && resolved.startsWith("/")) CdFollow.normalizePosix(resolved) else CdFollow.normalizePosix(path)
    }

    private suspend fun resolvedHome(channel: io.github.openfinalshell.android.core.ssh.SftpChannel): String? {
        val resolved = runCatching { channel.realpath("~") }.getOrNull() ?: return null
        return resolved.takeIf { it.startsWith("/") }?.let(CdFollow::normalizePosix)
    }

    private fun looksLikeCd(command: String): Boolean {
        val trimmed = command.trim()
        return trimmed == "cd" || trimmed.startsWith("cd ") || trimmed.startsWith("cd\t")
    }

    private suspend fun execCapture(sessionId: String, command: String): Pair<Int, String> =
        kotlinx.coroutines.withTimeout(120_000L) {
            val channel = sessions.openExec(sessionId, command)
            try {
                val output = StringBuilder()
                channel.output.collect { bytes ->
                    check(output.length + bytes.size <= 16 * 1024)
                    output.append(bytes.toString(Charsets.UTF_8))
                }
                (channel.exitCode.value ?: -1) to output.toString().trim()
            } finally { channel.close() }
        }

    private fun configuredConflictPolicy(): ConflictPolicy = when (transferSettings.sftpConflictPolicy) {
        "overwrite" -> ConflictPolicy.OVERWRITE
        "skip" -> ConflictPolicy.SKIP
        "rename" -> ConflictPolicy.RENAME
        else -> ConflictPolicy.ASK
    }

    private fun nodeKind(entry: SftpEntry): NodeKind =
        if (entry.type == SftpEntry.Type.DIRECTORY) NodeKind.DIRECTORY else NodeKind.FILE

    private suspend fun resolvePlacement(
        batch: TransferBatch,
        parent: String,
        name: String,
        source: NodeKind,
        listing: List<Pair<String, NodeKind>>,
    ): TransferConflictPlan.Resolution {
        val occupied = batch.reserved.getOrPut(parent) { LinkedHashMap() }
        listing.forEach { (listedName, kind) -> occupied.putIfAbsent(listedName, kind) }
        val policy = batch.sticky ?: configuredConflictPolicy()
        var resolution = placeOne(name, source, occupied, policy)
        if (resolution is TransferConflictPlan.Resolution.Ask) {
            val choice = askConflict(resolution.name)
            if (choice.applyToRest && choice.policy != ConflictPolicy.ASK) batch.sticky = choice.policy
            resolution = placeOne(name, source, occupied, choice.policy)
        }
        when (resolution) {
            is TransferConflictPlan.Resolution.Use -> occupied[resolution.name] = source
            is TransferConflictPlan.Resolution.Overwrite -> occupied[resolution.name] = source
            is TransferConflictPlan.Resolution.Rename -> occupied[resolution.name] = source
            else -> Unit
        }
        return resolution
    }

    private fun placeOne(
        name: String,
        source: NodeKind,
        occupied: Map<String, NodeKind>,
        policy: ConflictPolicy,
    ): TransferConflictPlan.Resolution = TransferConflictPlan.plan(
        listOf(TransferNode(name, source)),
        occupied.map { TransferNode(it.key, it.value) },
        if (name in occupied) listOf(BatchChoice(policy, false)) else emptyList(),
    ).single()

    private fun mutateSftp(success: StatusKey, operation: suspend (io.github.openfinalshell.android.core.ssh.SftpChannel) -> Unit) {
        val sessionId = state.value.selectedSessionId ?: return setStatus(StatusKey.SESSION_REQUIRED)
        viewModelScope.launch {
            var channel: io.github.openfinalshell.android.core.ssh.SftpChannel? = null
            try {
                channel = sessions.openSftp(sessionId)
                operation(channel)
                browseSftp(sessionId, confirmedDirectory(sessionId))
                setStatus(success)
            } catch (error: Throwable) {
                setStatus(readableError(error, StatusKey.SFTP_OPERATION_FAILED))
            } finally {
                runCatching { channel?.close() }
            }
        }
    }

    private fun resolveChild(directory: String, child: String): String {
        val name = child.trim()
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/')) { "invalid SFTP name" }
        return directory.trimEnd('/').ifEmpty { "/" }.let { if (it == "/") "/$name" else "$it/$name" }
    }

    private fun parentPath(path: String): String {
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        if (normalized == "/") return "/"
        return normalized.substringBeforeLast('/').ifEmpty { "/" }
    }

    fun openShell(sessionId: String, cols: Int, rows: Int, restoredMarker: String? = null) {
        val tracker = terminalSizes.getOrPut(sessionId) { TerminalSizeTracker() }
        val openSize = tracker.beginOpen(cols, rows) ?: return
        viewModelScope.launch {
            var opened = false
            try {
                val shell = sessions.openShell(sessionId, openSize.cols, openSize.rows)
                shells[sessionId] = shell
                shellOutputJobs.remove(sessionId)?.cancel()
                val existing = terminalControllers[sessionId]
                val controller: TerminalHostController = if (shell.ownsEmulator) {
                    // A channel that owns its emulator already renders its own bytes; it exposes the
                    // controller it was built with instead of having a second one built around it.
                    val local = shell as? LocalShellChannel
                        ?: error("a channel that owns its emulator must expose its controller")
                    local.controller
                } else if (existing is SshTerminalController) {
                    existing.replaceInputSink { bytes -> shell.write(bytes) }
                    existing.setInputEnabled(true)
                    existing
                } else {
                    SshTerminalController(
                        inputScope = viewModelScope,
                        inputSink = { bytes -> shell.write(bytes) },
                        initialCols = openSize.cols,
                        initialRows = openSize.rows,
                        scrollbackLines = ScrollbackLimits.forEmulator(transferSettings.terminalScrollbackLines)
                    )
                }
                terminalControllers[sessionId] = controller
                attachCommandCapture(sessionId, controller)
                updateState { current ->
                    current.copy(terminalSessionIds = current.terminalSessionIds + sessionId)
                }
                if (!shell.ownsEmulator && controller is SshTerminalController) {
                    if (restoredMarker != null) controller.appendLocalMarker(restoredMarker)
                    shellOutputJobs[sessionId] = launch {
                        shell.output.collect { bytes ->
                            controller.write(bytes)
                            val command = commandCaptures[sessionId]?.onRemoteOutput(controller.emulator, bytes)
                            if (command != null) {
                                recordCapturedCommand(command)
                                followCapturedDirectory(sessionId, command)
                            }
                        }
                    }
                }
                tracker.finishOpen(openSize.cols, openSize.rows)
                opened = true
                flushTerminalSize(sessionId, shell, controller)
            } catch (error: Throwable) {
                if (!opened) tracker.failOpen()
                setStatus(readableError(error, StatusKey.TERMINAL_OPEN_FAILED))
            }
        }
    }

    private fun rebindShell(sessionId: String) {
        val tracker = terminalSizes.getOrPut(sessionId) { TerminalSizeTracker() }
        tracker.prepareRebind()
        val size = tracker.desiredSize()
        val marker = getApplication<Application>().getString(R.string.session_restored_marker)
        openShell(sessionId, size.cols, size.rows, marker)
    }

    /** Writes user input and terminal protocol replies to the SSH shell without text round-tripping. */
    fun sendTerminalInput(sessionId: String, data: ByteArray) {
        deliverTerminalInput(sessionId, data, programmaticInsert = false, programmaticSubmit = false)
    }

    fun sendScreenKey(sessionId: String, key: ScreenKey, ctrl: Boolean) {
        val emulator = terminalControllers[sessionId]?.emulator ?: return
        val bytes = screenKeyBytes(emulator, key, ctrl)
        if (bytes.isNotEmpty()) deliverTerminalInput(sessionId, bytes, programmaticInsert = false, programmaticSubmit = false)
    }

    /**
     * Every paste entry uses [TerminalPastePlan]. Cancellation is a zero-byte plan and is not written.
     * Bracketed paste follows the emulator's current DECSET 2004 state.
     */
    fun pasteTerminalText(sessionId: String, text: String, confirmed: Boolean) {
        val controller = terminalControllers[sessionId] ?: return
        val bracketed = bracketedPasteEnabled(sessionId)
        val plan = TerminalPastePlan.of(
            text,
            bracketed,
            transferSettings.terminalConfirmMultilinePaste,
            confirmed
        )
        if (plan.bytes.isEmpty()) return
        deliverTerminalInput(sessionId, plan.bytes, programmaticInsert = false, programmaticSubmit = false)
    }

    fun refreshCommandHistory() {
        viewModelScope.launch {
            mutableCommandHistory.value = runCatching { commandHistory.historyRecords() }.getOrDefault(emptyList())
        }
    }

    fun clearCommandHistory() {
        viewModelScope.launch {
            runCatching { commandHistory.clearHistory() }
            mutableCommandHistory.value = emptyList()
        }
    }

    private fun deliverTerminalInput(
        sessionId: String,
        data: ByteArray,
        programmaticInsert: Boolean,
        programmaticSubmit: Boolean
    ) {
        if (data.isEmpty()) return
        val controller = terminalControllers[sessionId]
        noteTerminalInput(sessionId, controller, data, programmaticInsert, programmaticSubmit)
        viewModelScope.launch {
            runCatching { writeTerminalBytes(sessionId, controller, data) }
                .onFailure { setStatus(readableError(it, StatusKey.TERMINAL_WRITE_FAILED)) }
        }
    }

    private fun noteTerminalInput(
        sessionId: String,
        controller: TerminalHostController?,
        data: ByteArray,
        programmaticInsert: Boolean,
        programmaticSubmit: Boolean
    ) {
        commandCaptures.getOrPut(sessionId) { TerminalCommandCapture() }
            .onInput(controller?.emulator, data, programmaticInsert, programmaticSubmit)
    }

    private suspend fun writeTerminalBytes(sessionId: String, controller: TerminalHostController?, data: ByteArray) {
        if (controller != null) controller.sendInput(data)
        else shells[sessionId]?.write(data)
    }

    private fun attachCommandCapture(sessionId: String, controller: TerminalHostController) {
        val capture = commandCaptures.getOrPut(sessionId) { TerminalCommandCapture() }
        if (controller is LocalTerminalController) {
            controller.onEmulatorUpdated = {
                val emulator = controller.emulator
                val command = emulator?.let(capture::onScreenChanged)
                if (command != null) viewModelScope.launch {
                    recordCapturedCommand(command)
                    followCapturedDirectory(sessionId, command)
                }
            }
        }
    }

    private suspend fun recordCapturedCommand(command: String) {
        if (database.documents().find(ToolRepository.HISTORY_ENABLED_DOCUMENT)?.json != "true") return
        runCatching { commandHistory.recordCommand(command) }
        mutableCommandHistory.value = runCatching { commandHistory.historyRecords() }.getOrDefault(mutableCommandHistory.value)
    }

    fun sendTerminalInput(sessionId: String, data: String) =
        sendTerminalInput(sessionId, data.toByteArray(Charsets.UTF_8))

    fun insertAssistantCommand(sessionId: String, text: String) {
        if (state.value.sessions[sessionId]?.state != SessionState.READY) return setStatus(StatusKey.SESSION_REQUIRED)
        // Filling a command must never inject Enter, ESC, DEL or other terminal controls.
        val command = text.trimEnd('\r', '\n')
        if (command.length > 32_768 || command.any { it.code < 32 || it.code in 127..159 }) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_command_single_line)
            return
        }
        deliverTerminalInput(sessionId, command.toByteArray(Charsets.UTF_8), programmaticInsert = true, programmaticSubmit = false)
    }

    /** Only the separate, explicit Execute action can submit Enter. */
    fun executeAssistantCommand(sessionId: String, text: String, onSubmitted: () -> Unit) {
        if (state.value.sessions[sessionId]?.state != SessionState.READY) return setStatus(StatusKey.SESSION_REQUIRED)
        if (isRootTierUnconfirmed(sessionId)) return setStatus(StatusKey.LOCAL_ROOT_CONFIRM)
        val command = text.trimEnd('\r', '\n')
        if (command.isBlank() || command.length > 32_768 || command.any { it.code < 32 || it.code in 127..159 }) {
            mutablePortTransfer.value = mutablePortTransfer.value.copy(messageRes = R.string.port_command_single_line)
            return
        }
        val controller = terminalControllers[sessionId] ?: return setStatus(StatusKey.SESSION_REQUIRED)
        viewModelScope.launch {
            try {
                if (terminalControllers[sessionId] !== controller || state.value.sessions[sessionId]?.state != SessionState.READY) return@launch
                // Re-checked inside the coroutine as well: a confirmation withdrawn between the tap
                // and the write must not be bypassable by a stale UI.
                if (isRootTierUnconfirmed(sessionId)) {
                    setStatus(StatusKey.LOCAL_ROOT_CONFIRM)
                    return@launch
                }
                val bytes = (command + "\r").toByteArray(Charsets.UTF_8)
                noteTerminalInput(sessionId, controller, bytes, programmaticInsert = true, programmaticSubmit = true)
                controller.sendInput(bytes)
                onSubmitted()
            } catch (error: Exception) { setStatus(readableError(error, StatusKey.TERMINAL_WRITE_FAILED)) }
        }
    }

    /**
     * Sessions that can take a command editor write: ready, with a shell that still accepts input.
     * Reconnecting, closed, and sessions whose terminal was never opened are left out.
     * A local root shell stays in [CommandTargetRoster.rootBlocked] until its confirmation is current.
     */
    fun commandTargetRoster(): CommandTargetRoster {
        val eligible = mutableListOf<CommandWorkspaceTarget>()
        val rootBlocked = mutableListOf<CommandWorkspaceTarget>()
        for (snapshot in state.value.sessions.values) {
            if (!isCommandWritable(snapshot.sessionId)) continue
            val row = CommandWorkspaceTarget(commandTarget(snapshot), commandSessionLabel(snapshot.sessionId))
            if (isRootTierUnconfirmed(snapshot.sessionId)) rootBlocked += row else eligible += row
        }
        return CommandTargetRoster(eligible, rootBlocked)
    }

    fun commandSessionLabel(sessionId: String): String {
        val current = state.value
        val title = current.sessionPanels[sessionId]?.title?.takeIf { it.isNotBlank() }
        return title ?: current.sessions[sessionId]?.profile?.name ?: sessionId
    }

    /** Same rule as multiline paste: the editor or shortcut text itself contains CR or LF. */
    fun commandBodyNeedsConfirm(raw: String): Boolean =
        transferSettings.terminalConfirmMultilinePaste && (raw.contains('\r') || raw.contains('\n'))

    fun noteLocalRootCommandBlocked() = setStatus(StatusKey.LOCAL_ROOT_CONFIRM)

    /**
     * Writes [request] to the targets captured at click time.
     * Each target is checked again immediately before its write. History records only a successful
     * submit, once, through the existing command store; the terminal echo of that write is suppressed.
     */
    fun sendCommandWorkspace(request: CommandDispatchRequest) {
        if (mutableCommandSendBusy.value) return
        if (request.targets.isEmpty() || CommandBody.normalize(request.raw).isEmpty()) return
        mutableCommandSendBusy.value = true
        val locked = request.copy(targets = request.targets.toList())
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val outcome = CommandBatch.execute(
                    locked.raw,
                    locked.placeholdersEnabled,
                    locked.autoEnter,
                    locked.targets,
                    recheck = { id -> onMain { commandTargetStillWritable(id) } },
                    write = { id, bytes -> onMain { writeCommandWorkspace(id, bytes, locked) } }
                )
                withContext(Dispatchers.Main.immediate) {
                    runCatching { recordProgrammaticSubmits(outcome.history) }
                    if (outcome.failed.any { isRootTierUnconfirmed(it.targetId) }) {
                        setStatus(StatusKey.LOCAL_ROOT_CONFIRM)
                    }
                    val retryTargets = outcome.retryTargets(locked.targets)
                    mutableCommandReport.value = CommandWorkspaceReport(
                        id = System.nanoTime(),
                        clearEditor = locked.fromEditor && outcome.clearBody,
                        editorRaw = locked.raw,
                        succeeded = outcome.succeeded.map(::commandSessionLabel),
                        failed = outcome.failed.map { commandSessionLabel(it.targetId) },
                        skipped = outcome.skipped.map { commandSessionLabel(it.targetId) },
                        retry = if (retryTargets.isEmpty()) null else locked.copy(targets = retryTargets)
                    )
                }
            } finally {
                mutableCommandSendBusy.value = false
            }
        }
    }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking(Dispatchers.Main.immediate) { block() }

    private fun commandTarget(snapshot: io.github.openfinalshell.android.core.ssh.SessionSnapshot): CommandTarget {
        val profile = snapshot.profile
        return CommandTarget(
            snapshot.sessionId,
            profile.host,
            profile.username,
            profile.port,
            bracketedPasteEnabled(snapshot.sessionId)
        )
    }

    private fun bracketedPasteEnabled(sessionId: String): Boolean {
        val controller = terminalControllers[sessionId] ?: return false
        return when (controller) {
            is SshTerminalController -> controller.bracketedPasteEnabled()
            is LocalTerminalController -> controller.bracketedPasteEnabled()
            else -> TerminalModes.bracketedPaste(controller.emulator, null)
        }
    }

    private fun isCommandWritable(sessionId: String): Boolean {
        val session = state.value.sessions[sessionId] ?: return false
        if (session.state != SessionState.READY) return false
        val controller = terminalControllers[sessionId] ?: return false
        if (shells[sessionId] == null) return false
        return when (controller) {
            is SshTerminalController -> controller.canAcceptInput()
            is LocalTerminalController -> controller.canAcceptInput()
            else -> false
        }
    }

    /** Ready, writable, and still allowed to touch a local root shell. */
    private fun commandTargetStillWritable(sessionId: String): Boolean =
        isCommandWritable(sessionId) && !isRootTierUnconfirmed(sessionId)

    private suspend fun writeCommandWorkspace(sessionId: String, bytes: ByteArray, request: CommandDispatchRequest): Boolean {
        if (!commandTargetStillWritable(sessionId)) return false
        val target = request.targets.find { it.id == sessionId } ?: return false
        val text = bytes.toString(Charsets.UTF_8)
        val multilineInsert = !request.autoEnter && target.bracketedPaste && '\n' in text
        val payload = if (multilineInsert) TerminalPastePlan.encode(text, bracketedPaste = true) else bytes
        if (payload.isEmpty()) return false
        val controller = terminalControllers[sessionId] ?: return false
        val lineBreak = payload.any { it == '\r'.code.toByte() || it == '\n'.code.toByte() }
        noteTerminalInput(
            sessionId,
            controller,
            payload,
            programmaticInsert = true,
            programmaticSubmit = request.autoEnter || lineBreak
        )
        return try {
            controller.sendInput(payload)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
    }

    /** One store record per successful submit. Echo capture is already suppressed for that write. */
    private suspend fun recordProgrammaticSubmits(commands: List<String>) {
        if (commands.isEmpty()) return
        if (database.documents().find(ToolRepository.HISTORY_ENABLED_DOCUMENT)?.json != "true") return
        for (command in commands) runCatching { commandHistory.recordCommand(command) }
        mutableCommandHistory.value = runCatching { commandHistory.historyRecords() }.getOrDefault(mutableCommandHistory.value)
    }

    /** Snapshot stream for a native terminal view; the SSH channel remains private to the VM. */
    fun terminalSnapshot(sessionId: String): StateFlow<TerminalSnapshot>? =
        terminalControllers[sessionId]?.snapshot

    fun terminalController(sessionId: String): TerminalHostController? = terminalControllers[sessionId]

    fun resizeTerminal(sessionId: String, cols: Int, rows: Int) {
        val tracker = terminalSizes.getOrPut(sessionId) { TerminalSizeTracker() }
        tracker.noteResize(cols, rows)
        val controller = terminalControllers[sessionId] ?: return
        val shell = shells[sessionId]
        viewModelScope.launch {
            if (shell == null) controller.resize(cols, rows)
            else flushTerminalSize(sessionId, shell, controller)
        }
    }

    private suspend fun flushTerminalSize(sessionId: String, shell: ShellChannel, controller: TerminalHostController) {
        val tracker = terminalSizes[sessionId] ?: return
        terminalResizeLocks.getOrPut(sessionId) { Mutex() }.withLock {
            while (true) {
                val size = tracker.unapplied() ?: break
                controller.resize(size.cols, size.rows)
                shell.resize(size.cols, size.rows)
                tracker.markApplied(size.cols, size.rows)
            }
        }
    }

    fun clearTerminal(sessionId: String) {
        terminalControllers[sessionId]?.clearScreen()
    }

    fun visibleSessions(): List<SessionSnapshot> {
        val current = state.value
        return current.sessions.values.filter { snapshot ->
            snapshot.state != SessionState.CLOSED || snapshot.sessionId in current.terminalSessionIds
        }
    }

    fun disconnect(sessionId: String) {
        cancelSessionTransfers(sessionId)
        releaseSessionMonitor(sessionId)
        terminalSizes.remove(sessionId)?.reset()
        terminalResizeLocks.remove(sessionId)
        // Disconnecting drops the root confirmation, so reconnecting has to ask again. The teardown
        // is where the session stops being something the user is actively watching.
        state.value.sessions[sessionId]?.profile?.id?.let { confirmedRootProfiles -= it }
        forwardingSessions.filterValues { it == sessionId }.keys.toList().forEach { id ->
            closeForwardHandle(id)
            forwardingSessions.remove(id)
            updateState { it.copy(forwardStates = it.forwardStates - id) }
        }
        forwardLatch = ForwardRunLatch.tabClosed(forwardLatch, sessionId)
        sessionReadyAt.remove(sessionId)
        monitorReconnect.remove(sessionId)
        updateBackgroundService()
        shellOutputJobs.remove(sessionId)?.cancel()
        commandCaptures.remove(sessionId)
        cdAnchors.remove(sessionId)
        (terminalControllers[sessionId] as? LocalTerminalController)?.onEmulatorUpdated = null
        terminalControllers.remove(sessionId)?.let { controller ->
            viewModelScope.launch { controller.closeSession() }
        }
        shells.remove(sessionId)
        val next = if (state.value.selectedSessionId == sessionId) {
            visibleSessions().firstOrNull { it.sessionId != sessionId }?.sessionId
        } else {
            state.value.selectedSessionId
        }
        updateState {
            it.copy(
                terminalSessionIds = it.terminalSessionIds - sessionId,
                sessionFiles = it.sessionFiles - sessionId,
                sessionEntries = it.sessionEntries - sessionId,
                sessionPendingDirs = it.sessionPendingDirs - sessionId,
                sessionPanels = it.sessionPanels - sessionId,
                selectedSessionId = if (it.selectedSessionId == sessionId) null else it.selectedSessionId,
                status = if (it.selectedSessionId == sessionId) UiStatus(StatusKey.DISCONNECTED) else it.status
            )
        }
        if (next != null) selectSession(next)
        viewModelScope.launch {
            sessions.disconnect(sessionId)
            // Do not stop the FGS just because sessions are gone — transfers / forwards /
            // connectsInFlight must keep it alive via the same hold policy as updates.
            updateBackgroundService()
        }
    }

    fun setStatus(status: UiStatus) {
        updateState { it.copy(status = status) }
    }

    private fun setStatus(key: StatusKey, vararg args: Any) {
        setStatus(UiStatus(key, args.toList()))
    }

    /** Keep implementation details out of the status bar while retaining them in Logcat. */
    private fun readableError(error: Throwable, fallback: StatusKey): UiStatus {
        Log.e(TAG, fallback.name, error)
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is LinkageError || it is ExceptionInInitializerError }) {
            return UiStatus(StatusKey.SSH_COMPONENTS_UNAVAILABLE)
        }
        return UiStatus(fallback)
    }

    private fun readableErrorText(error: Throwable, fallback: StatusKey): String {
        val status = readableError(error, fallback)
        val context = getApplication<Application>()
        return context.getString(status.key.resourceId, *status.args.toTypedArray())
    }

    private fun sessionStatus(state: SessionState): UiStatus = UiStatus(
        when (state) {
            SessionState.CONNECTING -> StatusKey.CONNECTING
            SessionState.AUTHENTICATING -> StatusKey.AUTHENTICATING
            SessionState.READY -> StatusKey.CONNECTED
            SessionState.RECONNECTING -> StatusKey.RECONNECTING
            SessionState.CLOSED -> StatusKey.DISCONNECTED
        }
    )

    private fun updateState(transform: (AndroidUiState) -> AndroidUiState) {
        mutableState.value = transform(mutableState.value)
    }

    override fun onCleared() {
        transferQueue.cancelAll()
        conflictDecision?.complete(BatchChoice(ConflictPolicy.SKIP, false))
        forwardingHandles.values.forEach { runCatching { it.close() } }
        forwardingHandles.clear()
        forwardingSessions.clear()
        shellOutputJobs.values.forEach(Job::cancel)
        terminalControllers.values.forEach { it.disposeView() }
        terminalControllers.clear()
        shells.values.forEach { shell -> viewModelScope.launch { runCatching { shell.close() } } }
        monitorJobs.values.forEach { it.cancel() }
        monitors.values.forEach { it.stop() }
        viewModelScope.launch { sessions.disconnect() }
        database.close()
        super.onCleared()
    }

    private fun probeDirectLatency(profile: ConnectionProfile, sessionId: String) {
        // A local session has no link to measure. Probing anyway would TCP-connect to
        // localhost:22 and present the result as though it described a network path.
        if (profile.protocol == LOCAL_SHELL_PROTOCOL) return
        if (profile.proxy?.type?.let { it != "none" } == true || profile.proxyId != null) return
        viewModelScope.launch {
            val monitor = monitorFor(sessionId)
            monitor.applyDirectLatency(LatencyProbe.measure(profile.host, profile.port))
            publishMonitor(sessionId, monitor.state.value)
        }
    }

    /** TOFU host-key policy: first key is recorded, later changes are rejected. */
    private suspend fun verifyHostKey(host: String, port: Int, key: PublicKey): Boolean {
        val keyType = HostKeyFingerprint.keyType(key)
        val id = "$host:$port:$keyType"
        val fingerprint = HostKeyFingerprint.sha256(key)
        val previous = knownHosts.find(id)
        if (previous == null) {
            val decision = CompletableDeferred<Boolean>()
            pendingHostKey?.complete(false)
            pendingHostKey = decision
            updateState {
                it.copy(
                    hostKeyPrompt = HostKeyPrompt(host, port, keyType, fingerprint),
                    status = UiStatus(StatusKey.HOST_KEY_CONFIRMATION_REQUIRED)
                )
            }
            val accepted = try {
                decision.await()
            } finally {
                if (pendingHostKey === decision) pendingHostKey = null
                updateState { it.copy(hostKeyPrompt = null) }
            }
            if (!accepted) return false
            knownHosts.trust(KnownHostEntity(id, keyType, fingerprint, System.currentTimeMillis()))
            return true
        }
        if (previous.fingerprintSha256 == fingerprint) return true
        val decision = CompletableDeferred<Boolean>()
        pendingHostKey?.complete(false)
        pendingHostKey = decision
        updateState {
            it.copy(
                hostKeyPrompt = HostKeyPrompt(host, port, keyType, fingerprint, previous.fingerprintSha256, id),
                status = UiStatus(StatusKey.HOST_KEY_CONFIRMATION_REQUIRED)
            )
        }
        try {
            decision.await()
        } finally {
            if (pendingHostKey === decision) pendingHostKey = null
            updateState { current -> current.copy(hostKeyPrompt = null) }
        }
        return false
    }

    companion object {
        private const val TAG = "OpenFinalShell"

        /** `profiles.host` and `profiles.username` are NOT NULL; a local session has neither. */
        private const val LOCAL_HOST_PLACEHOLDER = "localhost"
        private const val LOCAL_USERNAME_PLACEHOLDER = "shell"
    }
}

private class FileTransferSource(private val file: File) : TransferSource {
    override val size: Long get() = file.length()

    override suspend fun read(offset: Long, maxBytes: Int): ByteArray {
        return withContext(Dispatchers.IO) {
            require(offset >= 0 && maxBytes > 0)
            RandomAccessFile(file, "r").use { input ->
                if (offset >= input.length()) return@use ByteArray(0)
                input.seek(offset)
                val buffer = ByteArray(minOf(maxBytes.toLong(), input.length() - offset).toInt())
                var read = 0
                while (read < buffer.size) {
                    val count = input.read(buffer, read, buffer.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            }
        }
    }
}

private class FileTransferSink(private val file: File) : TransferSink {
    override suspend fun reset() {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(0) }
        }
    }

    override suspend fun write(offset: Long, data: ByteArray) {
        withContext(Dispatchers.IO) {
            require(offset >= 0)
            RandomAccessFile(file, "rw").use { output ->
                output.seek(offset)
                output.write(data)
            }
        }
    }
}

private data class CdAnchor(val cwd: String, val home: String?)

private class TransferBatch {
    var sticky: ConflictPolicy? = null
    val reserved = HashMap<String, MutableMap<String, NodeKind>>()
}

package io.github.openfinalshell.android.core.ssh

import android.util.Log
import io.github.openfinalshell.android.core.connection.CharsetShellChannel
import io.github.openfinalshell.android.core.connection.ConnectionTiming
import io.github.openfinalshell.android.core.connection.SshRuntimePolicy
import io.github.openfinalshell.android.core.connection.charsetNeedsTranslation
import io.github.openfinalshell.android.core.model.ConnectionProfile
import io.github.openfinalshell.android.core.model.ForwardRule
import io.github.openfinalshell.android.core.model.SessionState
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import org.apache.sshd.common.session.SessionHeartbeatController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.common.config.keys.FilePasswordProvider

/** Apache MINA SSHD connection adapter. Channel adapters are isolated behind SshTransport. */
class MinaSshTransport(
    private val hostKeyVerifier: HostKeyVerifier? = null,
    private val proxyPasswordResolver: suspend (String) -> CharArray? = { null }
) : SshTransport {
    private companion object {
        const val TAG = "MinaSshTransport"
        /** Connect/KEX may block on the host-key UI; keep this longer than readyTimeout. */
        const val HOST_KEY_DECISION_TIMEOUT_MS = 10 * 60 * 1000L
    }

    private val mutableState = MutableStateFlow(SessionState.CLOSED)
    override val state: StateFlow<SessionState> = mutableState
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<TransportEvent> = mutableEvents.asSharedFlow()
    private var client: SshClient? = null
    private var session: ClientSession? = null
    private var intentionalClose = false
    private var proxyTunnel: ProxyTunnel? = null
    private var ptyType = "xterm-256color"
    private var charsetName = "utf-8"
    private var startupCommand: String? = null

    override suspend fun connect(profile: ConnectionProfile, credentials: Credentials) = withContext(Dispatchers.IO) {
        require(profile.port in 1..65535) { "invalid SSH port" }
        check(session == null) { "SSH session is already connected" }
        intentionalClose = false
        ptyType = profile.terminal.termType.ifBlank { "xterm-256color" }
        charsetName = profile.terminal.charset.ifBlank { "utf-8" }
        startupCommand = profile.terminal.startupCommand?.trim()?.ifEmpty { null }
        mutableState.value = SessionState.CONNECTING
        val ssh: SshClient
        var connected: ClientSession? = null
        try {
            // sshd-core resolves its I/O provider while constructing ClientBuilder. Keep this
            // inside the guarded block so provider/class-loading failures are cleaned up and
            // logged with their complete cause chain instead of leaking a class name to the UI.
            // Android can report every default KEX as unsupported during MINA's capability
            // scan. Supply the provider-aware list explicitly so start() never receives an
            // empty KeyExchangeFactories configuration.
            val baseKex = AndroidSshdInitializer.keyExchangeFactories()
            val kexFactories = if (profile.options.legacyAlgorithms) {
                SshRuntimePolicy.appendLegacyKex(baseKex)
            } else {
                baseKex
            }
            ssh = ClientBuilder.builder()
                .keyExchangeFactories(kexFactories)
                .signatureFactories(AndroidSshdInitializer.signatureFactories())
                .build()
            if (profile.options.legacyAlgorithms) SshRuntimePolicy.applyLegacy(ssh)
            if (profile.options.compress) {
                val compression = SshRuntimePolicy.compressionFactories()
                if (compression.any { it.name != "none" }) ssh.compressionFactories = compression
            }
            // Register the instance before any configuration/start call so every partially
            // initialized client is stopped by the common failure path below.
            client = ssh
            ssh.serverKeyVerifier = if (hostKeyVerifier == null) {
                // Never silently trust an unknown host. Callers must provide the app's TOFU policy.
                RejectAllServerKeyVerifier.INSTANCE
            } else {
                org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, key ->
                    runBlocking { hostKeyVerifier.verify(profile.host, profile.port, key) }
                }
            }
            // Some servers expose password authentication only as keyboard-interactive. Reuse the
            // explicitly supplied password for non-echo prompts; never fabricate an answer.
            ssh.userInteraction = PasswordKeyboardInteraction(credentials.password ?: credentials.passphrase)
            ssh.start()
            mutableState.value = SessionState.AUTHENTICATING
            val timeout = ConnectionTiming.readyTimeoutMillis(profile.options.readyTimeout)
            // Host-key TOFU may wait on a Compose dialog. Connect/KEX uses a longer budget so the
            // readyTimeout does not abort while the user reads the fingerprint; auth stays short.
            val connectTimeout = if (hostKeyVerifier != null) {
                maxOf(timeout, HOST_KEY_DECISION_TIMEOUT_MS)
            } else {
                timeout
            }
            val proxy = profile.proxy?.takeUnless { it.type == "none" }
            if (proxy != null) {
                val password = proxy.passwordRef?.let { proxyPasswordResolver(it) }
                try { proxyTunnel = ProxyTunnel.open(proxy, profile.host, profile.port, password, timeout.toInt()) }
                finally { password?.fill('\u0000') }
            }
            connected = ssh.connect(profile.username, if (proxyTunnel != null) "127.0.0.1" else profile.host,
                proxyTunnel?.port ?: profile.port).verify(connectTimeout).session
            credentials.password?.let { connected.addPasswordIdentity(String(it)) }
            // Key parsing is delegated to Apache MINA's parser so OpenSSH/PEM formats remain supported.
            credentials.privateKey?.let { keyBytes ->
                val parser = org.apache.sshd.common.util.security.SecurityUtils.getKeyPairResourceParser()
                val passwordProvider = credentials.passphrase?.let { FilePasswordProvider.of(String(it)) }
                parser.loadKeyPairs(null, null, passwordProvider, ByteArrayInputStream(keyBytes))
                    .forEach { connected.addPublicKeyIdentity(it) }
            }
            connected.auth().verify(timeout)
            val keepaliveSeconds = ConnectionTiming.keepaliveSeconds(profile.options.keepaliveInterval)
            if (keepaliveSeconds > 0) {
                connected.setSessionHeartbeat(SessionHeartbeatController.HeartbeatType.IGNORE, TimeUnit.SECONDS, keepaliveSeconds.toLong())
            }
            session = connected
            connected.addCloseFutureListener {
                if (!intentionalClose && session === connected) {
                    session = null
                    proxyTunnel?.close()
                    proxyTunnel = null
                    mutableState.value = SessionState.CLOSED
                    mutableEvents.tryEmit(TransportEvent.Disconnected())
                }
            }
            mutableState.value = SessionState.READY
        } catch (error: Throwable) {
            Log.e(TAG, "SSH client initialization or connection failed", error)
            connected?.close(false)
            client?.stop()
            client = null
            session = null
            proxyTunnel?.close()
            proxyTunnel = null
            mutableState.value = SessionState.CLOSED
            throw error
        }
    }

    override suspend fun openShell(cols: Int, rows: Int): ShellChannel = withContext(Dispatchers.IO) {
        checkNotNull(session) { "SSH session is not connected" }.let { current ->
            val channel = current.createShellChannel()
            channel.setPtyType(ptyType)
            channel.setPtyColumns(cols)
            channel.setPtyLines(rows)
            channel.open().verify()
            val shell = MinaShellChannel(channel)
            val command = startupCommand
            if (!charsetNeedsTranslation(charsetName)) {
                if (command != null) shell.write((command + "\n").toByteArray(Charsets.UTF_8))
                shell
            } else {
                CharsetShellChannel(shell, charsetName).also { translated ->
                    if (command != null) translated.writeRemoteText(command + "\n")
                }
            }
        }
    }

    override suspend fun openExec(command: String): ExecChannel = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "exec command must not be blank" }
        val current = checkNotNull(session) { "SSH session is not connected" }
        val channel = current.createExecChannel(command)
        channel.setRedirectErrorStream(true)
        channel.open().verify()
        MinaExecChannel(channel)
    }

    override suspend fun openSftp(): SftpChannel = withContext(Dispatchers.IO) {
        MinaSftpChannel(
            StrictSftpClientFactory.createSftpClient(checkNotNull(session) { "SSH session is not connected" })
        )
    }

    /**
     * Delegates tunnel lifecycle to MINA's standard forwarding trackers. This keeps all three
     * forwarding modes inside the authenticated SSH connection and lets the tracker own socket
     * cleanup when the session is disconnected.
     */
    override suspend fun startForwarding(rule: ForwardRule): AutoCloseable = withContext(Dispatchers.IO) {
        require(rule.bindPort in 1..65535) { "invalid forwarding bind port" }
        val current = checkNotNull(session) { "SSH session is not connected" }
        val bind = org.apache.sshd.common.util.net.SshdSocketAddress(rule.bindAddr, rule.bindPort)
        val destination = if (rule.type.equals("dynamic", ignoreCase = true)) {
            null
        } else {
            val host = rule.dstHost ?: error("forwarding destination host is required")
            val port = rule.dstPort ?: error("forwarding destination port is required")
            require(port in 1..65535) { "invalid forwarding destination port" }
            org.apache.sshd.common.util.net.SshdSocketAddress(host, port)
        }
        when (rule.type.lowercase()) {
            "local" -> current.createLocalPortForwardingTracker(bind, requireNotNull(destination))
            "remote" -> current.createRemotePortForwardingTracker(bind, requireNotNull(destination))
            "dynamic" -> current.createDynamicPortForwardingTracker(bind)
            else -> error("unsupported forwarding type: ${rule.type}")
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        intentionalClose = true
        session?.close(false)
        proxyTunnel?.close()
        proxyTunnel = null
        client?.stop()
        session = null
        client = null
        ptyType = "xterm-256color"
        charsetName = "utf-8"
        startupCommand = null
        mutableState.value = SessionState.CLOSED
    }
}

private class PasswordKeyboardInteraction(secret: CharArray?) : UserInteraction {
    private val value = secret?.concatToString()

    override fun isInteractionAllowed(session: ClientSession): Boolean = !value.isNullOrEmpty()

    override fun interactive(
        session: ClientSession,
        name: String,
        instruction: String,
        lang: String,
        prompts: Array<String>,
        echo: BooleanArray
    ): Array<String> = Array(prompts.size) { index ->
        if (!echo.getOrElse(index) { true }) value.orEmpty() else ""
    }

    override fun getUpdatedPassword(session: ClientSession, prompt: String, lang: String): String? = value
}

package io.github.openfinalshell.android.core.ssh

import android.util.Log
import java.nio.file.Path
import java.security.Security
import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityProviderChoice
import org.apache.sshd.common.util.security.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Supplies Apache MINA SSHD with an Android-compatible user home directory.
 *
 * SSHD resolves its default host configuration and key locations while
 * initializing ClientBuilder. Android does not expose a conventional
 * java.home/user.home directory, so this must be configured before the first
 * SshClient is created.
 */
object AndroidSshdInitializer {
    private const val TAG = "AndroidSshdInitializer"
    private val lock = Any()

    // These factories use the JCA DH/EC primitives available on Android API 26+.
    // Curve25519 is probed first because newer Android/BC providers support it.
    private val portableKexFactories = listOf(
        BuiltinDHFactories.curve25519,
        BuiltinDHFactories.ecdhp256,
        BuiltinDHFactories.ecdhp384,
        BuiltinDHFactories.ecdhp521,
        BuiltinDHFactories.dhg14_256,
        BuiltinDHFactories.dhg14
    )

    /**
     * Host-key / signature algorithms offered to the peer. Modern OpenSSH defaults to
     * ssh-ed25519; without it, negotiation fails against servers that only present Ed25519.
     */
    private val preferredSignatures = listOf(
        BuiltinSignatures.ed25519,
        BuiltinSignatures.sk_ssh_ed25519,
        BuiltinSignatures.nistp256,
        BuiltinSignatures.nistp384,
        BuiltinSignatures.nistp521,
        BuiltinSignatures.sk_ecdsa_sha2_nistp256,
        BuiltinSignatures.rsaSHA512,
        BuiltinSignatures.rsaSHA256,
        BuiltinSignatures.rsa
    )

    @Volatile
    private var configured = false

    /** Configure SSHD once for the current Android application process. */
    fun configure(userHome: Path) {
        require(userHome.toString().isNotBlank()) { "SSH user home path must not be blank" }
        if (configured) return

        synchronized(lock) {
            if (configured) return
            PathUtils.setUserHomeFolderResolver { userHome }
            registerSecurityProviderLocked()
            configured = true
        }
    }

    /**
     * Returns KEX factories that are usable by the Android runtime.
     *
     * MINA's default setup filters factories using JCA capability probes. On some Android
     * provider combinations those probes return an empty list even though the underlying
     * DH/EC implementation is usable. The explicit fallback keeps the client configurable
     * and verifies each factory can be constructed before exposing it to SSHD.
     */
    fun keyExchangeFactories(): List<KeyExchangeFactory> {
        synchronized(lock) { registerSecurityProviderLocked() }

        val detected = runCatching { ClientBuilder.setUpDefaultKeyExchanges(true) }
            .onFailure { Log.w(TAG, "MINA default KEX detection failed; using portable fallback", it) }
            .getOrNull()
            .orEmpty()
        if (detected.isNotEmpty()) return detected

        val fallback = portableKexFactories.mapNotNull { factory ->
            runCatching {
                // Constructing the DH implementation exercises the provider lookups used by
                // the actual handshake (KeyAgreement, KeyPairGenerator and digest selection).
                factory.create()
                ClientBuilder.DH2KEX.apply(factory)
            }.onFailure {
                Log.w(TAG, "Android KEX unavailable: ${factory.name}", it)
            }.getOrNull()
        }
        check(fallback.isNotEmpty()) {
            "No Android-compatible SSH key exchange factory is available"
        }
        return fallback
    }

    /**
     * Signature factories used both for client auth and for negotiating server host-key
     * algorithms. Explicitly prefer ssh-ed25519 once the EdDSA provider is registered.
     */
    fun signatureFactories(): List<NamedFactory<Signature>> {
        synchronized(lock) { registerSecurityProviderLocked() }

        val detected = runCatching { ClientBuilder.setUpDefaultSignatureFactories(true) }
            .onFailure { Log.w(TAG, "MINA default signature detection failed; using portable list", it) }
            .getOrNull()
            .orEmpty()

        val preferred = preferredSignatures.filter { factory ->
            runCatching { factory.isSupported }.getOrDefault(false)
        }
        if (preferred.none { it.name == BuiltinSignatures.ed25519.name }) {
            Log.w(TAG, "ssh-ed25519 is not supported after EdDSA registration")
        }

        // Prefer our ordered list (ed25519 first) while keeping any extras MINA detected.
        val byName = LinkedHashMap<String, NamedFactory<Signature>>()
        preferred.forEach { byName[it.name] = it }
        detected.forEach { byName.putIfAbsent(it.name, it) }
        check(byName.isNotEmpty()) { "No Android-compatible SSH signature factory is available" }
        return byName.values.toList()
    }

    private fun registerSecurityProviderLocked() {
        // Android (and some OEMs) ship a JCA provider literally named "BC" that is a
        // stripped stub without MessageDigest SHA-256. Apache MINA SSHD will happily
        // bind digests to MessageDigest.getInstance(algo, "BC") once its registrar
        // sees that name — which breaks every KEX (DHGClient.init) once EdDSA/BC
        // registration runs. Replace the stub with the packaged bcprov first so any
        // named-"BC" lookup resolves to a full provider, then keep MINA on the
        // default JCA chain for digests.
        ensurePackagedBouncyCastleLocked()

        // Must run before the first SecurityUtils.register() call. Harmless if register
        // already happened: we also force EMPTY below so late recovery still works
        // when the MessageDigest factory cache has not been populated yet.
        SecurityUtils.setAPrioriDisabledProvider(SecurityUtils.BOUNCY_CASTLE, true)
        SecurityUtils.setDefaultProviderChoice(SecurityProviderChoice.EMPTY)

        // Trigger MINA's registrar after the provider table is sane. With BC a-priori
        // disabled, MINA leaves digests on the platform provider; EdDSA still registers
        // via net.i2p.crypto:eddsa for ssh-ed25519.
        runCatching { SecurityUtils.isBouncyCastleRegistered() }
            .onFailure { Log.w(TAG, "MINA security provider registration failed", it) }

        val eddsa = runCatching { SecurityUtils.isEDDSACurveSupported() }
            .onFailure { Log.w(TAG, "EdDSA provider probe failed", it) }
            .getOrDefault(false)
        if (!eddsa) {
            Log.w(TAG, "EdDSA curve is not available; ssh-ed25519 host keys will not negotiate")
        }
    }

    /**
     * Ensure [Security] exposes a full [BouncyCastleProvider] under the name "BC".
     * Returns without changes when the packaged provider is already installed.
     */
    private fun ensurePackagedBouncyCastleLocked() {
        val expectedClass = BouncyCastleProvider::class.java.name
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing != null && existing.javaClass.name == expectedClass) {
            return
        }

        val replacedFrom = existing?.javaClass?.name
        runCatching {
            if (existing != null) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            }
            val position = Security.insertProviderAt(BouncyCastleProvider(), 1)
            val now = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (now == null || now.javaClass.name != expectedClass) {
                error(
                    "Packaged BouncyCastleProvider not installed " +
                        "(insertProviderAt=$position, now=${now?.javaClass?.name})"
                )
            }
        }.onSuccess {
            if (replacedFrom != null) {
                // android.util.Log is not mocked in JVM unit tests — never fail init on logging.
                runCatching {
                    Log.i(TAG, "Replaced incompatible JCA provider BC ($replacedFrom) with packaged bcprov")
                }
            }
        }.onFailure { err ->
            runCatching { Log.w(TAG, "Unable to install packaged BouncyCastleProvider", err) }
        }
    }
}

package io.github.openfinalshell.android.core.connection

import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.SshClient
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.compression.BuiltinCompressions
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.signature.BuiltinSignatures

/**
 * Legacy algorithms are appended only when a connection asks for them. Compression factories are
 * offered only when that connection has compression enabled.
 */
object SshRuntimePolicy {
    val legacyKexNames = listOf("diffie-hellman-group14-sha1", "diffie-hellman-group1-sha1")
    val legacyHostKeyNames = listOf("ssh-rsa", "ssh-dss")
    val legacyCipherNames = listOf("aes128-cbc", "aes256-cbc", "3des-cbc")

    fun appendLegacyKex(current: List<KeyExchangeFactory>): List<KeyExchangeFactory> {
        val extra = listOf(BuiltinDHFactories.dhg14, BuiltinDHFactories.dhg1).mapNotNull { factory ->
            runCatching {
                factory.create()
                ClientBuilder.DH2KEX.apply(factory)
            }.getOrNull()
        }
        val present = current.map { it.name }.toSet()
        return current + extra.filter { it.name !in present }
    }

    fun applyLegacy(client: SshClient) {
        val ciphers = listOf(
            BuiltinCiphers.aes128cbc,
            BuiltinCiphers.aes256cbc,
            BuiltinCiphers.tripledescbc
        ).filter { it.isSupported }
        val presentCiphers = client.cipherFactories.map { it.name }.toSet()
        val missingCiphers = ciphers.filter { it.name !in presentCiphers }
        if (missingCiphers.isNotEmpty()) client.cipherFactories = client.cipherFactories + missingCiphers

        val signatures = listOf(BuiltinSignatures.rsa, BuiltinSignatures.dsa).filter { it.isSupported }
        val presentSignatures = client.signatureFactories.map { it.name }.toSet()
        val missingSignatures = signatures.filter { it.name !in presentSignatures }
        if (missingSignatures.isNotEmpty()) client.signatureFactories = client.signatureFactories + missingSignatures
    }

    fun compressionFactories(): List<org.apache.sshd.common.compression.CompressionFactory> =
        listOf(BuiltinCompressions.delayedZlib, BuiltinCompressions.zlib, BuiltinCompressions.none)
            .filter { it.isSupported }
}

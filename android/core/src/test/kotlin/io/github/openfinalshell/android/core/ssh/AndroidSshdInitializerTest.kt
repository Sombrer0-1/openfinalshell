package io.github.openfinalshell.android.core.ssh

import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.sshd.common.util.security.SecurityProviderChoice
import org.apache.sshd.common.util.security.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Test

class AndroidSshdInitializerTest {
    @Test
    fun `key exchange factory selection never returns an empty list`() {
        val factories = AndroidSshdInitializer.keyExchangeFactories()

        assertTrue(factories.isNotEmpty())
        assertTrue(factories.all { it.name.isNotBlank() })
    }

    @Test
    fun `MINA uses a provider that exposes SHA-256`() {
        AndroidSshdInitializer.keyExchangeFactories()

        assertTrue(SecurityUtils.getAPrioriDisabledProviders().contains(SecurityUtils.BOUNCY_CASTLE))
        assertEquals(SecurityProviderChoice.EMPTY, SecurityUtils.getDefaultProviderChoice())
        val digest = SecurityUtils.getMessageDigest("SHA-256")
        assertTrue(digest.digest(ByteArray(0)).isNotEmpty())
    }

    @Test
    fun `packaged BouncyCastle replaces stripped BC so named SHA-256 works`() {
        // Simulate Android framework BC (same name, no MessageDigest.SHA-256).
        val previous = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (previous != null) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        }
        Security.insertProviderAt(object : Provider("BC", 1.0, "stripped-android-bc") {}, 1)

        try {
            AndroidSshdInitializer.keyExchangeFactories()

            val installed = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            assertNotNull(installed)
            assertEquals(BouncyCastleProvider::class.java.name, installed.javaClass.name)

            // Even if MINA cached MessageDigest.getInstance(algo, "BC"), the Security
            // lookup must resolve to full bcprov after our replace.
            val named = MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME)
            assertTrue(named.digest(ByteArray(0)).isNotEmpty())
            assertTrue(SecurityUtils.getMessageDigest("SHA-256").digest(ByteArray(0)).isNotEmpty())
        } finally {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (previous != null) {
                Security.insertProviderAt(previous, 1)
            }
        }
    }

    @Test
    fun `signature factories prefer ssh-ed25519 when EdDSA is available`() {
        val factories = AndroidSshdInitializer.signatureFactories()
        val names = factories.map { it.name }

        assertTrue(factories.isNotEmpty())
        // Java enum `.name` is the constant id ("ed25519"); algorithm id is getName().
        assertTrue("ssh-ed25519" in names, "expected ssh-ed25519 among $names")
        assertEquals("ssh-ed25519", names.first())
        assertTrue(SecurityUtils.isEDDSACurveSupported())
    }
}

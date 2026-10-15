package io.github.openfinalshell.android.core.ssh

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.apache.sshd.sftp.client.SftpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class MinaSftpChannelTest {
    @Test fun settingPermissionsSendsTheOriginalSpecialBitsToTheServer() = runTest {
        var applied: Int? = null
        val client = Proxy.newProxyInstance(
            SftpClient::class.java.classLoader, arrayOf(SftpClient::class.java)
        ) { _, method, args ->
            when (method.name) {
                "stat" -> SftpClient.Attributes().perms(384) // A newly created temporary file: 0600.
                "setStat" -> { applied = (args!![1] as SftpClient.Attributes).permissions; null }
                else -> error("unexpected SFTP call: ${method.name}")
            }
        } as SftpClient
        val channel = MinaSftpChannel(client)
        channel.permissions("/.ofs-edit.tmp", "4755".toInt(8))
        assertEquals("4755".toInt(8), applied)
        channel.permissions("/.ofs-edit.tmp", "2750".toInt(8))
        assertEquals("2750".toInt(8), applied)
        channel.permissions("/.ofs-edit.tmp", "1755".toInt(8))
        assertEquals("1755".toInt(8), applied)
    }
}

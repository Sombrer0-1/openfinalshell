package io.github.openfinalshell.android.core.sftp

import io.github.openfinalshell.android.core.ssh.SftpEntry
import java.util.Locale

/**
 * One ordering for the file list. Directory refresh must not reshuffle rows relative to the
 * previous listing just because the server returned names in a different order.
 */
fun orderedSftpEntries(entries: List<SftpEntry>): List<SftpEntry> =
    entries.sortedWith(
        compareBy<SftpEntry> { it.type != SftpEntry.Type.DIRECTORY }
            .thenBy { it.name.lowercase(Locale.ROOT) }
            // Case-folded names compare equal, and a stable sort would keep whatever order the
            // server happened to return. The raw name and path make a refresh land on one order.
            .thenBy { it.name }
            .thenBy { it.path }
    )

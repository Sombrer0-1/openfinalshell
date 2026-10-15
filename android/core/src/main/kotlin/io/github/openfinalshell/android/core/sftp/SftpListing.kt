package io.github.openfinalshell.android.core.sftp

import io.github.openfinalshell.android.core.ssh.SftpEntry
import java.util.Locale

enum class SftpSortKey { NAME, SIZE, MODIFIED, OWNER }

enum class SftpSortDirection { ASC, DESC }

/**
 * One ordering for the file list. Directories stay ahead of every other type in both directions.
 * A missing size, time, or owner sorts after every present value, including when the key is descending.
 * Equal keys then use the raw name and path so a refresh does not follow the server's order.
 */
fun orderedSftpEntries(
    entries: List<SftpEntry>,
    key: SftpSortKey = SftpSortKey.NAME,
    direction: SftpSortDirection = SftpSortDirection.ASC,
): List<SftpEntry> {
    val directoriesFirst = compareBy<SftpEntry> { it.type != SftpEntry.Type.DIRECTORY }
    val primary = when (key) {
        SftpSortKey.NAME -> textOrder(direction) { it.name.lowercase(Locale.ROOT) }
        SftpSortKey.SIZE -> valueOrder(direction) { it.size }
        SftpSortKey.MODIFIED -> valueOrder(direction) { it.modifiedAtMillis }
        SftpSortKey.OWNER -> textOrder(direction) { ownerKey(it) }
    }
    val tieBreak = compareBy<SftpEntry> { it.name.lowercase(Locale.ROOT) }
        .thenBy { it.name }
        .thenBy { it.path }
    return entries.sortedWith(directoriesFirst.then(primary).then(tieBreak))
}

private fun ownerKey(entry: SftpEntry): String? = entry.owner?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)

private fun textOrder(direction: SftpSortDirection, selector: (SftpEntry) -> String?): Comparator<SftpEntry> =
    valueOrder(direction, selector)

private fun <T : Comparable<T>> valueOrder(
    direction: SftpSortDirection,
    selector: (SftpEntry) -> T?,
): Comparator<SftpEntry> = Comparator { left, right ->
    val a = selector(left)
    val b = selector(right)
    when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        direction == SftpSortDirection.ASC -> a.compareTo(b)
        else -> b.compareTo(a)
    }
}

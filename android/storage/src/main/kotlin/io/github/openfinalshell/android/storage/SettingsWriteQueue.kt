package io.github.openfinalshell.android.storage

/**
 * Serializes settings edits against the last document that was actually loaded or saved.
 *
 * The UI may change several fields before the first load returns, and again while a write is
 * still in flight. Those edits are transforms applied to the confirmed document. A write always
 * persists that confirmed document plus the transforms it captured, and a later edit is left
 * queued instead of being overwritten by the snapshot that was just saved.
 */
class SettingsWriteQueue {
    private val lock = Any()
    private var confirmed: AndroidSettings? = null
    private var dirty = false
    private val transforms = ArrayDeque<(AndroidSettings) -> AndroidSettings>()

    fun isConfirmed(): Boolean = synchronized(lock) { confirmed != null }

    fun needsSave(): Boolean = synchronized(lock) { dirty || transforms.isNotEmpty() }

    fun current(): AndroidSettings = synchronized(lock) { render() }

    fun edit(transform: (AndroidSettings) -> AndroidSettings): AndroidSettings = synchronized(lock) {
        transforms.addLast(transform)
        render()
    }

    /**
     * Installs the document read from storage. Pending transforms stay and are replayed on top,
     * so a load that finishes late cannot discard edits the user already made.
     */
    fun confirm(stored: AndroidSettings, dirty: Boolean = false): AndroidSettings = synchronized(lock) {
        confirmed = stored
        if (dirty) this.dirty = true
        render()
    }

    fun snapshotForSave(): SettingsSaveBatch? = synchronized(lock) {
        if (confirmed == null || (!dirty && transforms.isEmpty())) return null
        SettingsSaveBatch(render(), transforms.size)
    }

    fun commit(batch: SettingsSaveBatch) = synchronized(lock) {
        repeat(batch.transformCount.coerceAtMost(transforms.size)) { transforms.removeFirst() }
        confirmed = batch.settings
        dirty = transforms.isNotEmpty()
    }

    private fun render(): AndroidSettings {
        var value = confirmed ?: AndroidSettings()
        for (transform in transforms) value = transform(value)
        return value
    }
}

data class SettingsSaveBatch(val settings: AndroidSettings, val transformCount: Int)

package io.github.openfinalshell.android.core.filesync

enum class SaveIntent { SAVE, SWITCH_ENCODING }

sealed interface RemoteSnapshot {
    data class Present(val realPath: String, val fingerprint: String, val size: Long) : RemoteSnapshot
    data object Absent : RemoteSnapshot
}

sealed interface AcknowledgedRemote {
    data class Content(val fingerprint: String) : AcknowledgedRemote
    data object Absent : AcknowledgedRemote
}

/**
 * Facts the caller already gathered. This function does not open a session.
 * Each release flag covers only its own gate.
 */
data class SaveInput(
    val intent: SaveIntent = SaveIntent.SAVE,
    val roundTripOk: Boolean = true,
    val containsNul: Boolean = false,
    val encodedSize: Long = 0,
    val openedRealPath: String = "",
    val remote: RemoteSnapshot = RemoteSnapshot.Absent,
    val baselineFingerprint: String = "",
    val creating: Boolean = false,
    val atomicAvailable: Boolean = true,
    val overwriteRemoteChanges: Boolean = false,
    val allowNonAtomic: Boolean = false,
    val allowShrink: Boolean = false,
    val acknowledged: AcknowledgedRemote? = null,
    val draftDirty: Boolean = false,
    val discardDraft: Boolean = false,
    val rereadFailed: Boolean = false,
)

data class SaveRelease(
    val overwriteRemoteChanges: Boolean,
    val allowNonAtomic: Boolean,
    val allowShrink: Boolean,
    val acknowledged: AcknowledgedRemote? = null,
)

sealed interface RemoteSaveDecision {
    data object Proceed : RemoteSaveDecision
    data class HardReject(val reason: Reason) : RemoteSaveDecision {
        enum class Reason { ENCODING, NUL, TOO_LARGE, REALPATH_CHANGED }
    }
    data class Conflict(val kind: Kind, val fingerprint: String?) : RemoteSaveDecision {
        enum class Kind { DELETED, CONTENT }
    }
    data class ShrinkConfirm(val remoteBytes: Long, val localBytes: Long) : RemoteSaveDecision
    data object NonAtomicConfirm : RemoteSaveDecision
    data object AlreadyExists : RemoteSaveDecision
    data object Reread : RemoteSaveDecision
    data object KeepDraft : RemoteSaveDecision

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val SHRINK_FLOOR = 4 * 1024

        fun decide(input: SaveInput): RemoteSaveDecision {
            if (input.intent == SaveIntent.SWITCH_ENCODING) {
                if (input.rereadFailed || (input.draftDirty && !input.discardDraft)) return KeepDraft
                return Reread
            }
            if (!input.roundTripOk) return HardReject(HardReject.Reason.ENCODING)
            if (input.containsNul) return HardReject(HardReject.Reason.NUL)
            if (input.encodedSize > MAX_BYTES) return HardReject(HardReject.Reason.TOO_LARGE)
            if (input.creating) {
                if (input.remote is RemoteSnapshot.Present) return AlreadyExists
                return Proceed
            }
            val remote = input.remote
            if (remote is RemoteSnapshot.Present && remote.realPath != input.openedRealPath) {
                return HardReject(HardReject.Reason.REALPATH_CHANGED)
            }
            val overwrite = overwriteMatches(input, remote)
            if (remote is RemoteSnapshot.Absent) {
                if (!overwrite) return Conflict(Conflict.Kind.DELETED, null)
            } else if (remote is RemoteSnapshot.Present && remote.fingerprint != input.baselineFingerprint) {
                if (!overwrite) return Conflict(Conflict.Kind.CONTENT, remote.fingerprint)
            }
            val remoteSize = (remote as? RemoteSnapshot.Present)?.size ?: 0L
            if (!input.allowShrink && looksTruncated(remoteSize, input.encodedSize)) {
                return ShrinkConfirm(remoteSize, input.encodedSize)
            }
            if (!input.atomicAvailable && !input.allowNonAtomic) return NonAtomicConfirm
            return Proceed
        }

        /**
         * Empty remote content is never a shrink. Zero new bytes always are, at any remote size.
         * The quarter rule starts at 4 KiB and is strict.
         */
        fun looksTruncated(remoteSize: Long, nextSize: Long): Boolean {
            if (remoteSize == 0L) return false
            if (nextSize == 0L) return true
            return remoteSize >= SHRINK_FLOOR && nextSize * 4 < remoteSize
        }

        private fun overwriteMatches(input: SaveInput, remote: RemoteSnapshot): Boolean {
            if (!input.overwriteRemoteChanges) return false
            val acknowledged = input.acknowledged ?: return false
            return when {
                remote is RemoteSnapshot.Absent && acknowledged is AcknowledgedRemote.Absent -> true
                remote is RemoteSnapshot.Present &&
                    acknowledged is AcknowledgedRemote.Content &&
                    acknowledged.fingerprint == remote.fingerprint -> true
                else -> false
            }
        }
    }
}

package io.github.openfinalshell.android.core.filesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSaveDecisionTest {
    private val present = RemoteSnapshot.Present("/file", "base", 80)

    private fun save(build: SaveInput.() -> SaveInput = { this }) = RemoteSaveDecision.decide(SaveInput(
        openedRealPath = "/file",
        remote = present,
        baselineFingerprint = "base",
        encodedSize = 80,
    ).build())

    @Test fun hardRejectsIgnoreEveryGate() {
        val open = SaveInput(
            roundTripOk = false,
            containsNul = true,
            encodedSize = RemoteSaveDecision.MAX_BYTES + 1L,
            openedRealPath = "/file",
            remote = RemoteSnapshot.Present("/elsewhere", "other", 80),
            baselineFingerprint = "base",
            overwriteRemoteChanges = true,
            allowNonAtomic = true,
            allowShrink = true,
            acknowledged = AcknowledgedRemote.Content("other"),
        )
        assertEquals(
            RemoteSaveDecision.HardReject(RemoteSaveDecision.HardReject.Reason.ENCODING),
            RemoteSaveDecision.decide(open),
        )
        assertEquals(
            RemoteSaveDecision.HardReject(RemoteSaveDecision.HardReject.Reason.NUL),
            RemoteSaveDecision.decide(open.copy(roundTripOk = true)),
        )
        assertEquals(
            RemoteSaveDecision.HardReject(RemoteSaveDecision.HardReject.Reason.TOO_LARGE),
            RemoteSaveDecision.decide(open.copy(roundTripOk = true, containsNul = false)),
        )
        assertEquals(
            RemoteSaveDecision.HardReject(RemoteSaveDecision.HardReject.Reason.REALPATH_CHANGED),
            RemoteSaveDecision.decide(open.copy(
                roundTripOk = true,
                containsNul = false,
                encodedSize = 80,
            )),
        )
        assertEquals(RemoteSaveDecision.Proceed, save { copy(encodedSize = RemoteSaveDecision.MAX_BYTES.toLong()) })
    }

    @Test fun deletionAndContentConflictsAreScopedToTheSeenRemote() {
        assertEquals(
            RemoteSaveDecision.Conflict(RemoteSaveDecision.Conflict.Kind.DELETED, null),
            save { copy(remote = RemoteSnapshot.Absent, overwriteRemoteChanges = true, acknowledged = AcknowledgedRemote.Content("base")) },
        )
        assertEquals(
            RemoteSaveDecision.Proceed,
            save {
                copy(
                    remote = RemoteSnapshot.Absent,
                    overwriteRemoteChanges = true,
                    acknowledged = AcknowledgedRemote.Absent,
                    atomicAvailable = true,
                )
            },
        )
        val changed = RemoteSnapshot.Present("/file", "now", 80)
        assertEquals(
            RemoteSaveDecision.Conflict(RemoteSaveDecision.Conflict.Kind.CONTENT, "now"),
            save { copy(remote = changed, overwriteRemoteChanges = true, acknowledged = AcknowledgedRemote.Content("base")) },
        )
        assertEquals(
            RemoteSaveDecision.Proceed,
            save { copy(remote = changed, overwriteRemoteChanges = true, acknowledged = AcknowledgedRemote.Content("now")) },
        )
    }

    @Test fun gatesDoNotReleaseEachOther() {
        val changed = RemoteSnapshot.Present("/file", "now", 8192)
        val conflict = save {
            copy(remote = changed, encodedSize = 0, allowShrink = true, allowNonAtomic = true, atomicAvailable = false)
        }
        assertEquals(RemoteSaveDecision.Conflict(RemoteSaveDecision.Conflict.Kind.CONTENT, "now"), conflict)
        val shrink = save {
            copy(
                remote = changed,
                encodedSize = 0,
                overwriteRemoteChanges = true,
                acknowledged = AcknowledgedRemote.Content("now"),
                allowNonAtomic = true,
                atomicAvailable = false,
            )
        }
        assertEquals(RemoteSaveDecision.ShrinkConfirm(8192, 0), shrink)
        val nonatomic = save {
            copy(
                remote = changed,
                encodedSize = 8000,
                overwriteRemoteChanges = true,
                acknowledged = AcknowledgedRemote.Content("now"),
                allowShrink = true,
                atomicAvailable = false,
            )
        }
        assertEquals(RemoteSaveDecision.NonAtomicConfirm, nonatomic)
        assertEquals(
            RemoteSaveDecision.Proceed,
            save {
                copy(
                    remote = changed,
                    encodedSize = 0,
                    overwriteRemoteChanges = true,
                    allowNonAtomic = true,
                    allowShrink = true,
                    acknowledged = AcknowledgedRemote.Content("now"),
                    atomicAvailable = false,
                )
            },
        )
    }

    @Test fun newEmptyFileDoesNotAskToShrinkAndEncodingSwitchKeepsTheDraft() {
        assertEquals(
            RemoteSaveDecision.Proceed,
            RemoteSaveDecision.decide(SaveInput(creating = true, remote = RemoteSnapshot.Absent, encodedSize = 0)),
        )
        assertEquals(
            RemoteSaveDecision.AlreadyExists,
            RemoteSaveDecision.decide(SaveInput(
                creating = true,
                remote = present,
                encodedSize = 0,
                overwriteRemoteChanges = true,
                allowShrink = true,
            )),
        )
        assertEquals(
            RemoteSaveDecision.Proceed,
            RemoteSaveDecision.decide(SaveInput(
                creating = true,
                remote = RemoteSnapshot.Absent,
                encodedSize = 0,
                atomicAvailable = false,
            )),
        )
        assertEquals(
            RemoteSaveDecision.KeepDraft,
            RemoteSaveDecision.decide(SaveInput(intent = SaveIntent.SWITCH_ENCODING, draftDirty = true)),
        )
        assertEquals(
            RemoteSaveDecision.Reread,
            RemoteSaveDecision.decide(SaveInput(intent = SaveIntent.SWITCH_ENCODING, draftDirty = true, discardDraft = true)),
        )
        assertEquals(
            RemoteSaveDecision.KeepDraft,
            RemoteSaveDecision.decide(SaveInput(
                intent = SaveIntent.SWITCH_ENCODING,
                draftDirty = true,
                discardDraft = true,
                rereadFailed = true,
            )),
        )
        assertEquals(
            RemoteSaveDecision.Reread,
            RemoteSaveDecision.decide(SaveInput(intent = SaveIntent.SWITCH_ENCODING)),
        )
    }

    @Test fun truncationTableMatchesTheDesktopThresholds() {
        val rows = listOf(
            Triple(0L, 0L, false),
            Triple(0L, 10L, false),
            Triple(40L, 0L, true),
            Triple(20480L, 0L, true),
            Triple(4096L, 1023L, true),
            Triple(4096L, 1024L, false),
            Triple(4095L, 1L, false),
            Triple(500L, 10L, false),
            Triple(100000L, 60000L, false),
            Triple(100000L, 24999L, true),
            Triple(1000L, 1200L, false),
        )
        for ((remote, next, expected) in rows) {
            assertEquals("$remote -> $next", expected, RemoteSaveDecision.looksTruncated(remote, next))
        }
        assertFalse(RemoteSaveDecision.looksTruncated(0, 0))
        assertTrue(RemoteSaveDecision.looksTruncated(40, 0))
    }
}

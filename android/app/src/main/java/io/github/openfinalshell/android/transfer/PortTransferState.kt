package io.github.openfinalshell.android.transfer

import io.github.openfinalshell.android.core.sftp.RemoteTextDocument

data class FastDeleteReport(
    val exitCode: Int,
    val output: String,
    val remaining: List<String>,
)

data class PortTransferState(
    val conflictPath: String? = null,
    val messageRes: Int? = null,
    val editor: RemoteTextDocument? = null,
    val editorText: String = "",
    val editorEncoding: String = "utf8",
    val editorSessionId: String? = null,
    val creating: Boolean = false,
    val editorBusy: Boolean = false,
    val nonAtomicConfirmation: Boolean = false,
    val editorConflict: Boolean = false,
    val conflictDeleted: Boolean = false,
    val conflictFingerprint: String? = null,
    val shrinkRemoteBytes: Long? = null,
    val shrinkLocalBytes: Long? = null,
    val alreadyExists: Boolean = false,
    val retargeted: Boolean = false,
    val pendingEncoding: String? = null,
    val pendingOpenPath: String? = null,
    val pendingOpenEncoding: String? = null,
    val pendingNewFileName: String? = null,
    val fastDeleteCommand: String? = null,
    val fastDeletePaths: List<String> = emptyList(),
    val fastDeleteReport: FastDeleteReport? = null,
)

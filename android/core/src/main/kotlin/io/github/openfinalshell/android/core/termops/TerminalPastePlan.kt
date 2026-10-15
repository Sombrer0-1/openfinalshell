package io.github.openfinalshell.android.core.termops

/**
 * Multiline paste confirmation and the bytes that follow it.
 * Content that contains CR or LF needs confirmation when [of]'s `confirmMultiline` is set.
 * A single line never does. Cancelling produces zero bytes. Confirmed text normalizes line
 * breaks to CR; bracketed paste wraps that body in ESC [200~ … ESC [201~.
 */
class TerminalPastePlan(
    val needsConfirmation: Boolean,
    val bytes: ByteArray
) {
    companion object {
        fun of(
            text: String,
            bracketedPaste: Boolean,
            confirmMultiline: Boolean,
            confirmed: Boolean
        ): TerminalPastePlan {
            val needsConfirmation = confirmMultiline && (text.contains('\r') || text.contains('\n'))
            if (needsConfirmation && !confirmed) return TerminalPastePlan(true, ByteArray(0))
            return TerminalPastePlan(needsConfirmation, encode(text, bracketedPaste))
        }

        fun cancelled(): TerminalPastePlan = TerminalPastePlan(true, ByteArray(0))

        fun encode(text: String, bracketedPaste: Boolean): ByteArray {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace('\n', '\r')
            val payload = if (!bracketedPaste) normalized else {
                val body = normalized.trimEnd('\r')
                if (body.isEmpty()) "" else "\u001b[200~$body\u001b[201~"
            }
            return payload.toByteArray(Charsets.UTF_8)
        }
    }
}

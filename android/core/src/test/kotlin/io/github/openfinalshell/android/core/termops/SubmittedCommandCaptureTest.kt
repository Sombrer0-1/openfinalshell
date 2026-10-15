package io.github.openfinalshell.android.core.termops

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SubmittedCommandCaptureTest {
    @Test
    fun `backspace history recall and tab completion are read from the echoed line`() {
        val capture = SubmittedCommandCapture()
        val prompt = "root@host:/etc# "
        capture.noteKeystroke(buffer(listOf(prompt), cursorX = prompt.length, cursorY = 0))

        val backspaced = "$prompt" + "ls"
        val recalled = "$prompt" + "git status"
        val completed = "$prompt" + "cd /etc/v2node"
        val snapshot = capture.snapshot()
        capture.noteSubmit()

        assertEquals("ls", capture.captureEcho(buffer(listOf(backspaced), cursorX = 0, cursorY = 1), snapshot))
        assertEquals("git status", capture.captureEcho(buffer(listOf(recalled), cursorX = 0, cursorY = 1), snapshot))
        assertEquals("cd /etc/v2n", extractSubmittedCommand(prompt + "cd /etc/v2n", prompt.length))
        assertEquals("cd /etc/v2node", capture.captureEcho(buffer(listOf(completed), cursorX = 0, cursorY = 1), snapshot))
    }

    @Test
    fun `a wrapped command is one command and the prompt column survives the wrap`() {
        val rows = listOf(
            TerminalBufferLine("root@h:~# for i in 1 2 3; do echo line", wrapped = false),
            TerminalBufferLine(" && date", wrapped = true)
        )
        val capture = SubmittedCommandCapture()
        capture.noteKeystroke(buffer(listOf(TerminalBufferLine("root@h:~# ")), cursorX = "root@h:~# ".length, cursorY = 0))
        val snapshot = capture.snapshot()
        capture.noteSubmit()
        val command = capture.captureEcho(buffer(rows, cursorX = 0, cursorY = 2), snapshot)
        assertTrue(command!!.startsWith("for i in 1 2 3;"))
        assertTrue(command.endsWith("&& date"))
        assertEquals(80 + " && date".length, readLogicalLine(buffer(rows, cursorX = 9, cursorY = 1)).length)
    }

    @Test
    fun `a wrapped prompt measures the cursor inside the logical line`() {
        val promptRows = listOf(
            TerminalBufferLine("localhost:/data/data/io.github.openfinalshell.", wrapped = false),
            TerminalBufferLine("android/files/home $ ", wrapped = true)
        )
        val capture = SubmittedCommandCapture()
        capture.noteKeystroke(buffer(promptRows, cursorX = "android/files/home $ ".length, cursorY = 1))
        val snapshot = capture.snapshot()
        capture.noteSubmit()

        val echoed = listOf(
            TerminalBufferLine("localhost:/data/data/io.github.openfinalshell.", wrapped = false),
            TerminalBufferLine("android/files/home $ uname -a", wrapped = true),
            TerminalBufferLine("Linux localhost 6.6.66 x86_64 Toybox", wrapped = false),
            TerminalBufferLine("localhost:/data/data/io.github.openfinalshell.", wrapped = false),
            TerminalBufferLine("android/files/home $ ", wrapped = true)
        )
        assertEquals("uname -a", capture.captureEcho(buffer(echoed, cursorX = "android/files/home $ ".length, cursorY = 4), snapshot))
    }

    @Test
    fun `uncertain boundaries password prompts and the alternate screen are dropped`() {
        assertNull(extractSubmittedCommand("➜  ~ echo a > b.txt", null))
        assertNull(extractSubmittedCommand("PS C:\\> dir", null))
        assertEquals("echo hi # 注释", extractSubmittedCommand("root@h:~# echo hi # 注释", null))
        assertEquals("ls", extractSubmittedCommand("root@h:~# ls", 999))
        assertNull(extractSubmittedCommand("[sudo] password for zhang: hunter2", "[sudo] password for zhang: ".length))
        assertNull(extractSubmittedCommand("Enter passphrase for key: 我的口令", 26))
        assertEquals("mysql -uroot -pxxx", extractSubmittedCommand("root@h:~# mysql -uroot -pxxx", null))
        assertNull(extractSubmittedCommand("root@h:~# ", "root@h:~# ".length))

        val capture = SubmittedCommandCapture()
        val alternate = buffer(listOf("root@h:~# 看着像命令"), cursorX = 0, cursorY = 1, kind = TerminalScreenKind.ALTERNATE)
        assertNull(capture.captureEcho(alternate, capture.snapshot()))
        assertNull(capture.captureEcho(buffer(listOf("root@h:~# ls"), cursorX = 0, cursorY = 0), capture.snapshot()))
        assertNull(capture.captureEcho(buffer(listOf("Reading package lists..."), cursorX = 0, cursorY = 1), PromptSnapshot(13, true, "root@host:~#")))
    }

    @Test
    fun `the length cap is 32768 characters and a reused row measures the new prompt`() {
        assertEquals(32_768, extractSubmittedCommand("root@h:~# ${"a".repeat(32_768)}", null)!!.length)
        assertEquals(2_001, extractSubmittedCommand("root@h:~# ${"b".repeat(2_001)}", null)!!.length)
        assertNull(extractSubmittedCommand("root@h:~# ${"c".repeat(32_769)}", null))

        val capture = SubmittedCommandCapture()
        val row = 5023
        capture.noteKeystroke(saturated(row, "root@host:~# cd /tmp", cursorX = 13))
        assertEquals("cd /tmp", submit(capture, saturated(row, "root@host:~# cd /tmp", cursorX = 20)))
        val second = "root@host:/tmp# cd /var/log"
        capture.noteKeystroke(saturated(row, second, cursorX = 16))
        assertEquals("cd /var/log", submit(capture, saturated(row, second, cursorX = 27)))
    }

    @Test
    fun `a programmatic write is not trusted and submit forgets the column`() {
        val capture = SubmittedCommandCapture()
        val rows = listOf("root@h:~# df -h --si")
        capture.noteProgrammaticWrite(buffer(rows, cursorX = 10, cursorY = 0))
        capture.noteKeystroke(buffer(rows, cursorX = 15, cursorY = 0))
        assertNull(capture.promptColumn(buffer(rows, cursorX = 20, cursorY = 0)))
        assertEquals("df -h --si", submit(capture, buffer(rows, cursorX = 20, cursorY = 0)))

        capture.noteKeystroke(buffer(listOf("root@host:~# pwd"), cursorX = 13, cursorY = 0))
        assertEquals("pwd", submit(capture, buffer(listOf("root@host:~# pwd"), cursorX = 16, cursorY = 0)))
        assertNull(capture.promptColumn(buffer(listOf("root@host:~# pwd"), cursorX = 16, cursorY = 0)))
    }

    private fun submit(capture: SubmittedCommandCapture, beforeEnter: TerminalLineBuffer): String? {
        val snapshot = capture.snapshot()
        capture.noteSubmit()
        return capture.captureEcho(beforeEnter.copy(cursorX = 0, cursorY = beforeEnter.cursorY + 1), snapshot)
    }

    private fun buffer(
        rows: List<Any>,
        cursorX: Int,
        cursorY: Int,
        kind: TerminalScreenKind = TerminalScreenKind.NORMAL
    ): TerminalLineBuffer = TerminalLineBuffer(
        rows = rows.map { row ->
            when (row) {
                is TerminalBufferLine -> row
                is String -> TerminalBufferLine(row)
                else -> error(row)
            }
        },
        cursorX = cursorX,
        cursorY = cursorY,
        kind = kind
    )

    private fun saturated(absoluteRow: Int, text: String, cursorX: Int): TerminalLineBuffer = TerminalLineBuffer(
        rows = listOf(TerminalBufferLine(text)),
        cursorX = cursorX,
        cursorY = 0,
        baseY = absoluteRow,
        origin = absoluteRow
    )
}

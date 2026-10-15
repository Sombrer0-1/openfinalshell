package io.github.openfinalshell.android.core.filesync

/** Low nine permission bits, plus the special bits a later chmod must not clear. */
object PermissionBits {
    const val ACCESS_MASK = 0x1FF
    const val SPECIAL_MASK = 0xE00

    fun formatOctal(mode: Int): String = (mode and ACCESS_MASK).toString(8).padStart(3, '0')

    /** The dialog's octal field. Special bits in the text are ignored; the file keeps its own. */
    fun parseOctal(text: String): Int {
        val cleaned = text.trim()
        require(cleaned.isNotEmpty() && cleaned.all { it in '0'..'7' }) { "octal permission" }
        return cleaned.toInt(8) and ACCESS_MASK
    }

    /** Nine cells, owner/group/other × read/write/execute. */
    fun toGrid(mode: Int): BooleanArray = BooleanArray(9) { index ->
        mode and (1 shl (8 - index)) != 0
    }

    fun fromGrid(grid: BooleanArray): Int {
        require(grid.size == 9) { "permission grid" }
        var mode = 0
        for (index in grid.indices) {
            if (grid[index]) mode = mode or (1 shl (8 - index))
        }
        return mode
    }

    /** [requested] supplies the low nine bits. setuid, setgid, and sticky stay from [existing]. */
    fun merge(existing: Int, requested: Int): Int =
        (existing and SPECIAL_MASK) or (requested and ACCESS_MASK)
}

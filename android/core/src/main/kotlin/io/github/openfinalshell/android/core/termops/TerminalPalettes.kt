package io.github.openfinalshell.android.core.termops

enum class TerminalPaletteId(val storageKey: String) {
    ONE_DARK("one-dark"),
    DRACULA("dracula"),
    NORD("nord"),
    SOLARIZED_DARK("solarized-dark"),
    GITHUB_LIGHT("github-light"),
    SOLARIZED_LIGHT("solarized-light"),
    FOLLOW_UI("auto");

    companion object {
        fun fromStorageKey(key: String): TerminalPaletteId =
            entries.firstOrNull { it.storageKey == key } ?: FOLLOW_UI
    }
}

enum class TerminalUiMode { DARK, LIGHT }

data class TerminalPalette(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    /** ANSI colors 0–15: normal black…white, then bright black…white. ARGB. */
    val colors: List<Int>
) {
    init {
        require(colors.size == 16)
    }
}

/** Color values match the desktop `themes/terminal.ts` table. Alpha is opaque. */
object TerminalPalettes {
    val oneDark: TerminalPalette = palette(
        "#282c34", "#abb2bf", "#528bff",
        "#282c34", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#abb2bf",
        "#5c6370", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#ffffff"
    )
    val dracula: TerminalPalette = palette(
        "#282a36", "#f8f8f2", "#f8f8f2",
        "#21222c", "#ff5555", "#50fa7b", "#f1fa8c", "#bd93f9", "#ff79c6", "#8be9fd", "#f8f8f2",
        "#6272a4", "#ff6e6e", "#69ff94", "#ffffa5", "#d6acff", "#ff92df", "#a4ffff", "#ffffff"
    )
    val nord: TerminalPalette = palette(
        "#2e3440", "#d8dee9", "#d8dee9",
        "#3b4252", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead", "#88c0d0", "#e5e9f0",
        "#4c566a", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead", "#8fbcbb", "#eceff4"
    )
    val solarizedDark: TerminalPalette = palette(
        "#002b36", "#839496", "#839496",
        "#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5",
        "#586e75", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3"
    )
    val githubLight: TerminalPalette = palette(
        "#ffffff", "#24292f", "#0969da",
        "#24292f", "#cf222e", "#116329", "#4d2d00", "#0969da", "#8250df", "#1b7c83", "#6e7781",
        "#57606a", "#a40e26", "#1a7f37", "#633c01", "#218bff", "#a475f9", "#3192aa", "#8c959f"
    )
    val solarizedLight: TerminalPalette = palette(
        "#fdf6e3", "#657b83", "#657b83",
        "#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5",
        "#002b36", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3"
    )

    fun resolve(id: TerminalPaletteId, uiMode: TerminalUiMode): TerminalPalette = when (id) {
        TerminalPaletteId.ONE_DARK -> oneDark
        TerminalPaletteId.DRACULA -> dracula
        TerminalPaletteId.NORD -> nord
        TerminalPaletteId.SOLARIZED_DARK -> solarizedDark
        TerminalPaletteId.GITHUB_LIGHT -> githubLight
        TerminalPaletteId.SOLARIZED_LIGHT -> solarizedLight
        TerminalPaletteId.FOLLOW_UI -> if (uiMode == TerminalUiMode.DARK) oneDark else githubLight
    }

    private fun palette(background: String, foreground: String, cursor: String, vararg ansi: String): TerminalPalette =
        TerminalPalette(argb(background), argb(foreground), argb(cursor), ansi.map(::argb))

    private fun argb(hex: String): Int = (0xFF shl 24) or hex.removePrefix("#").toInt(16)
}

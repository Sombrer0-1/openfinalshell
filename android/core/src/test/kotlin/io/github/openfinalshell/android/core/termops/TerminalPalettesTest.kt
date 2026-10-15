package io.github.openfinalshell.android.core.termops

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class TerminalPalettesTest {
    @Test
    fun `named palettes use the desktop ARGB values`() {
        assertPalette(
            TerminalPalettes.oneDark,
            "#282c34", "#abb2bf", "#528bff",
            "#282c34", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#abb2bf",
            "#5c6370", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#ffffff"
        )
        assertPalette(
            TerminalPalettes.dracula,
            "#282a36", "#f8f8f2", "#f8f8f2",
            "#21222c", "#ff5555", "#50fa7b", "#f1fa8c", "#bd93f9", "#ff79c6", "#8be9fd", "#f8f8f2",
            "#6272a4", "#ff6e6e", "#69ff94", "#ffffa5", "#d6acff", "#ff92df", "#a4ffff", "#ffffff"
        )
        assertPalette(
            TerminalPalettes.nord,
            "#2e3440", "#d8dee9", "#d8dee9",
            "#3b4252", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead", "#88c0d0", "#e5e9f0",
            "#4c566a", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead", "#8fbcbb", "#eceff4"
        )
        assertPalette(
            TerminalPalettes.solarizedDark,
            "#002b36", "#839496", "#839496",
            "#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5",
            "#586e75", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3"
        )
        assertPalette(
            TerminalPalettes.githubLight,
            "#ffffff", "#24292f", "#0969da",
            "#24292f", "#cf222e", "#116329", "#4d2d00", "#0969da", "#8250df", "#1b7c83", "#6e7781",
            "#57606a", "#a40e26", "#1a7f37", "#633c01", "#218bff", "#a475f9", "#3192aa", "#8c959f"
        )
        assertPalette(
            TerminalPalettes.solarizedLight,
            "#fdf6e3", "#657b83", "#657b83",
            "#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5",
            "#002b36", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3"
        )
    }

    @Test
    fun `follow interface uses One Dark in dark mode and GitHub Light in light mode`() {
        assertEquals(TerminalPalettes.oneDark, TerminalPalettes.resolve(TerminalPaletteId.FOLLOW_UI, TerminalUiMode.DARK))
        assertEquals(TerminalPalettes.githubLight, TerminalPalettes.resolve(TerminalPaletteId.FOLLOW_UI, TerminalUiMode.LIGHT))
        assertEquals(
            TerminalPalettes.dracula,
            TerminalPalettes.resolve(TerminalPaletteId.DRACULA, TerminalUiMode.LIGHT)
        )
        assertEquals(TerminalPaletteId.FOLLOW_UI, TerminalPaletteId.fromStorageKey("auto"))
        assertEquals(TerminalPaletteId.FOLLOW_UI, TerminalPaletteId.fromStorageKey("missing"))
        assertTrue(TerminalPalettes.oneDark.colors.all { it ushr 24 == 0xFF })
    }

    private fun assertPalette(
        palette: TerminalPalette,
        background: String,
        foreground: String,
        cursor: String,
        vararg ansi: String
    ) {
        assertEquals(argb(background), palette.background)
        assertEquals(argb(foreground), palette.foreground)
        assertEquals(argb(cursor), palette.cursor)
        assertEquals(ansi.map(::argb), palette.colors)
    }

    private fun argb(hex: String): Int = (0xFF shl 24) or hex.removePrefix("#").toInt(16)
}

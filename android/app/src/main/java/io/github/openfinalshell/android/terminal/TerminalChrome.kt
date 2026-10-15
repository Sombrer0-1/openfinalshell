package io.github.openfinalshell.android.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.core.termops.TerminalPalette
import io.github.openfinalshell.android.storage.CommandHistoryRecord
import java.text.DateFormat
import java.util.Date

@Composable
fun TerminalSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    caseSensitive: Boolean,
    onCase: (Boolean) -> Unit,
    regex: Boolean,
    onRegex: (Boolean) -> Unit,
    message: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            },
            label = { Text(stringResource(R.string.terminal_find_hint)) },
            singleLine = true,
            isError = message != null && regex,
            keyboardOptions = KeyboardOptions.Default
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPrevious) { Text(stringResource(R.string.terminal_find_previous)) }
            TextButton(onClick = onNext) { Text(stringResource(R.string.terminal_find_next)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = caseSensitive, onCheckedChange = onCase)
                Text(stringResource(R.string.terminal_find_case))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = regex, onCheckedChange = onRegex)
                Text(stringResource(R.string.terminal_find_regex))
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
        }
        if (!message.isNullOrBlank()) {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TerminalKeyRow(
    ctrl: Boolean,
    onCtrl: () -> Unit,
    onKey: (ScreenKey) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KeyButton(stringResource(R.string.action_enter), stringResource(R.string.action_enter)) { onKey(ScreenKey.ENTER) }
        KeyButton(stringResource(R.string.terminal_key_esc), stringResource(R.string.terminal_key_esc)) { onKey(ScreenKey.ESC) }
        KeyButton(stringResource(R.string.terminal_key_tab), stringResource(R.string.terminal_key_tab)) { onKey(ScreenKey.TAB) }
        TextButton(onClick = onCtrl) {
            Text(
                stringResource(R.string.terminal_key_ctrl),
                color = if (ctrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        KeyButton("↑", stringResource(R.string.terminal_key_up)) { onKey(ScreenKey.UP) }
        KeyButton("↓", stringResource(R.string.terminal_key_down)) { onKey(ScreenKey.DOWN) }
        KeyButton("←", stringResource(R.string.terminal_key_left)) { onKey(ScreenKey.LEFT) }
        KeyButton("→", stringResource(R.string.terminal_key_right)) { onKey(ScreenKey.RIGHT) }
        KeyButton("/", stringResource(R.string.terminal_key_slash)) { onKey(ScreenKey.SLASH) }
    }
}

@Composable
private fun KeyButton(label: String, description: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Text(label)
    }
}

@Composable
fun TerminalHistoryOverlay(
    records: List<CommandHistoryRecord>,
    filter: String,
    onFilter: (String) -> Unit,
    onInsert: (String) -> Unit,
    onView: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shown = if (filter.isEmpty()) records else records.filter { it.text.contains(filter, ignoreCase = true) }
    val clock = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    Surface(modifier.fillMaxWidth().heightIn(max = 360.dp), tonalElevation = 6.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.terminal_history_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onClear) { Text(stringResource(R.string.terminal_history_clear)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
            }
            OutlinedTextField(
                value = filter,
                onValueChange = onFilter,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.terminal_history_filter)) },
                singleLine = true
            )
            if (shown.isEmpty()) {
                Text(stringResource(R.string.terminal_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { row ->
                        val multiline = row.text.contains('\n') || row.text.contains('\r')
                        Column(Modifier.fillMaxWidth()) {
                            if (multiline) {
                                Text(
                                    row.text.lineSequence().first().ifBlank { stringResource(R.string.terminal_history_multiline) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(stringResource(R.string.terminal_history_multiline), style = MaterialTheme.typography.labelSmall)
                                Row {
                                    TextButton(onClick = { onView(row.text) }) { Text(stringResource(R.string.terminal_history_view)) }
                                    TextButton(onClick = { onCopy(row.text) }) { Text(stringResource(R.string.terminal_copy)) }
                                }
                            } else {
                                Text(
                                    row.text,
                                    modifier = Modifier.fillMaxWidth().clickable { onInsert(row.text) },
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                stringResource(R.string.terminal_history_count, row.useCount) + " · " + clock.format(Date(row.lastSubmittedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalHistoryViewDialog(text: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_history_view)) },
        text = {
            SelectionContainer {
                Text(
                    text,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.terminal_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
fun TerminalPasteDialog(sessionName: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_paste_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.terminal_paste_message, sessionName, text.take(4000)))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.terminal_paste)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
fun TerminalContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onFind: () -> Unit,
    onHistory: () -> Unit,
    onDisconnect: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.terminal_copy)) }, onClick = { onDismiss(); onCopy() })
        DropdownMenuItem(text = { Text(stringResource(R.string.terminal_paste)) }, onClick = { onDismiss(); onPaste() })
        DropdownMenuItem(text = { Text(stringResource(R.string.terminal_select_all)) }, onClick = { onDismiss(); onSelectAll() })
        DropdownMenuItem(text = { Text(stringResource(R.string.action_clear)) }, onClick = { onDismiss(); onClear() })
        DropdownMenuItem(text = { Text(stringResource(R.string.terminal_find)) }, onClick = { onDismiss(); onFind() })
        DropdownMenuItem(text = { Text(stringResource(R.string.terminal_history_title)) }, onClick = { onDismiss(); onHistory() })
        DropdownMenuItem(text = { Text(stringResource(R.string.action_disconnect)) }, onClick = { onDismiss(); onDisconnect() })
    }
}

@Composable
fun TerminalPalettePreview(palette: TerminalPalette, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(Color(palette.background)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            stringResource(R.string.terminal_palette_preview),
            color = Color(palette.foreground),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "user@host:~$ ls",
            color = Color(palette.cursor),
            fontFamily = FontFamily.Monospace
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            palette.colors.take(8).forEach { color ->
                Box(Modifier.size(18.dp).background(Color(color)))
            }
        }
    }
}

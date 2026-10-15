package io.github.openfinalshell.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.openfinalshell.android.AndroidUiState
import io.github.openfinalshell.android.MainViewModel
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.core.connection.AccentPalette
import io.github.openfinalshell.android.core.connection.profilesUsingProxy
import io.github.openfinalshell.android.storage.KnownHostEntity
import io.github.openfinalshell.android.storage.SavedProxyEntity

@Composable
fun accentColorLabel(hex: String): String {
    val res = when (AccentPalette.canonical(hex)) {
        "#1677FF" -> R.string.accent_color_blue
        "#13C2C2" -> R.string.accent_color_cyan
        "#52C41A" -> R.string.accent_color_green
        "#FAAD14" -> R.string.accent_color_gold
        "#FA8C16" -> R.string.accent_color_orange
        "#F5222D" -> R.string.accent_color_red
        "#EB2F96" -> R.string.accent_color_pink
        "#722ED1" -> R.string.accent_color_purple
        else -> R.string.cd_tag_color
    }
    return stringResource(res)
}

fun parseHexColor(hex: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

@Composable
fun AccentSwatches(selected: String?, allowClear: Boolean, onSelected: (String?) -> Unit) {
    val chosen = selected?.let { AccentPalette.canonical(it) }
    Column(verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.XSmall)) {
        AccentPalette.presets.chunked(4).forEach { row ->
            Row {
                row.forEach { hex ->
                    val label = accentColorLabel(hex)
                    val color = parseHexColor(hex) ?: Color.Gray
                    Box(
                        modifier = Modifier
                            .size(OpenFinalShellSpacing.MinimumTouchTarget)
                            .clickable(role = Role.Button) { onSelected(hex) }
                            .semantics {
                                contentDescription = label
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .background(color, CircleShape)
                                .then(
                                    if (chosen == hex) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }
        }
        if (allowClear) {
            TextButton(
                onClick = { onSelected(null) },
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) {
                Text(
                    stringResource(R.string.tag_color_none),
                    color = if (selected.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ProxySettingsContent(state: AndroidUiState, viewModel: MainViewModel) {
    var draft by remember { mutableStateOf<ProxyDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedProxyEntity?>(null) }
    if (state.savedProxies.isEmpty()) {
        Text(stringResource(R.string.settings_proxies_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.savedProxies.forEach { proxy ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(proxy.name)
                Text(
                    proxySummary(proxy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = {
                    draft = ProxyDraft(proxy.id, proxy.name, proxy.type, proxy.host, proxy.port.toString(), proxy.username.orEmpty(), "")
                },
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
            ) { Text(stringResource(R.string.action_edit)) }
            TextButton(
                onClick = { pendingDelete = proxy },
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.action_delete)) }
        }
    }
    Button(
        onClick = { draft = ProxyDraft(null, "", "http", "", "8080", "", "") },
        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
        shape = OpenFinalShellShapes.medium
    ) { Text(stringResource(R.string.action_add_proxy)) }
    draft?.let { current ->
        ProxyEditorDialog(
            draft = current,
            onChange = { draft = it },
            onDismiss = { draft = null },
            onSave = {
                val port = current.port.toIntOrNull()
                if (current.name.isNotBlank() && current.host.isNotBlank() && port != null && port in 1..65535) {
                    viewModel.saveProxy(
                        current.name.trim(),
                        current.type,
                        current.host.trim(),
                        port,
                        current.username.trim().ifEmpty { null },
                        current.password,
                        current.id
                    )
                    draft = null
                }
            }
        )
    }
    pendingDelete?.let { proxy ->
        val used = profilesUsingProxy(state.profiles, proxy.id)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_proxy_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    Text(stringResource(R.string.delete_proxy_message, proxy.name))
                    ReferenceLine(used.joinToString { it.name })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProxy(proxy)
                        pendingDelete = null
                    },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
fun KnownHostSettingsContent(state: AndroidUiState, viewModel: MainViewModel) {
    var pending by remember { mutableStateOf<KnownHostEntity?>(null) }
    if (state.knownHosts.isEmpty()) {
        Text(stringResource(R.string.settings_known_hosts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.knownHosts.forEach { host ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(host.key)
                Text(
                    "${host.keyType}  ${host.fingerprintSha256}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { pending = host },
                modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.action_revoke_trust)) }
        }
    }
    pending?.let { host ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.revoke_host_title)) },
            text = { Text(stringResource(R.string.revoke_host_message, host.key)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.revokeKnownHost(host.key)
                        pending = null
                    },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.action_revoke_trust)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pending = null },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
fun PrivateKeyReferenceText(names: List<String>) {
    ReferenceLine(names.joinToString())
}

@Composable
private fun ReferenceLine(names: String) {
    Text(
        if (names.isBlank()) stringResource(R.string.delete_unused) else stringResource(R.string.delete_references, names),
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun proxySummary(proxy: SavedProxyEntity): String {
    val type = if (proxy.type == "socks5") stringResource(R.string.proxy_type_socks5) else stringResource(R.string.proxy_type_http)
    val user = proxy.username?.takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty()
    return "$type  ${proxy.host}:${proxy.port}$user"
}

private data class ProxyDraft(
    val id: String?,
    val name: String,
    val type: String,
    val host: String,
    val port: String,
    val username: String,
    val password: String
)

@Composable
private fun ProxyEditorDialog(
    draft: ProxyDraft,
    onChange: (ProxyDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val port = draft.port.toIntOrNull()
    val valid = draft.name.isNotBlank() && draft.host.isNotBlank() && port != null && port in 1..65535
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(OpenFinalShellSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
            ) {
                Text(
                    stringResource(if (draft.id == null) R.string.action_add_proxy else R.string.edit_proxy_title),
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    draft.name,
                    { onChange(draft.copy(name = it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true
                )
                ProxyTypeField(draft.type) { onChange(draft.copy(type = it)) }
                OutlinedTextField(
                    draft.host,
                    { onChange(draft.copy(host = it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_host)) },
                    singleLine = true
                )
                OutlinedTextField(
                    draft.port,
                    { onChange(draft.copy(port = it.filter(Char::isDigit).take(5))) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    draft.username,
                    { onChange(draft.copy(username = it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_username)) },
                    singleLine = true
                )
                OutlinedTextField(
                    draft.password,
                    { onChange(draft.copy(password = it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = if (draft.id != null) {
                        { Text(stringResource(R.string.proxy_password_keep)) }
                    } else {
                        null
                    },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                    ) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        onClick = onSave,
                        enabled = valid,
                        modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyTypeField(selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("http" to R.string.proxy_type_http, "socks5" to R.string.proxy_type_socks5)
    val label = stringResource(options.firstOrNull { it.first == selected }?.second ?: R.string.proxy_type_http)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.label_proxy_type)) },
            trailingIcon = {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.label_proxy_type))
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, res) ->
                DropdownMenuItem(
                    text = { Text(stringResource(res)) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    },
                    modifier = Modifier.heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget)
                )
            }
        }
    }
}

package io.github.openfinalshell.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.openfinalshell.android.FinalShellImportStage
import io.github.openfinalshell.android.ImportFailure
import io.github.openfinalshell.android.PortableImportStage
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.core.workspace.ConflictAction
import io.github.openfinalshell.android.core.workspace.DropReason
import io.github.openfinalshell.android.core.workspace.EndpointConflict
import io.github.openfinalshell.android.core.workspace.FinalShellCatalog
import io.github.openfinalshell.android.core.workspace.FinalShellPlan
import io.github.openfinalshell.android.core.workspace.ImportCatalog
import io.github.openfinalshell.android.core.workspace.ImportCategory
import io.github.openfinalshell.android.core.workspace.ImportKind
import io.github.openfinalshell.android.core.workspace.ImportPlan
import io.github.openfinalshell.android.core.workspace.ImportPlanResult
import io.github.openfinalshell.android.core.workspace.LocalIndex
import io.github.openfinalshell.android.core.workspace.RecordRef
import io.github.openfinalshell.android.core.workspace.RowAction
import java.util.UUID

@Composable
fun PortableImportDialog(
    stage: PortableImportStage,
    passphrase: String,
    onPassphrase: (String) -> Unit,
    onRead: () -> Unit,
    onApply: (ImportPlanResult) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = OpenFinalShellShapes.large) {
            when (stage) {
                PortableImportStage.Idle, PortableImportStage.Reading -> Column(
                    Modifier.fillMaxSize().padding(OpenFinalShellSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
                ) {
                    Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleMedium)
                    if (stage is PortableImportStage.Reading) CircularProgressIndicator()
                    PassphraseField(passphrase, onPassphrase)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRead, enabled = stage !is PortableImportStage.Reading, shape = OpenFinalShellShapes.medium) {
                            Text(stringResource(R.string.import_read))
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                is PortableImportStage.Failed -> Column(
                    Modifier.fillMaxSize().padding(OpenFinalShellSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
                ) {
                    Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (stage.kind == ImportFailure.Passphrase) R.string.import_passphrase_required else R.string.import_read_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                    PassphraseField(passphrase, onPassphrase)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRead, shape = OpenFinalShellShapes.medium) { Text(stringResource(R.string.import_read)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                is PortableImportStage.Ready -> ReadyImport(
                    stage = stage,
                    passphrase = passphrase,
                    onPassphrase = onPassphrase,
                    onRead = onRead,
                    onApply = onApply,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun ReadyImport(
    stage: PortableImportStage.Ready,
    passphrase: String,
    onPassphrase: (String) -> Unit,
    onRead: () -> Unit,
    onApply: (ImportPlanResult) -> Unit,
    onDismiss: () -> Unit
) {
    var categories by remember(stage.openedAt) { mutableStateOf(ImportCategory.entries.toSet()) }
    var conflict by remember(stage.openedAt) { mutableStateOf(ConflictAction.Skip) }
    var cancelled by remember(stage.openedAt) { mutableStateOf(setOf<RecordRef>()) }
    var preview by remember(stage.openedAt) { mutableStateOf(false) }
    val ids = remember(stage.openedAt) { mutableStateMapOf<RecordRef, String>() }
    val secretIds = remember(stage.openedAt) { mutableStateMapOf<String, String>() }
    val newId = { kind: ImportKind, source: String -> ids.getOrPut(RecordRef(kind, source)) { UUID.randomUUID().toString() } }
    val newSecret = { source: String -> secretIds.getOrPut(source) { UUID.randomUUID().toString() } }
    val shown = ImportPlan.build(stage.catalog, stage.local, categories, conflict, emptySet(), newId, newSecret)
    val plan = ImportPlan.build(stage.catalog, stage.local, categories, conflict, cancelled, newId, newSecret)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenFinalShellSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
    ) {
        Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleMedium)
        if (stage.secretsLocked) {
            Text(stringResource(R.string.import_secrets_locked))
            PassphraseField(passphrase, onPassphrase)
            Button(onClick = onRead, shape = OpenFinalShellShapes.medium) { Text(stringResource(R.string.import_read)) }
        }
        if (stage.invalid > 0) Text(stringResource(R.string.import_invalid, stage.invalid))
        if (stage.rdpSkipped > 0) Text(stringResource(R.string.import_rdp_skipped, stage.rdpSkipped))
        if (stage.materialRefsIgnored > 0) Text(stringResource(R.string.import_material_ignored, stage.materialRefsIgnored))
        Text(stringResource(R.string.import_same_name), style = MaterialTheme.typography.bodySmall)
        if (!preview) {
            Text(stringResource(R.string.import_choose_categories), style = MaterialTheme.typography.titleSmall)
            ImportCategory.entries.forEach { category ->
                CheckRow(stringResource(categoryLabel(category)), category in categories) { checked ->
                    categories = if (checked) categories + category else categories - category
                }
            }
            ConflictPicker(conflict) { conflict = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { preview = true }, shape = OpenFinalShellShapes.medium) { Text(stringResource(R.string.import_preview)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        } else {
            ConflictPicker(conflict) { conflict = it }
            PreviewBody(shown, plan, stage.catalog, cancelled) { ref, want ->
                cancelled = if (want) cancelled - ref else cancelled + ref
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApply(plan) }, shape = OpenFinalShellShapes.medium) { Text(stringResource(R.string.import_apply)) }
                TextButton(onClick = { preview = false }) { Text(stringResource(R.string.import_back)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
fun FinalShellImportDialog(
    stage: FinalShellImportStage,
    onApply: (FinalShellPlan) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = OpenFinalShellShapes.large) {
            when (stage) {
                FinalShellImportStage.Idle, FinalShellImportStage.Reading -> Column(Modifier.padding(OpenFinalShellSpacing.Medium)) {
                    if (stage is FinalShellImportStage.Reading) CircularProgressIndicator()
                    else Text(stringResource(R.string.finalshell_import))
                }
                is FinalShellImportStage.Failed -> Column(
                    Modifier.padding(OpenFinalShellSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
                ) {
                    Text(stringResource(R.string.finalshell_no_files), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
                is FinalShellImportStage.Ready -> FinalShellReady(stage, onApply, onDismiss)
            }
        }
    }
}

@Composable
private fun FinalShellReady(stage: FinalShellImportStage.Ready, onApply: (FinalShellPlan) -> Unit, onDismiss: () -> Unit) {
    var conflict by remember(stage.openedAt) { mutableStateOf(EndpointConflict.Skip) }
    val ids = remember(stage.openedAt) { mutableStateMapOf<String, String>() }
    val plan = FinalShellCatalog.plan(stage.parsed, stage.existing, conflict) { source ->
        ids.getOrPut(source) { UUID.randomUUID().toString() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenFinalShellSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)
    ) {
        Text(stringResource(R.string.finalshell_import), style = MaterialTheme.typography.titleMedium)
        if (stage.files == 0) Text(stringResource(R.string.finalshell_no_files))
        Text(stringResource(R.string.finalshell_counts, stage.parsed.connections.size, stage.parsed.groups.size))
        Text(stringResource(R.string.finalshell_username_root), style = MaterialTheme.typography.bodySmall)
        if (stage.parsed.invalidCount > 0) Text(stringResource(R.string.finalshell_invalid, stage.parsed.invalidCount))
        if (stage.parsed.unsupportedCount > 0) Text(stringResource(R.string.finalshell_unsupported, stage.parsed.unsupportedCount))
        stage.parsed.unknownCharsets.forEach { row ->
            Text(stringResource(R.string.finalshell_unknown_charset, row.raw))
        }
        val ignored = stage.parsed.ignored
        if (ignored.passwordIds.isNotEmpty()) Text(stringResource(R.string.finalshell_ignored_passwords, ignored.passwordIds.size))
        if (ignored.privateKeyIds.isNotEmpty()) Text(stringResource(R.string.finalshell_ignored_keys, ignored.privateKeyIds.size))
        if (ignored.proxyIds.isNotEmpty()) Text(stringResource(R.string.finalshell_ignored_proxies, ignored.proxyIds.size))
        val forwardCount = ignored.forwards.sumOf { it.count }
        if (forwardCount > 0) Text(stringResource(R.string.finalshell_ignored_forwards, forwardCount))
        Text(stringResource(R.string.finalshell_conflict), style = MaterialTheme.typography.titleSmall)
        RadioRow(stringResource(R.string.finalshell_skip), conflict == EndpointConflict.Skip) { conflict = EndpointConflict.Skip }
        RadioRow(stringResource(R.string.finalshell_save_as), conflict == EndpointConflict.SaveAs) { conflict = EndpointConflict.SaveAs }
        plan.connections.forEach { row ->
            val charset = row.unknownCharset?.let { stringResource(R.string.finalshell_unknown_charset, it) }.orEmpty()
            Text(stringResource(R.string.finalshell_row, row.name, row.host, row.port, row.username) + if (charset.isEmpty()) "" else "\n$charset")
        }
        plan.skipped.forEach { row ->
            Text(stringResource(R.string.finalshell_skipped_row, row.name))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onApply(plan) },
                enabled = plan.connections.isNotEmpty() || plan.groups.isNotEmpty(),
                shape = OpenFinalShellShapes.medium
            ) { Text(stringResource(R.string.import_apply)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun PreviewBody(
    shown: ImportPlanResult,
    plan: ImportPlanResult,
    catalog: ImportCatalog,
    cancelled: Set<RecordRef>,
    onToggle: (RecordRef, Boolean) -> Unit
) {
    fun name(ref: RecordRef): String = when (ref.kind) {
        ImportKind.Group -> catalog.groups.firstOrNull { it.id == ref.id }?.name
        ImportKind.Proxy -> catalog.proxies.firstOrNull { it.id == ref.id }?.name
        ImportKind.PrivateKey -> catalog.privateKeys.firstOrNull { it.id == ref.id }?.name
        ImportKind.Connection -> catalog.connections.firstOrNull { it.id == ref.id }?.name
        ImportKind.Forward -> catalog.forwards.firstOrNull { it.id == ref.id }?.label?.ifBlank { null }
        ImportKind.CommandGroup -> catalog.commandGroups.firstOrNull { it.id == ref.id }?.name
        ImportKind.Command -> catalog.commands.firstOrNull { it.id == ref.id }?.name
        ImportKind.HostTrust -> ref.id
    } ?: ref.id
    val refs = buildList {
        addAll(shown.actions.keys)
        shown.dropped.forEach { add(it.ref) }
        shown.hostTrust.forEach { add(RecordRef(ImportKind.HostTrust, it.key)) }
    }.distinct()
    refs.forEach { ref ->
        val action = plan.actions[ref]
        val trust = plan.hostTrust.firstOrNull { it.key == ref.id && ref.kind == ImportKind.HostTrust }
            ?: shown.hostTrust.firstOrNull { it.key == ref.id && ref.kind == ImportKind.HostTrust }
        val drop = plan.dropped.firstOrNull { it.ref == ref } ?: shown.dropped.firstOrNull { it.ref == ref }
        val selectedTrust = plan.hostTrust.firstOrNull { it.key == ref.id && ref.kind == ImportKind.HostTrust }
        val detail = when {
            action != null -> actionText(action) + secretNote(plan, ref)
            selectedTrust?.action == RowAction.Insert -> stringResource(R.string.import_host_new)
            trust?.action == RowAction.Skip -> stringResource(R.string.import_host_same)
            trust?.action == RowAction.Block || drop?.reason == DropReason.PrivateKeyFingerprintMismatch ->
                stringResource(if (ref.kind == ImportKind.HostTrust) R.string.import_host_conflict else R.string.import_drop_fingerprint)
            drop != null -> dropText(drop.reason)
            else -> ""
        }
        val checked = action != null || selectedTrust?.action == RowAction.Insert
        val fingerprintLocked = drop?.reason == DropReason.PrivateKeyFingerprintMismatch || trust?.action == RowAction.Block
        val hostChoice = ref.kind != ImportKind.HostTrust || shown.hostTrust.any { it.key == ref.id && it.action == RowAction.Insert }
        val enabled = hostChoice && !fingerprintLocked && (checked || ref in cancelled)
        CheckRow(name(ref) + "\n" + detail, checked, enabled = enabled) { want -> onToggle(ref, want) }
    }
}

@Composable
private fun secretNote(plan: ImportPlanResult, ref: RecordRef): String {
    val pending = when (ref.kind) {
        ImportKind.Proxy -> plan.proxies.firstOrNull { it.sourceId == ref.id }?.pendingSecret == true
        ImportKind.PrivateKey -> plan.privateKeys.firstOrNull { it.sourceId == ref.id }?.pendingSecret == true
        ImportKind.Connection -> plan.connections.firstOrNull { it.sourceId == ref.id }?.pendingSecrets?.isNotEmpty() == true
        else -> false
    }
    val material = plan.privateKeys.firstOrNull { it.sourceId == ref.id && ref.kind == ImportKind.PrivateKey }
    val materialText = when {
        material?.bindExistingMaterial == true -> stringResource(R.string.import_material_kept)
        material?.materialPending == true -> stringResource(R.string.import_material_missing)
        else -> ""
    }
    val secretText = if (pending) stringResource(R.string.import_pending_secret, "") else ""
    return materialText + secretText
}

@Composable
private fun ConflictPicker(selected: ConflictAction, onSelected: (ConflictAction) -> Unit) {
    Text(stringResource(R.string.import_conflict_label), style = MaterialTheme.typography.titleSmall)
    RadioRow(stringResource(R.string.import_conflict_skip), selected == ConflictAction.Skip) { onSelected(ConflictAction.Skip) }
    RadioRow(stringResource(R.string.import_conflict_overwrite), selected == ConflictAction.Overwrite) { onSelected(ConflictAction.Overwrite) }
    RadioRow(stringResource(R.string.import_conflict_duplicate), selected == ConflictAction.Duplicate) { onSelected(ConflictAction.Duplicate) }
}

@Composable
private fun actionText(action: RowAction): String = stringResource(
    when (action) {
        RowAction.Insert -> R.string.import_action_insert
        RowAction.Overwrite -> R.string.import_action_overwrite
        RowAction.Skip -> R.string.import_action_skip
        RowAction.Duplicate -> R.string.import_action_duplicate
        RowAction.Block -> R.string.import_action_block
    }
)

@Composable
private fun dropText(reason: DropReason): String = stringResource(
    when (reason) {
        DropReason.DependencyCancelled -> R.string.import_drop_dependency
        DropReason.DanglingReference -> R.string.import_drop_dangling
        DropReason.PrivateKeyFingerprintMismatch -> R.string.import_drop_fingerprint
        DropReason.NoLongerRequired -> R.string.import_drop_unused
    }
)

private fun categoryLabel(category: ImportCategory): Int = when (category) {
    ImportCategory.Connections -> R.string.import_category_connections
    ImportCategory.Groups -> R.string.import_category_groups
    ImportCategory.Proxies -> R.string.import_category_proxies
    ImportCategory.PrivateKeys -> R.string.import_category_keys
    ImportCategory.Forwards -> R.string.import_category_forwards
    ImportCategory.Commands -> R.string.import_category_commands
    ImportCategory.HostTrust -> R.string.import_category_hosts
}

@Composable
private fun PassphraseField(value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.settings_backup_passphrase)) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = OpenFinalShellSpacing.MinimumTouchTarget), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.weight(1f))
    }
}

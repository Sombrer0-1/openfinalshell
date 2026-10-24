package io.github.openfinalshell.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.openfinalshell.android.AndroidUiState
import io.github.openfinalshell.android.BuildConfig
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.core.connection.maskHost
import io.github.openfinalshell.android.core.model.LOCAL_SHELL_PROTOCOL
import io.github.openfinalshell.android.core.model.SessionState
import io.github.openfinalshell.android.core.sftp.TransferKind
import io.github.openfinalshell.android.core.sftp.TransferState
import java.util.Locale

private val ThemeGlyph: ImageVector = ImageVector.Builder(
    name = "Theme",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    addPath(
        pathData = listOf(
            PathNode.MoveTo(12f, 2f),
            PathNode.ArcTo(10f, 10f, 0f, true, true, 12f, 22f),
            PathNode.ArcTo(10f, 10f, 0f, true, true, 12f, 2f),
            PathNode.Close
        ),
        fill = SolidColor(Color.Black)
    )
}.build()

/**
 * Desktop activity bar: icon column, accent marker on the active item, theme and settings pinned
 * to the bottom. Selecting the active item again collapses the side panel.
 */
@Composable
fun WorkbenchActivityBar(
    connectionsSelected: Boolean,
    commandsSelected: Boolean,
    forwardsSelected: Boolean,
    transfersSelected: Boolean,
    transferCount: Int,
    onConnections: () -> Unit,
    onCommands: () -> Unit,
    onForwards: () -> Unit,
    onTransfers: () -> Unit,
    onToggleTheme: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .width(SessionLayoutLimits.RAIL_DP.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ActivityItem(Icons.Filled.Home, stringResource(R.string.tab_connections), connectionsSelected, onConnections)
        ActivityItem(Icons.Filled.Menu, stringResource(R.string.tab_commands), commandsSelected, onCommands)
        ActivityItem(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.tab_forwards), forwardsSelected, onForwards)
        ActivityItem(Icons.Filled.Share, stringResource(R.string.tab_transfers), transfersSelected, onTransfers, badge = transferCount)
        Spacer(Modifier.weight(1f))
        ActivityItem(ThemeGlyph, stringResource(R.string.cd_toggle_theme), selected = false, onClick = onToggleTheme)
        ActivityItem(Icons.Filled.Settings, stringResource(R.string.tab_settings), selected = false, onClick = onSettings)
    }
}

@Composable
private fun ActivityItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int = 0
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(SessionLayoutLimits.RAIL_DP.dp)
            .clip(RoundedCornerShape(OpenFinalShellCornerTokens.Control))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = if (badge > 0) "$label $badge" else label
            },
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(22.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
            )
        }
        Icon(icon, contentDescription = null, tint = tint)
        if (badge > 0) {
            Text(
                if (badge > 99) "99+" else badge.toString(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 2.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(horizontal = 3.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
fun WorkbenchTitleStrip(title: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .fillMaxWidth()
                    .height(SessionLayoutLimits.TITLE_DP.dp)
                    .padding(horizontal = OpenFinalShellSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        }
    }
}

@Composable
fun SidePanelHeading(title: String, onCollapse: () -> Unit) {
    val collapse = stringResource(R.string.cd_collapse_side_panel)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().height(SessionLayoutLimits.TITLE_DP.dp).padding(start = OpenFinalShellSpacing.Medium, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                Modifier
                    .size(OpenFinalShellSpacing.MinimumTouchTarget)
                    .clickable(onClick = onCollapse)
                    .semantics {
                        role = Role.Button
                        contentDescription = collapse
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    }
}

@Composable
fun WorkbenchStatusBar(
    state: AndroidUiState,
    maskHosts: Boolean,
    onOpenTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val session = state.selectedSessionId?.let { state.sessions[it] }
    val profile = session?.profile
    val connected = session?.state == SessionState.READY
    val title = when {
        session == null -> stringResource(R.string.status_not_connected)
        !state.sessionPanels[session.sessionId]?.title.isNullOrBlank() -> state.sessionPanels[session.sessionId]?.title.orEmpty()
        else -> profile?.name ?: stringResource(R.string.status_not_connected)
    }
    val endpoint = profile?.let {
        if (it.protocol == LOCAL_SHELL_PROTOCOL) it.name
        else {
            val host = if (maskHosts) maskHost(it.host) else it.host
            "${it.username}@$host:${it.port}"
        }
    }
    val running = state.transfers.filter {
        it.state == TransferState.RUNNING || it.state == TransferState.QUEUED || it.state == TransferState.PAUSED
    }
    val up = state.transfers.filter { it.state == TransferState.RUNNING && it.kind == TransferKind.UPLOAD }.sumOf { it.speedBps }
    val down = state.transfers.filter { it.state == TransferState.RUNNING && it.kind == TransferKind.DOWNLOAD }.sumOf { it.speedBps }
    val charset = profile?.terminal?.charset?.uppercase(Locale.ROOT)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(SessionLayoutLimits.STATUS_DP.dp)
                    .padding(horizontal = OpenFinalShellSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Medium)
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (connected) OpenFinalShellColors.Success else MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                )
                Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (endpoint != null) {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.weight(1f))
                if (running.isNotEmpty()) {
                    Text(
                        stringResource(R.string.status_transfer_brief, running.size, formatRate(down), formatRate(up)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.clickable(onClick = onOpenTransfers)
                    )
                }
                if (!charset.isNullOrBlank()) {
                    Text(charset, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun WorkbenchDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(OpenFinalShellCornerTokens.Card),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(), content = content)
        }
    }
}

private fun formatRate(bytesPerSecond: Long): String {
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSecond.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytesPerSecond} ${units[index]}" else String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}

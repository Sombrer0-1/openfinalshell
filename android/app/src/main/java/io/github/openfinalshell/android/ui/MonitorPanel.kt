package io.github.openfinalshell.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.openfinalshell.android.AndroidUiState
import io.github.openfinalshell.android.MainViewModel
import io.github.openfinalshell.android.R
import io.github.openfinalshell.android.core.model.ConnectionCounts
import io.github.openfinalshell.android.core.model.MemorySnapshot
import io.github.openfinalshell.android.core.monitor.MonitorAvailability
import io.github.openfinalshell.android.core.monitor.MonitorState
import io.github.openfinalshell.android.core.workspace.DiskLevel
import io.github.openfinalshell.android.core.workspace.Metric
import io.github.openfinalshell.android.core.workspace.MonitorPresentation
import io.github.openfinalshell.android.core.workspace.MonitorView

@Composable
internal fun MonitorPanel(state: AndroidUiState, viewModel: MainViewModel) {
    val monitor = state.monitor
    val sessionId = state.selectedSessionId
    val folded = remember { mutableStateMapOf<String, Boolean>() }
    val collapsed = sessionId != null && folded[sessionId] == true
    val view = monitor.snapshot?.let { snapshot ->
        MonitorPresentation.present(snapshot, presentedAvailability(monitor), collapsed)
    }
    val unavailable = stringResource(R.string.monitor_metric_unavailable)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tab_monitor), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { if (monitor.running) viewModel.stopMonitoring() else viewModel.startMonitoring() }) {
                    Text(stringResource(if (monitor.running) R.string.action_stop else R.string.action_start))
                }
            }
        }
        item {
            Button(onClick = viewModel::refreshPortTraffic, enabled = sessionId != null) {
                Text(stringResource(R.string.action_ports))
            }
        }
        monitor.error?.let {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
                    MonitorError(stringResource(R.string.status_monitor_failed))
                    TextButton(onClick = { viewModel.startMonitoring() }) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
        item {
            MonitorCard(stringResource(R.string.monitor_latency)) {
                LatencyLine(
                    stringResource(R.string.monitor_direct_latency, latencyText(view?.directLatencyMs, monitor.directLatencyMs, unavailable)),
                    held = view?.directLatencyMs ?: monitor.directLatencyMs?.let { Metric.Present(it) } ?: Metric.Missing
                )
                LatencyLine(
                    stringResource(R.string.monitor_connection_latency, latencyText(view?.connectionLatencyMs, monitor.connectionLatencyMs, unavailable)),
                    held = view?.connectionLatencyMs ?: monitor.connectionLatencyMs?.let { Metric.Present(it) } ?: Metric.Missing
                )
            }
        }
        monitor.staticInfo?.let { info ->
            item {
                MonitorCard(stringResource(R.string.monitor_server_info)) {
                    Text(listOfNotNull(info.hostname.ifBlank { null }, info.distro.ifBlank { null }).joinToString(" | "))
                    Text(
                        listOf(info.kernel, info.arch).filter { it.isNotBlank() }.joinToString(" ") +
                            " | ${info.cpuCores} ${stringResource(R.string.monitor_cores)}"
                    )
                    if (info.ips.isNotEmpty()) Text(info.ips.joinToString())
                }
            }
        }
        if (view == null) {
            item {
                MonitorEmpty(
                    stringResource(R.string.monitor_waiting_title),
                    stringResource(R.string.monitor_latency_pending)
                )
            }
        } else {
            item {
                MonitorCard(stringResource(R.string.monitor_resources)) {
                    ResourceBody(view, monitor, unavailable)
                }
            }
            item {
                MonitorCard(stringResource(R.string.monitor_network_title)) {
                    NetworkBody(view, monitor, unavailable)
                }
            }
            item {
                MonitorCard(stringResource(R.string.monitor_disk)) {
                    DiskBody(view, unavailable)
                }
            }
            item {
                MonitorCard(stringResource(R.string.monitor_disk_io)) {
                    DiskIoBody(view, monitor, unavailable)
                }
            }
            item {
                MonitorCard(stringResource(R.string.monitor_processes)) {
                    ProcessBody(view, unavailable, collapsed) {
                        if (sessionId != null) folded[sessionId] = !collapsed
                    }
                }
            }
            item {
                MonitorCard(stringResource(R.string.monitor_connections)) {
                    ConnectionBody(view, unavailable)
                }
            }
            item {
                MonitorCard(stringResource(R.string.monitor_tcp)) {
                    TcpBody(view, unavailable)
                }
            }
        }
        item { PortTrafficBody(monitor, unavailable) }
    }
}

@Composable
private fun ResourceBody(view: MonitorView, monitor: MonitorState, unavailable: String) {
    val uptime = view.uptimeSec
    if (uptime is Metric.Present && "UPTIME" in monitor.held) {
        Text(stringResource(R.string.monitor_uptime, formatUptime(uptime.value)))
    } else {
        Text(stringResource(R.string.monitor_uptime, unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val cpu = view.cpu
    if (cpu is Metric.Present && "STAT" in monitor.held) {
        UsageMeter(stringResource(R.string.monitor_cpu_total, cpu.value.usagePct), cpu.value.usagePct)
        cpu.value.perCore.forEachIndexed { index, value ->
            UsageMeter(stringResource(R.string.monitor_cpu_core, index, value), value)
        }
    } else {
        Text("CPU $unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (cpu is Metric.Present && "LOAD" in monitor.held && cpu.value.loadAvg.isNotEmpty()) {
        Text(stringResource(R.string.monitor_load, cpu.value.loadAvg.joinToString(" / ") { "%.2f".format(it) }))
    } else {
        Text(stringResource(R.string.monitor_load, unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when (val memory = view.memory) {
        is Metric.Present -> MemoryBody(memory.value)
        Metric.Missing -> Text(
            stringResource(R.string.monitor_memory, unavailable, unavailable),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MemoryBody(memory: MemorySnapshot) {
    UsageMeter(
        stringResource(R.string.monitor_memory, formatBytes(memory.usedKb * 1024), formatBytes(memory.totalKb * 1024)),
        percentOf(memory.usedKb, memory.totalKb)
    )
    UsageMeter(
        stringResource(R.string.monitor_swap, formatBytes(memory.swapUsedKb * 1024), formatBytes(memory.swapTotalKb * 1024)),
        percentOf(memory.swapUsedKb, memory.swapTotalKb)
    )
}

@Composable
private fun NetworkBody(view: MonitorView, monitor: MonitorState, unavailable: String) {
    val net = view.net
    if (net !is Metric.Present || "NET" !in monitor.held) {
        Text(unavailable, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (net.value.isEmpty()) {
        Text(stringResource(R.string.monitor_network_empty))
        return
    }
    net.value.forEach { iface ->
        Text(stringResource(R.string.monitor_network, iface.iface, formatBytes(iface.rxBps), formatBytes(iface.txBps)))
    }
}

@Composable
private fun DiskBody(view: MonitorView, unavailable: String) {
    when (val disks = view.disks) {
        Metric.Missing -> Text(unavailable, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is Metric.Present -> disks.value.forEach { disk ->
            UsageMeter(
                stringResource(R.string.monitor_disk_entry, disk.mount, disk.usePct),
                disk.usePct,
                diskColor(disk.level)
            )
        }
    }
}

@Composable
private fun DiskIoBody(view: MonitorView, monitor: MonitorState, unavailable: String) {
    val io = view.diskIo
    if (io !is Metric.Present || "DISKIO" !in monitor.held) {
        Text(unavailable, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (io.value.isEmpty()) {
        Text(stringResource(R.string.monitor_disk_io_empty))
        return
    }
    io.value.forEach { disk ->
        Text(stringResource(R.string.monitor_disk_io_entry, disk.dev, formatBytes(disk.readBps), formatBytes(disk.writeBps)))
    }
}

@Composable
private fun ProcessBody(view: MonitorView, unavailable: String, collapsed: Boolean, onToggle: () -> Unit) {
    when (val processes = view.processes) {
        Metric.Missing -> Text(unavailable, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is Metric.Present -> {
            TextButton(onClick = onToggle) {
                Text(stringResource(if (collapsed) R.string.monitor_processes_show else R.string.monitor_processes_hide))
            }
            if (!collapsed) {
                if (processes.value.isEmpty()) {
                    Text(stringResource(R.string.monitor_processes_empty))
                } else {
                    processes.value.forEach { process ->
                        Text("${process.pid} ${process.name}  ${"%.1f".format(process.cpuPct)}%  ${"%.1f".format(process.memPct)}%")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBody(view: MonitorView, unavailable: String) {
    when (val connections = view.connections) {
        Metric.Missing -> Text(unavailable, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is Metric.Present -> Text(connectionText(connections.value))
    }
}

@Composable
private fun TcpBody(view: MonitorView, unavailable: String) {
    when (val states = view.tcpStates) {
        Metric.Missing -> Text(unavailable, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is Metric.Present -> states.value.forEach { (name, count) ->
            Text(stringResource(R.string.monitor_tcp_entry, name, count))
        }
    }
}

@Composable
private fun PortTrafficBody(monitor: MonitorState, unavailable: String) {
    if (!monitor.available.portTraffic || monitor.portCountersUnavailable) {
        MonitorCard(stringResource(R.string.monitor_port_traffic)) {
            Text(stringResource(R.string.monitor_port_counters_unavailable))
        }
        return
    }
    val ports = monitor.ports ?: return
    MonitorCard(stringResource(R.string.monitor_port_traffic)) {
        if (ports.ports.isEmpty()) {
            Text(stringResource(R.string.monitor_ports_empty))
        } else {
            ports.ports.take(20).forEach { port ->
                Text(
                    if (port.ratesAvailable) {
                        stringResource(R.string.monitor_port_rate, port.port, port.connections, formatBytes(port.rxBps), formatBytes(port.txBps))
                    } else {
                        stringResource(R.string.monitor_port_no_rate, port.port, port.connections)
                    }
                )
            }
        }
    }
}

@Composable
private fun connectionText(counts: ConnectionCounts): String = stringResource(
    R.string.monitor_connections_detail,
    counts.socketsUsed,
    counts.tcpInuse,
    counts.tcpOrphan,
    counts.tcpTw,
    counts.udpInuse
)

@Composable
private fun LatencyLine(text: String, held: Metric<Long>) {
    if (held is Metric.Missing) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(text)
    }
}

private fun latencyText(metric: Metric<Long>?, fallbackMs: Long?, unavailable: String): String {
    val value = when (metric) {
        is Metric.Present -> metric.value
        Metric.Missing -> return unavailable
        null -> fallbackMs
    }
    return value?.let { "$it ms" } ?: unavailable
}

/** Memory is missing when this sample failed, even if the source can read it. Other gaps are already null sections. */
private fun presentedAvailability(monitor: MonitorState): MonitorAvailability =
    monitor.available.copy(memory = monitor.available.memory && "MEM" in monitor.held)

private fun diskColor(level: DiskLevel): Color = when (level) {
    DiskLevel.Normal -> OpenFinalShellColors.Success
    DiskLevel.Warning -> OpenFinalShellColors.Warning
    DiskLevel.Error -> OpenFinalShellColors.Error
}

@Composable
private fun UsageMeter(label: String, percent: Double, color: Color = MaterialTheme.colorScheme.primary) {
    val fraction = (percent / 100.0).toFloat().coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Text(label)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(current = fraction, range = 0f..1f)
            },
            color = color,
            trackColor = color.copy(alpha = 0.24f)
        )
    }
}

@Composable
private fun MonitorCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier.padding(OpenFinalShellSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.XSmall)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun MonitorEmpty(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenFinalShellSpacing.Small)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MonitorError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun percentOf(used: Long, total: Long): Double =
    if (total <= 0L) 0.0 else used.coerceAtLeast(0L) * 100.0 / total

private fun formatUptime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val days = total / 86_400
    val hours = (total % 86_400) / 3_600
    val minutes = (total % 3_600) / 60
    return if (days > 0) "${days}d ${hours}h ${minutes}m" else "${hours}h ${minutes}m"
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var amount = value.toDouble()
    var index = -1
    while (amount >= 1024 && index < units.lastIndex) {
        amount /= 1024
        index++
    }
    return "%.1f ${units[index]}".format(amount)
}

package io.github.openfinalshell.android.core.workspace

import io.github.openfinalshell.android.core.model.ConnectionCounts
import io.github.openfinalshell.android.core.model.CpuSnapshot
import io.github.openfinalshell.android.core.model.DiskFsSnapshot
import io.github.openfinalshell.android.core.model.DiskIoSnapshot
import io.github.openfinalshell.android.core.model.MemorySnapshot
import io.github.openfinalshell.android.core.model.MonitorSnapshot
import io.github.openfinalshell.android.core.model.NetworkSnapshot
import io.github.openfinalshell.android.core.model.ProcessSnapshot
import io.github.openfinalshell.android.core.monitor.MonitorAvailability

/**
 * A collected number, or an explicit gap.
 *
 * Callers must not substitute `0` for [Missing]. A denied `/proc` read and a host that is actually
 * using nothing are different facts; drawing the first as a meter is how an app-uid shell used to
 * show `0 B / 0 B`.
 */
sealed class Metric<out T> {
    data class Present<T>(val value: T) : Metric<T>()
    data object Missing : Metric<Nothing>()
}

/** Disk fill past 75% is a warning; past 90% is an error. The bounds are exclusive (`>`). */
enum class DiskLevel { Normal, Warning, Error }

data class PresentedDisk(
    val fs: String,
    val mount: String,
    val totalKb: Long,
    val usedKb: Long,
    val usePct: Double,
    val level: DiskLevel
)

data class MonitorView(
    val uptimeSec: Metric<Double>,
    val cpu: Metric<CpuSnapshot>,
    val memory: Metric<MemorySnapshot>,
    val net: Metric<List<NetworkSnapshot>>,
    val disks: Metric<List<PresentedDisk>>,
    val diskIo: Metric<List<DiskIoSnapshot>>,
    val processes: Metric<List<ProcessSnapshot>>,
    /** Display flag only. [processes] still carries every row when this is true. */
    val processesCollapsed: Boolean,
    val connections: Metric<ConnectionCounts>,
    val tcpStates: Metric<Map<String, Int>>,
    val directLatencyMs: Metric<Long>,
    val connectionLatencyMs: Metric<Long>
)

object MonitorPresentation {
    fun diskLevel(usePct: Double): DiskLevel = when {
        usePct > 90.0 -> DiskLevel.Error
        usePct > 75.0 -> DiskLevel.Warning
        else -> DiskLevel.Normal
    }

    /**
     * @param processesCollapsed fold control for the process list. It never drops rows.
     * Availability false, or a null section, is [Metric.Missing] even when the snapshot holds zeros.
     */
    fun present(
        snapshot: MonitorSnapshot,
        available: MonitorAvailability,
        processesCollapsed: Boolean
    ): MonitorView {
        val disks = if (available.disk) snapshot.diskFs?.map(::presentDisk) else null
        return MonitorView(
            uptimeSec = Metric.Present(snapshot.uptimeSec),
            cpu = Metric.Present(snapshot.cpu),
            memory = if (available.memory) Metric.Present(snapshot.mem) else Metric.Missing,
            net = Metric.Present(snapshot.net),
            disks = if (disks == null) Metric.Missing else Metric.Present(disks),
            diskIo = if (available.disk) Metric.Present(snapshot.diskIo) else Metric.Missing,
            processes = if (available.processes && snapshot.topProcs != null) {
                Metric.Present(snapshot.topProcs)
            } else {
                Metric.Missing
            },
            processesCollapsed = processesCollapsed,
            connections = if (available.portTraffic && snapshot.conns != null) {
                Metric.Present(snapshot.conns)
            } else {
                Metric.Missing
            },
            tcpStates = if (available.tcpStates && snapshot.tcpStates != null) {
                Metric.Present(snapshot.tcpStates)
            } else {
                Metric.Missing
            },
            directLatencyMs = snapshot.directLatencyMs?.let { Metric.Present(it) } ?: Metric.Missing,
            connectionLatencyMs = snapshot.connectionLatencyMs?.let { Metric.Present(it) } ?: Metric.Missing
        )
    }

    private fun presentDisk(disk: DiskFsSnapshot) = PresentedDisk(
        disk.fs,
        disk.mount,
        disk.totalKb,
        disk.usedKb,
        disk.usePct,
        diskLevel(disk.usePct)
    )
}

package io.github.openfinalshell.android.core.workspace

import io.github.openfinalshell.android.core.model.ConnectionCounts
import io.github.openfinalshell.android.core.model.CpuSnapshot
import io.github.openfinalshell.android.core.model.DiskFsSnapshot
import io.github.openfinalshell.android.core.model.DiskIoSnapshot
import io.github.openfinalshell.android.core.model.MemorySnapshot
import io.github.openfinalshell.android.core.model.MonitorSnapshot
import io.github.openfinalshell.android.core.model.NetworkSnapshot
import io.github.openfinalshell.android.core.model.ProcessSnapshot
import io.github.openfinalshell.android.core.model.SessionState
import io.github.openfinalshell.android.core.monitor.MonitorAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorWorkspaceTest {
    @Test
    fun diskBoundsUseGreaterThan() {
        assertEquals(DiskLevel.Normal, MonitorPresentation.diskLevel(0.0))
        assertEquals(DiskLevel.Normal, MonitorPresentation.diskLevel(75.0))
        assertEquals(DiskLevel.Warning, MonitorPresentation.diskLevel(75.1))
        assertEquals(DiskLevel.Warning, MonitorPresentation.diskLevel(90.0))
        assertEquals(DiskLevel.Error, MonitorPresentation.diskLevel(90.1))
        assertEquals(DiskLevel.Error, MonitorPresentation.diskLevel(100.0))
    }

    @Test
    fun unavailableSectionsStayMissingWhenTheSnapshotIsZero() {
        val view = MonitorPresentation.present(
            snapshot(
                disk = listOf(DiskFsSnapshot("/dev/a", "/", 0, 0, 0.0)),
                diskIo = listOf(DiskIoSnapshot("sda", 0, 0)),
                procs = listOf(ProcessSnapshot(1, "init", 0.0, 0.0)),
                conns = ConnectionCounts(0, 0, 0, 0, 0),
                tcp = mapOf("ESTABLISHED" to 0)
            ),
            MonitorAvailability(
                memory = false,
                disk = false,
                processes = false,
                tcpStates = false,
                portTraffic = false
            ),
            processesCollapsed = true
        )
        assertTrue(view.memory is Metric.Missing)
        assertTrue(view.disks is Metric.Missing)
        assertTrue(view.diskIo is Metric.Missing)
        assertTrue(view.processes is Metric.Missing)
        assertTrue(view.connections is Metric.Missing)
        assertTrue(view.tcpStates is Metric.Missing)
        assertTrue(view.cpu is Metric.Present)
        assertEquals(0.0, (view.cpu as Metric.Present).value.usagePct, 0.0)
    }

    @Test
    fun nullSectionsAreMissingAndRealZerosStayPresent() {
        val view = MonitorPresentation.present(
            snapshot(disk = null, procs = null, conns = null, tcp = null, direct = null),
            MonitorAvailability(),
            processesCollapsed = false
        )
        assertTrue(view.disks is Metric.Missing)
        assertTrue(view.processes is Metric.Missing)
        assertTrue(view.connections is Metric.Missing)
        assertTrue(view.tcpStates is Metric.Missing)
        assertTrue(view.directLatencyMs is Metric.Missing)
        assertTrue(view.memory is Metric.Present)
        assertEquals(0L, (view.memory as Metric.Present).value.usedKb)
        val withLatency = MonitorPresentation.present(
            snapshot(direct = 0L),
            MonitorAvailability(),
            processesCollapsed = false
        )
        assertEquals(Metric.Present(0L), withLatency.directLatencyMs)
    }

    @Test
    fun collapsedProcessesKeepEveryRowAndDiskLevelsFollowEachMount() {
        val procs = (1..5).map { ProcessSnapshot(it, "p$it", it.toDouble(), 1.0) }
        val view = MonitorPresentation.present(
            snapshot(
                disk = listOf(
                    DiskFsSnapshot("a", "/", 100, 75, 75.0),
                    DiskFsSnapshot("b", "/var", 100, 80, 80.0),
                    DiskFsSnapshot("c", "/data", 100, 95, 95.0)
                ),
                procs = procs
            ),
            MonitorAvailability(),
            processesCollapsed = true
        )
        assertTrue(view.processesCollapsed)
        val shown = (view.processes as Metric.Present).value
        assertEquals(procs, shown)
        val disks = (view.disks as Metric.Present).value
        assertEquals(listOf(DiskLevel.Normal, DiskLevel.Warning, DiskLevel.Error), disks.map { it.level })
    }

    @Test
    fun emptyNetworkIsPresentRatherThanAFakeZeroCard() {
        val view = MonitorPresentation.present(snapshot(), MonitorAvailability(), false)
        assertEquals(Metric.Present(emptyList<NetworkSnapshot>()), view.net)
    }

    @Test
    fun panelCollapseDoesNotChangeCollection() {
        for (state in SessionState.entries) {
            for (enabled in listOf(true, false)) {
                for (reconnected in listOf(true, false)) {
                    assertEquals(
                        MonitorCollectionPolicy.decide(state, enabled, false, reconnected),
                        MonitorCollectionPolicy.decide(state, enabled, true, reconnected)
                    )
                }
            }
        }
    }

    @Test
    fun collectionFollowsReadyEnabledCloseAndReconnect() {
        assertEquals(
            CollectionDirective(start = true, stop = false, resetDelta = false),
            MonitorCollectionPolicy.decide(SessionState.READY, true, false, false)
        )
        assertEquals(
            CollectionDirective(start = true, stop = false, resetDelta = true),
            MonitorCollectionPolicy.decide(SessionState.READY, true, true, true)
        )
        assertEquals(
            CollectionDirective(start = false, stop = true, resetDelta = false),
            MonitorCollectionPolicy.decide(SessionState.READY, false, false, true)
        )
        assertEquals(
            CollectionDirective(start = false, stop = true, resetDelta = false),
            MonitorCollectionPolicy.decide(SessionState.CLOSED, true, false, true)
        )
        assertEquals(
            CollectionDirective(start = false, stop = true, resetDelta = false),
            MonitorCollectionPolicy.decide(SessionState.RECONNECTING, true, false, true)
        )
        assertEquals(
            CollectionDirective(start = false, stop = false, resetDelta = false),
            MonitorCollectionPolicy.decide(SessionState.CONNECTING, true, false, false)
        )
        assertEquals(
            CollectionDirective(start = false, stop = false, resetDelta = false),
            MonitorCollectionPolicy.decide(SessionState.AUTHENTICATING, true, false, false)
        )
    }

    private fun snapshot(
        disk: List<DiskFsSnapshot>? = emptyList(),
        diskIo: List<DiskIoSnapshot> = emptyList(),
        procs: List<ProcessSnapshot>? = emptyList(),
        conns: ConnectionCounts? = null,
        tcp: Map<String, Int>? = null,
        direct: Long? = null
    ) = MonitorSnapshot(
        ts = 1L,
        uptimeSec = 10.0,
        cpu = CpuSnapshot(0.0),
        mem = MemorySnapshot(0, 0, 0, 0, 0),
        net = emptyList(),
        diskFs = disk,
        diskIo = diskIo,
        topProcs = procs,
        conns = conns,
        tcpStates = tcp,
        directLatencyMs = direct,
        connectionLatencyMs = null
    )
}

class ForwardWorkspaceTest {
    @Test
    fun runningBindingBeatsANewSelectedTab() {
        val decision = ForwardBindingPolicy.choose(
            "conn",
            listOf(
                session("old", readyAt = 1, selected = false),
                session("new", readyAt = 2, selected = true)
            ),
            RunningForward("old")
        )
        assertEquals(ForwardBind.Keep("old"), decision)
    }

    @Test
    fun reconnectingBindingIsKept() {
        val decision = ForwardBindingPolicy.choose(
            "conn",
            listOf(
                session("old", state = SessionState.RECONNECTING, readyAt = 1, selected = false),
                session("new", readyAt = 2, selected = true)
            ),
            RunningForward("old")
        )
        assertEquals(ForwardBind.Keep("old"), decision)
    }

    @Test
    fun freshStartPrefersSelectedReadyOtherwiseEarliest() {
        val sessions = listOf(
            session("early", readyAt = 1, selected = false),
            session("late", readyAt = 5, selected = false),
            session("busy", state = SessionState.CONNECTING, readyAt = 0, selected = true)
        )
        assertEquals(
            ForwardBind.Start("early"),
            ForwardBindingPolicy.choose("conn", sessions, null)
        )
        assertEquals(
            ForwardBind.Start("late"),
            ForwardBindingPolicy.choose("conn", sessions.map { if (it.sessionId == "late") it.copy(selected = true) else it }, null)
        )
    }

    @Test
    fun otherProfilesAndClosedBindingsDoNotWin() {
        val decision = ForwardBindingPolicy.choose(
            "conn",
            listOf(
                session("other", profileId = "else", readyAt = 1, selected = true),
                session("gone", state = SessionState.CLOSED, readyAt = 1, selected = true),
                session("real", readyAt = 3, selected = false)
            ),
            RunningForward("gone")
        )
        assertEquals(ForwardBind.Start("real"), decision)
    }

    @Test
    fun localShellIsRejectedAndDoesNotStealAnSshSession() {
        assertEquals(
            ForwardBind.RejectLocalShell,
            ForwardBindingPolicy.choose("conn", listOf(session("local", readyAt = 1, selected = true, localShell = true)), null)
        )
        assertEquals(
            ForwardBind.Start("ssh"),
            ForwardBindingPolicy.choose(
                "conn",
                listOf(
                    session("local", readyAt = 1, selected = true, localShell = true),
                    session("ssh", readyAt = 2, selected = false)
                ),
                null
            )
        )
        assertEquals(
            ForwardBind.NoReadySession,
            ForwardBindingPolicy.choose("conn", listOf(session("down", state = SessionState.CONNECTING, readyAt = 1, selected = true)), null)
        )
    }

    @Test
    fun manualStopSurvivesReconnectAndOtherTabsDoNotTakeOver() {
        val stopped = ForwardRunLatch.manualStop(LatchState(), "rule", "tab")
        assertEquals(ReadyDecision.StayStopped, ForwardRunLatch.onReady(stopped, "rule", "tab", "tab"))
        assertEquals(ReadyDecision.IgnoreOtherTab, ForwardRunLatch.onReady(stopped, "rule", "tab", "other"))
        assertTrue(RuleSession("rule", "tab") in stopped.suppressed)
        val released = ForwardRunLatch.manualStart(stopped, "rule", "tab")
        assertEquals(ReadyDecision.Restore, ForwardRunLatch.onReady(released, "rule", "tab", "tab"))
        assertEquals(ForwardBind.Keep("tab"), ForwardBindingPolicy.choose(
            "conn",
            listOf(session("tab", readyAt = 1, selected = false), session("other", readyAt = 2, selected = true)),
            RunningForward("tab")
        ))
    }

    @Test
    fun deletingTheRuleOrClosingTheTabClearsOnlyThatLatch() {
        var state = ForwardRunLatch.manualStop(LatchState(), "rule", "tab")
        state = ForwardRunLatch.manualStop(state, "other-rule", "tab")
        state = ForwardRunLatch.manualStop(state, "rule", "elsewhere")
        state = ForwardRunLatch.tabClosed(state, "tab")
        assertEquals(setOf(RuleSession("rule", "elsewhere")), state.suppressed)
        state = ForwardRunLatch.ruleDeleted(state, "rule")
        assertTrue(state.suppressed.isEmpty())
    }

    private fun session(
        id: String,
        profileId: String = "conn",
        state: SessionState = SessionState.READY,
        readyAt: Long,
        selected: Boolean,
        localShell: Boolean = false
    ) = ForwardSessionRef(id, profileId, state, readyAt, selected, localShell)
}

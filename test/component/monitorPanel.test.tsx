// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import '@/i18n'
import { DEFAULT_SETTINGS } from '@shared/constants'
import type { MonitorSnapshot, SessionId } from '@shared/types'
import { MonitorPanel } from '@/features/monitor/MonitorPanel'
import { historyOf, useMonitorStore } from '@/stores/useMonitorStore'
import { useSettingsStore } from '@/stores/useSettingsStore'
import { useSessionStore, type SessionTab } from '@/stores/useSessionStore'
import { fakeOfs } from './fakeOfs'

const graph = vi.hoisted(() => vi.fn())
vi.mock('@/features/monitor/MonitorGraph', () => ({
  MonitorGraph: (props: unknown) => { graph(props); return <canvas data-testid="network-graph" /> }
}))
const sessionId = 'monitor-ui' as SessionId
const tab: SessionTab = { id: 'monitor-tab', profileId: 'p1', sessionId, termId: null, title: 'server', state: 'ready', sftpOpen: true, monitorOpen: true, shellEpoch: 0 }
let snapshot: MonitorSnapshot
let container: HTMLElement
const openPortTrafficTab = vi.fn()
const originalOpenPortTraffic = useSessionStore.getState().openPortTrafficTab
function fill(label: string): HTMLElement { return screen.getByRole('progressbar', { name: label }).firstElementChild as HTMLElement }
function mount(): void { container = render(<MonitorPanel tab={tab} onClose={() => {}} />).container }
function update(next: MonitorSnapshot): void {
  act(() => { useMonitorStore.setState({ latest: { [sessionId]: next } }) })
}
beforeEach(() => {
  fakeOfs.reset()
  fakeOfs.handle('monitor:start', () => undefined)
  graph.mockClear()
  openPortTrafficTab.mockClear()
  snapshot = {
    ts: 1, uptimeSec: 900,
    cpu: { usagePct: 84.5, perCore: [0, 50, 79, 100], loadAvg: [43.09, 52.01, 54.58] },
    mem: { totalKb: 1000, usedKb: 647, availableKb: 353, swapTotalKb: 200, swapUsedKb: 148 },
    net: [{ iface: 'eth0', rxBps: 1024, txBps: 2048, rxTotalBytes: 10000, txTotalBytes: 20000 }],
    diskFs: [{ fs: '/dev/sda1', mount: '/', totalKb: 1000, usedKb: 300, usePct: 30 }],
    diskIo: [], conns: null, directLatencyMs: 12, connectionLatencyMs: 21
  }
  useSettingsStore.setState({ settings: structuredClone(DEFAULT_SETTINGS) })
  useMonitorStore.setState({ latest: { [sessionId]: snapshot }, staticInfo: {}, state: {} })
  useSessionStore.setState({ openPortTrafficTab })
  const history = historyOf(sessionId)
  history.rxBps.splice(0, history.rxBps.length, 100, 1024)
  history.txBps.splice(0, history.txBps.length, 200, 2048)
})
afterEach(() => {
  cleanup()
  useMonitorStore.getState().clear(sessionId)
  useSessionStore.setState({ openPortTrafficTab: originalOpenPortTraffic })
})

describe('compact monitor panel', () => {
  it('shows CPU, memory, Swap and disk percentages as progress bars; only network has a graph', () => {
    mount()
    expect(screen.getByRole('progressbar', { name: 'CPU' }).getAttribute('aria-valuenow')).toBe('84.5')
    expect(fill('CPU').style.width).toBe('84.5%')
    expect(screen.getByRole('progressbar', { name: '内存' }).getAttribute('aria-valuenow')).toBe('64.7')
    expect(fill('内存').style.width).toBe('64.7%')
    expect(fill('Swap').style.width).toBe('74%')
    expect(fill('/').style.width).toBe('30%')
    expect(screen.getAllByTestId('network-graph')).toHaveLength(1)
    expect(screen.getByTestId('network-graph').closest('button')).toBeTruthy()
  })

  it('expands individual core bars with the correct percentages and warning colors', () => {
    mount()
    const toggle = screen.getByRole('button', { name: 'CPU 分核占用' })
    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(fill('CPU 0').style.width).toBe('0%')
    expect(fill('CPU 1').style.width).toBe('50%')
    expect(fill('CPU 2').style.width).toBe('79%')
    expect(fill('CPU 3').style.width).toBe('100%')
    expect(fill('CPU 2').style.background).toBe('var(--ofs-warning)')
    expect(fill('CPU 3').style.background).toBe('var(--ofs-error)')
    fireEvent.click(toggle)
    expect(screen.queryByRole('progressbar', { name: 'CPU 0' })).toBeNull()
  })

  it('updates current percentages and both latency values when a new snapshot arrives', () => {
    mount()
    update({ ...snapshot, ts: 2, cpu: { ...snapshot.cpu, usagePct: 12.3 }, mem: { ...snapshot.mem, usedKb: 350 }, directLatencyMs: 33, connectionLatencyMs: 44 })
    expect(fill('CPU').style.width).toBe('12.3%')
    expect(fill('内存').style.width).toBe('35%')
    expect(screen.getByText('直连 IP').parentElement?.textContent).toContain('33ms')
    expect(screen.getByText('实际连接').parentElement?.textContent).toContain('44ms')
    expect(container.querySelectorAll('canvas')).toHaveLength(1)
  })

  it('displays unavailable direct latency without presenting it as zero', () => {
    mount()
    update({ ...snapshot, directLatencyMs: undefined, connectionLatencyMs: 0 })
    expect(screen.getByText('直连 IP').parentElement?.textContent).toContain('不可用')
    expect(screen.getByText('实际连接').parentElement?.textContent).toContain('0ms')
  })

  it('handles zero memory and no Swap without invalid progress values', () => {
    mount()
    update({ ...snapshot, cpu: { ...snapshot.cpu, usagePct: 0 }, mem: { totalKb: 0, availableKb: 0, usedKb: 0, swapTotalKb: 0, swapUsedKb: 0 } })
    expect(fill('CPU').style.width).toBe('0%')
    expect(fill('内存').style.width).toBe('0%')
    expect(screen.queryByRole('progressbar', { name: 'Swap' })).toBeNull()
  })

  it('preserves network history, rates and the port-traffic action', () => {
    mount()
    expect(graph).toHaveBeenLastCalledWith(expect.objectContaining({ primary: [100, 1024], secondary: [200, 2048], height: 48 }))
    const network = screen.getByTestId('network-graph').closest('button')!
    expect(network.textContent).toContain('1.00 KB')
    expect(network.textContent).toContain('2.00 KB')
    fireEvent.click(network)
    expect(openPortTrafficTab).toHaveBeenCalledWith(tab.id, expect.stringContaining('server'))
  })
})

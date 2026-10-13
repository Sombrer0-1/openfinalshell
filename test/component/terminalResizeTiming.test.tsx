// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import type { Terminal } from '@xterm/xterm'
import '@/i18n'
import { DEFAULT_SETTINGS } from '@shared/constants'
import type { SessionId } from '@shared/types'
import { TerminalPane } from '@/features/terminal/TerminalPane'
import { useSettingsStore } from '@/stores/useSettingsStore'
import type { SessionTab } from '@/stores/useSessionStore'
import { deferred, fakeOfs } from './fakeOfs'

const rig = vi.hoisted(() => ({
  cols: 100, rows: 30, width: 900, height: 500,
  term: null as Terminal | null,
  fit: vi.fn(), refresh: vi.fn(), clearAtlas: vi.fn()
}))
vi.mock('@/features/terminal/createTerminal', async () => {
  const { Terminal } = await import('@xterm/xterm')
  return { createTerminal: () => {
    const term = new Terminal({ allowProposedApi: true })
    term.open = () => {}
    term.focus = () => {}
    term.refresh = rig.refresh
    term.clearTextureAtlas = rig.clearAtlas
    rig.term = term
    rig.fit.mockImplementation(() => term.resize(rig.cols, rig.rows))
    return { term, fit: { fit: rig.fit }, search: {}, attachWebgl: () => {}, detachWebgl: () => {}, dispose: () => term.dispose() }
  } }
})

const observers: Array<{ callback: ResizeObserverCallback; target?: Element }> = []
const tab: SessionTab = { id: 'resize-tab', profileId: 'p1', sessionId: 'resize-session' as SessionId, termId: null, title: 'server', state: 'ready', sftpOpen: false, monitorOpen: true, shellEpoch: 0 }
let opening: ReturnType<typeof deferred<{ termId: string }>>
async function flush(): Promise<void> { await act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }) }
async function mount(): Promise<void> {
  render(<AntdApp><TerminalPane tab={tab} active uiMode="light" /></AntdApp>)
  await flush()
}
async function resize(cols: number, rows: number): Promise<void> {
  rig.cols = cols; rig.rows = rows
  const observer = observers.find(item => item.target?.className.includes('mount'))!
  expect(observer).toBeTruthy()
  await act(async () => {
    observer.callback([], {} as ResizeObserver)
    await vi.advanceTimersByTimeAsync(130)
  })
}
function writes(): unknown[] { return fakeOfs.invokes.filter(call => call.channel === 'term:resize').map(call => call.payload) }
beforeEach(() => {
  vi.useFakeTimers()
  fakeOfs.reset()
  observers.length = 0
  rig.cols = 100; rig.rows = 30; rig.width = 900; rig.height = 500
  rig.fit.mockClear(); rig.refresh.mockClear(); rig.clearAtlas.mockClear()
  opening = deferred()
  fakeOfs.handle('term:open', () => opening.promise)
  fakeOfs.handle('term:resize', () => undefined)
  fakeOfs.handle('term:close', () => undefined)
  fakeOfs.handle('term:ready', () => undefined)
  useSettingsStore.setState({ settings: structuredClone(DEFAULT_SETTINGS) })
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => rig.width)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(() => rig.height)
  vi.stubGlobal('ResizeObserver', class {
    private item: { callback: ResizeObserverCallback; target?: Element }
    constructor(callback: ResizeObserverCallback) { this.item = { callback }; observers.push(this.item) }
    observe(target: Element): void { this.item.target = target }
    unobserve(): void {}
    disconnect(): void {}
  })
})
afterEach(async () => {
  cleanup()
  await flush()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('terminal resize synchronization', () => {
  it('sends the latest grid after a resize during the pending shell request', async () => {
    await mount()
    expect(fakeOfs.invokes.find(call => call.channel === 'term:open')?.payload).toEqual({ sessionId: tab.sessionId, cols: 100, rows: 30 })
    await resize(72, 24)
    expect(writes()).toEqual([])
    expect(rig.term!.cols).toBe(72)
    await act(async () => { opening.resolve({ termId: 'term-resize' }) })
    await flush()
    expect(writes()).toEqual([{ termId: 'term-resize', cols: 72, rows: 24 }])
    expect(fakeOfs.invokes.filter(call => call.channel === 'term:open')).toHaveLength(1)
  })

  it('tracks xterm resize events and avoids duplicate writes for the same grid', async () => {
    await mount()
    await act(async () => { opening.resolve({ termId: 'term-resize' }) })
    await flush()
    expect(writes()).toEqual([])
    rig.cols = 90; rig.rows = 28
    act(() => rig.term!.resize(90, 28))
    await flush()
    await resize(90, 28)
    expect(writes()).toEqual([{ termId: 'term-resize', cols: 90, rows: 28 }])
  })

  it('rebuilds glyph positions and redraws all rows without clearing or recreating the terminal', async () => {
    await mount()
    await act(async () => { opening.resolve({ termId: 'term-resize' }) })
    const term = rig.term!
    const clear = vi.spyOn(term, 'clear')
    rig.clearAtlas.mockClear(); rig.refresh.mockClear()
    await resize(85, 26)
    expect(rig.clearAtlas).toHaveBeenCalledOnce()
    expect(rig.refresh).toHaveBeenLastCalledWith(0, 25)
    expect(clear).not.toHaveBeenCalled()
    expect(rig.term).toBe(term)
    expect(fakeOfs.invokes.some(call => call.channel === 'term:close')).toBe(false)
    rig.refresh.mockClear()
    await resize(85, 26)
    expect(rig.refresh).toHaveBeenLastCalledWith(0, 25)
    expect(rig.clearAtlas).toHaveBeenCalledTimes(2)
  })

  it('skips unmeasurable containers instead of shrinking the terminal to a tiny grid', async () => {
    await mount()
    await act(async () => { opening.resolve({ termId: 'term-resize' }) })
    rig.width = 0
    await resize(2, 1)
    expect(rig.term!.cols).toBe(100)
    expect(rig.term!.rows).toBe(30)
    expect(writes()).toEqual([])
  })
})

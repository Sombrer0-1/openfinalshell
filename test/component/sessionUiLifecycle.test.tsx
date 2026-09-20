// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, render } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import type { Terminal } from '@xterm/xterm'
import { DEFAULT_SETTINGS } from '@shared/constants'
import { SessionViewHost } from '@/features/sessions/SessionView'
import { useSessionStore, type SessionTab } from '@/stores/useSessionStore'
import { useSettingsStore } from '@/stores/useSettingsStore'
import { wireTermData, getTerm } from '@/features/terminal/termRegistry'
import { fakeOfs } from './fakeOfs'
import '@/i18n'

const created = vi.hoisted(() => ({ terminals: [] as Terminal[] }))
vi.mock('@/features/terminal/createTerminal', async () => {
  const { Terminal } = await import('@xterm/xterm')
  return { createTerminal: () => {
    const term = new Terminal({ allowProposedApi: true })
    term.open = () => {}
    term.focus = () => {}
    created.terminals.push(term)
    return { term, fit: { fit: () => {} }, search: {}, attachWebgl: () => {}, detachWebgl: () => {}, dispose: () => term.dispose() }
  } }
})
// The test exercises the real session host and terminal; SFTP I/O has its own timing suite.
vi.mock('@/features/sftp/SftpPane', () => ({ SftpPane: () => <div>SFTP</div> }))

afterEach(() => { cleanup(); fakeOfs.reset() })

it('20 UI switches preserve live shells, buffer contents and the single data subscription', async () => {
  fakeOfs.reset()
  useSettingsStore.setState({ settings: structuredClone(DEFAULT_SETTINGS), patch: () => {} })
  const tabs: SessionTab[] = ['a', 'b'].map((id) => ({
    id, profileId: id, sessionId: id as SessionTab['sessionId'], termId: null,
    title: id, state: 'ready', sftpOpen: false, monitorOpen: false, shellEpoch: 0
  }))
  useSessionStore.setState({ tabs, activeTabId: 'a' })
  fakeOfs.handle('term:open', (arg) => ({ termId: `term-${(arg as { sessionId: string }).sessionId}` }))
  fakeOfs.handle('term:close', () => undefined)
  fakeOfs.handle('term:resize', () => undefined)
  wireTermData()
  const initialListeners = fakeOfs.listenerCount()
  function Host({ dark }: { dark: boolean }) {
    const { tabs: current, activeTabId } = useSessionStore()
    return <AntdApp><SessionViewHost tabs={current} activeTabId={activeTabId} uiMode={dark ? 'dark' : 'light'} /></AntdApp>
  }
  const { rerender, unmount } = render(<Host dark />)
  await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
  const first = getTerm('term-a' as Parameters<typeof getTerm>[0])!
  expect(first).toBeTruthy()
  await act(async () => { await new Promise<void>((r) => first.write('preserved session buffer', r)) })
  for (let i = 0; i < 20; i++) {
    act(() => {
      useSessionStore.setState((state) => ({ activeTabId: i % 2 ? 'a' : 'b', tabs: state.tabs.map((tab) => ({ ...tab, sftpOpen: i % 2 === 0 })) }))
      useSettingsStore.setState((state) => ({ settings: { ...state.settings!, accent: i % 2 ? '#faad14' : '#1677ff' } }))
    })
    rerender(<Host dark={i % 2 === 0} />)
    await act(async () => {})
  }
  expect(created.terminals).toHaveLength(2)
  expect(fakeOfs.invokes.filter((c) => c.channel === 'term:open')).toHaveLength(2)
  expect(fakeOfs.invokes.filter((c) => c.channel === 'term:close')).toHaveLength(0)
  expect(getTerm('term-a' as Parameters<typeof getTerm>[0])).toBe(first)
  expect(first.buffer.active.getLine(0)?.translateToString(true)).toBe('preserved session buffer')
  expect(fakeOfs.listenerCount()).toBe(initialListeners)
  unmount()
  await act(async () => {})
  expect(fakeOfs.invokes.filter((c) => c.channel === 'term:close')).toHaveLength(2)
  expect(getTerm('term-a' as Parameters<typeof getTerm>[0])).toBeUndefined()
})

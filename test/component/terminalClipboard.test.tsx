// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp, ConfigProvider } from 'antd'
import type { Terminal } from '@xterm/xterm'
import { DEFAULT_SETTINGS } from '@shared/constants'
import { TerminalPane } from '@/features/terminal/TerminalPane'
import { useSettingsStore } from '@/stores/useSettingsStore'
import type { SessionTab } from '@/stores/useSessionStore'
import { getTerm, trackerFor } from '@/features/terminal/termRegistry'
import i18n from '@/i18n'
import { deferred, fakeOfs } from './fakeOfs'

const rig = vi.hoisted(() => ({ term: null as Terminal | null }))

vi.mock('@/features/terminal/createTerminal', async () => {
  const { Terminal } = await import('@xterm/xterm')
  return {
    createTerminal: () => {
      const term = new Terminal({ allowProposedApi: true })
      // Keep real xterm keyboard, parser and paste handling; skip DOM rendering.
      term.open = (element) => {
        const textarea = document.createElement('textarea')
        textarea.dataset.testid = 'terminal-input'
        element.append(textarea)
        ;(term as unknown as { _core: { textarea: HTMLTextAreaElement } })._core.textarea = textarea
      }
      term.focus = () => term.textarea?.focus()
      rig.term = term
      return {
        term, fit: { fit: () => {} }, search: {},
        attachWebgl: () => {}, detachWebgl: () => {}, dispose: () => term.dispose()
      }
    }
  }
})

const tab: SessionTab = {
  id: 'paste-tab', profileId: 'paste-profile', sessionId: 'paste-session', termId: null,
  title: 'paste fixture', state: 'ready', sftpOpen: false, monitorOpen: false, shellEpoch: 0
}
const clipboardText = '复制的内容'
const readText = vi.fn(async () => clipboardText)
const writeText = vi.fn(async (_text: string) => {})

beforeEach(() => {
  fakeOfs.reset()
  fakeOfs.handle('term:open', () => ({ termId: 'paste-term' }))
  fakeOfs.handle('term:close', () => undefined)
  fakeOfs.handle('term:ready', () => { expect(getTerm('paste-term')).toBe(rig.term) })
  useSettingsStore.setState({ settings: structuredClone(DEFAULT_SETTINGS) })
  readText.mockClear()
  writeText.mockClear()
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { readText, writeText } })
})

afterEach(() => {
  cleanup()
  document.querySelectorAll('[data-focus-source]').forEach(element => element.remove())
  vi.restoreAllMocks()
  Reflect.deleteProperty(navigator, 'clipboard')
})

describe('terminal clipboard shortcut', () => {
  it('keeps the mounted terminal when switching the right-click paste/menu preference', async () => {
    render(<AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp>)
    await act(async () => {})
    const input = screen.getByTestId('terminal-input')
    act(() => useSettingsStore.setState(state => ({ settings: { ...state.settings!, terminal: { ...state.settings!.terminal, rightClick: 'menu' } } })))
    expect(input.isConnected).toBe(true)
    expect(screen.getByTestId('terminal-input')).toBe(input)
    act(() => useSettingsStore.setState(state => ({ settings: { ...state.settings!, terminal: { ...state.settings!.terminal, rightClick: 'paste' } } })))
    expect(screen.getByTestId('terminal-input')).toBe(input)
  })

  it('returns focus to the terminal after choosing paste in the context menu', async () => {
    useSettingsStore.setState(state => ({ settings: { ...state.settings!, terminal: { ...state.settings!.terminal, rightClick: 'menu' } } }))
    render(<AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp>)
    await act(async () => {})
    fireEvent.contextMenu(screen.getByTestId('terminal-input'))
    const paste = await screen.findByRole('menuitem', { name: i18n.t('terminal.paste') })
    act(() => paste.focus())
    fireEvent.click(paste)
    await waitFor(() => expect(fakeOfs.sends.filter(c => c.channel === 'term:input')).toHaveLength(1))
    await waitFor(() => expect(document.activeElement).toBe(rig.term!.textarea))
  })

  it('restores terminal focus after the multiline confirmation dialog finishes closing', async () => {
    render(<ConfigProvider theme={{ token: { motion: false } }}><AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp></ConfigProvider>)
    await act(async () => {})
    const source = document.createElement('button')
    source.dataset.focusSource = 'paste-control'
    document.body.append(source)
    source.focus()
    fireEvent.paste(screen.getByTestId('terminal-input'), { clipboardData: { getData: () => 'one\ntwo' } })
    const ok = await screen.findByRole('button', { name: name => name.replace(/\s/g, '') === i18n.t('common.ok') })
    act(() => ok.focus())
    fireEvent.click(ok)
    await waitFor(() => expect(fakeOfs.sends.filter(c => c.channel === 'term:input')).toHaveLength(1))
    await waitFor(() => expect(document.querySelector('.ant-modal-wrap')).toBeNull())
    expect(document.activeElement).toBe(rig.term!.textarea)
  })

  it('does not reclaim focus if the user switched away while clipboard reading was pending', async () => {
    const text = deferred<string>()
    readText.mockReturnValueOnce(text.promise)
    const view = render(<AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp>)
    await act(async () => {})
    const core = (rig.term as unknown as { _core: { _keyDown(event: KeyboardEvent): void } })._core
    act(() => core._keyDown(new KeyboardEvent('keydown', { key: 'V', code: 'KeyV', ctrlKey: true, shiftKey: true, cancelable: true })))
    view.rerender(<AntdApp><TerminalPane tab={tab} active={false} uiMode="dark" /></AntdApp>)
    const other = document.createElement('button')
    other.dataset.focusSource = 'other-tab'
    document.body.append(other)
    other.focus()
    await act(async () => { text.resolve('paste into original session'); await new Promise(resolve => setTimeout(resolve, 40)) })
    expect(document.activeElement).toBe(other)
  })

  it.each([false, true])('sends one paste when bracketed paste is %s', async (bracketed) => {
    render(<AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp>)
    await waitFor(() => expect(fakeOfs.invokes.some(c => c.channel === 'term:open')).toBe(true))
    await act(async () => {})
    const term = rig.term!
    await act(() => new Promise<void>(resolve => term.write(bracketed ? '\x1b[?2004h' : '\x1b[?2004l', resolve)))
    const core = (term as unknown as {
      _core: { _keyDown(event: KeyboardEvent): boolean | undefined; textarea: HTMLTextAreaElement }
    })._core
    core.textarea = document.createElement('textarea')
    const key = new KeyboardEvent('keydown', {
      key: 'V', code: 'KeyV', ctrlKey: true, shiftKey: true, cancelable: true
    })
    await act(async () => {
      core._keyDown(key)
      // Browser default paste only follows an uncancelled keyboard shortcut.
      // jsdom does not perform default clipboard actions, so invoke xterm's
      // real paste path to model that action when it remains enabled.
      if (!key.defaultPrevented) term.paste(clipboardText)
    })
    const data = bracketed ? `\x1b[200~${clipboardText}\x1b[201~` : clipboardText
    expect(fakeOfs.sends.filter(c => c.channel === 'term:input')).toEqual([
      { channel: 'term:input', payload: { termId: 'paste-term', data } }
    ])
    expect(key.defaultPrevented).toBe(true)
    expect(readText).toHaveBeenCalledTimes(1)
  })

  it('uses current multiline confirmation settings for native paste without recreating the terminal', async () => {
    render(<AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp>)
    await act(async () => {})
    const term = rig.term
    act(() => useSettingsStore.setState(state => ({ settings: { ...state.settings!, terminal: { ...state.settings!.terminal, confirmMultilinePaste: false } } })))
    fireEvent.paste(screen.getByTestId('terminal-input'), { clipboardData: { getData: () => 'one\r\ntwo' } })
    expect(fakeOfs.sends.filter(c => c.channel === 'term:input')).toEqual([
      { channel: 'term:input', payload: { termId: 'paste-term', data: 'one\rtwo' } }
    ])
    fakeOfs.sends = []
    act(() => useSettingsStore.setState(state => ({ settings: { ...state.settings!, terminal: { ...state.settings!.terminal, confirmMultilinePaste: true } } })))
    fireEvent.paste(screen.getByTestId('terminal-input'), { clipboardData: { getData: () => 'three\nfour' } })
    await screen.findAllByText(i18n.t('terminal.multilinePasteTitle'))
    expect(fakeOfs.sends.filter(c => c.channel === 'term:input')).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: name => name.replace(/\s/g, '') === i18n.t('common.ok') }))
    await waitFor(() => expect(fakeOfs.sends.filter(c => c.channel === 'term:input')).toHaveLength(1))
    expect(rig.term).toBe(term)
  })

  it('does not paste delayed clipboard contents after closing the terminal', async () => {
    const text = deferred<string>()
    readText.mockReturnValueOnce(text.promise)
    const view = render(<AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp>)
    await act(async () => {})
    const core = (rig.term as unknown as { _core: { _keyDown(event: KeyboardEvent): void } })._core
    act(() => core._keyDown(new KeyboardEvent('keydown', { key: 'V', code: 'KeyV', ctrlKey: true, shiftKey: true, cancelable: true })))
    view.unmount()
    await act(async () => { text.resolve('late clipboard') })
    expect(fakeOfs.sends.filter(c => c.channel === 'term:input')).toEqual([])
  })

  it('forwards binary reports and leaves protocol replies out of prompt tracking', async () => {
    render(<AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp>)
    await act(async () => {})
    const term = rig.term!
    await act(() => new Promise<void>(resolve => term.write('user@host:~# \x1b[6n', resolve)))
    expect(trackerFor('paste-term').snapshot().trusted).toBe(false)
    const core = (term as unknown as { _core: { coreService: { triggerBinaryEvent(data: string): void } } })._core
    act(() => core.coreService.triggerBinaryEvent('\x1b[M\x20\xb4\xe6'))
    expect(fakeOfs.sends.find(c => c.channel === 'term:binary')?.payload).toEqual({ termId: 'paste-term', data: new Uint8Array([27, 91, 77, 32, 180, 230]) })
  })

  it('applies copy-on-select changes to the live terminal', async () => {
    useSettingsStore.setState(state => ({ settings: { ...state.settings!, terminal: { ...state.settings!.terminal, copyOnSelect: false } } }))
    render(<AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp>)
    await act(async () => {})
    vi.spyOn(rig.term!, 'getSelection').mockReturnValue('selected text')
    const selection = (rig.term as unknown as { _core: { _onSelectionChange: { fire(): void } } })._core._onSelectionChange
    act(() => selection.fire())
    expect(writeText).not.toHaveBeenCalled()
    act(() => useSettingsStore.setState(state => ({ settings: { ...state.settings!, terminal: { ...state.settings!.terminal, copyOnSelect: true } } })))
    act(() => selection.fire())
    expect(writeText).toHaveBeenCalledExactlyOnceWith('selected text')
  })

  it('leaves remote right clicks to the CLI and allows Shift to request local paste', async () => {
    render(<AntdApp><TerminalPane tab={tab} active uiMode="dark" /></AntdApp>)
    await act(async () => {})
    await act(() => new Promise<void>(resolve => rig.term!.write('\x1b[?1002h', resolve)))
    fireEvent.contextMenu(screen.getByTestId('terminal-input'))
    expect(readText).not.toHaveBeenCalled()
    fireEvent.contextMenu(screen.getByTestId('terminal-input'), { shiftKey: true })
    await act(async () => {})
    expect(readText).toHaveBeenCalledTimes(1)
    expect(fakeOfs.sends.filter(c => c.channel === 'term:input')).toHaveLength(1)
  })
})

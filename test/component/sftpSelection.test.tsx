// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import '@/i18n'
import { DEFAULT_SETTINGS } from '@shared/constants'
import type { SessionId, SftpEntry } from '@shared/types'
import { SftpPane } from '@/features/sftp/SftpPane'
import { useSettingsStore } from '@/stores/useSettingsStore'
import type { SessionTab } from '@/stores/useSessionStore'
import { fakeOfs } from './fakeOfs'

const tab: SessionTab = { id: 'select-tab', profileId: 'p1', sessionId: 's1' as SessionId, termId: null, title: 'server', state: 'ready', sftpOpen: true, monitorOpen: false, shellEpoch: 0 }
const entries: SftpEntry[] = ['a.txt', 'b.txt', 'c.txt', 'd.txt', 'e.txt', '.hidden'].map((name, i) => ({
  name, path: `/root/${name}`, type: 'file', size: 100 - i * 10,
  mode: 0o644, modeStr: '-rw-r--r--', owner: 'admin', group: 'admin', mtime: 1700000000 + i
}))
let container: HTMLElement
function row(name: string): HTMLElement {
  const found = container.querySelector<HTMLElement>(`.ant-table-row[data-row-key="/root/${name}"]`)
  expect(found, `Missing row ${name}`).toBeTruthy()
  return found!
}
function selection(): string[] {
  return Array.from(container.querySelectorAll('[data-row-key][aria-selected="true"]')).map((element) => element.getAttribute('data-row-key')!.split('/').pop()!)
}
function list(): HTMLElement { return screen.getByRole('grid', { name: '文件列表' }) }
async function mount(active = true): Promise<void> {
  container = render(<AntdApp><SftpPane tab={tab} active={active} /></AntdApp>).container
  await waitFor(() => expect(container.querySelectorAll('.ant-table-row')).toHaveLength(5))
}

beforeEach(() => {
  fakeOfs.reset()
  useSettingsStore.setState({ settings: { ...structuredClone(DEFAULT_SETTINGS), sftp: { ...DEFAULT_SETTINGS.sftp, showHiddenFiles: false, doubleClickAction: 'open' } } })
  fakeOfs.handle('sftp:realpath', () => '/root')
  fakeOfs.handle('sftp:readdir', () => entries)
  fakeOfs.handle('editor:openFile', () => undefined)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(450)
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(900)
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function (this: HTMLElement) { return this.classList.contains('ant-table-header') ? 38 : 39 })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('Explorer-style SFTP selection', () => {
  it('removes checkboxes, selects entire rows, and focuses the file list', async () => {
    await mount()
    expect(container.querySelector('input[type="checkbox"]')).toBeNull()
    fireEvent.click(row('a.txt'))
    expect(selection()).toEqual(['a.txt'])
    expect(document.activeElement).toBe(list())
    fireEvent.click(row('c.txt'))
    expect(selection()).toEqual(['c.txt'])
  })

  it('toggles individual files with Ctrl and Command', async () => {
    await mount()
    fireEvent.click(row('a.txt'))
    fireEvent.click(row('c.txt'), { ctrlKey: true })
    expect(selection()).toEqual(['a.txt', 'c.txt'])
    fireEvent.click(row('a.txt'), { ctrlKey: true })
    fireEvent.click(row('e.txt'), { metaKey: true })
    expect(selection()).toEqual(['c.txt', 'e.txt'])
  })

  it('extends and contracts Shift ranges from the anchor; Ctrl+Shift adds a range', async () => {
    await mount()
    fireEvent.click(row('b.txt'))
    fireEvent.click(row('e.txt'), { shiftKey: true })
    expect(selection()).toEqual(['b.txt', 'c.txt', 'd.txt', 'e.txt'])
    fireEvent.click(row('c.txt'), { shiftKey: true })
    expect(selection()).toEqual(['b.txt', 'c.txt'])
    fireEvent.click(row('e.txt'), { ctrlKey: true })
    fireEvent.click(row('d.txt'), { ctrlKey: true, shiftKey: true })
    expect(selection()).toEqual(['b.txt', 'c.txt', 'd.txt', 'e.txt'])
  })

  it('selects all displayed files with Ctrl+A and clears with Escape or a blank click', async () => {
    await mount()
    fireEvent.click(row('a.txt'))
    fireEvent.keyDown(list(), { key: 'a', ctrlKey: true })
    expect(selection()).toEqual(['a.txt', 'b.txt', 'c.txt', 'd.txt', 'e.txt'])
    fireEvent.keyDown(list(), { key: 'Escape' })
    expect(selection()).toEqual([])
    fireEvent.click(row('b.txt'))
    fireEvent.click(list())
    expect(selection()).toEqual([])
  })

  it('moves selection with arrows, extends with Shift, and moves focus alone with Ctrl', async () => {
    await mount()
    fireEvent.click(row('b.txt'))
    fireEvent.keyDown(list(), { key: 'ArrowDown', shiftKey: true })
    expect(selection()).toEqual(['b.txt', 'c.txt'])
    fireEvent.keyDown(list(), { key: 'ArrowDown', ctrlKey: true })
    expect(selection()).toEqual(['b.txt', 'c.txt'])
    expect(document.getElementById(list().getAttribute('aria-activedescendant')!)?.getAttribute('data-row-key')).toBe('/root/d.txt')
    fireEvent.keyDown(list(), { key: ' ', ctrlKey: true })
    expect(selection()).toEqual(['b.txt', 'c.txt', 'd.txt'])
    fireEvent.keyDown(list(), { key: 'End' })
    expect(selection()).toEqual(['e.txt'])
    fireEvent.keyDown(list(), { key: 'Home' })
    expect(selection()).toEqual(['a.txt'])
  })

  it('preserves a right-clicked selection and replaces it when clicking outside the group', async () => {
    await mount()
    fireEvent.click(row('a.txt'))
    fireEvent.click(row('c.txt'), { ctrlKey: true })
    fireEvent.contextMenu(row('a.txt'))
    expect(selection()).toEqual(['a.txt', 'c.txt'])
    fireEvent.keyDown(list(), { key: 'Escape' })
    expect(selection()).toEqual(['a.txt', 'c.txt'])
    fireEvent.contextMenu(row('e.txt'))
    expect(selection()).toEqual(['e.txt'])
  })

  it('uses displayed sort order for Shift ranges and keyboard navigation', async () => {
    await mount()
    fireEvent.click(screen.getByText('名称'))
    fireEvent.click(screen.getByText('名称'))
    expect(Array.from(container.querySelectorAll('.ant-table-row')).map((element) => element.getAttribute('data-row-key'))).toEqual(['e.txt', 'd.txt', 'c.txt', 'b.txt', 'a.txt'].map(name => `/root/${name}`))
    fireEvent.click(row('e.txt'))
    fireEvent.click(row('c.txt'), { shiftKey: true })
    expect(selection()).toEqual(['e.txt', 'd.txt', 'c.txt'])
    fireEvent.keyDown(list(), { key: 'ArrowDown' })
    expect(selection()).toEqual(['b.txt'])
  })

  it('keeps Ctrl+A and Delete inside the rename input; Escape cancels renaming', async () => {
    await mount()
    fireEvent.click(row('b.txt'))
    fireEvent.keyDown(list(), { key: 'F2' })
    const input = row('b.txt').querySelector('input')!
    expect(input).toBeTruthy()
    fireEvent.keyDown(input, { key: 'a', ctrlKey: true })
    fireEvent.keyDown(input, { key: 'Delete' })
    expect(selection()).toEqual(['b.txt'])
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(row('b.txt').querySelector('input')).toBeNull()
  })

  it('opens one file with Enter or double-click and retains the existing delete confirmation', async () => {
    await mount()
    fireEvent.click(row('b.txt'))
    fireEvent.keyDown(list(), { key: 'Enter' })
    await waitFor(() => expect(fakeOfs.invokes.filter(call => call.channel === 'editor:openFile')).toHaveLength(1))
    fireEvent.doubleClick(row('c.txt'))
    await waitFor(() => expect(fakeOfs.invokes.filter(call => call.channel === 'editor:openFile')).toHaveLength(2))
    fireEvent.keyDown(list(), { key: 'Delete' })
    await screen.findByRole('dialog')
    expect(fakeOfs.invokes.some(call => call.channel === 'sftp:delete')).toBe(false)
  })

  it('ignores shortcuts for inactive sessions', async () => {
    await mount(false)
    fireEvent.keyDown(list(), { key: 'a', ctrlKey: true })
    expect(selection()).toEqual([])
  })

  it('does not treat composing text as a file-list command', async () => {
    await mount()
    fireEvent.keyDown(list(), { key: 'a', ctrlKey: true, isComposing: true })
    expect(selection()).toEqual([])
  })
})

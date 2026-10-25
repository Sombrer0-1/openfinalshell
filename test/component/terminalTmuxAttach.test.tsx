// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import type { Terminal } from '@xterm/xterm'
import '@/i18n'
import i18n from '@/i18n'
import { DEFAULT_SETTINGS } from '@shared/constants'
import { TerminalPane } from '@/features/terminal/TerminalPane'
import { useSessionStore, wireSessionEvents, type SessionTab } from '@/stores/useSessionStore'
import { useSettingsStore } from '@/stores/useSettingsStore'
import { getTerm } from '@/features/terminal/termRegistry'
import { fakeOfs } from './fakeOfs'

/**
 * TerminalPane 的 tmux 附着模式：
 * ready 后不开 shell、直调 tmux:attach 并完成 term:ready 握手（否则输出一直缓冲）；
 * 断线重连后凭 targetId 重新附着；term:exit → 已退出态两按钮；
 * 关标签只是 detach（远端会话存活），绝不触发 tmux:kill。
 */

const rig = vi.hoisted(() => ({ term: null as Terminal | null }))

vi.mock('@/features/terminal/createTerminal', async () => {
  const { Terminal } = await import('@xterm/xterm')
  return {
    createTerminal: () => {
      const term = new Terminal({ allowProposedApi: true })
      // 保留真实 xterm 解析与缓冲；跳过 DOM 渲染（jsdom 量不出尺寸）
      term.open = () => {}
      term.focus = () => {}
      rig.term = term
      return {
        term, fit: { fit: () => {} }, search: {},
        attachWebgl: () => {}, detachWebgl: () => {}, dispose: () => term.dispose()
      }
    }
  }
})

const tab: SessionTab = {
  id: 'att-tab',
  kind: 'tmuxAttach',
  profileId: 'p1',
  targetId: '$3',
  tmuxName: 'b',
  sessionId: 'att-sess',
  termId: null,
  title: 'srv · tmux b',
  state: 'ready',
  sftpOpen: false,
  monitorOpen: false,
  shellEpoch: 0
}

function seedTab(overrides: Partial<SessionTab> = {}): void {
  useSessionStore.setState({ tabs: [{ ...tab, ...overrides }], activeTabId: 'att-tab' })
}

/** 与 SessionViewHost 同款：订阅 store，让 tab 状态变化驱动 TerminalPane 重渲染 */
function Host(): React.JSX.Element {
  const current = useSessionStore((s) => s.tabs[0])
  return (
    <AntdApp>
      {current ? <TerminalPane tab={current} active uiMode="dark" /> : null}
    </AntdApp>
  )
}

function mount(): void {
  render(<Host />)
}

function channels(): string[] {
  return fakeOfs.invokes.map((c) => c.channel)
}

beforeEach(() => {
  // 不用 fakeOfs.reset()：它会清掉事件监听，而 wireSessionEvents 有 wired 守卫不会重挂。
  // 只清流水、重装 handler（handle 覆盖式注册），监听保持整文件存续。
  fakeOfs.invokes.length = 0
  fakeOfs.sends.length = 0
  fakeOfs.handle('tmux:attach', () => ({ termId: 'tmux-term' }))
  fakeOfs.handle('term:ready', () => undefined)
  fakeOfs.handle('term:close', () => undefined)
  fakeOfs.handle('session:close', () => undefined)
  useSettingsStore.setState({ settings: structuredClone(DEFAULT_SETTINGS) })
  useSessionStore.setState({ tabs: [], activeTabId: null })
  wireSessionEvents()
})

afterEach(() => cleanup())

describe('TerminalPane 附着模式', () => {
  it('ready 后直调 tmux:attach 并完成 term:ready 握手，全程不开 shell', async () => {
    seedTab()
    mount()
    await waitFor(() => expect(channels()).toContain('term:ready'))

    const order = channels()
    expect(order.indexOf('tmux:attach')).toBeGreaterThanOrEqual(0)
    expect(order.indexOf('term:ready')).toBeGreaterThan(order.indexOf('tmux:attach'))
    expect(order).not.toContain('term:open')

    const attach = fakeOfs.invokes.find((c) => c.channel === 'tmux:attach')
    expect(attach?.payload).toMatchObject({ sessionId: 'att-sess', targetId: '$3' })
    expect(attach?.payload).toEqual(
      expect.objectContaining({ cols: expect.any(Number), rows: expect.any(Number) })
    )
    // 握手前 termId 已注册进 registry（term:data 的投递目标），标签也绑上了 termId
    expect(getTerm('tmux-term' as Parameters<typeof getTerm>[0])).toBe(rig.term)
    expect(useSessionStore.getState().tabs[0].termId).toBe('tmux-term')
  })

  it('附着失败（会话被外部杀掉）原样透出：已退出态可重试', async () => {
    fakeOfs.handle('tmux:attach', () => Promise.reject(new Error('会话已不存在')))
    seedTab()
    mount()
    await screen.findByText('会话已不存在')
    expect(useSessionStore.getState().tabs[0].state).toBe('closed')
  })

  it('detach（prefix+d / tmux 客户端退出）→ 已退出态，两个按钮：重新附着 / 关闭标签', async () => {
    seedTab()
    mount()
    await waitFor(() => expect(channels()).toContain('term:ready'))

    act(() => fakeOfs.emit('term:exit', { termId: 'tmux-term', reason: 'closed' }))

    const detached = i18n.t('tmux.detached')
    await screen.findByText(detached)
    expect(useSessionStore.getState().tabs[0]).toMatchObject({ state: 'closed', termId: null, error: detached })
    // 两按钮：重新附着（主）+ 关闭标签；不出现普通终端的“重新连接”文案
    expect(screen.getByRole('button', { name: (n) => n.replace(/\s/g, '') === i18n.t('tmux.reattach') })).toBeTruthy()
    expect(screen.getByRole('button', { name: (n) => n.replace(/\s/g, '') === i18n.t('tmux.closeTab') })).toBeTruthy()
    expect(screen.queryByText(i18n.t('terminal.reconnected') + '')).toBeNull()
  })

  it('已退出态点「重新附着」：不重连（连接还在），重开附着通道并重走握手', async () => {
    seedTab()
    mount()
    await waitFor(() => expect(channels()).toContain('term:ready'))
    const firstAttachCount = channels().filter((c) => c === 'tmux:attach').length

    act(() => fakeOfs.emit('term:exit', { termId: 'tmux-term', reason: 'closed' }))
    fireEvent.click(
      await screen.findByRole('button', { name: (n) => n.replace(/\s/g, '') === i18n.t('tmux.reattach') })
    )

    await waitFor(() =>
      expect(channels().filter((c) => c === 'tmux:attach')).toHaveLength(firstAttachCount + 1)
    )
    // detach 后连接仍在：重新附着不触发重连，也不新开连接
    expect(channels()).not.toContain('session:reconnect')
    expect(channels()).not.toContain('session:open')
    // 重附着成功后回到 ready 并重新握手
    await waitFor(() => expect(channels().filter((c) => c === 'term:ready').length).toBeGreaterThanOrEqual(2))
    expect(useSessionStore.getState().tabs[0].state).toBe('ready')
  })

  it('断线重连（连接恢复）后凭 targetId 自动重新附着，不回退开 shell', async () => {
    seedTab({ everReady: true })
    mount()
    await waitFor(() => expect(channels()).toContain('term:ready'))
    const firstAttachCount = channels().filter((c) => c === 'tmux:attach').length

    // 真实时序：掉线先 term:exit(reconnected) 清 termId，连接恢复走 session:state ready（epoch 递增）
    act(() => fakeOfs.emit('term:exit', { termId: 'tmux-term', reason: 'reconnected' }))
    act(() => fakeOfs.emit('session:state', { sessionId: 'att-sess', state: 'reconnecting', error: '断开' }))
    act(() => fakeOfs.emit('session:state', { sessionId: 'att-sess', state: 'ready' }))

    await waitFor(() =>
      expect(channels().filter((c) => c === 'tmux:attach').length).toBeGreaterThan(firstAttachCount)
    )
    expect(channels()).not.toContain('term:open')
    // 重附着仍带着同一目标会话
    const attaches = fakeOfs.invokes.filter((c) => c.channel === 'tmux:attach')
    expect(attaches.every((c) => (c.payload as { targetId: string }).targetId === '$3')).toBe(true)
    await waitFor(() => expect(useSessionStore.getState().tabs[0].state).toBe('ready'))
  })

  it('关闭附着标签 = detach：只断自有连接，绝不 kill 远端会话', async () => {
    seedTab()
    mount()
    await waitFor(() => expect(channels()).toContain('term:ready'))

    // 标签栏 × 的行为：走 store 的 closeTab（附着态没有浮层按钮）
    await act(async () => {
      await useSessionStore.getState().closeTab('att-tab')
    })

    expect(useSessionStore.getState().tabs).toEqual([])
    expect(channels()).toContain('session:close')
    expect(channels()).toContain('term:close') // TerminalPane 卸载时关附着通道
    expect(channels()).not.toContain('tmux:kill')
    expect(channels()).not.toContain('tmux:stop')
  })
})

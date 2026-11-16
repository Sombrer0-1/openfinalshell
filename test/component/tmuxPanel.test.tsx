// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp, ConfigProvider } from 'antd'
import '@/i18n'
import i18n from '@/i18n'
import type { TmuxSessionInfo } from '@shared/types'
import { TmuxPanelTab } from '@/features/tmux/TmuxPanelTab'
import { useTmuxStore } from '@/stores/useTmuxStore'
import { useSessionStore, type SessionTab } from '@/stores/useSessionStore'
import { useConnectionStore } from '@/stores/useConnectionStore'
import { fakeOfs } from './fakeOfs'

/**
 * Tmux 面板的五态渲染与交互：
 * 连接中 / 采集中 / 未安装 / 失败 / 已停止（外加 running 的列表与空态），
 * 新建表单（重名就地报错、默认创建并进入）、关闭会话确认（attached > 0 警告）、
 * 鼠标提示条的显隐与关闭。
 */

function makeTab(overrides: Partial<SessionTab> = {}): SessionTab {
  return {
    id: 'tmux-tab',
    kind: 'tmuxPanel',
    profileId: 'p1',
    sessionId: 'tmux-ui',
    termId: null,
    title: 'srv · Tmux',
    state: 'ready',
    sftpOpen: false,
    monitorOpen: false,
    shellEpoch: 0,
    ...overrides
  }
}

function setPanelState(
  state: 'collecting' | 'running' | 'failed' | 'unsupported' | 'stopped',
  error?: string
): void {
  act(() => {
    useTmuxStore.setState((s) => ({ state: { ...s.state, 'tmux-ui': state }, error: { ...s.error, 'tmux-ui': error } }))
  })
}

function setSnapshot(sessions: TmuxSessionInfo[], mouseOn?: boolean): void {
  act(() => {
    useTmuxStore.setState((s) => ({
      latest: { ...s.latest, 'tmux-ui': { ts: 1748000000_000, sessions, ...(mouseOn === undefined ? {} : { mouseOn }) } }
    }))
  })
}

function mount(tab: SessionTab = makeTab()): void {
  render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <AntdApp>
        <TmuxPanelTab tab={tab} />
      </AntdApp>
    </ConfigProvider>
  )
}

const ROW: TmuxSessionInfo = { id: '$1', name: 'main', windows: 2, attached: 1, created: 1748000000 }
const ROW2: TmuxSessionInfo = { id: '$2', name: 'build', windows: 1, attached: 0, created: 1748000600 }

beforeEach(() => {
  fakeOfs.reset()
  fakeOfs.handle('tmux:start', () => undefined)
  fakeOfs.handle('tmux:stop', () => undefined)
  fakeOfs.handle('session:open', () => ({ sessionId: 'sid-attach' }))
  useTmuxStore.setState({ latest: {}, state: {}, error: {}, mouseTipDismissed: {} })
  useSessionStore.setState({ tabs: [], activeTabId: null })
  // 附着标签入口要按 profileId 找到连接配置才走得下去
  useConnectionStore.setState({
    profiles: [
      {
        id: 'p1',
        name: 'srv',
        groupId: null,
        host: 'h',
        port: 22,
        username: 'root',
        auth: { method: 'password' },
        terminal: { charset: 'utf-8', termType: 'xterm' },
        options: {
          keepaliveInterval: 15000,
          readyTimeout: 20000,
          legacyAlgorithms: false,
          autoReconnect: true,
          monitorEnabled: true,
          compress: false
        },
        createdAt: 1,
        updatedAt: 1
      }
    ] as never
  })
})

/** antd 会给两字中文按钮自动插空格（“开 启”），比对可访问名时忽略空白 */
function cjkName(label: string): (name: string) => boolean {
  return (name: string) => name.replace(/\s/g, '') === label
}

afterEach(() => {
  cleanup()
  // modal.confirm 挂在独立 React root 上，cleanup 卸不掉，得手动清（防止串进下一个用例的查询）
  document.querySelectorAll('.ant-modal-root').forEach((el) => el.remove())
})

describe('面板五态渲染', () => {
  it('自有连接尚未建立：显示「连接中」', () => {
    mount(makeTab({ sessionId: null, state: 'connecting' }))
    expect(screen.getByText(i18n.t('tmux.connecting'))).toBeTruthy()
  })

  it('自有连接失败：显示失败原因与重试（重试走 reconnectTab）', () => {
    mount(makeTab({ sessionId: null, state: 'closed', error: '认证失败' }))
    expect(screen.getByText('认证失败')).toBeTruthy()
    expect(screen.getByRole('button', { name: cjkName(i18n.t('common.retry')) })).toBeTruthy()
  })

  it('SSH 连接已断时隐藏旧列表，重试会重新连接', async () => {
    const tab = makeTab({ state: 'closed', error: 'network lost' })
    useSessionStore.setState({ tabs: [tab], activeTabId: tab.id })
    fakeOfs.handle('session:reconnect', () => undefined)
    setPanelState('running')
    setSnapshot([ROW])
    mount(tab)
    expect(screen.getByText('network lost')).toBeTruthy()
    expect(screen.queryByText('main')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: cjkName(i18n.t('common.retry')) }))
    await waitFor(() => expect(fakeOfs.invokes).toContainEqual({
      channel: 'session:reconnect', payload: 'tmux-ui'
    }))
  })

  it('未安装 tmux：显示未安装提示，且无重试按钮（重试解决不了没装）', () => {
    mount()
    setPanelState('unsupported')
    expect(screen.getByText(i18n.t('tmux.unsupported'))).toBeTruthy()
    expect(screen.queryByRole('button', { name: cjkName(i18n.t('common.retry')) })).toBeNull()
  })

  it('采集失败（SECTION:ERROR）：透出错误原因 + 重试按钮，重试重发 tmux:start', () => {
    mount()
    setPanelState('failed', 'error connecting to /tmp/tmux-1000/default (Permission denied)')
    expect(screen.getByText(/Permission denied/)).toBeTruthy()
    const startsBefore = fakeOfs.invokes.filter((c) => c.channel === 'tmux:start').length
    fireEvent.click(screen.getByRole('button', { name: cjkName(i18n.t('common.retry')) }))
    expect(fakeOfs.invokes.filter((c) => c.channel === 'tmux:start')).toHaveLength(startsBefore + 1)
  })

  it('采集已停止：显示停止态与重试', () => {
    mount()
    setPanelState('stopped')
    expect(screen.getByText(i18n.t('tmux.stopped'))).toBeTruthy()
    expect(screen.getByRole('button', { name: cjkName(i18n.t('common.retry')) })).toBeTruthy()
  })

  it('首帧未回（无快照）：显示采集中过渡语，不显示空列表文案', () => {
    mount()
    setPanelState('collecting')
    expect(screen.getByText(i18n.t('tmux.collecting'))).toBeTruthy()
    expect(screen.queryByText(i18n.t('tmux.empty'))).toBeNull()
  })

  it('合法空态：无会话时空列表 + 新建入口；就绪即发 tmux:start', () => {
    mount()
    setPanelState('running')
    setSnapshot([])
    expect(screen.getByText(i18n.t('tmux.empty'))).toBeTruthy()
    expect(screen.getAllByRole('button', { name: cjkName(i18n.t('tmux.newSession')) })[0]).toBeTruthy()
    expect(fakeOfs.invokes.map((c) => c.channel)).toContain('tmux:start')
  })
})

describe('会话列表与新建交互', () => {
  it('列表行渲染名称 / 窗口数 / attached 徽标 / 操作，附着走新连接', () => {
    mount()
    setPanelState('running')
    setSnapshot([ROW, ROW2])
    const row = screen.getByText('main').closest('tr')!
    expect(row.textContent).toContain('main')
    expect(row.textContent).toContain('2') // 窗口数
    expect(row.textContent).toContain('1') // attached 徽标（悬停说明“附着将共享该会话”）

    fireEvent.click(screen.getAllByRole('button', { name: cjkName(i18n.t('tmux.attach')) })[0])
    expect(fakeOfs.invokes.find((c) => c.channel === 'session:open')).toEqual({
      channel: 'session:open',
      payload: 'p1'
    })
  })

  it('新建：空名校验就地报错，不发 tmux:new', () => {
    mount()
    setPanelState('running')
    setSnapshot([])
    fireEvent.click(screen.getAllByRole('button', { name: cjkName(i18n.t('tmux.newSession')) })[0])
    fireEvent.click(screen.getByRole('button', { name: cjkName(i18n.t('tmux.create')) }))
    expect(screen.getByText(i18n.t('tmux.nameRequired'))).toBeTruthy()
    expect(fakeOfs.invokes.filter((c) => c.channel === 'tmux:new')).toHaveLength(0)
  })

  it('重名错误就地报错，表单不关、可以改了再交', async () => {
    fakeOfs.handle('tmux:new', () => Promise.reject(new Error('A session named "b" already exists')))
    mount()
    setPanelState('running')
    setSnapshot([])
    fireEvent.click(screen.getAllByRole('button', { name: cjkName(i18n.t('tmux.newSession')) })[0])
    fireEvent.change(screen.getByPlaceholderText(i18n.t('tmux.newNamePlaceholder')), {
      target: { value: 'b' }
    })
    fireEvent.click(screen.getByRole('button', { name: cjkName(i18n.t('tmux.create')) }))

    await screen.findByText('A session named "b" already exists')
    // 表单还开着（报错不关表单），输入框仍在
    expect(screen.getByPlaceholderText(i18n.t('tmux.newNamePlaceholder'))).toBeTruthy()
  })

  it('创建成功默认「创建并进入」：成功后新开附着标签（新连接）', async () => {
    fakeOfs.handle('tmux:new', () => ({ id: '$9' }))
    const openTmuxAttachTab = vi.fn()
    const original = useSessionStore.getState().openTmuxAttachTab
    useSessionStore.setState({ openTmuxAttachTab })
    mount()
    setPanelState('running')
    setSnapshot([])
    fireEvent.click(screen.getAllByRole('button', { name: cjkName(i18n.t('tmux.newSession')) })[0])
    fireEvent.change(screen.getByPlaceholderText(i18n.t('tmux.newNamePlaceholder')), {
      target: { value: 'b' }
    })
    fireEvent.click(screen.getByRole('button', { name: cjkName(i18n.t('tmux.create')) }))

    await waitFor(() =>
      expect(fakeOfs.invokes.find((c) => c.channel === 'tmux:new')).toEqual({
        channel: 'tmux:new',
        payload: { sessionId: 'tmux-ui', name: 'b' }
      })
    )
    await waitFor(() => expect(openTmuxAttachTab).toHaveBeenCalledWith('p1', '$9', 'b'))
    useSessionStore.setState({ openTmuxAttachTab: original })
  })

  it('取消「创建并进入」：只创建，不开附着标签', async () => {
    fakeOfs.handle('tmux:new', () => ({ id: '$9' }))
    const openTmuxAttachTab = vi.fn()
    const original = useSessionStore.getState().openTmuxAttachTab
    useSessionStore.setState({ openTmuxAttachTab })
    mount()
    setPanelState('running')
    setSnapshot([])
    fireEvent.click(screen.getAllByRole('button', { name: cjkName(i18n.t('tmux.newSession')) })[0])
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.change(screen.getByPlaceholderText(i18n.t('tmux.newNamePlaceholder')), {
      target: { value: 'b' }
    })
    fireEvent.click(screen.getByRole('button', { name: cjkName(i18n.t('tmux.create')) }))

    await waitFor(() => expect(fakeOfs.invokes.filter((c) => c.channel === 'tmux:new')).toHaveLength(1))
    await act(async () => {})
    expect(openTmuxAttachTab).not.toHaveBeenCalled()
    useSessionStore.setState({ openTmuxAttachTab: original })
  })
})

describe('关闭会话确认', () => {
  /** antd confirm 的标题会同时渲染进 .ant-modal-title 与 .ant-modal-confirm-title，按容器取 */
  async function confirmText(): Promise<{ title: string; content: string }> {
    await waitFor(() => {
      if (!document.querySelector('.ant-modal-confirm-content')) throw new Error('confirm not open yet')
    })
    return {
      title: document.querySelector('.ant-modal-confirm-title')?.textContent ?? '',
      content: document.querySelector('.ant-modal-confirm-content')?.textContent ?? ''
    }
  }

  it('attached > 0：确认弹窗写明已连接客户端数与断开后果，确认后 tmux:kill', async () => {
    fakeOfs.handle('tmux:kill', () => undefined)
    mount()
    setPanelState('running')
    setSnapshot([ROW]) // attached: 1
    fireEvent.click(screen.getAllByRole('button', { name: cjkName(i18n.t('tmux.kill')) })[0])

    const { title, content } = await confirmText()
    expect(title).toBe(i18n.t('tmux.killTitle', { name: 'main' }))
    expect(content).toContain(i18n.t('tmux.killAttachedContent', { count: 1 }))

    // 确认弹窗自己的 OK（与行内同名“关闭会话”按钮区分：只认 confirm 按钮区里的那个）
    const ok = [...document.querySelectorAll('.ant-modal-confirm-btns button')].find(
      (b) => b.textContent?.replace(/\s/g, '') === i18n.t('tmux.killOk')
    )!
    expect(ok).toBeTruthy()
    fireEvent.click(ok)
    await waitFor(() =>
      expect(fakeOfs.invokes.find((c) => c.channel === 'tmux:kill')).toEqual({
        channel: 'tmux:kill',
        payload: { sessionId: 'tmux-ui', targetId: '$1' }
      })
    )
  })

  it('无连接客户端：确认弹窗用普通文案（不带 attached 警告）', async () => {
    mount()
    setPanelState('running')
    setSnapshot([ROW2]) // attached: 0
    fireEvent.click(screen.getAllByRole('button', { name: cjkName(i18n.t('tmux.kill')) })[0])

    const { title, content } = await confirmText()
    expect(title).toBe(i18n.t('tmux.killTitle', { name: 'build' }))
    expect(content).toContain(i18n.t('tmux.killContent'))
    expect(content).not.toContain('attached')
  })
})

describe('鼠标提示条', () => {
  it('有会话且 mouse 为 off 时出现；一键开启发 tmux:setMouse；点 × 关闭后不再出现', async () => {
    fakeOfs.handle('tmux:setMouse', () => undefined)
    mount()
    setPanelState('running')
    setSnapshot([ROW], false)
    expect(screen.getByText(i18n.t('tmux.mouseTip'))).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: cjkName(i18n.t('tmux.enableMouse')) }))
    await waitFor(() =>
      expect(fakeOfs.invokes.find((c) => c.channel === 'tmux:setMouse')).toEqual({
        channel: 'tmux:setMouse',
        payload: { sessionId: 'tmux-ui', enabled: true }
      })
    )

    fireEvent.click(screen.getByRole('button', { name: cjkName(i18n.t('common.close')) }))
    expect(screen.queryByText(i18n.t('tmux.mouseTip'))).toBeNull()
  })

  it('mouse 已开启或没有会话时不出现', () => {
    mount()
    setPanelState('running')
    setSnapshot([ROW], true)
    expect(screen.queryByText(i18n.t('tmux.mouseTip'))).toBeNull()

    setSnapshot([], false)
    expect(screen.queryByText(i18n.t('tmux.mouseTip'))).toBeNull()
  })
})

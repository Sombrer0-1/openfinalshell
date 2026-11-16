import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { EventMap } from '@shared/ipc'

/**
 * 会话 tab 的状态机（renderer 侧）。
 * 重点是"首次连上"与"重新建连"必须区分开 —— 二者在 main 侧都是 session:state=ready，
 * 分不清就会在每次新建会话时打出"—— 连接已恢复 ——"分隔线。
 */

type Handler = (payload: unknown) => void
const handlers = new Map<string, Handler[]>()
/** 记录所有 invoke，用来断言"关面板时真的发了 monitor:stop" */
const calls: Array<[string, unknown[]]> = []

/** 每个用例可替换的 invoke 实现：要模拟"事件先到、session:open 后 resolve"的真实时序 */
let invokeImpl: (channel: string, ...args: unknown[]) => Promise<unknown> = async () => ({
  sessionId: 'sid-1'
})

vi.mock('@/ipc/api', () => ({
  ofs: {
    invoke: vi.fn((channel: string, ...args: unknown[]) => {
      calls.push([channel, args])
      return invokeImpl(channel, ...args)
    }),
    send: vi.fn(),
    on: (channel: string, handler: Handler) => {
      const list = handlers.get(channel) ?? []
      list.push(handler)
      handlers.set(channel, list)
      return () => {}
    }
  }
}))

const { useSessionStore, wireSessionEvents } = await import('@/stores/useSessionStore')
const { useSettingsStore } = await import('@/stores/useSettingsStore')
const { useMonitorStore, historyOf } = await import('@/stores/useMonitorStore')
const { usePortTrafficStore } = await import('@/stores/usePortTrafficStore')
const { useProcessPanelStore } = await import('@/stores/useProcessPanelStore')
const { useTmuxStore } = await import('@/stores/useTmuxStore')
const { useConnectionStore } = await import('@/stores/useConnectionStore')
const i18n = (await import('@/i18n')).default
const { DEFAULT_SETTINGS } = await import('@shared/constants')
type SessionTab = ReturnType<typeof useSessionStore.getState>['tabs'][number]

function fire<K extends keyof EventMap>(channel: K, payload: EventMap[K]): void {
  for (const h of handlers.get(channel) ?? []) h(payload)
}

/** 直接塞一个已连上的 tab，绕过 openForProfile 的 IPC */
function seedTab(overrides: Record<string, unknown> = {}): string {
  const id = 'tab-1'
  useSessionStore.setState({
    tabs: [
      {
        id,
        profileId: 'p1',
        sessionId: 'sid-1',
        termId: null,
        title: 'srv',
        state: 'connecting',
        sftpOpen: false,
        monitorOpen: false,
        shellEpoch: 0,
        ...overrides
      }
    ],
    activeTabId: id
  })
  return id
}

function tab(): NonNullable<ReturnType<typeof useSessionStore.getState>['tabs'][number]> {
  return useSessionStore.getState().tabs[0]
}

beforeEach(() => {
  useSessionStore.setState({ tabs: [], activeTabId: null })
  useSettingsStore.setState({ settings: null })
  useMonitorStore.setState({ latest: {}, staticInfo: {}, state: {} })
  usePortTrafficStore.setState({ latest: {}, state: {}, error: {} })
  useProcessPanelStore.setState({ latest: {}, state: {}, error: {}, details: {} })
  useTmuxStore.setState({ latest: {}, state: {}, error: {}, mouseTipDismissed: {} })
  useConnectionStore.setState({ profiles: [PROFILE] as never })
  calls.length = 0
  invokeImpl = async () => ({ sessionId: 'sid-1' })
  wireSessionEvents()
})

const PROFILE = {
  id: 'p1',
  name: 'srv',
  groupId: null,
  host: 'h',
  port: 22,
  username: 'root',
  auth: { method: 'password' as const },
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

const RDP_PROFILE = {
  ...PROFILE,
  protocol: 'rdp' as const,
  port: 3389,
  username: '',
  rdp: { clipboard: true, certificatePolicy: 'prompt' as const }
}

/**
 * 真实时序：主进程先把 connecting/authenticating/ready 发出来，session:open 之后才 resolve。
 * 此时 tab 的 sessionId 还是 null —— 事件按 id 匹配会全部落空。
 *
 * 这一组是回归护栏：之前的用例都用 seedTab() 预置好 sessionId，把这个时序绕过去了，
 * 于是"主进程连上了、界面永远停在正在连接"这个 bug 一路漏到了发布版本里。
 */
describe('开连期间的状态事件（tab 还不知道自己的 sessionId）', () => {
  it('连上后 tab 变 ready，而不是卡在 connecting', async () => {
    invokeImpl = async (channel) => {
      if (channel !== 'session:open') return undefined
      // resolve 之前就把事件发出去，跟主进程的真实顺序一致
      fire('session:state', { sessionId: 'sid-9', state: 'connecting' })
      fire('session:state', { sessionId: 'sid-9', state: 'authenticating' })
      fire('session:state', { sessionId: 'sid-9', state: 'ready' })
      return { sessionId: 'sid-9' }
    }
    await useSessionStore.getState().openForProfile(PROFILE)

    expect(tab().sessionId).toBe('sid-9')
    expect(tab().state).toBe('ready') // ← 卡在 connecting 就是终端永远不开
    expect(tab().everReady).toBe(true)
    expect(tab().shellEpoch).toBe(0) // 首连不打"连接已恢复"分隔线
  })

  it('开连过程中又掉线：认领时用真实的最新状态，不硬写 ready', async () => {
    invokeImpl = async (channel) => {
      if (channel !== 'session:open') return undefined
      fire('session:state', { sessionId: 'sid-8', state: 'ready' })
      fire('session:state', { sessionId: 'sid-8', state: 'reconnecting', error: '断开，1 秒后重连' })
      return { sessionId: 'sid-8' }
    }
    await useSessionStore.getState().openForProfile(PROFILE)

    expect(tab().state).toBe('reconnecting')
    expect(tab().error).toBe('断开，1 秒后重连')
    expect(tab().everReady).toBeFalsy()
  })

  it('认领后的事件照常按 sessionId 生效（重连仍会递增 epoch）', async () => {
    invokeImpl = async (channel) => {
      if (channel !== 'session:open') return undefined
      fire('session:state', { sessionId: 'sid-7', state: 'ready' })
      return { sessionId: 'sid-7' }
    }
    await useSessionStore.getState().openForProfile(PROFILE)
    useSessionStore.getState().bindTerm(tab().id, 'term-7')

    fire('term:exit', { termId: 'term-7', reason: 'reconnected' })
    fire('session:state', { sessionId: 'sid-7', state: 'ready' })
    expect(tab().shellEpoch).toBe(1)
  })

  it('别的会话的残留状态不会串到新 tab 上', async () => {
    // 一个没人认领的会话先留下状态
    fire('session:state', { sessionId: 'sid-other', state: 'closed', error: '不该被别人捡走' })
    invokeImpl = async (channel) => {
      if (channel !== 'session:open') return undefined
      fire('session:state', { sessionId: 'sid-6', state: 'ready' })
      return { sessionId: 'sid-6' }
    }
    await useSessionStore.getState().openForProfile(PROFILE)
    expect(tab().state).toBe('ready')
    expect(tab().error).toBeUndefined()
  })

  it('RDP 也认领 open 返回前到达的状态，并且不误建 SSH 会话', async () => {
    invokeImpl = async (channel) => {
      if (channel !== 'rdp:open') return undefined
      fire('rdp:state', { sessionId: 'rdp-9', state: 'connecting' })
      fire('rdp:state', { sessionId: 'rdp-9', state: 'ready' })
      return { sessionId: 'rdp-9' }
    }

    const kind = await useSessionStore.getState().launchProfile(RDP_PROFILE)

    expect(kind).toBe('rdp')
    expect(tab()).toMatchObject({
      kind: 'rdp',
      sessionId: 'rdp-9',
      state: 'ready',
      sftpOpen: false,
      monitorOpen: false,
      everReady: true
    })
    expect(calls.map(([channel]) => channel)).toContain('rdp:open')
    expect(calls.map(([channel]) => channel)).not.toContain('session:open')
    expect(calls.map(([channel]) => channel)).not.toContain('conn:launchRdp')
  })

  it('RDP failed 状态映射成可重试的 closed tab，并保留 sessionId 给显式降级', async () => {
    invokeImpl = async (channel) => {
      if (channel !== 'rdp:open') return undefined
      fire('rdp:state', {
        sessionId: 'rdp-8', state: 'failed', errorCode: 'WORKER_MISSING',
        error: 'RDP worker is not installed'
      })
      return { sessionId: 'rdp-8' }
    }

    await useSessionStore.getState().openRdpForProfile(RDP_PROFILE)

    expect(tab()).toMatchObject({
      kind: 'rdp',
      sessionId: 'rdp-8',
      state: 'closed',
      error: 'RDP worker is not installed',
      errorCode: 'WORKER_MISSING'
    })
  })

  it('retains RDP errorCode in the view-model and clears it when reconnecting', () => {
    seedTab({ kind: 'rdp', sessionId: 'rdp-error', state: 'connecting' })
    fire('rdp:state', {
      sessionId: 'rdp-error', state: 'failed', errorCode: 'AUTH_FAILED', error: 'Authentication failed'
    })
    expect(tab()).toMatchObject({ state: 'closed', error: 'Authentication failed', errorCode: 'AUTH_FAILED' })

    fire('rdp:state', { sessionId: 'rdp-error', state: 'reconnecting' })
    expect(tab()).toMatchObject({ state: 'reconnecting', errorCode: undefined, error: undefined })
  })

  it('RDP 重连复用 sessionId 时递增端口代际，促使 renderer 重绑 MessagePort', async () => {
    seedTab({ kind: 'rdp', sessionId: 'rdp-1', state: 'closed', rdpPortEpoch: 0 })
    invokeImpl = async (channel) => {
      if (channel === 'rdp:reconnect') return undefined
      return { sessionId: 'sid-1' }
    }

    await useSessionStore.getState().reconnectTab('tab-1')

    expect(calls.map(([channel]) => channel)).toContain('rdp:reconnect')
    expect(tab().rdpPortEpoch).toBe(1)
  })
})

/**
 * 连上就打开 SFTP 与监控。两个开关**故意分属两处**：
 * SFTP 是全局偏好（settings.sftp.autoOpenOnConnect），
 * 监控是连接自己的属性（profile.options.monitorEnabled）—— 低配服务器通道紧张时
 * 要能按连接关掉，而不是一刀切。
 */
describe('新会话的面板初值', () => {
  it('默认两个面板都自动打开', async () => {
    await useSessionStore.getState().openForProfile(PROFILE)
    expect(tab().sftpOpen).toBe(true)
    expect(tab().monitorOpen).toBe(true)
  })

  it('设置还没加载完时用默认值，而不是当成关', async () => {
    // useSettingsStore.settings 此刻是 null（init 是异步的）
    expect(useSettingsStore.getState().settings).toBeNull()
    await useSessionStore.getState().openForProfile(PROFILE)
    expect(tab().sftpOpen).toBe(DEFAULT_SETTINGS.sftp.autoOpenOnConnect)
  })

  it('全局设置关掉 autoOpenOnConnect → 不开 SFTP，但监控照旧', async () => {
    useSettingsStore.setState({
      settings: {
        ...DEFAULT_SETTINGS,
        sftp: { ...DEFAULT_SETTINGS.sftp, autoOpenOnConnect: false }
      }
    })
    await useSessionStore.getState().openForProfile(PROFILE)
    expect(tab().sftpOpen).toBe(false)
    expect(tab().monitorOpen).toBe(true)
  })

  it('连接自己关掉 monitorEnabled → 不开监控，但 SFTP 照旧', async () => {
    await useSessionStore
      .getState()
      .openForProfile({ ...PROFILE, options: { ...PROFILE.options, monitorEnabled: false } })
    expect(tab().monitorOpen).toBe(false)
    expect(tab().sftpOpen).toBe(true)
  })
})

/**
 * 关监控必须同时停采集器。
 * stop 早先只写在 MainLayout.closeMonitor 里，而终端悬浮工具条那个按钮直接调
 * toggleMonitor —— 从工具条关面板时主进程仍在每 2s 采一整帧，一条泄漏的通道。
 * 自动打开会让它从"偶发"变成"每条会话都发生"，所以护栏盯的是 store 而不是某个组件。
 */
describe('toggleMonitor 与采集器生命周期', () => {
  it('关掉监控时发 monitor:stop', () => {
    seedTab({ monitorOpen: true, state: 'ready' })
    useSessionStore.getState().toggleMonitor('tab-1')
    expect(tab().monitorOpen).toBe(false)
    expect(calls.map(([c]) => c)).toContain('monitor:stop')
  })

  it('打开监控时不发 stop（采集由 MonitorPanel 自己起）', () => {
    seedTab({ monitorOpen: false, state: 'ready' })
    useSessionStore.getState().toggleMonitor('tab-1')
    expect(tab().monitorOpen).toBe(true)
    expect(calls.map(([c]) => c)).not.toContain('monitor:stop')
  })

  it('还没拿到 sessionId 就关面板：不发 stop，也不报错', () => {
    seedTab({ monitorOpen: true, sessionId: null })
    useSessionStore.getState().toggleMonitor('tab-1')
    expect(calls.map(([c]) => c)).not.toContain('monitor:stop')
  })

  it('关 tab 时释放监控快照与历史（否则关掉的会话永不回收）', async () => {
    seedTab({ state: 'ready' })
    useMonitorStore.setState({ state: { 'sid-1': 'running' } })
    historyOf('sid-1').cpu.push(1, 2, 3)

    await useSessionStore.getState().closeTab('tab-1')

    expect(useMonitorStore.getState().state['sid-1']).toBeUndefined()
    expect(historyOf('sid-1').cpu).toEqual([])
  })
})

describe('端口流量标签', () => {
  it('复用当前 SSH 会话打开标签，不会重复发起 session:open', () => {
    seedTab({ state: 'ready' })
    useSessionStore.getState().openPortTrafficTab('tab-1', 'srv · 端口流量')

    const tabs = useSessionStore.getState().tabs
    expect(tabs).toHaveLength(2)
    expect(tabs[1]).toMatchObject({
      kind: 'portTraffic',
      sourceTabId: 'tab-1',
      sessionId: 'sid-1',
      termId: null,
      sftpOpen: false,
      monitorOpen: false
    })
    expect(useSessionStore.getState().activeTabId).toBe(tabs[1].id)
    expect(calls.map(([channel]) => channel)).not.toContain('session:open')

    // 同一会话重复点击应激活现有标签，而不是多开多个采集器。
    useSessionStore.getState().openPortTrafficTab('tab-1', 'ignored')
    expect(useSessionStore.getState().tabs).toHaveLength(2)
  })

  it('关闭流量标签只停止端口采集，不会关闭源 SSH 会话', async () => {
    seedTab({ state: 'ready' })
    useSessionStore.getState().openPortTrafficTab('tab-1', 'srv · 端口流量')
    const flowTabId = useSessionStore.getState().tabs[1].id

    await useSessionStore.getState().closeTab(flowTabId)

    expect(useSessionStore.getState().tabs.map((t) => t.id)).toEqual(['tab-1'])
    expect(calls.map(([channel]) => channel)).toContain('portTraffic:stop')
    expect(calls.map(([channel]) => channel)).not.toContain('session:close')
  })

  it('关闭源会话时连同流量标签一起移除', async () => {
    seedTab({ state: 'ready' })
    useSessionStore.getState().openPortTrafficTab('tab-1', 'srv · 端口流量')

    await useSessionStore.getState().closeTab('tab-1')

    expect(useSessionStore.getState().tabs).toEqual([])
    expect(calls.map(([channel]) => channel)).toContain('portTraffic:stop')
    expect(calls.map(([channel]) => channel)).toContain('session:close')
  })
})


describe('进程面板标签', () => {
  it('复用当前 SSH 会话打开标签，不会重复发起 session:open', () => {
    seedTab({ state: 'ready' })
    useSessionStore.getState().openProcessPanelTab('tab-1', 'srv · 进程')

    const tabs = useSessionStore.getState().tabs
    expect(tabs).toHaveLength(2)
    expect(tabs[1]).toMatchObject({
      kind: 'processPanel',
      sourceTabId: 'tab-1',
      sessionId: 'sid-1',
      termId: null,
      sftpOpen: false,
      monitorOpen: false
    })
    expect(useSessionStore.getState().activeTabId).toBe(tabs[1].id)
    expect(calls.map(([channel]) => channel)).not.toContain('session:open')

    useSessionStore.getState().openProcessPanelTab('tab-1', 'ignored')
    expect(useSessionStore.getState().tabs).toHaveLength(2)
  })

  it('关闭进程标签只停止进程采集，不会关闭源 SSH 会话', async () => {
    seedTab({ state: 'ready' })
    useSessionStore.getState().openProcessPanelTab('tab-1', 'srv · 进程')
    const panelTabId = useSessionStore.getState().tabs[1].id
    useSessionStore.setState(({ tabs }) => ({ tabs: [...tabs, { ...tabs[0], id: 'tab-2' }] }))

    await useSessionStore.getState().closeTab(panelTabId)

    expect(useSessionStore.getState().tabs.map((t) => t.id)).toEqual(['tab-1', 'tab-2'])
    expect(useSessionStore.getState().activeTabId).toBe('tab-1')
    expect(calls.map(([channel]) => channel)).toContain('processPanel:stop')
    expect(calls.map(([channel]) => channel)).not.toContain('session:close')
  })

  it('关闭源会话时连同进程标签一起移除', async () => {
    seedTab({ state: 'ready' })
    useSessionStore.getState().openProcessPanelTab('tab-1', 'srv · 进程')

    await useSessionStore.getState().closeTab('tab-1')

    expect(useSessionStore.getState().tabs).toEqual([])
    expect(calls.map(([channel]) => channel)).toContain('processPanel:stop')
    expect(calls.map(([channel]) => channel)).toContain('session:close')
  })
})

describe('session:state → tab', () => {
  it('首次连上不算"恢复"：shellEpoch 保持 0（否则每次新建会话都会打恢复分隔线）', () => {
    seedTab()
    fire('session:state', { sessionId: 'sid-1', state: 'ready' })
    expect(tab().state).toBe('ready')
    expect(tab().shellEpoch).toBe(0)
    expect(tab().everReady).toBe(true)
  })

  it('重新建连才递增 shellEpoch，让终端重开并打恢复分隔线', () => {
    seedTab()
    fire('session:state', { sessionId: 'sid-1', state: 'ready' })
    useSessionStore.getState().bindTerm('tab-1', 'term-1')

    // 掉线：main 先发 term:exit(reconnected) 清掉 termId，再发状态
    fire('term:exit', { termId: 'term-1', reason: 'reconnected' })
    expect(tab().termId).toBeNull()
    fire('session:state', { sessionId: 'sid-1', state: 'reconnecting', error: '断开，1 秒后重连' })
    fire('session:state', { sessionId: 'sid-1', state: 'ready' })

    expect(tab().shellEpoch).toBe(1)
    expect(tab().state).toBe('ready')
  })

  it('终端还在（termId 未清）时不重复递增 epoch', () => {
    seedTab()
    fire('session:state', { sessionId: 'sid-1', state: 'ready' })
    useSessionStore.getState().bindTerm('tab-1', 'term-1')
    fire('session:state', { sessionId: 'sid-1', state: 'ready' })
    expect(tab().shellEpoch).toBe(0)
  })

  it('用户敲 exit（reason=closed）时标记会话结束，而不是当成待重连', () => {
    seedTab()
    fire('session:state', { sessionId: 'sid-1', state: 'ready' })
    useSessionStore.getState().bindTerm('tab-1', 'term-1')
    fire('term:exit', { termId: 'term-1', reason: 'closed' })
    expect(tab().state).toBe('closed')
    expect(tab().termId).toBeNull()
  })

  it('事件按 sessionId 定向，不串到别的 tab', () => {
    seedTab()
    useSessionStore.setState((s: { tabs: SessionTab[] }) => ({
      tabs: [...s.tabs, { ...s.tabs[0], id: 'tab-2', sessionId: 'sid-2' }]
    }))
    fire('session:state', { sessionId: 'sid-2', state: 'ready' })
    expect(useSessionStore.getState().tabs[0].state).toBe('connecting')
    expect(useSessionStore.getState().tabs[1].state).toBe('ready')
  })
})

/**
 * Tmux 两种新 kind 的生命周期（方案 §3 连接复用规则 + §5 标签模型）：
 * - tmuxPanel：优先复用就绪终端的连接（面板成为其子面板），未命中才自建连接；
 * - tmuxAttach：自带一条连接，就绪后不开 shell、直调 tmux:attach；
 *   关闭标签 = detach（远端会话存活），只有面板的「关闭会话」才是 kill。
 */
describe('tmux 面板标签（tmuxPanel）', () => {
  it('自有连接认证失败后可以重试', async () => {
    invokeImpl = async () => { throw new Error('authentication failed') }
    useSessionStore.getState().openTmuxPanelTab('p1')
    await vi.waitFor(() => expect(tab().state).toBe('closed'))
    invokeImpl = async () => ({ sessionId: 'retried-panel' })
    calls.length = 0
    await useSessionStore.getState().reconnectTab(tab().id)
    expect(calls).toContainEqual(['session:open', ['p1']])
    expect(tab()).toMatchObject({ sessionId: 'retried-panel', state: 'ready' })
  })

  it.each(['panel', 'attach'] as const)('建连完成前关闭 %s 标签会释放迟到的连接', async (kind) => {
    let resolveOpen!: (value: { sessionId: string }) => void
    const pending = new Promise<{ sessionId: string }>((resolve) => { resolveOpen = resolve })
    invokeImpl = async (channel) => channel === 'session:open' ? pending : undefined
    const opened = kind === 'attach'
      ? useSessionStore.getState().openTmuxAttachTab('p1', '$0')
      : useSessionStore.getState().openTmuxPanelTab('p1')
    await useSessionStore.getState().closeTab(tab().id)
    resolveOpen({ sessionId: 'late-panel-connection' })
    await opened
    await vi.waitFor(() => expect(calls).toContainEqual(['session:close', ['late-panel-connection']]))
    expect(useSessionStore.getState().tabs).toEqual([])
  })
  it('有就绪终端时复用其连接：不发 session:open，面板成为源终端的子面板', () => {
    seedTab({ kind: 'terminal', state: 'ready' })
    useSessionStore.getState().openTmuxPanelTab('p1')

    const tabs = useSessionStore.getState().tabs
    expect(tabs).toHaveLength(2)
    expect(tabs[1]).toMatchObject({
      kind: 'tmuxPanel',
      sourceTabId: 'tab-1',
      profileId: 'p1',
      sessionId: 'sid-1',
      termId: null,
      sftpOpen: false,
      monitorOpen: false
    })
    expect(useSessionStore.getState().activeTabId).toBe(tabs[1].id)
    expect(calls.map(([channel]) => channel)).not.toContain('session:open')

    // 同一 profile 的面板只开一个：再点入口只是回到已开的那个
    useSessionStore.getState().openTmuxPanelTab('p1')
    expect(useSessionStore.getState().tabs).toHaveLength(2)
  })

  it('没有就绪终端时自建连接：session:open，面板自有连接', async () => {
    seedTab({ kind: 'terminal', state: 'connecting' }) // 未 ready，不作复用候选
    useSessionStore.getState().openTmuxPanelTab('p1')

    const panel = useSessionStore.getState().tabs[1]
    expect(panel).toMatchObject({ kind: 'tmuxPanel', sessionId: null, state: 'connecting' })
    expect(calls.map(([channel]) => channel)).toContain('session:open')
    expect(calls.find(([channel]) => channel === 'session:open')?.[1]).toEqual(['p1'])

    // session:open resolve 后认领 sessionId
    await vi.waitFor(() =>
      expect(useSessionStore.getState().tabs[1]).toMatchObject({ sessionId: 'sid-1', state: 'ready' })
    )
  })

  it('关闭借用型面板只停采集，不动源终端的连接', async () => {
    seedTab({ kind: 'terminal', state: 'ready' })
    useSessionStore.getState().openTmuxPanelTab('p1')
    const panelTabId = useSessionStore.getState().tabs[1].id

    await useSessionStore.getState().closeTab(panelTabId)

    expect(useSessionStore.getState().tabs.map((t) => t.id)).toEqual(['tab-1'])
    expect(calls.map(([channel]) => channel)).toContain('tmux:stop')
    expect(calls.map(([channel]) => channel)).not.toContain('session:close')
  })

  it('关闭自有连接的面板：采集与连接一起断开', async () => {
    invokeImpl = async () => ({ sessionId: 'sid-panel' })
    await useSessionStore.getState().openTmuxPanelTab('p1')
    const panelTabId = useSessionStore.getState().tabs[0].id
    await vi.waitFor(() =>
      expect(useSessionStore.getState().tabs[0].sessionId).toBe('sid-panel')
    )

    await useSessionStore.getState().closeTab(panelTabId)

    expect(useSessionStore.getState().tabs).toEqual([])
    expect(calls.map(([channel]) => channel)).toContain('tmux:stop')
    expect(calls.find(([channel]) => channel === 'session:close')?.[1]).toEqual(['sid-panel'])
  })

  it('关闭源终端时连带关闭其 tmux 面板（借用型级联）', async () => {
    seedTab({ kind: 'terminal', state: 'ready' })
    useSessionStore.getState().openTmuxPanelTab('p1')

    await useSessionStore.getState().closeTab('tab-1')

    expect(useSessionStore.getState().tabs).toEqual([])
    expect(calls.map(([channel]) => channel)).toContain('tmux:stop')
    expect(calls.map(([channel]) => channel)).toContain('session:close')
  })
})

describe('tmux 附着标签（tmuxAttach）', () => {
  it('正常附着时重复操作保留现有通道', async () => {
    seedAttachTab({ termId: 'live-term' })
    await useSessionStore.getState().reattachTmuxTab('tab-1')
    expect(tab()).toMatchObject({ termId: 'live-term', state: 'ready', shellEpoch: 0 })
    expect(calls).toEqual([])
  })
  function seedAttachTab(overrides: Record<string, unknown> = {}): string {
    useSessionStore.setState({
      tabs: [
        {
          id: 'tab-1',
          kind: 'tmuxAttach',
          profileId: 'p1',
          targetId: '$3',
          tmuxName: 'b',
          sessionId: 'sid-1',
          termId: null,
          title: 'srv · tmux b',
          state: 'ready',
          sftpOpen: false,
          monitorOpen: false,
          shellEpoch: 0,
          ...overrides
        }
      ],
      activeTabId: 'tab-1'
    })
    return 'tab-1'
  }

  it('openTmuxAttachTab 内部 session:open 建自有连接，携带 targetId 与会话名', async () => {
    invokeImpl = async () => ({ sessionId: 'sid-att' })
    await useSessionStore.getState().openTmuxAttachTab('p1', '$3', 'b')

    const attachTab = useSessionStore.getState().tabs[0]
    expect(attachTab).toMatchObject({
      kind: 'tmuxAttach',
      profileId: 'p1',
      targetId: '$3',
      tmuxName: 'b',
      sessionId: 'sid-att',
      state: 'ready',
      termId: null
    })
    expect(attachTab.title).toContain('tmux b')
    expect(calls.find(([channel]) => channel === 'session:open')?.[1]).toEqual(['p1'])
  })

  it('关闭附着标签只断自有连接（detach 语义）：不发 tmux:stop，远端会话不受影响', async () => {
    seedAttachTab({ termId: 'term-att' })
    await useSessionStore.getState().closeTab('tab-1')

    expect(useSessionStore.getState().tabs).toEqual([])
    expect(calls.find(([channel]) => channel === 'session:close')?.[1]).toEqual(['sid-1'])
    expect(calls.map(([channel]) => channel)).not.toContain('tmux:stop')
    expect(calls.map(([channel]) => channel)).not.toContain('tmux:kill')
  })

  it('kill / detach 之后附着通道退出：标签进入已退出态并带 detach 文案', () => {
    seedAttachTab({ termId: 'term-att' })
    fire('term:exit', { termId: 'term-att', reason: 'closed' })

    expect(tab()).toMatchObject({ state: 'closed', termId: null, error: i18n.t('tmux.detached') })
  })

  it('detach 后「重新附着」不走 session:reconnect：直接重开附着通道（递增 epoch）', async () => {
    seedAttachTab({ termId: 'term-att' })
    fire('term:exit', { termId: 'term-att', reason: 'closed' })

    await useSessionStore.getState().reattachTmuxTab('tab-1')

    expect(tab()).toMatchObject({ state: 'ready', termId: null, shellEpoch: 1 })
    expect(calls.map(([channel]) => channel)).not.toContain('session:reconnect')
    expect(calls.map(([channel]) => channel)).not.toContain('session:open')
  })

  it('附着失败（连接仍在，如 sessionGone）后「重新附着」同样不走 session:reconnect', async () => {
    seedAttachTab({ termId: null, everReady: true })
    // TerminalPane 的附着失败路径：closed 但 SSH 连接仍在（会先调 noteTmuxAttachFailed）
    useSessionStore.getState().noteTmuxAttachFailed('tab-1')
    useSessionStore.getState().updateTab('tab-1', { state: 'closed', error: '会话已不存在' })

    await useSessionStore.getState().reattachTmuxTab('tab-1')

    expect(calls.map(([channel]) => channel)).not.toContain('session:reconnect')
    expect(calls.map(([channel]) => channel)).not.toContain('session:open')
    expect(tab()).toMatchObject({ state: 'ready', termId: null, shellEpoch: 1 })
  })

  it('连接已断（detach 标记作废）时「重新附着」先走正常重连', async () => {
    seedAttachTab({ termId: null, everReady: true })
    // 连接本身挂了（非 detach）：session:state closed 会让 detach 标记作废
    fire('session:state', { sessionId: 'sid-1', state: 'closed', error: '连接已断开' })
    expect(tab().state).toBe('closed')

    await useSessionStore.getState().reattachTmuxTab('tab-1')

    expect(calls.map(([channel]) => channel)).toContain('session:reconnect')
    expect(tab()).toMatchObject({ state: 'ready', termId: null, shellEpoch: 1 })
  })

  it('duplicateTab 对附着标签 = 对同一目标再附着一次（新标签、新连接）', async () => {
    invokeImpl = async () => ({ sessionId: 'sid-att-2' })
    seedAttachTab()
    await useSessionStore.getState().duplicateTab('tab-1', [PROFILE])

    const tabs = useSessionStore.getState().tabs
    expect(tabs).toHaveLength(2)
    expect(tabs[1]).toMatchObject({
      kind: 'tmuxAttach',
      targetId: '$3',
      tmuxName: 'b',
      profileId: 'p1'
    })
    expect(tabs[1].id).not.toBe('tab-1')
    expect(calls.filter(([channel]) => channel === 'session:open')).toHaveLength(1)
  })

  it('session:state ready 重连后附着标签凭 targetId 重附着（epoch 递增驱动 TerminalPane）', () => {
    seedAttachTab({ termId: null, everReady: true })
    fire('session:state', { sessionId: 'sid-1', state: 'ready' })
    expect(tab().shellEpoch).toBe(1)
  })
})

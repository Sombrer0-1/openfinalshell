import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { EventMap } from '@shared/ipc'

/**
 * Tmux 面板 store 的行为（与 sessionStore.test 同一套 ipc 替身手法）：
 *  1. 事件 → store 迁移：tmux:sessions 置 running 并带快照，tmux:state 覆盖状态与错误。
 *  2. 操作后即刷：refresh 是 stop → start 重建采集器（主进程没有独立 refresh 通道）。
 *  3. 清理路径：clear 释放四个键控表；stop 失败不拦 clear；鼠标提示条可单独关掉。
 */

type Handler = (payload: unknown) => void
const handlers = new Map<string, Handler[]>()
const calls: Array<[string, unknown[]]> = []
let invokeImpl: (channel: string, ...args: unknown[]) => Promise<unknown> = async () => undefined

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

const { useTmuxStore, wireTmuxEvents } = await import('@/stores/useTmuxStore')

function fire<K extends keyof EventMap>(channel: K, payload: EventMap[K]): void {
  for (const h of handlers.get(channel) ?? []) h(payload)
}

beforeEach(() => {
  useTmuxStore.setState({ latest: {}, state: {}, error: {}, mouseTipDismissed: {} })
  calls.length = 0
  invokeImpl = async () => undefined
  wireTmuxEvents()
})

describe('事件 → store 迁移', () => {
  it('tmux:sessions 落快照并把状态置 running、清掉旧错误', () => {
    fire('tmux:state', { sessionId: 'sid-1', state: 'collecting' })
    expect(useTmuxStore.getState().state['sid-1']).toBe('collecting')

    fire('tmux:sessions', {
      sessionId: 'sid-1',
      ts: 1234,
      sessions: [{ id: '$0', name: 'main', windows: 2, attached: 1, created: 1748000000 }],
      mouseOn: false
    })

    const s = useTmuxStore.getState()
    expect(s.latest['sid-1']).toEqual({
      ts: 1234,
      sessions: [{ id: '$0', name: 'main', windows: 2, attached: 1, created: 1748000000 }],
      mouseOn: false
    })
    expect(s.state['sid-1']).toBe('running')
  })

  it('mouseOn 缺失（无 server 合法空态）时不写假值', () => {
    fire('tmux:sessions', { sessionId: 'sid-2', ts: 9, sessions: [] })
    const snapshot = useTmuxStore.getState().latest['sid-2']
    expect(snapshot?.sessions).toEqual([])
    expect(snapshot?.mouseOn).toBeUndefined()
  })

  it('tmux:state 覆盖状态并携带错误（SECTION:ERROR → failed 的透出路径）', () => {
    fire('tmux:state', { sessionId: 'sid-3', state: 'failed', error: 'error connecting to socket' })
    const s = useTmuxStore.getState()
    expect(s.state['sid-3']).toBe('failed')
    expect(s.error['sid-3']).toBe('error connecting to socket')

    // 恢复：新快照把状态拉回 running，error 清空
    fire('tmux:sessions', { sessionId: 'sid-3', ts: 10, sessions: [] })
    expect(useTmuxStore.getState().state['sid-3']).toBe('running')
    expect(useTmuxStore.getState().error['sid-3']).toBeUndefined()
  })

  it('事件按 sessionId 键控，不串会话', () => {
    fire('tmux:sessions', { sessionId: 'sid-a', ts: 1, sessions: [], mouseOn: true })
    fire('tmux:state', { sessionId: 'sid-b', state: 'unsupported' })
    const s = useTmuxStore.getState()
    expect(s.latest['sid-b']).toBeUndefined()
    expect(s.state['sid-a']).toBe('running')
    expect(s.state['sid-b']).toBe('unsupported')
  })
})

describe('操作与刷新', () => {
  it('start / stop / new / kill / setMouse / attach 各走各的通道与入参', async () => {
    invokeImpl = async () => ({ id: '$5' })
    const store = useTmuxStore.getState()
    await store.start('sid-1')
    await store.stop('sid-1')
    await store.newSession('sid-1', 'b')
    await store.killSession('sid-1', '$2')
    await store.setMouse('sid-1', true)
    invokeImpl = async () => ({ termId: 'term-9' })
    await store.attach('sid-1', '$2', 120, 30)

    expect(calls).toEqual([
      ['tmux:start', ['sid-1']],
      ['tmux:stop', ['sid-1']],
      ['tmux:new', [{ sessionId: 'sid-1', name: 'b' }]],
      ['tmux:kill', [{ sessionId: 'sid-1', targetId: '$2' }]],
      ['tmux:setMouse', [{ sessionId: 'sid-1', enabled: true }]],
      ['tmux:attach', [{ sessionId: 'sid-1', targetId: '$2', cols: 120, rows: 30 }]]
    ])
  })

  it('refresh = stop 后立刻 start（面板手动刷新的即刷路径，不等 2s 轮询）', async () => {
    await useTmuxStore.getState().refresh('sid-7')
    expect(calls.map(([channel]) => channel)).toEqual(['tmux:stop', 'tmux:start'])
    expect(calls.map(([, args]) => args[0])).toEqual(['sid-7', 'sid-7'])
  })

  it('refresh 里 stop 失败不拦 start（采集器可能已随连接销毁）', async () => {
    invokeImpl = async (channel) => {
      if (channel === 'tmux:stop') throw new Error('already gone')
      return undefined
    }
    await expect(useTmuxStore.getState().refresh('sid-8')).resolves.toBeUndefined()
    expect(calls.map(([channel]) => channel)).toEqual(['tmux:stop', 'tmux:start'])
  })
})

describe('清理路径', () => {
  it('clear 释放该会话的快照 / 状态 / 错误 / 提示条记忆，不影响别的会话', () => {
    fire('tmux:sessions', { sessionId: 'sid-1', ts: 1, sessions: [], mouseOn: true })
    fire('tmux:sessions', { sessionId: 'sid-2', ts: 2, sessions: [] })
    useTmuxStore.getState().dismissMouseTip('sid-1')

    useTmuxStore.getState().clear('sid-1')

    const s = useTmuxStore.getState()
    expect(s.latest['sid-1']).toBeUndefined()
    expect(s.state['sid-1']).toBeUndefined()
    expect(s.error['sid-1']).toBeUndefined()
    expect(s.mouseTipDismissed['sid-1']).toBeUndefined()
    expect(s.latest['sid-2']).toBeDefined()
    expect(s.mouseTipDismissed['sid-2']).toBeUndefined()
  })

  it('dismissMouseTip 只记本会话（重开面板标签后提示条会再出现）', () => {
    useTmuxStore.getState().dismissMouseTip('sid-9')
    expect(useTmuxStore.getState().mouseTipDismissed['sid-9']).toBe(true)
    expect(useTmuxStore.getState().mouseTipDismissed['sid-10']).toBeUndefined()
  })
})

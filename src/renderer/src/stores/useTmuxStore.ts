import { create } from 'zustand'
import type { SessionId, TermId, TmuxPanelState, TmuxSessionInfo } from '@shared/types'
import { ofs } from '@/ipc/api'

/** tmux:sessions 事件负载：列表 + 全局鼠标开关（无 server 的合法空态时缺失）。 */
export interface TmuxSnapshot {
  ts: number
  sessions: TmuxSessionInfo[]
  mouseOn?: boolean
}

interface TmuxStore {
  latest: Record<SessionId, TmuxSnapshot | undefined>
  state: Record<SessionId, TmuxPanelState | undefined>
  error: Record<SessionId, string | undefined>
  /** 鼠标提示条已被用户关闭的会话（面板标签关闭即忘，重开后如仍适用会再次出现） */
  mouseTipDismissed: Record<SessionId, boolean | undefined>
  start: (sessionId: SessionId) => Promise<void>
  stop: (sessionId: SessionId) => Promise<void>
  clear: (sessionId: SessionId) => void
  /** 手动刷新 = 重建采集器（主进程没有独立的 refresh 通道，操作后的即时帧由 manager 自己 poke） */
  refresh: (sessionId: SessionId) => Promise<void>
  newSession: (sessionId: SessionId, name: string) => Promise<{ id: string }>
  killSession: (sessionId: SessionId, targetId: string) => Promise<void>
  setMouse: (sessionId: SessionId, enabled: boolean) => Promise<void>
  attach: (sessionId: SessionId, targetId: string, cols: number, rows: number) => Promise<{ termId: TermId }>
  dismissMouseTip: (sessionId: SessionId) => void
}

/** tmux 面板数据只在对应标签打开期间存在，与 useProcessPanelStore 同一套生命周期。 */
export const useTmuxStore = create<TmuxStore>((set, get) => ({
  latest: {},
  state: {},
  error: {},
  mouseTipDismissed: {},

  start: async (sessionId) => {
    await ofs.invoke('tmux:start', sessionId)
  },

  stop: async (sessionId) => {
    await ofs.invoke('tmux:stop', sessionId)
  },

  clear: (sessionId) =>
    set((s) => {
      const latest = { ...s.latest }
      const state = { ...s.state }
      const error = { ...s.error }
      const mouseTipDismissed = { ...s.mouseTipDismissed }
      delete latest[sessionId]
      delete state[sessionId]
      delete error[sessionId]
      delete mouseTipDismissed[sessionId]
      return { latest, state, error, mouseTipDismissed }
    }),

  refresh: async (sessionId) => {
    // stop 会先推一帧 stopped；面板用本地 refreshing 状态把这一闪压住，不显示"已停止"。
    await get().stop(sessionId).catch(() => {})
    await get().start(sessionId)
  },

  newSession: (sessionId, name) => ofs.invoke('tmux:new', { sessionId, name }),

  killSession: (sessionId, targetId) => ofs.invoke('tmux:kill', { sessionId, targetId }),

  setMouse: (sessionId, enabled) => ofs.invoke('tmux:setMouse', { sessionId, enabled }),

  attach: (sessionId, targetId, cols, rows) =>
    ofs.invoke('tmux:attach', { sessionId, targetId, cols, rows }),

  dismissMouseTip: (sessionId) =>
    set((s) => ({ mouseTipDismissed: { ...s.mouseTipDismissed, [sessionId]: true } }))
}))

let wired = false

export function wireTmuxEvents(): void {
  if (wired) return
  wired = true

  ofs.on('tmux:sessions', ({ sessionId, ts, sessions, mouseOn }) => {
    useTmuxStore.setState((s) => ({
      latest: { ...s.latest, [sessionId]: { ts, sessions, ...(mouseOn === undefined ? {} : { mouseOn }) } },
      state: { ...s.state, [sessionId]: 'running' },
      error: { ...s.error, [sessionId]: undefined }
    }))
  })

  ofs.on('tmux:state', ({ sessionId, state, error }) => {
    useTmuxStore.setState((s) => ({
      state: { ...s.state, [sessionId]: state },
      error: { ...s.error, [sessionId]: error }
    }))
  })
}

import { create } from 'zustand'
import type { ProcessDetail, ProcessPanelState, ProcessSnapshot, SessionId } from '@shared/types'
import { ofs } from '@/ipc/api'

interface ProcessPanelStore {
  latest: Record<SessionId, ProcessSnapshot | undefined>
  state: Record<SessionId, ProcessPanelState | undefined>
  error: Record<SessionId, string | undefined>
  details: Record<string, ProcessDetail | undefined>
  start: (sessionId: SessionId) => Promise<void>
  stop: (sessionId: SessionId) => Promise<void>
  clear: (sessionId: SessionId) => void
  fetchDetail: (sessionId: SessionId, pid: number) => Promise<ProcessDetail | null>
  kill: (sessionId: SessionId, pid: number, force?: boolean) => Promise<void>
}

function detailKey(sessionId: SessionId, pid: number): string {
  return `${sessionId}:${pid}`
}

/** 进程快照只在对应工具标签打开期间存在，不与右侧监控的 topProcs 混用。 */
export const useProcessPanelStore = create<ProcessPanelStore>((set, get) => ({
  latest: {},
  state: {},
  error: {},
  details: {},

  start: async (sessionId) => {
    await ofs.invoke('processPanel:start', sessionId)
  },

  stop: async (sessionId) => {
    await ofs.invoke('processPanel:stop', sessionId)
  },

  clear: (sessionId) =>
    set((s) => {
      const latest = { ...s.latest }
      const state = { ...s.state }
      const error = { ...s.error }
      const details = { ...s.details }
      delete latest[sessionId]
      delete state[sessionId]
      delete error[sessionId]
      for (const key of Object.keys(details)) {
        if (key.startsWith(`${sessionId}:`)) delete details[key]
      }
      return { latest, state, error, details }
    }),

  fetchDetail: async (sessionId, pid) => {
    const key = detailKey(sessionId, pid)
    const detail = await ofs.invoke('processPanel:detail', { sessionId, pid })
    if (detail) {
      set((s) => {
        const details = { ...s.details, [key]: detail }
        // 有界缓存：按插入序丢掉最旧条目，避免只写不读时无限增长。
        const keys = Object.keys(details)
        if (keys.length > 32) {
          for (const k of keys) {
            if (k === key) continue
            delete details[k]
            if (Object.keys(details).length <= 32) break
          }
        }
        return { details }
      })
    }
    return detail
  },

  kill: async (sessionId, pid, force) => {
    await ofs.invoke('processPanel:kill', { sessionId, pid, force })
    // 杀掉后清掉该 pid 的详情缓存；列表下一帧会自然刷新。
    set((s) => {
      const details = { ...s.details }
      delete details[detailKey(sessionId, pid)]
      return { details }
    })
    void get()
  }
}))

let wired = false

export function wireProcessPanelEvents(): void {
  if (wired) return
  wired = true
  ofs.on('processPanel:data', ({ sessionId, snapshot }) => {
    useProcessPanelStore.setState((s) => ({
      latest: { ...s.latest, [sessionId]: snapshot },
      state: { ...s.state, [sessionId]: 'running' },
      error: { ...s.error, [sessionId]: undefined }
    }))
  })
  ofs.on('processPanel:state', ({ sessionId, state, error }) => {
    useProcessPanelStore.setState((s) => ({
      state: { ...s.state, [sessionId]: state },
      error: { ...s.error, [sessionId]: error }
    }))
  })
}

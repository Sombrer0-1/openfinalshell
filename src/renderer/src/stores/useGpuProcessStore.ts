import { create } from 'zustand'
import type { GpuProcessSnapshot, GpuProcessState, SessionId } from '@shared/types'
import { ofs } from '@/ipc/api'

interface GpuProcessStore {
  latest: Record<SessionId, GpuProcessSnapshot | undefined>
  state: Record<SessionId, GpuProcessState | undefined>
  error: Record<SessionId, string | undefined>
  start: (sessionId: SessionId) => Promise<void>
  stop: (sessionId: SessionId) => Promise<void>
  clear: (sessionId: SessionId) => void
}

/** GPU 进程快照只在对应工具标签打开期间存在。 */
export const useGpuProcessStore = create<GpuProcessStore>((set) => ({
  latest: {},
  state: {},
  error: {},

  start: async (sessionId) => {
    await ofs.invoke('gpuProcess:start', sessionId)
  },

  stop: async (sessionId) => {
    await ofs.invoke('gpuProcess:stop', sessionId)
  },

  clear: (sessionId) =>
    set((s) => {
      const latest = { ...s.latest }
      const state = { ...s.state }
      const error = { ...s.error }
      delete latest[sessionId]
      delete state[sessionId]
      delete error[sessionId]
      return { latest, state, error }
    })
}))

let wired = false

export function wireGpuProcessEvents(): void {
  if (wired) return
  wired = true
  ofs.on('gpuProcess:data', ({ sessionId, snapshot }) => {
    useGpuProcessStore.setState((s) => ({
      latest: { ...s.latest, [sessionId]: snapshot },
      state: { ...s.state, [sessionId]: 'running' },
      error: { ...s.error, [sessionId]: undefined }
    }))
  })
  ofs.on('gpuProcess:state', ({ sessionId, state, error }) => {
    useGpuProcessStore.setState((s) => ({
      state: { ...s.state, [sessionId]: state },
      error: { ...s.error, [sessionId]: error }
    }))
  })
}

import { create } from 'zustand'
import type { AppSettings } from '@shared/types'
import { ofs } from '@/ipc/api'
import i18n from '@/i18n'

interface SettingsStore {
  settings: AppSettings | null
  /** 启动时加载一次并订阅 main 侧变更 */
  init: () => Promise<void>
  /** 局部更新：乐观合并本地 + 持久化到 main */
  patch: (patch: Partial<AppSettings>) => void
  patchLayout: (patch: Partial<AppSettings['layout']>) => void
  /** 接收持久化快照，同时保留尚未保存的本地改动。 */
  receive: (settings: AppSettings) => void
}

function deepMergeLocal<T>(base: T, patch: unknown): T {
  if (patch === undefined) return base
  if (
    base === null ||
    patch === null ||
    typeof base !== 'object' ||
    typeof patch !== 'object' ||
    Array.isArray(base) ||
    Array.isArray(patch)
  ) {
    return patch as T
  }
  const out: Record<string, unknown> = { ...(base as Record<string, unknown>) }
  for (const [k, v] of Object.entries(patch as Record<string, unknown>)) {
    out[k] = deepMergeLocal((base as Record<string, unknown>)[k], v)
  }
  return out as T
}

let initialized = false

export const useSettingsStore = create<SettingsStore>((set, get) => {
  let saved: AppSettings | null = null
  let pending: Partial<AppSettings> = {}
  let inFlight: Partial<AppSettings> | null = null
  let timer: ReturnType<typeof setTimeout> | undefined

  const receive = (settings: AppSettings): void => {
    // Keep the expected snapshot of queued edits so peer changes still conflict.
    if (!inFlight && Object.keys(pending).length === 0) saved = settings
    set({ settings: deepMergeLocal(deepMergeLocal(settings, inFlight ?? {}), pending) })
  }

  // Only confirmed snapshots are used for optimistic concurrency checks. The
  // next write waits for the previous reply; drag frames coalesce in pending.
  const flush = async (): Promise<void> => {
    if (inFlight || !saved || Object.keys(pending).length === 0) return
    const writing = pending
    pending = {}
    inFlight = writing
    try {
      const settings = await ofs.invoke('settings:set', writing, saved)
      saved = settings
      inFlight = null
      receive(settings)
    } catch {
      // A real conflict still reloads rather than overwriting another window.
      pending = {}
      try {
        const settings = await ofs.invoke('settings:get')
        pending = {}
        inFlight = null
        receive(settings)
      } catch {
        inFlight = null
        set({ settings: saved })
      }
      window.dispatchEvent(new CustomEvent('ofs:configConflict', { detail: i18n.t('settings.saveFailed') }))
    }
    if (!timer) void flush()
  }

  return {
    settings: null,

    init: async () => {
      if (initialized) return
      initialized = true
      const settings = await ofs.invoke('settings:get')
      receive(settings)
      ofs.on('settings:changed', receive)
    },

    receive,

    patchLayout: (patch) => {
      const layout = get().settings?.layout
      if (layout) get().patch({ layout: { ...layout, ...patch } })
    },

    patch: (patch) => {
      const current = get().settings
      if (!current) return
      const next = deepMergeLocal(current, patch)
      if (JSON.stringify(next) === JSON.stringify(current)) return
      if (!inFlight && Object.keys(pending).length === 0) saved = current
      pending = deepMergeLocal(pending, patch)
      set({ settings: next })
      if (timer) clearTimeout(timer)
      timer = undefined
      if (Object.keys(patch).every((key) => key === 'layout')) {
        timer = setTimeout(() => { timer = undefined; void flush() }, 120)
      } else {
        void flush()
      }
    }
  }
})

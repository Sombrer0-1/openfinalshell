// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_SETTINGS } from '@shared/constants'
import type { AppSettings } from '@shared/types'
import { useSettingsStore } from '@/stores/useSettingsStore'
import { deferred, fakeOfs } from './fakeOfs'

let stored: AppSettings
let conflicts: string[]
const conflict = (event: Event): void => { conflicts.push((event as CustomEvent<string>).detail) }
const drain = async (): Promise<void> => { for (let i = 0; i < 20; i++) await Promise.resolve() }

beforeEach(() => {
  vi.useFakeTimers()
  fakeOfs.reset()
  stored = structuredClone(DEFAULT_SETTINGS)
  conflicts = []
  useSettingsStore.setState({ settings: stored })
  window.addEventListener('ofs:configConflict', conflict)
  vi.spyOn(fakeOfs, 'invoke').mockImplementation(async (channel, patch, ...args: unknown[]) => {
    if (channel === 'settings:get') return structuredClone(stored)
    if (channel !== 'settings:set') throw new Error(channel)
    await Promise.resolve() // IPC reaches main after the current drag frame.
    const expected = args[0] as AppSettings
    const change = patch as Partial<AppSettings>
    for (const key of Object.keys(change) as Array<keyof AppSettings>) {
      if (JSON.stringify(expected[key]) !== JSON.stringify(stored[key])) throw new Error('CONFIG_CONFLICT')
    }
    stored = { ...stored, ...change }
    return structuredClone(stored)
  })
})

afterEach(async () => {
  await vi.runAllTimersAsync()
  await drain()
  window.removeEventListener('ofs:configConflict', conflict)
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('panel resize settings persistence', () => {
  it('coalesces drag frames and merges simultaneous horizontal and vertical changes', async () => {
    const { patch } = useSettingsStore.getState()
    for (let i = 1; i <= 30; i++) {
      patch({ layout: { ...useSettingsStore.getState().settings!.layout, sidePanelSizePct: 22 + i / 10 } })
      patch({ layout: { ...useSettingsStore.getState().settings!.layout, sftpPaneHeightPct: 30 + i } })
    }
    await vi.advanceTimersByTimeAsync(120)
    expect(stored.layout.sidePanelSizePct).toBe(25)
    expect(stored.layout.sftpPaneHeightPct).toBe(60)
    expect(conflicts).toEqual([])
    expect(vi.mocked(fakeOfs.invoke).mock.calls.filter(([channel]) => channel === 'settings:set')).toHaveLength(1)
  })

  it('keeps newer drag values visible while an earlier reply and broadcast arrive', async () => {
    const first = deferred<AppSettings>()
    const save = vi.mocked(fakeOfs.invoke).getMockImplementation()!
    let writes = 0
    vi.mocked(fakeOfs.invoke).mockImplementation((channel, ...args) => {
      if (channel === 'settings:set' && ++writes === 1) return first.promise
      return save(channel, ...args)
    })
    const { patch } = useSettingsStore.getState()
    patch({ themeMode: 'light' })
    patch({ layout: { ...stored.layout, sidePanelSizePct: 28 } })
    await vi.advanceTimersByTimeAsync(120)
    expect(writes).toBe(1)
    stored = { ...stored, themeMode: 'light' }
    useSettingsStore.getState().receive(stored)
    expect(useSettingsStore.getState().settings!.layout.sidePanelSizePct).toBe(28)
    first.resolve(structuredClone(stored))
    await drain()
    expect(stored.layout.sidePanelSizePct).toBe(28)
    expect(useSettingsStore.getState().settings!.layout.sidePanelSizePct).toBe(28)
    expect(writes).toBe(2)
    expect(conflicts).toEqual([])
  })

  it('does not save unchanged panel measurements', async () => {
    useSettingsStore.getState().patch({ layout: { ...stored.layout } })
    await vi.advanceTimersByTimeAsync(120)
    expect(fakeOfs.invoke).not.toHaveBeenCalled()
  })

  it('still rejects and reloads a real competing window edit', async () => {
    useSettingsStore.getState().patch({ layout: { ...stored.layout, sidePanelSizePct: 28 } })
    stored = { ...stored, layout: { ...stored.layout, sidePanelSizePct: 30 } }
    useSettingsStore.getState().receive(stored)
    await vi.advanceTimersByTimeAsync(120)
    expect(stored.layout.sidePanelSizePct).toBe(30)
    expect(useSettingsStore.getState().settings!.layout.sidePanelSizePct).toBe(30)
    expect(conflicts).toHaveLength(1)
  })
})

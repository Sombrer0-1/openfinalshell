// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EventMap, OfsApi } from '@shared/ipc'
import { createMockOfs } from '@/ipc/mock'

let api: OfsApi
beforeEach(() => {
  vi.useFakeTimers()
  api = createMockOfs()
})
afterEach(() => {
  vi.clearAllTimers()
  vi.useRealTimers()
})

describe('浏览器 tmux 模拟', () => {
  it('同一服务器的新连接可以附着，其他服务器的会话互相隔离', async () => {
    const { profiles: [profile, otherProfile] } = await api.invoke('conn:list')
    const panel = await api.invoke('session:open', profile.id)
    await api.invoke('tmux:start', panel.sessionId)
    const { id } = await api.invoke('tmux:new', { sessionId: panel.sessionId, name: 'shared' })
    const attach = await api.invoke('session:open', profile.id)
    await expect(api.invoke('tmux:attach', {
      sessionId: attach.sessionId, targetId: id, cols: 80, rows: 24
    })).resolves.toEqual({ termId: expect.any(String) })
    const other = await api.invoke('session:open', otherProfile.id)
    await expect(api.invoke('tmux:attach', {
      sessionId: other.sessionId, targetId: id, cols: 80, rows: 24
    })).rejects.toThrow()
  })

  it('刷新和关闭管理连接保留远端会话及鼠标设置', async () => {
    const { profiles: [profile] } = await api.invoke('conn:list')
    const panel = await api.invoke('session:open', profile.id)
    const snapshots: Array<EventMap['tmux:sessions']> = []
    api.on('tmux:sessions', (snapshot) => snapshots.push(snapshot))
    await api.invoke('tmux:start', panel.sessionId)
    const { id } = await api.invoke('tmux:new', { sessionId: panel.sessionId, name: 'persisted' })
    await api.invoke('tmux:setMouse', { sessionId: panel.sessionId, enabled: true })
    await api.invoke('tmux:stop', panel.sessionId)
    await api.invoke('tmux:start', panel.sessionId)
    expect(snapshots.at(-1)).toMatchObject({ mouseOn: true, sessions: expect.arrayContaining([
      expect.objectContaining({ id, name: 'persisted' })
    ]) })
    await api.invoke('tmux:stop', panel.sessionId)
    await api.invoke('session:close', panel.sessionId)
    const reopened = await api.invoke('session:open', profile.id)
    await api.invoke('tmux:start', reopened.sessionId)
    expect(snapshots.at(-1)).toMatchObject({ mouseOn: true, sessions: expect.arrayContaining([
      expect.objectContaining({ id, name: 'persisted' })
    ]) })
  })
})

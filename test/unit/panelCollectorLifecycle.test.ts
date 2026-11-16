import { EventEmitter } from 'node:events'
import type { ClientChannel } from 'ssh2'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProcessPanelCollector } from '../../src/main/monitor/ProcessPanelCollector'
import { TmuxCollector } from '../../src/main/monitor/TmuxCollector'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe.each([
  ['ProcessPanel', ProcessPanelCollector], ['Tmux', TmuxCollector]
] as const)('%s 开通道时序', (_name, Collector) => {
  it('旧请求失败不会中止已经恢复的采集通道', async () => {
    let rejectFirst!: (error: Error) => void
    const first = new Promise<ClientChannel>((_resolve, reject) => { rejectFirst = reject })
    const channel = new EventEmitter() as EventEmitter & {
      stderr: EventEmitter
      write: ReturnType<typeof vi.fn>
      close: ReturnType<typeof vi.fn>
    }
    channel.stderr = new EventEmitter()
    channel.write = vi.fn()
    channel.close = vi.fn(() => channel.emit('close'))
    const open = vi.fn<() => Promise<ClientChannel>>()
      .mockReturnValueOnce(first)
      .mockResolvedValue(channel as unknown as ClientChannel)
    const collector = new Collector('panel-race', open, { onSnapshot: vi.fn(), onState: vi.fn() })
    const started = collector.start()
    try {
      await collector.reattach()
      const recoveredState = collector.state
      rejectFirst(new Error('obsolete channel failed'))
      await expect(started).resolves.toBeUndefined()
      expect(collector.state).toBe(recoveredState)
      expect(channel.close).not.toHaveBeenCalled()
      expect(channel.write).toHaveBeenCalledOnce()
    } finally {
      collector.stop()
    }
  })

  it('停止期间旧请求失败不会再发布失败状态', async () => {
    let rejectOpen!: (error: Error) => void
    const pending = new Promise<ClientChannel>((_resolve, reject) => { rejectOpen = reject })
    const onState = vi.fn()
    const collector = new Collector('panel-stopped', () => pending, { onSnapshot: vi.fn(), onState })
    const started = collector.start()
    collector.stop()
    rejectOpen(new Error('connection closed'))
    await expect(started).resolves.toBeUndefined()
    expect(collector.state).toBe('stopped')
    expect(onState).toHaveBeenLastCalledWith('stopped', undefined)
  })
})

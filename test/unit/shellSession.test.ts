import { EventEmitter } from 'node:events'
import type { ClientChannel } from 'ssh2'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TERM_FLOW_PAUSE_BYTES, TERM_FLUSH_INTERVAL_MS } from '@shared/constants'
import { ShellSession } from '../../src/main/ssh/ShellSession'

const { emit } = vi.hoisted(() => ({ emit: vi.fn() }))
vi.mock('../../src/main/ipc/registry', () => ({ emit }))

function channel() {
  return Object.assign(new EventEmitter(), {
    stderr: new EventEmitter(), write: vi.fn(), pause: vi.fn(), resume: vi.fn(),
    close: vi.fn(), setWindow: vi.fn()
  })
}
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks() })

describe('SSH terminal stream', () => {
  it('holds startup protocol bytes until the renderer is registered and ready', () => {
    vi.useFakeTimers()
    const ch = channel()
    const shell = new ShellSession('ready-term', ch as unknown as ClientChannel, 'utf-8', () => {})
    const first = Buffer.from('\x1b[?u\x1b]52;c;', 'utf8')
    const second = Buffer.from('aGVsbG8=\x07', 'utf8')
    ch.emit('data', first)
    ch.emit('data', second)
    vi.advanceTimersByTime(TERM_FLUSH_INTERVAL_MS * 2)
    expect(emit).not.toHaveBeenCalled()
    shell.ready()
    expect(emit).toHaveBeenCalledTimes(1)
    expect(emit.mock.calls[0][0]).toBe('term:data')
    expect(Buffer.from(emit.mock.calls[0][1].data)).toEqual(Buffer.concat([first, second]))
    shell.ready()
    expect(emit).toHaveBeenCalledTimes(1)
    shell.close()
  })

  it('applies backpressure while waiting for readiness and resumes after consumption', () => {
    const ch = channel()
    const shell = new ShellSession('flow-term', ch as unknown as ClientChannel, 'utf-8', () => {})
    const bytes = Buffer.alloc(TERM_FLOW_PAUSE_BYTES + 1, 65)
    ch.emit('data', bytes)
    expect(ch.pause).toHaveBeenCalledTimes(1)
    expect(emit).not.toHaveBeenCalled()
    shell.ready()
    shell.ack(bytes.length)
    expect(ch.resume).toHaveBeenCalledTimes(1)
    shell.close()
  })

  it.each(['utf-8', 'gbk'])('preserves binary mouse bytes with %s text encoding', charset => {
    const ch = channel()
    const shell = new ShellSession('binary-term', ch as unknown as ClientChannel, charset, () => {})
    const bytes = new Uint8Array([27, 91, 77, 32, 180, 230])
    shell.writeBinary(bytes)
    expect(ch.write).toHaveBeenCalledWith(Buffer.from(bytes))
    shell.close()
    shell.writeBinary(bytes)
    expect(ch.write).toHaveBeenCalledTimes(1)
  })
})

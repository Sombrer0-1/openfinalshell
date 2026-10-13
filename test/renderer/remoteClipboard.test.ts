import { afterEach, describe, expect, it, vi } from 'vitest'
import { Terminal } from '@xterm/xterm'
import { attachRemoteClipboard } from '@/features/terminal/remoteClipboard'

const write = (term: Terminal, data: string): Promise<void> => new Promise(resolve => term.write(data, resolve))
let term: Terminal
afterEach(() => term?.dispose())

describe('remote OSC 52 clipboard', () => {
  it.each(['\x07', '\x1b\\'])('copies UTF-8 across every byte boundary with %s terminator', async end => {
    term = new Terminal({ allowProposedApi: true })
    const copy = vi.fn(async (_text: string) => {})
    attachRemoteClipboard(term, copy)
    const text = '远端复制🙂\n第二行'
    const sequence = `\x1b]52;c;${Buffer.from(text).toString('base64')}${end}`
    for (const char of sequence) await write(term, char)
    expect(copy).toHaveBeenCalledExactlyOnceWith(text)
  })

  it('accepts the default target and does not send clipboard contents in response to queries', async () => {
    term = new Terminal({ allowProposedApi: true })
    const copy = vi.fn(async () => {})
    const send = vi.fn()
    term.onData(send)
    attachRemoteClipboard(term, copy)
    await write(term, '\x1b]52;c;?\x07\x1b]52;;aGVsbG8=\x07')
    expect(send).not.toHaveBeenCalled()
    expect(copy).toHaveBeenCalledExactlyOnceWith('hello')
  })

  it('does not erase the clipboard on invalid base64 or UTF-8', async () => {
    term = new Terminal({ allowProposedApi: true })
    const copy = vi.fn(async () => {})
    attachRemoteClipboard(term, copy)
    await write(term, '\x1b]52;c;%%%%\x07\x1b]52;c;/w==\x07\x1b]52;p;aGVsbG8=\x07')
    expect(copy).not.toHaveBeenCalled()
  })

  it('continues terminal parsing without waiting for an OS clipboard operation', async () => {
    term = new Terminal({ allowProposedApi: true })
    attachRemoteClipboard(term, () => new Promise(() => {}))
    await write(term, '\x1b]52;c;aGVsbG8=\x07after-copy')
    expect(term.buffer.active.getLine(0)?.translateToString(true)).toBe('after-copy')
  })

  it('removes the handler when disposed', async () => {
    term = new Terminal({ allowProposedApi: true })
    const copy = vi.fn(async () => {})
    attachRemoteClipboard(term, copy).dispose()
    await write(term, '\x1b]52;c;aGVsbG8=\x07')
    expect(copy).not.toHaveBeenCalled()
  })
})

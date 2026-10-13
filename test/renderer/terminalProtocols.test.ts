import { describe, expect, it } from 'vitest'
import { Terminal } from '@xterm/xterm'

const write = (term: Terminal, data: string): Promise<void> => new Promise(resolve => term.write(data, resolve))
const lines = (prefix: string): string => Array.from({ length: 40 }, (_, i) => `${prefix}-${i}\r\n`).join('')
function scrollBack(term: Terminal): void {
  // No DOM viewport in node; use the real buffer scrolling implementation.
  const core = (term as unknown as { _core: { _bufferService: { scrollLines(n: number): void } } })._core
  core._bufferService.scrollLines(-8)
}

describe('terminal TUI protocols', () => {
  it('follows new output after the scrolled-back history is erased with ED3', async () => {
    const term = new Terminal({ cols: 30, rows: 6, scrollback: 1000 })
    try {
      await write(term, lines('old'))
      scrollBack(term)
      expect(term.buffer.active.viewportY).toBeLessThan(term.buffer.active.baseY)
      await write(term, '\x1b[3J' + lines('new'))
      expect(term.buffer.active.baseY).toBeGreaterThan(0)
      expect(term.buffer.active.viewportY).toBe(term.buffer.active.baseY)
    } finally { term.dispose() }
  })

  it('keeps the user viewport when ordinary output arrives without clearing history', async () => {
    const term = new Terminal({ cols: 30, rows: 6, scrollback: 1000 })
    try {
      await write(term, lines('old'))
      scrollBack(term)
      const viewport = term.buffer.active.viewportY
      await write(term, lines('new'))
      expect(term.buffer.active.viewportY).toBe(viewport)
    } finally { term.dispose() }
  })

  it('recognizes synchronized output and its mode query across byte chunks', async () => {
    const term = new Terminal({ allowProposedApi: true })
    try {
      const replies: string[] = []
      term.onData(data => replies.push(data))
      for (const char of '\x1b[?2026h\x1b[?2026$p') await write(term, char)
      expect(replies).toEqual(['\x1b[?2026;1$y'])
      await write(term, '\x1b[?2026l\x1b[?2026$p')
      expect(replies.at(-1)).toBe('\x1b[?2026;2$y')
    } finally { term.dispose() }
  })

  it('supports keyboard enhancement push/query/pop for modified Enter', async () => {
    const term = new Terminal({ allowProposedApi: true, vtExtensions: { kittyKeyboard: true } })
    try {
      const replies: string[] = []
      term.onData(data => replies.push(data))
      await write(term, '\x1b[?u\x1b[>1u\x1b[?u\x1b[<u\x1b[?u')
      expect(replies).toEqual(['\x1b[?0u', '\x1b[?1u', '\x1b[?0u'])
    } finally { term.dispose() }
  })
})

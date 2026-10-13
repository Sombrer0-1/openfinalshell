import { afterEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_SETTINGS } from '@shared/constants'
import { createTerminal, type TerminalBundle } from '@/features/terminal/createTerminal'

vi.mock('@/ipc/api', () => ({ ofs: { invoke: vi.fn() } }))

let bundle: TerminalBundle
afterEach(() => { bundle?.dispose(); vi.unstubAllGlobals() })
describe('application terminal capabilities', () => {
  it('negotiates enhanced keyboard input and measures joined emoji as a single glyph', async () => {
    bundle = createTerminal(structuredClone(DEFAULT_SETTINGS), 'dark')
    const replies: string[] = []
    bundle.term.onData(data => replies.push(data))
    await new Promise<void>(resolve => bundle.term.write('\x1b[?uA👨‍👩‍👧‍👦B', resolve))
    expect(replies).toEqual(['\x1b[?0u'])
    expect(bundle.term.buffer.active.cursorX).toBe(4)
  })

  it('installs remote clipboard writes without allowing remote clipboard reads', async () => {
    const writeText = vi.fn(async () => {}), readText = vi.fn(async () => 'private local clipboard')
    vi.stubGlobal('navigator', { clipboard: { writeText, readText } })
    bundle = createTerminal(structuredClone(DEFAULT_SETTINGS), 'dark')
    await new Promise<void>(resolve => bundle.term.write('\x1b]52;c;?\x07\x1b]52;c;aGVsbG8=\x07', resolve))
    expect(readText).not.toHaveBeenCalled()
    expect(writeText).toHaveBeenCalledExactlyOnceWith('hello')
  })
})

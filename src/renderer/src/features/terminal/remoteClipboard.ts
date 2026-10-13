import type { IDisposable, Terminal } from '@xterm/xterm'

const MAX_BASE64_LENGTH = 1_400_000

/** OSC 52 writes remote selections to the local clipboard; queries never read it. */
export function attachRemoteClipboard(
  term: Terminal,
  writeText: (text: string) => Promise<void> = text => navigator.clipboard.writeText(text)
): IDisposable {
  return term.parser.registerOscHandler(52, data => {
    const separator = data.indexOf(';')
    if (separator < 0) return true
    const selection = data.slice(0, separator)
    const base64 = data.slice(separator + 1)
    if ((selection !== '' && !selection.includes('c')) || base64 === '?' || base64.length > MAX_BASE64_LENGTH) return true
    // Invalid sequences must not erase the existing clipboard.
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(base64)) return true
    let text: string
    try {
      const bytes = Uint8Array.from(atob(base64), char => char.charCodeAt(0))
      text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    } catch { return true }
    // Clipboard permission/focus failure must not block parsing and flow ACKs.
    void writeText(text).catch(() => { console.warn('Terminal clipboard write failed') })
    return true
  })
}

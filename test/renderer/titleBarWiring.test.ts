import { describe, expect, it } from 'vitest'
import { flat, read, stripComments } from '../sourceGuard'

const CSS = 'src/renderer/src/features/layout/TitleBar.module.css'

function rule(selector: string): string {
  const css = stripComments(read(CSS))
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(css)
  if (!match) throw new Error(`Missing CSS rule: ${selector}`)
  return flat(match[1])
}

// jsdom cannot measure flex layout or Electron's native draggable regions.
// Keep the scrolling interaction region separate from the window drag space.
describe('main window title bar drag space', () => {
  it('does not expand the no-drag tab strip across the empty title bar', () => {
    expect(rule('.tabStrip')).toContain('-webkit-app-region: no-drag')
    expect(rule('.tabStrip')).toContain('flex: 0 1 auto')
    expect(rule('.tabStrip')).toContain('min-width: 0')
    expect(rule('.tabStrip')).toContain('overflow-x: auto')
  })

  it('reserves a full-height drag space even when the tabs overflow', () => {
    const gap = rule('.dragSpace')
    expect(gap).toContain('-webkit-app-region: drag')
    expect(gap).toContain('flex: 1 0 40px')
    expect(gap).toContain('align-self: stretch')
    const source = read('src/renderer/src/features/layout/TitleBar.tsx')
    expect(source).toMatch(/<\/div>\s*<div className=\{styles\.dragSpace\} aria-hidden="true"\s*\/>\s*<\/header>/)
  })
})

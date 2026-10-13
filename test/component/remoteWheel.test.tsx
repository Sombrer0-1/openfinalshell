// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { Terminal } from '@xterm/xterm'
import { attachRemoteWheel } from '@/features/terminal/remoteWheel'

const write = (term: Terminal, data: string): Promise<void> => new Promise(resolve => term.write(data, resolve))
let term: Terminal
afterEach(() => term?.dispose())

describe('remote wheel movement', () => {
  it('sends three direction reports for a mouse notch and accumulates small trackpad movements', async () => {
    term = new Terminal({ allowProposedApi: true })
    await write(term, '\x1b[?1002h')
    const mount = document.createElement('div'), screen = document.createElement('div')
    mount.append(screen)
    const wheel = attachRemoteWheel(mount, term)
    const reports: number[] = []
    screen.addEventListener('wheel', e => reports.push((e as WheelEvent).deltaY))
    screen.dispatchEvent(new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 100 }))
    expect(reports).toEqual([1, 1, 1])
    reports.length = 0
    for (let i = 0; i < 10; i++) screen.dispatchEvent(new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: -10 }))
    expect(reports).toEqual([-1, -1, -1])
    wheel.dispose()
  })

  it('leaves normal scrollback and Ctrl-wheel font adjustment to their existing handlers', async () => {
    term = new Terminal({ allowProposedApi: true })
    const mount = document.createElement('div'), screen = document.createElement('div')
    mount.append(screen)
    const wheel = attachRemoteWheel(mount, term)
    const normal = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 100 })
    screen.dispatchEvent(normal)
    expect(normal.defaultPrevented).toBe(false)
    await write(term, '\x1b[?1002h')
    const zoom = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 100, ctrlKey: true })
    screen.dispatchEvent(zoom)
    expect(zoom.defaultPrevented).toBe(false)
    wheel.dispose()
  })
})

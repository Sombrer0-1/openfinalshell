import type { IDisposable, Terminal } from '@xterm/xterm'

/** Expand wheel movement into remote mouse reports using xterm's own encoder. */
export function attachRemoteWheel(mount: HTMLElement, term: Terminal): IDisposable {
  let remainder = 0
  let relaying = false
  const onWheel = (event: WheelEvent): void => {
    if (relaying || event.ctrlKey || event.metaKey || event.shiftKey || !event.deltaY || term.modes.mouseTrackingMode === 'none') return
    if (!(event.target instanceof Element)) return
    const movement = event.deltaMode === WheelEvent.DOM_DELTA_LINE ? event.deltaY
      : event.deltaMode === WheelEvent.DOM_DELTA_PAGE ? event.deltaY * term.rows
        : event.deltaY * 3 / 100
    if (Math.sign(remainder) !== Math.sign(movement)) remainder = 0
    remainder += movement
    const rounded = Math.round(remainder)
    if (Math.abs(remainder - rounded) < 1e-9) remainder = rounded
    const steps = Math.trunc(remainder)
    remainder -= steps
    event.preventDefault()
    event.stopImmediatePropagation()
    relaying = true
    try {
      for (let i = 0; i < Math.min(Math.abs(steps), 100); i++) {
        event.target.dispatchEvent(new WheelEvent('wheel', {
          bubbles: true, cancelable: true, deltaMode: WheelEvent.DOM_DELTA_LINE,
          deltaY: Math.sign(steps), clientX: event.clientX, clientY: event.clientY,
          altKey: event.altKey
        }))
      }
    } finally { relaying = false }
  }
  mount.addEventListener('wheel', onWheel, { capture: true, passive: false })
  return { dispose: () => mount.removeEventListener('wheel', onWheel, true) }
}

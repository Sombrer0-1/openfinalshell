import type { IDisposable, Terminal } from '@xterm/xterm'

interface Point { clientX: number; clientY: number }
interface MouseCoords {
  getCoords(event: Point, element: HTMLElement, cols: number, rows: number, selection?: boolean): [number, number] | undefined
  getMouseReportCoords(event: Point, element: HTMLElement): { col: number; row: number; x: number; y: number } | undefined
}

function logicalPoint(event: Point, element: HTMLElement): Point {
  const rect = element.getBoundingClientRect()
  const style = getComputedStyle(element)
  const width = parseFloat(style.width) || element.clientWidth
  const height = parseFloat(style.height) || element.clientHeight
  const scaleX = width > 0 && rect.width > 0 ? rect.width / width : 1
  const scaleY = height > 0 && rect.height > 0 ? rect.height / height : 1
  return {
    clientX: rect.left + (event.clientX - rect.left) / scaleX,
    clientY: rect.top + (event.clientY - rect.top) / scaleY
  }
}

/**
 * xterm's coordinate service uses logical cell sizes but browser client coordinates
 * include the app's CSS zoom. Adapt only this per-terminal service, keeping DOM
 * events and popup positions intact. This seam is covered against our pinned xterm.
 */
export function attachMouseCoordinates(term: Terminal): IDisposable {
  const service = (term as unknown as { _core: { _mouseCoordsService?: MouseCoords } })._core._mouseCoordsService
  if (!service) return { dispose: () => {} } // Terminal not opened (headless tests).
  const coords = service.getCoords
  const report = service.getMouseReportCoords
  service.getCoords = (event, element, cols, rows, selection) =>
    coords.call(service, logicalPoint(event, element), element, cols, rows, selection)
  service.getMouseReportCoords = (event, element) => report.call(service, logicalPoint(event, element), element)
  return { dispose: () => { service.getCoords = coords; service.getMouseReportCoords = report } }
}

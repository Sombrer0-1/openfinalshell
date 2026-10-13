// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Terminal } from '@xterm/xterm'
import { attachMouseCoordinates } from '@/features/terminal/mouseCoordinates'

afterEach(() => vi.restoreAllMocks())
describe('terminal coordinates under CSS zoom', () => {
  it.each([0.8, 1, 1.25, 1.5])('converts client coordinates to logical cells at zoom %s', zoom => {
    const element = document.createElement('div')
    element.style.width = '800px'; element.style.height = '400px'
    vi.spyOn(element, 'getBoundingClientRect').mockReturnValue({ left: 200, top: 100, width: 800 * zoom, height: 400 * zoom } as DOMRect)
    const coords = vi.fn((_event: unknown, _element: HTMLElement, _cols: number, _rows: number, _selection?: boolean) => [40, 10] as [number, number])
    const report = vi.fn((_event: unknown, _element: HTMLElement) => ({ col: 40, row: 10, x: 400, y: 200 }))
    const service = { getCoords: coords, getMouseReportCoords: report }
    const term = { _core: { _mouseCoordsService: service } } as unknown as Terminal
    const adapter = attachMouseCoordinates(term)
    const point = { clientX: 200 + 400 * zoom, clientY: 100 + 200 * zoom }
    service.getCoords(point, element, 80, 20)
    service.getMouseReportCoords(point, element)
    expect(coords).toHaveBeenCalledWith({ clientX: 600, clientY: 300 }, element, 80, 20, undefined)
    expect(report).toHaveBeenCalledWith({ clientX: 600, clientY: 300 }, element)
    adapter.dispose()
    expect(service.getCoords).toBe(coords)
    expect(service.getMouseReportCoords).toBe(report)
  })
})

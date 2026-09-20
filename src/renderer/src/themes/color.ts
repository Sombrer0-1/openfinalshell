/** Small sRGB helpers shared by both theme outputs. Stored accent values stay unchanged. */
function rgb(hex: string): number[] {
  return [1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16))
}

export function mixColor(color: string, target: string, amount: number): string {
  const other = rgb(target)
  return '#' + rgb(color).map((value, i) =>
    Math.round(value + (other[i] - value) * amount).toString(16).padStart(2, '0')
  ).join('')
}

function luminance(hex: string): number {
  const [r, g, b] = rgb(hex).map((value) => {
    const s = value / 255
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return r * 0.2126 + g * 0.7152 + b * 0.0722
}

export function contrastRatio(a: string, b: string): number {
  const x = luminance(a), y = luminance(b)
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}

export function resolveAccent(seed: string, mode: 'dark' | 'light', surfaces: string[]) {
  const normalized = /^#[\da-f]{6}$/i.test(seed) ? seed : /^#[\da-f]{3}$/i.test(seed)
    ? '#' + seed.slice(1).split('').map((c) => c + c).join('') : '#1677ff'
  const target = mode === 'dark' ? '#ffffff' : '#000000'
  let color = normalized
  for (let step = 0; step <= 100; step++) {
    color = mixColor(normalized, target, step / 100)
    if (surfaces.every((surface) => contrastRatio(color, surface) >= 4.5)) break
  }
  const onColor = contrastRatio(color, '#ffffff') >= contrastRatio(color, '#111318')
    ? '#ffffff' : '#111318'
  return { seed: normalized, color, onColor, soft: mixColor(surfaces[1], color, 0.1) }
}

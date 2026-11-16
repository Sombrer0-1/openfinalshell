import { describe, expect, it } from 'vitest'
import { buildStaticFrame, splitSections } from '../../src/main/monitor/script'

describe('监控采集脚本', () => {
  it('段名只能是大写字母 —— splitSections 的正则认不出带下划线/数字的段名', () => {
    // 这条护的是文件头警告的那个坑：HAS_PS_SORT 这类名字不报错、静默并进上一段
    const frame = buildStaticFrame()
    const names = [...frame.matchAll(/@@OFS:([A-Za-z0-9_]+)@@/g)]
      .map((m) => m[1])
      .filter((n) => !/^(BEGIN|END)/.test(n))
    for (const name of names) {
      expect(name).toMatch(/^[A-Z]+$/)
    }
    // 且每个段名都能被 splitSections 真的切出来
    const body = frame.replace(/^echo "@@OFS:BEGIN:0@@"\n/, '').replace(/echo "@@OFS:END:0@@"\n?$/, '')
    const echoed = body.replace(/^echo "(@@OFS:[A-Z]+@@)"$/gm, '$1')
    const sections = splitSections(echoed)
    for (const name of names) {
      expect(sections.has(name), `section ${name} 应能被切出`).toBe(true)
    }
  })
})

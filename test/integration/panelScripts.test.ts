import { spawnSync } from 'node:child_process'
import { describe, expect, it } from 'vitest'
import { parseTmuxSessions } from '../../src/main/monitor/TmuxCollector'
import { buildTmuxListFrame, buildTmuxNewSessionScript } from '../../src/main/monitor/tmuxScript'
import { parseProcessAux } from '../../src/main/monitor/ProcessPanelCollector'
import { buildProcessListFrame } from '../../src/main/monitor/processPanelScript'
import { splitSections } from '../../src/main/monitor/script'

function runShell(input: string) {
  return spawnSync(process.platform === 'win32' ? 'wsl.exe' : 'sh',
    process.platform === 'win32' ? ['--', 'sh'] : [],
    { input: `export LC_ALL=C LANG=C\n${input}`, encoding: 'utf8', timeout: 15_000 })
}

const hasTmux = runShell('command -v tmux >/dev/null 2>&1').status === 0
const hasBusyBox = runShell('command -v busybox >/dev/null 2>&1').status === 0

describe('真实远端工具输出（Windows 使用 WSL）', () => {
  it.skipIf(!hasTmux)('检测已经存在的 tmux 会话，保留名称中的分隔符', () => {
    const result = runShell([
      'unset TMUX',
      'export TMUX_TMPDIR=$(mktemp -d /tmp/ofs-tmux-test.XXXXXX)',
      'trap \'tmux kill-server 2>/dev/null; rmdir "$TMUX_TMPDIR/tmux-$(id -u)" "$TMUX_TMPDIR" 2>/dev/null\' EXIT',
      buildTmuxNewSessionScript('already|existing'),
      buildTmuxListFrame(1)
    ].join('\n'))
    expect(result.error).toBeUndefined()
    expect(result.status).toBe(0)
    const sections = splitSections(result.stdout)
    expect(sections.has('ERROR')).toBe(false)
    expect(parseTmuxSessions(sections.get('SESSIONS') ?? '')).toEqual([
      expect.objectContaining({ id: '$0', name: 'already|existing', windows: 1, attached: 0 })
    ])
  })

  it.skipIf(!hasBusyBox)('BusyBox 的进程列表能够解析出当前 shell', () => {
    const result = runShell([
      'ps() { busybox ps "$@"; }',
      'echo "CURRENT_PID=$$"',
      buildProcessListFrame(1, false)
    ].join('\n'))
    expect(result.error).toBeUndefined()
    expect(result.status).toBe(0)
    const pid = Number(/CURRENT_PID=(\d+)/.exec(result.stdout)?.[1])
    expect(parseProcessAux(splitSections(result.stdout).get('PROCS') ?? ''))
      .toContainEqual(expect.objectContaining({ pid }))
  })
})

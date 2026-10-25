import { describe, expect, it } from 'vitest'
import {
  parseProcessAux,
  parseProcessEo,
  processShortName,
  ProcessPanelCollector
} from '../../src/main/monitor/ProcessPanelCollector'
import {
  buildProcessDetailScript,
  buildProcessKillScript,
  buildProcessListFrame,
  buildProcessProbeFrame
} from '../../src/main/monitor/processPanelScript'

describe('进程面板解析', () => {
  it('从 cmdline 抽出短名，内核线程去掉方括号', () => {
    expect(processShortName('/usr/bin/python3 app.py')).toBe('python3')
    expect(processShortName('[kworker/19:0-events]')).toBe('kworker/19:0-events')
    expect(processShortName('')).toBe('')
  })

  it('只接收严格的 ps -eo 行，表头与畸形行一律丢弃', () => {
    const procs = parseProcessEo(`
  PID USER         RSS %CPU COMMAND
    1 root        1284  0.0 /sbin/init
  420 demo      123456 12.5 /usr/bin/node server.js --port 8080
   -1 root           0  0.0 weird
  abc root           1  1.0 no
  700 root           0  -1 badcpu
  701 root          -5  1.0 badrss
`)
    expect(procs).toEqual([
      {
        pid: 1,
        user: 'root',
        memBytes: 1284 * 1024,
        cpu: 0,
        name: 'init',
        cmdline: '/sbin/init',
        location: ''
      },
      {
        pid: 420,
        user: 'demo',
        memBytes: 123456 * 1024,
        cpu: 12.5,
        name: 'node',
        cmdline: '/usr/bin/node server.js --port 8080',
        location: ''
      }
    ])
  })

  it('ps aux 回落路径能抽出 USER/PID/%CPU/RSS/COMMAND', () => {
    const procs = parseProcessAux(`
USER       PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND
root         1  0.0  0.1 169484 11420 ?        Ss   Jan01   0:02 /sbin/init
demo       420  8.2  1.5 999999 50000 pts/0    Sl   10:00   1:02 node server.js
`)
    expect(procs.map((p) => p.pid)).toEqual([420, 1])
    expect(procs[0]).toMatchObject({
      pid: 420,
      user: 'demo',
      cpu: 8.2,
      memBytes: 50000 * 1024,
      name: 'node',
      cmdline: 'node server.js'
    })
  })

  it('列表帧在服务器侧用 ps，并带 NOPS / HASPSSORT 降级哨兵', () => {
    const probe = buildProcessProbeFrame(3)
    expect(probe).toContain('@@OFS:BEGIN:3@@')
    expect(probe).toContain('@@OFS:HASPSSORT@@')
    expect(probe).toContain('ps -eo pid,user,rss,pcpu,args --sort=-pcpu')
    expect(probe).toContain('@@OFS:END:3@@')

    const list = buildProcessListFrame(4, true)
    expect(list).toContain('command -v ps')
    expect(list).toContain('@@OFS:NOPS@@')
    expect(list).toContain('--sort=-pcpu')

    const busy = buildProcessListFrame(5, false)
    expect(busy).toContain('ps aux')
    expect(busy).not.toContain('--sort=-pcpu')
  })

  it('详情与 kill 脚本只接受已校验的数字 pid，并走 /proc 与 kill -s', () => {
    const detail = buildProcessDetailScript(42)
    expect(detail).toContain("/proc/$PID/comm")
    expect(detail).toContain("/proc/$PID/exe")
    expect(detail).toContain("/proc/$PID/cwd")
    expect(detail).toContain("/proc/$PID/environ")
    expect(detail).toContain("'42'")

    const term = buildProcessKillScript(42, false)
    expect(term).toContain('kill -s TERM --')
    expect(term).toContain("'42'")
    const kill = buildProcessKillScript(42, true)
    expect(kill).toContain('kill -s KILL --')
  })

  it('同一 SSH 数据块里的过期帧不会遮蔽当前采样帧', async () => {
    const collector = new ProcessPanelCollector(
      'session-1',
      async () => {
        throw new Error('not opened in this parser test')
      },
      { onSnapshot: () => {}, onState: () => {} }
    )
    const result = new Promise<string | null>((resolve) => {
      ;(collector as unknown as { pending: { seq: number; resolve: (body: string | null) => void } }).pending = {
        seq: 2,
        resolve
      }
    })

    ;(collector as unknown as { onData: (chunk: Buffer) => void }).onData(
      Buffer.from(
        [
          '@@OFS:BEGIN:1@@',
          '@@OFS:PROCS@@',
          '1 root 1 0.0 /sbin/init',
          '@@OFS:END:1@@',
          '@@OFS:BEGIN:2@@',
          '@@OFS:PROCS@@',
          '420 demo 100 1.5 node',
          '@@OFS:END:2@@',
          ''
        ].join('\n')
      )
    )

    await expect(result).resolves.toBe('@@OFS:PROCS@@\n420 demo 100 1.5 node\n')
  })
})

import { describe, expect, it, vi } from 'vitest'
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
  it('BusyBox 简化格式保留 PID、用户和完整命令', () => {
    expect(parseProcessAux('PID USER COMMAND\n42 root /bin/sh -c sleep 30\n')).toEqual([
      { pid: 42, user: 'root', cpu: 0, memBytes: 0, name: 'sh', cmdline: '/bin/sh -c sleep 30', location: '' }
    ])
    expect(parseProcessAux('PID USER VSZ STAT COMMAND\n43 app 1200 S node server.js\n'))
      .toEqual([expect.objectContaining({ pid: 43, user: 'app', cpu: 0, memBytes: 0, cmdline: 'node server.js' })])
  })
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

  it('列表帧在服务器侧用 ps，并带 NOPS / HASPSSORT 降级哨兵与 head 截断', () => {
    const probe = buildProcessProbeFrame(3)
    expect(probe).toContain('@@OFS:BEGIN:3@@')
    expect(probe).toContain('@@OFS:HASPSSORT@@')
    expect(probe).toContain('ps -eo pid,user,rss,pcpu,args --sort=-pcpu')
    expect(probe).toContain('head -n 4001')
    expect(probe).toContain('@@OFS:END:3@@')

    const list = buildProcessListFrame(4, true)
    expect(list).toContain('command -v ps')
    expect(list).toContain('@@OFS:NOPS@@')
    expect(list).toContain('--sort=-pcpu')
    expect(list).toContain('head -n 4001')

    const busy = buildProcessListFrame(5, false)
    expect(busy).toContain('ps aux')
    expect(busy).toContain('head -n 4001')
    expect(busy).not.toContain('--sort=-pcpu')
  })

  it('详情与 kill 脚本只接受已校验的数字 pid，并走 /proc 与 kill -s', () => {
    const detail = buildProcessDetailScript(42)
    expect(detail).toContain("/proc/$PID/comm")
    expect(detail).toContain("/proc/$PID/exe")
    expect(detail).toContain("/proc/$PID/cwd")
    expect(detail).toContain("/proc/$PID/environ")
    expect(detail).toContain("'42'")
    // ExecRunner 契约：用 (exit N) 留 $?，禁止提前 exit 跳过 RC 哨兵
    expect(detail).toContain('(exit 3)')
    expect(detail).toContain('(exit 0)')
    expect(detail).not.toMatch(/(?:^|\n)\s*exit\s+\d+/m)

    const term = buildProcessKillScript(42, false)
    expect(term).toContain('kill -s TERM --')
    expect(term).toContain("'42'")
    expect(term).toContain('(exit 3)')
    expect(term).not.toContain('2>/dev/null')
    expect(term).not.toMatch(/(?:^|\n)\s*exit\s+\d+/m)
    const kill = buildProcessKillScript(42, true)
    expect(kill).toContain('kill -s KILL --')
    expect(kill).not.toContain('2>/dev/null')
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
  it('SSH 通道意外关闭后会安排 5 秒延迟重启，而不是只计失败后假死', async () => {
    vi.useFakeTimers()
    const openCalls: Array<() => void> = []
    let openCount = 0
    const collector = new ProcessPanelCollector(
      'session-restart',
      async () => {
        openCount += 1
        const handlers: Record<string, Array<(...args: unknown[]) => void>> = {}
        const channel = {
          on: (event: string, cb: (...args: unknown[]) => void) => {
            ;(handlers[event] ??= []).push(cb)
            return channel
          },
          stderr: { on: () => channel },
          write: () => true,
          close: () => {
            for (const cb of handlers.close ?? []) cb()
          }
        }
        openCalls.push(() => {
          for (const cb of handlers.close ?? []) cb()
        })
        return channel as never
      },
      { onSnapshot: () => {}, onState: () => {} }
    )

    await collector.start()
    expect(openCount).toBe(1)
    // 模拟常驻 sh 被杀
    openCalls[0]!()
    expect(openCount).toBe(1)
    await vi.advanceTimersByTimeAsync(5000)
    expect(openCount).toBe(2)
    collector.stop()
    vi.useRealTimers()
  })

  it('open() 在途守卫：start 在途时 reattach 并发，后落地者胜出，先到者就地关闭不注册', async () => {
    vi.useFakeTimers()
    let closeCount = 0
    let openCount = 0
    let releaseHang!: (channel: unknown) => void
    const hangPending = new Promise<unknown>((resolve) => {
      releaseHang = resolve
    })
    const makeCh = () => {
      const handlers: Record<string, Array<(...args: unknown[]) => void>> = {}
      const channel = {
        on: (event: string, cb: (...args: unknown[]) => void) => {
          ;(handlers[event] ??= []).push(cb)
          return channel
        },
        stderr: { on: () => channel },
        write: () => true,
        close: () => {
          closeCount += 1
          for (const cb of handlers.close ?? []) cb()
        }
      }
      return channel
    }
    const collector = new ProcessPanelCollector(
      'session-guard',
      async () => {
        openCount += 1
        if (openCount === 1) return (await hangPending) as never
        return makeCh() as never
      },
      { onSnapshot: () => {}, onState: () => {} }
    )
    const started = collector.start()
    // 第一个 open 挂在途中的窗口里，SSH 重连驱动 reattach（第二个通道立即就绪）
    await collector.reattach()
    // 放行第一个通道：代际已过期 → 就地关闭，不注册监听、不设定时器
    releaseHang(makeCh())
    await started
    expect(closeCount).toBe(1)
    collector.stop()
    expect(closeCount).toBe(2) // stop 只关当前注册的那个通道，孤儿已在落地时关过
    vi.useRealTimers()
  })

})

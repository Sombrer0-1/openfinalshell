import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * Tmux 管理的远端层：脚本构造（哨兵 / shQuote / 无提前 exit）、列表解析
 * （空正文 = 合法空态、SECTION:ERROR → failed、名称含空格 / 中文 / 前导 `-`、
 * 非法 id 行丢弃、mouse 探测）、new 的 id 提取与 has-session 语义、
 * collector 的过期帧与失败计数。
 */

// ---- TmuxManager 的依赖收口：execOnce / sshManager 可编程，i18n 固定英文 ----
const execOnce = vi.hoisted(() => vi.fn())
vi.mock('../../src/main/ssh/ExecRunner', () => ({ execOnce }))
vi.mock('../../src/main/ssh/SshConnectionManager', () => ({
  sshManager: { get: vi.fn(() => ({})), openTmuxAttach: vi.fn() }
}))
vi.mock('../../src/main/services/settings', () => ({
  getSettings: () => ({ language: 'en-US' })
}))

import { parseTmuxMouse, parseTmuxSessions, TmuxCollector } from '../../src/main/monitor/TmuxCollector'
import {
  buildTmuxAttachCommand,
  buildTmuxHasSessionScript,
  buildTmuxKillSessionScript,
  buildTmuxListFrame,
  buildTmuxNewSessionScript,
  buildTmuxProbeFrame,
  buildTmuxSetMouseScript
} from '../../src/main/monitor/tmuxScript'

const { tmuxManager } = await import('../../src/main/monitor/TmuxManager')

afterEach(() => {
  execOnce.mockReset()
  tmuxManager.stopAll()
})

// ---- 脚本构造 ----

describe('tmux 脚本构造', () => {
  it('探测帧带 HASTMUX / VERSION 段哨兵，不提前 exit', () => {
    const probe = buildTmuxProbeFrame(3)
    expect(probe).toContain('@@OFS:BEGIN:3@@')
    expect(probe).toContain('@@OFS:HASTMUX@@')
    expect(probe).toContain('if command -v tmux >/dev/null 2>&1; then echo yes; else echo no; fi')
    expect(probe).toContain('@@OFS:VERSION@@')
    expect(probe).toContain('tmux -V 2>/dev/null')
    expect(probe).toContain('@@OFS:END:3@@')
    expect(probe.endsWith('\n')).toBe(true)
    expect(probe).not.toMatch(/(?:^|\n)\s*exit\s+\d+/m)
  })

  it('列表帧：session_name 放最后、no-server 与其余错误分流、附 MOUSE 段', () => {
    const frame = buildTmuxListFrame(7)
    expect(frame).toContain('@@OFS:BEGIN:7@@')
    expect(frame).toContain('@@OFS:SESSIONS@@')
    // 字段顺序：id\twindows\tattached\tcreated\tname —— 名称放最后，含空格 / 中文 / 前导 - 的行才能按定长前缀切分
    expect(frame).toContain(
      `out=$(tmux list-sessions -F '#{session_id}\t#{session_windows}\t#{session_attached}\t#{session_created}\t#{session_name}' 2>&1)`
    )
    // 退出码 1 不等于没有 server：按 stderr 文本区分 no-server（合法空态）与其余错误
    expect(frame).toContain('*"no server running"*')
    expect(frame).toContain('|"error connecting to "**"No such file or directory"*) : ;;')
    expect(frame).toContain('@@OFS:ERROR@@')
    expect(frame).toContain('head -n 3')
    expect(frame).toContain('@@OFS:MOUSE@@')
    expect(frame).toContain('tmux show-options -gv mouse 2>/dev/null')
    expect(frame).toContain('@@OFS:END:7@@')
    // ExecRunner 契约：常驻 sh 上的帧同样不写提前 exit（通道要活着跑下一帧）
    expect(frame).not.toMatch(/(?:^|\n)\s*exit\s+\d+/m)
  })

  it('低频操作走 execOnce：入参 shQuote，命令串无提前 exit', () => {
    expect(buildTmuxNewSessionScript('my session')).toBe(
      `tmux new-session -d -P -F '#{session_id}' -s 'my session'`
    )
    expect(buildTmuxNewSessionScript("it's")).toBe(`tmux new-session -d -P -F '#{session_id}' -s 'it'\\''s'`)
    expect(buildTmuxKillSessionScript('$2')).toBe("tmux kill-session -t '$2'")
    expect(buildTmuxSetMouseScript(true)).toBe('tmux set-option -g mouse on')
    expect(buildTmuxSetMouseScript(false)).toBe('tmux set-option -g mouse off')
    expect(buildTmuxHasSessionScript('$9')).toBe("tmux has-session -t '$9'")
    for (const script of [
      buildTmuxNewSessionScript('b'),
      buildTmuxKillSessionScript('$2'),
      buildTmuxSetMouseScript(true),
      buildTmuxHasSessionScript('$9')
    ]) {
      expect(script).not.toMatch(/(?:^|\n)\s*exit\s+\d+/m)
    }
  })

  it('附着命令是整条 exec 串（pty 对象在通道层，此层只能校验命令本身）', () => {
    expect(buildTmuxAttachCommand('$3')).toBe("tmux attach -t '$3'")
  })
})

// ---- 列表解析（纯函数） ----

describe('tmux 列表解析', () => {
  it('空正文 = 合法空态（无 server / 无会话），不是错误', () => {
    expect(parseTmuxSessions('')).toEqual([])
    expect(parseTmuxSessions('\n\r\n')).toEqual([])
  })

  it('名称含空格、中文、前导 - 的行按前四个定长字段可靠切分', () => {
    const sessions = parseTmuxSessions(
      [
        '$2\t3\t1\t1748000000\tmy dev session',
        '$1\t1\t0\t1748000001\t中文会话',
        '$4\t2\t0\t1748000002\t-dash-name',
        // 名称内被 tmux 转义后残余的 \t 归名称（第 5 列吸收所有剩余列）
        '$5\t1\t2\t1748000003\tname\twith\ttabs'
      ].join('\r\n')
    )
    // 名称按 UTF-16 码元定序：'-' < ASCII 字母 < 中文
    expect(sessions).toEqual([
      { id: '$4', name: '-dash-name', windows: 2, attached: 0, created: 1748000002 },
      { id: '$2', name: 'my dev session', windows: 3, attached: 1, created: 1748000000 },
      { id: '$5', name: 'name\twith\ttabs', windows: 1, attached: 2, created: 1748000003 },
      { id: '$1', name: '中文会话', windows: 1, attached: 0, created: 1748000001 }
    ])
  })

  it('id 不合 $N 形状、字段数不足、数值列非法的行一律丢弃', () => {
    const sessions = parseTmuxSessions(
      [
        'weird\t1\t0\t1748000000\tbadid', // 非 $N
        'x$1\t1\t0\t1748000000\tpartial', // 仍非 $N
        '$3\t1\t0\t1748000000', // 字段不足
        '$4\t0\t0\t1748000000\tzero-win', // windows < 1
        '$5\t-1\t0\t1748000000\tneg-win',
        '$6\t1\t-1\t1748000000\tneg-att',
        '$7\t1\t0\tabc\tnan-created',
        '$8\t1.5\t0\t1748000000\tfrac-win',
        '$9\t1\t0\t1748000000\tgood'
      ].join('\n')
    )
    expect(sessions.map((s) => s.id)).toEqual(['$9'])
  })

  it('按名称稳定排序，同名按 id 定序（刷新不跳行）', () => {
    const sessions = parseTmuxSessions(
      ['$3\t1\t0\t1\tb', '$1\t1\t0\t1\ta', '$2\t1\t0\t1\tb'].join('\n')
    )
    expect(sessions.map((s) => `${s.name}/${s.id}`)).toEqual(['a/$1', 'b/$2', 'b/$3'])
  })

  it('MOUSE 段：on/off 如实回值，空正文（无 server）与垃圾输出都是 undefined', () => {
    expect(parseTmuxMouse('on\n')).toBe(true)
    expect(parseTmuxMouse('off\n')).toBe(false)
    expect(parseTmuxMouse('')).toBeUndefined()
    expect(parseTmuxMouse('\n')).toBeUndefined()
    expect(parseTmuxMouse('weird\n')).toBeUndefined()
  })
})

// ---- TmuxManager：new 的 id 提取与 has-session 语义 ----

describe('TmuxManager new / has', () => {
  it('new 成功：stdout 第一行就是新会话 id', async () => {
    execOnce.mockResolvedValue({ code: 0, stdout: '$7\n', stderr: '' })
    await expect(tmuxManager.newSession('session-mgr', 'b')).resolves.toEqual({ id: '$7' })
    expect(execOnce).toHaveBeenCalledWith(
      expect.anything(),
      `tmux new-session -d -P -F '#{session_id}' -s 'b'`,
      expect.objectContaining({ timeoutMs: 10_000 })
    )
  })

  it('rc=0 但 stdout 形状不对：不当成功（不拿假 id 去附着）', async () => {
    execOnce.mockResolvedValue({ code: 0, stdout: 'Welcome to Ubuntu 22.04\n$7\n', stderr: '' })
    await expect(tmuxManager.newSession('session-mgr', 'b')).rejects.toThrow(/create tmux session/i)
  })

  it('duplicate session 错误映射为重名文案（表单就地报错的依据）', async () => {
    execOnce.mockResolvedValue({ code: 1, stdout: '', stderr: 'duplicate session: b' })
    await expect(tmuxManager.newSession('session-mgr', 'b')).rejects.toThrow(/already exists/i)
  })

  it('has-session：0 = 存在、1 = 不存在、其余（含 null）是错误', async () => {
    execOnce.mockResolvedValueOnce({ code: 0, stdout: '', stderr: '' })
    await expect(tmuxManager.hasSession('session-mgr', '$1')).resolves.toBe(true)

    execOnce.mockResolvedValueOnce({ code: 1, stdout: '', stderr: "can't find session" })
    await expect(tmuxManager.hasSession('session-mgr', '$1')).resolves.toBe(false)

    execOnce.mockResolvedValueOnce({ code: 127, stdout: '', stderr: 'command not found' })
    await expect(tmuxManager.hasSession('session-mgr', '$1')).rejects.toThrow(/check the tmux session/i)
  })
})

// ---- TmuxCollector：帧应答、空态 / ERROR 分流、过期帧、失败计数 ----

/** 可编程的假常驻 sh 通道：write 进来的帧脚本按应答器回一对哨兵帧 */
function makeChannel(responder: (script: string) => string) {
  const handlers: Record<string, Array<(...args: unknown[]) => void>> = {}
  const writes: string[] = []
  const channel = {
    on: (event: string, cb: (...args: unknown[]) => void) => {
      ;(handlers[event] ??= []).push(cb)
      return channel
    },
    stderr: { on: () => channel },
    write: (script: string) => {
      writes.push(script)
      const m = /@@OFS:BEGIN:(\d+)@@/.exec(script)
      if (m) {
        // 模拟远端 round-trip：异步回帧，不与 write 同拍
        void Promise.resolve().then(() => {
          for (const cb of handlers.data ?? []) cb(Buffer.from(responder(script)))
        })
      }
      return true
    },
    close: () => {
      for (const cb of handlers.close ?? []) cb()
    }
  }
  return { channel, writes }
}

function frame(seq: number, body: string): string {
  return `@@OFS:BEGIN:${seq}@@\r\n${body}@@OFS:END:${seq}@@\r\n`
}

describe('TmuxCollector', () => {
  it('探测通过 → 首帧列表：发布会话与 mouse 值，进入 running', async () => {
    const snapshots: Array<{ sessions: unknown[]; mouseOn?: boolean }> = []
    const states: string[] = []
    const probe = makeChannel((script) =>
      script.includes('HASTMUX')
        ? frame(1, '@@OFS:HASTMUX@@\nyes\n@@OFS:VERSION@@\ntmux 3.3a\n')
        : frame(2, '@@OFS:SESSIONS@@\n$1\t1\t0\t1748000000\tmain\n@@OFS:MOUSE@@\noff\n')
    )
    const collector = new TmuxCollector('session-collect', async () => probe.channel as never, {
      onSnapshot: (s) => snapshots.push({ sessions: s.sessions, mouseOn: s.mouseOn }),
      onState: (state) => states.push(state)
    })
    await collector.start()
    await vi.waitFor(() => expect(states).toContain('running'))
    collector.stop()

    // 探测通过后不等 2s tick，立即补首帧列表
    expect(probe.writes[1]).toContain('@@OFS:SESSIONS@@')
    expect(snapshots).toEqual([
      { sessions: [{ id: '$1', name: 'main', windows: 1, attached: 0, created: 1748000000 }], mouseOn: false }
    ])
  })

  it('探测无 tmux → unsupported，且不再发帧', async () => {
    const states: string[] = []
    const probe = makeChannel(() => frame(1, '@@OFS:HASTMUX@@\nno\n@@OFS:VERSION@@\n\n'))
    const collector = new TmuxCollector('session-collect', async () => probe.channel as never, {
      onSnapshot: () => {},
      onState: (state) => states.push(state)
    })
    await collector.start()
    await vi.waitFor(() => expect(collector.state).toBe('unsupported'))

    expect(states).toEqual(['collecting', 'unsupported'])
    expect(probe.writes).toHaveLength(1) // 探测帧之后停表，没有列表帧
    collector.stop()
  })

  it('no-server 空正文是合法空态：空列表 + 无 mouse 值，不是 failed', async () => {
    const snapshots: Array<{ sessions: unknown[]; mouseOn?: boolean }> = []
    const probe = makeChannel((script) =>
      script.includes('HASTMUX')
        ? frame(1, '@@OFS:HASTMUX@@\nyes\n@@OFS:VERSION@@\ntmux 3.3a\n')
        : frame(2, '@@OFS:SESSIONS@@\n\n@@OFS:MOUSE@@\n\n')
    )
    const collector = new TmuxCollector('session-collect', async () => probe.channel as never, {
      onSnapshot: (s) => snapshots.push({ sessions: s.sessions, ...(s.mouseOn === undefined ? {} : { mouseOn: s.mouseOn }) }),
      onState: () => {}
    })
    await collector.start()
    await vi.waitFor(() => expect(snapshots).toHaveLength(1))

    expect(snapshots[0]).toEqual({ sessions: [] })
    expect(collector.state).toBe('running')
    collector.stop()
  })

  it('SECTION:ERROR → failed 并透出错误正文（权限 / socket 问题不伪装成空列表）', async () => {
    const states: Array<[string, string | undefined]> = []
    const probe = makeChannel((script) =>
      script.includes('HASTMUX')
        ? frame(1, '@@OFS:HASTMUX@@\nyes\n@@OFS:VERSION@@\ntmux 3.3a\n')
        : frame(
            2,
            '@@OFS:SESSIONS@@\n\n@@OFS:ERROR@@\nerror connecting to /tmp/tmux-1000/default (Permission denied)\nline2\nline3\nline4\n@@OFS:MOUSE@@\n\n'
          )
    )
    const collector = new TmuxCollector('session-collect', async () => probe.channel as never, {
      onSnapshot: () => {},
      onState: (state, error) => states.push([state, error])
    })
    await collector.start()
    await vi.waitFor(() => expect(collector.state).toBe('failed'))
    collector.stop()

    const failed = states.find(([state]) => state === 'failed')
    expect(failed?.[1]).toContain('Permission denied')
  })

  it('同一数据块里的过期帧不会遮蔽当前帧（seq 配对）', async () => {
    const collector = new TmuxCollector(
      'session-stale',
      async () => {
        throw new Error('not opened in this parser test')
      },
      { onSnapshot: () => {}, onState: () => {} }
    )
    const result = new Promise<string | null>((resolve) => {
      ;(
        collector as unknown as {
          pending: { seq: number; resolve: (body: string | null) => void }
        }
      ).pending = { seq: 2, resolve }
    })

    ;(collector as unknown as { onData: (chunk: Buffer) => void }).onData(
      Buffer.from(
        [
          '@@OFS:BEGIN:1@@',
          '@@OFS:SESSIONS@@',
          '$1\t1\t0\t1\tstale',
          '@@OFS:END:1@@',
          '@@OFS:BEGIN:2@@',
          '@@OFS:SESSIONS@@',
          '$2\t1\t0\t1\tcurrent',
          '@@OFS:END:2@@',
          ''
        ].join('\n')
      )
    )

    await expect(result).resolves.toBe('@@OFS:SESSIONS@@\n$2\t1\t0\t1\tcurrent\n')
  })

  it('连续 3 次帧超时才进 failed（前两次只是计数，不误杀慢链路）', async () => {
    vi.useFakeTimers()
    try {
      const states: string[] = []
      // 应答器故意不应答：每帧都等满 TMUX_FRAME_TIMEOUT_MS 超时
      const probe = makeChannel(() => 'ignored')
      const collector = new TmuxCollector('session-timeout', async () => probe.channel as never, {
        onSnapshot: () => {},
        onState: (state) => states.push(state)
      })
      await collector.start()

      // 第 1 次超时（8s）：只计失败，不置 failed
      await vi.advanceTimersByTimeAsync(8000)
      expect(collector.state).not.toBe('failed')
      expect(collector.state).toBe('collecting')

      // 2s 轮询继续发探测帧；累计到第 3 次超时 → failed
      await vi.advanceTimersByTimeAsync(40_000)
      expect(collector.state).toBe('failed')
      expect(states.filter((s) => s === 'failed')).toHaveLength(1)
      collector.stop()
    } finally {
      vi.useRealTimers()
    }
  })

  it('SSH 通道意外关闭后 5 秒重建（而不是只计失败后假死）', async () => {
    vi.useFakeTimers()
    try {
      let openCount = 0
      let first: ReturnType<typeof makeChannel> | null = null
      const collector = new TmuxCollector(
        'session-rebuild',
        async () => {
          openCount += 1
          first = makeChannel(() => 'ignored')
          return first.channel as never
        },
        { onSnapshot: () => {}, onState: () => {} }
      )
      await collector.start()
      expect(openCount).toBe(1)
      // 模拟常驻 sh 被杀
      first!.channel.close()
      await vi.advanceTimersByTimeAsync(5000)
      expect(openCount).toBe(2)
      collector.stop()
    } finally {
      vi.useRealTimers()
    }
  })

  it('重建 open() 失败会重排重试，连续 3 次失败后进 failed 且不再重排', async () => {
    vi.useFakeTimers()
    try {
      const states: string[] = []
      let openCount = 0
      let first: ReturnType<typeof makeChannel> | null = null
      const collector = new TmuxCollector(
        'session-retry',
        async () => {
          openCount += 1
          if (openCount === 1) {
            first = makeChannel(() => 'ignored')
            return first.channel as never
          }
          throw new Error('open failed')
        },
        { onSnapshot: () => {}, onState: (state) => states.push(state) }
      )
      await collector.start()
      expect(collector.state).toBe('collecting')
      // 通道死亡：在途帧收尾计第 1 次失败，5s 后第一次重建（open 抛错，第 2 次）
      first!.channel.close()
      await vi.advanceTimersByTimeAsync(5000)
      expect(openCount).toBe(2)
      expect(collector.state).toBe('collecting')
      // 第二次重建抛错 → 第 3 次失败 → failed，不再排重试
      await vi.advanceTimersByTimeAsync(5000)
      expect(openCount).toBe(3)
      expect(collector.state).toBe('failed')
      expect(states.filter((s) => s === 'failed')).toHaveLength(1)
      await vi.advanceTimersByTimeAsync(5000)
      expect(openCount).toBe(3)
      collector.stop()
    } finally {
      vi.useRealTimers()
    }
  })

  it('open() 在途守卫：重建在途时 reattach，后落地者胜出，先到者就地关闭不注册', async () => {
    vi.useFakeTimers()
    try {
      let closeCount = 0
      let openCount = 0
      let releaseHang!: (channel: unknown) => void
      const hangPending = new Promise<unknown>((resolve) => {
        releaseHang = resolve
      })
      const makeHang = () => {
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
      const collector = new TmuxCollector(
        'session-guard',
        async () => {
          openCount += 1
          if (openCount === 1) return (await hangPending) as never
          return makeChannel((script) => {
            const seq = /@@OFS:BEGIN:(\d+)@@/.exec(script)?.[1] ?? '1'
            return `@@OFS:BEGIN:${seq}@@\r\n@@OFS:HASTMUX@@\r\nno\r\n@@OFS:END:${seq}@@\r\n`
          }).channel as never
        },
        { onSnapshot: () => {}, onState: () => {} }
      )
      const started = collector.start()
      // 第一个 open 挂在途中的窗口里，SSH 重连驱动 reattach（第二个通道立即就绪）
      const reattached = collector.reattach()
      await vi.advanceTimersByTimeAsync(0)
      await expect(reattached).resolves.toBeUndefined()
      // 放行第一个通道：代际已过期 → 就地关闭，不注册监听、不设定时器
      releaseHang(makeHang())
      await started
      expect(closeCount).toBe(1)
      expect(collector.state).toBe('unsupported')
      collector.stop()
    } finally {
      vi.useRealTimers()
    }
  })
})

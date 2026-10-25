import type { ClientChannel } from 'ssh2'
import type { ProcessEntry, ProcessPanelState, ProcessSnapshot, SessionId } from '@shared/types'
import {
  PROCESS_PANEL_FRAME_TIMEOUT_MS,
  PROCESS_PANEL_INTERVAL_MS,
  PROCESS_PANEL_MAX_FRAME_BYTES,
  PROCESS_PANEL_MAX_PROCS
} from '@shared/constants'
import { t } from '../services/i18n'
import { scopedLogger } from '../utils/logger'
import { buildProcessListFrame, buildProcessProbeFrame } from './processPanelScript'
import { splitSections } from './script'

const log = scopedLogger('process-panel')
const FRAME_RE = /@@OFS:BEGIN:(\d+)@@\r?\n([\s\S]*?)@@OFS:END:\1@@\r?\n?/
const MAX_CONSECUTIVE_FAILURES = 3

export interface ProcessPanelCallbacks {
  onSnapshot: (snapshot: ProcessSnapshot) => void
  onState: (state: ProcessPanelState, error?: string) => void
}

/** 从 cmdline 抽出短名：`/usr/bin/foo bar` → `foo`；`[kworker/0]` → `kworker/0`。 */
export function processShortName(cmdline: string): string {
  const trimmed = cmdline.trim()
  if (!trimmed) return ''
  if (trimmed.startsWith('[') && trimmed.endsWith(']')) return trimmed.slice(1, -1)
  const first = trimmed.split(/\s+/)[0] ?? ''
  const base = first.split('/').pop() ?? first
  return base || first
}

/**
 * 解析 `ps -eo pid,user,rss,pcpu,args`（含表头）。
 * 远端输出不可信：畸形行丢弃，rss/cpu 限制在有限非负数，cmdline 截断防撑爆 IPC。
 */
export function parseProcessEo(text: string, limit = PROCESS_PANEL_MAX_PROCS): ProcessEntry[] {
  const out: ProcessEntry[] = []
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const f = line.split(/\s+/)
    if (f.length < 5) continue
    const pid = Number(f[0])
    const rssKb = Number(f[2])
    const cpu = Number(f[3])
    // 表头 PID/USER/%CPU 等：pid 不是有限整数
    if (!Number.isInteger(pid) || pid <= 0 || !Number.isSafeInteger(pid)) continue
    if (!Number.isFinite(rssKb) || rssKb < 0 || !Number.isFinite(cpu) || cpu < 0) continue
    const user = f[1]
    if (!user || user.length > 64) continue
    const cmdline = f.slice(4).join(' ').slice(0, 4000)
    const name = processShortName(cmdline).slice(0, 256) || String(pid)
    out.push({
      pid,
      user: user.slice(0, 64),
      memBytes: Math.min(Number.MAX_SAFE_INTEGER, Math.round(rssKb * 1024)),
      cpu,
      name,
      cmdline: cmdline || name,
      location: ''
    })
    if (out.length >= limit) break
  }
  return out
}

/**
 * BusyBox / 无 --sort 时的 `ps aux` 回落。
 * 完整 aux：USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND…
 * BusyBox 给不出可靠 %CPU/%MEM 时填 0，名单本身仍有价值。
 */
export function parseProcessAux(text: string, limit = PROCESS_PANEL_MAX_PROCS): ProcessEntry[] {
  const out: ProcessEntry[] = []
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const f = line.split(/\s+/)
    if (f.length < 11) continue
    const pid = Number(f[1])
    const cpu = Number(f[2])
    const rssKb = Number(f[5])
    if (!Number.isInteger(pid) || pid <= 0 || !Number.isSafeInteger(pid)) continue
    if (!Number.isFinite(cpu) || cpu < 0 || !Number.isFinite(rssKb) || rssKb < 0) continue
    const user = f[0]
    if (!user || user === 'USER' || user.length > 64) continue
    const cmdline = f.slice(10).join(' ').slice(0, 4000)
    const name = processShortName(cmdline).slice(0, 256) || String(pid)
    out.push({
      pid,
      user: user.slice(0, 64),
      memBytes: Math.min(Number.MAX_SAFE_INTEGER, Math.round(rssKb * 1024)),
      cpu,
      name,
      cmdline: cmdline || name,
      location: ''
    })
    if (out.length >= limit) break
  }
  return out.sort((a, b) => b.cpu - a.cpu || b.memBytes - a.memBytes || a.pid - b.pid)
}

/**
 * 打开进程标签后才运行的独立采集器。
 * 不复用服务器监控通道，避免全量 ps 拖慢 CPU/磁盘指标；也不每 tick 新建 exec。
 */
export class ProcessPanelCollector {
  private channel: ClientChannel | null = null
  private buffer = ''
  private seq = 0
  private timer: NodeJS.Timeout | null = null
  private frameTimer: NodeJS.Timeout | null = null
  private pending: { seq: number; resolve: (body: string | null) => void } | null = null
  private failures = 0
  private stopped = false
  private probed = false
  private hasPsSort = true
  state: ProcessPanelState = 'stopped'

  constructor(
    readonly sessionId: SessionId,
    private readonly openChannel: () => Promise<ClientChannel>,
    private readonly cb: ProcessPanelCallbacks
  ) {}

  async start(): Promise<void> {
    this.stopped = false
    this.pause()
    await this.open()
  }

  async reattach(): Promise<void> {
    if (this.stopped || this.state === 'unsupported') return
    this.pause()
    this.channel?.close()
    this.channel = null
    this.pending?.resolve(null)
    this.pending = null
    await this.open()
  }

  stop(): void {
    this.stopped = true
    this.pause()
    this.channel?.close()
    this.channel = null
    this.pending?.resolve(null)
    this.pending = null
    this.setState('stopped')
  }

  private async open(): Promise<void> {
    this.setState('running')
    const channel = await this.openChannel()
    if (this.stopped) {
      channel.close()
      return
    }
    this.channel = channel
    channel.on('data', (chunk: Buffer) => this.onData(chunk))
    channel.stderr.on('data', () => {
      /* ps 权限/能力问题按正文和帧超时处理 */
    })
    channel.on('close', () => this.onChannelClosed(channel))
    this.timer = setInterval(() => this.tick(), PROCESS_PANEL_INTERVAL_MS)
    this.tick()
  }

  private pause(): void {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    if (this.frameTimer) clearTimeout(this.frameTimer)
    this.frameTimer = null
  }

  private setState(state: ProcessPanelState, error?: string): void {
    if (this.state === state && error === undefined) return
    this.state = state
    this.cb.onState(state, error)
  }

  private tick(): void {
    if (this.stopped || !this.channel || this.pending) return
    this.seq += 1
    const seq = this.seq
    const script = this.probed
      ? buildProcessListFrame(seq, this.hasPsSort)
      : buildProcessProbeFrame(seq)
    void this.request(script, seq).then((body) => {
      if (body === null) {
        this.onFailure(t('err.net.collectTimeout'))
        return
      }
      const sections = splitSections(body)
      if (!this.probed) {
        this.hasPsSort = (sections.get('HASPSSORT') ?? 'yes').includes('yes')
        this.probed = true
      }
      if (sections.has('NOPS')) {
        this.pause()
        this.setState('unsupported')
        return
      }
      try {
        const text = sections.get('PROCS') ?? ''
        const processes = this.hasPsSort ? parseProcessEo(text) : parseProcessAux(text)
        // 探测帧若按 procps 采到空表且探测说无 sort，下一 tick 会走 aux；空表本身合法（极简容器）。
        this.cb.onSnapshot({ ts: Date.now(), processes })
        this.failures = 0
        if (this.state !== 'running') this.setState('running')
      } catch (err) {
        this.onFailure(err instanceof Error ? err.message : String(err))
      }
    })
  }

  private request(script: string, seq: number): Promise<string | null> {
    return new Promise((resolve) => {
      if (!this.channel) return resolve(null)
      this.pending = { seq, resolve }
      this.frameTimer = setTimeout(() => {
        if (this.pending?.seq !== seq) return
        this.pending = null
        this.frameTimer = null
        resolve(null)
      }, PROCESS_PANEL_FRAME_TIMEOUT_MS)
      this.channel.write(script)
    })
  }

  private onData(chunk: Buffer): void {
    this.buffer += chunk.toString('utf8')
    if (Buffer.byteLength(this.buffer, 'utf8') > PROCESS_PANEL_MAX_FRAME_BYTES) {
      this.buffer = ''
      const pending = this.pending
      if (pending) {
        this.pending = null
        if (this.frameTimer) clearTimeout(this.frameTimer)
        this.frameTimer = null
        pending.resolve(null)
      }
      this.onFailure('process list response exceeded the safety limit')
      return
    }

    let match: RegExpExecArray | null
    while ((match = FRAME_RE.exec(this.buffer))) {
      this.buffer = this.buffer.slice(match.index + match[0].length)
      const seq = Number(match[1])
      if (!this.pending || this.pending.seq !== seq) continue
      if (this.frameTimer) clearTimeout(this.frameTimer)
      this.frameTimer = null
      const { resolve } = this.pending
      this.pending = null
      resolve(match[2])
      return
    }
  }

  private onFailure(reason: string): void {
    if (this.stopped) return
    this.failures += 1
    log.debug(`session ${this.sessionId}: process panel failed (${this.failures}) ${reason}`)
    if (this.failures >= MAX_CONSECUTIVE_FAILURES) {
      this.pause()
      this.setState('failed', reason)
    }
  }

  private onChannelClosed(channel: ClientChannel): void {
    if (this.channel !== channel) return
    this.channel = null
    this.pause()
    // 在途帧永远等不到应答了，就地收尾 —— 否则 pending 一直占着，tick() 全被挡掉
    if (this.pending) {
      const { resolve } = this.pending
      this.pending = null
      resolve(null)
    }
    if (this.stopped) return
    // 通道被杀（如用户在进程面板里误杀了常驻 sh）：5s 后重建。
    // 整条 SSH 连接断了的情况由 ProcessPanelManager.reattach 在重连成功后驱动。
    // 对齐 MonitorCollector.onChannelClosed，避免单条 exec 通道死后面板永久假死。
    log.debug(`session ${this.sessionId}: process panel channel closed, retrying in 5s`)
    setTimeout(() => {
      if (this.stopped || this.channel) return
      void this.restart()
    }, 5000)
  }

  private async restart(): Promise<void> {
    try {
      this.buffer = ''
      await this.open()
    } catch (err) {
      this.onFailure(err instanceof Error ? err.message : String(err))
    }
  }
}

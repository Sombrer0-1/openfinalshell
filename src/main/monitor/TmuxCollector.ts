import type { ClientChannel } from 'ssh2'
import type { SessionId, TmuxPanelState, TmuxSessionInfo } from '@shared/types'
import { TMUX_FRAME_TIMEOUT_MS, TMUX_MAX_FRAME_BYTES, TMUX_POLL_INTERVAL_MS } from '@shared/constants'
import { t } from '../services/i18n'
import { scopedLogger } from '../utils/logger'
import { buildTmuxListFrame, buildTmuxProbeFrame } from './tmuxScript'
import { splitSections } from './script'

const log = scopedLogger('tmux')
const FRAME_RE = /@@OFS:BEGIN:(\d+)@@\r?\n([\s\S]*?)@@OFS:END:\1@@\r?\n?/
const MAX_CONSECUTIVE_FAILURES = 3
/** tmux 内部会话 id 的形状（`$0`、`$1`…）；不匹配的行直接丢弃。 */
export const TMUX_SESSION_ID_RE = /^\$\d+$/

export interface TmuxSnapshot {
  ts: number
  sessions: TmuxSessionInfo[]
  /** 全局 mouse 选项值；server 未运行（合法空态）时无值。 */
  mouseOn?: boolean
}

export interface TmuxCallbacks {
  onSnapshot: (snapshot: TmuxSnapshot) => void
  onState: (state: TmuxPanelState, error?: string) => void
}

/**
 * 解析 `tmux list-sessions -F 'id|windows|attached|created|name'` 的正文。
 * 远端输出不可信：id 不合 `$N` 形状、数值列非法的行丢弃；名称吸收第 4 个
 * 分隔符之后的所有内容，保留名称中的 `|`。
 */
export function parseTmuxSessions(text: string): TmuxSessionInfo[] {
  const out: TmuxSessionInfo[] = []
  for (const raw of text.split('\n')) {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw
    if (!line) continue
    const parts = line.split('|')
    if (parts.length < 5) continue
    const [id, windows, attached, created] = parts
    if (!TMUX_SESSION_ID_RE.test(id)) continue
    const win = Number(windows)
    const att = Number(attached)
    const cre = Number(created)
    if (!Number.isInteger(win) || win < 1) continue
    if (!Number.isInteger(att) || att < 0) continue
    if (!Number.isInteger(cre) || cre < 0) continue
    out.push({
      id,
      name: parts.slice(4).join('|').slice(0, 256),
      windows: win,
      attached: att,
      created: cre
    })
  }
  // 按名称稳定排序（刷新不跳行）；同名时按 id 定序，保证全序确定。
  return out.sort((a, b) =>
    a.name < b.name ? -1 : a.name > b.name ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0
  )
}

/** 解析 MOUSE 段：`on` / `off`；空正文（无 server）返回 undefined。 */
export function parseTmuxMouse(text: string): boolean | undefined {
  const v = text.trim()
  if (v === 'on') return true
  if (v === 'off') return false
  return undefined
}

/**
 * 打开 tmux 面板标签后才运行的独立采集器（与 ProcessPanelCollector 同构）。
 * 首帧探测 tmux 是否存在（no → unsupported），之后每 TMUX_POLL_INTERVAL_MS 一帧列表；
 * ERROR 段出现即 failed（透出前三行错误），no-server 是合法空态（空列表）。
 */
export class TmuxCollector {
  private channel: ClientChannel | null = null
  private buffer = ''
  private seq = 0
  private timer: NodeJS.Timeout | null = null
  private frameTimer: NodeJS.Timeout | null = null
  private pending: { seq: number; resolve: (body: string | null) => void } | null = null
  private failures = 0
  private openGen = 0
  private stopped = false
  private probed = false
  state: TmuxPanelState = 'stopped'

  constructor(
    readonly sessionId: SessionId,
    private readonly openChannel: () => Promise<ClientChannel>,
    private readonly cb: TmuxCallbacks
  ) {}

  async start(): Promise<void> {
    this.stopped = false
    this.pause()
    await this.open()
  }

  /** 面板操作（新建 / 关闭 / 开鼠标 / 附着）之后由 TmuxManager 立即补一帧。 */
  refreshNow(): void {
    this.tick()
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
    // 在途守卫：通道死亡的 5s 重建与 SSH 重连驱动的 reattach 可能同时在途各调一次
    // open()。代际计数让后发起者胜出：先落地者发现自己已过期，就地关通道返回，
    // 不注册监听、不设定时器 —— 否则 timer 被覆盖泄漏一个轮询，通道成为孤儿。
    const gen = ++this.openGen
    this.pause()
    this.setState('collecting')
    let channel: ClientChannel
    try {
      channel = await this.openChannel()
    } catch (err) {
      if (this.stopped || gen !== this.openGen) return
      throw err
    }
    // open() 在途守卫：等待通道期间被 stop()，就地关掉，不再注册
    if (this.stopped || gen !== this.openGen) {
      channel.close()
      return
    }
    this.channel = channel
    channel.on('data', (chunk: Buffer) => this.onData(chunk))
    channel.stderr.on('data', () => {
      /* tmux 的权限 / socket 问题按 ERROR 段和帧超时处理 */
    })
    channel.on('close', () => this.onChannelClosed(channel))
    this.timer = setInterval(() => this.tick(), TMUX_POLL_INTERVAL_MS)
    this.tick()
  }

  private pause(): void {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    if (this.frameTimer) clearTimeout(this.frameTimer)
    this.frameTimer = null
  }

  private setState(state: TmuxPanelState, error?: string): void {
    if (this.state === state && error === undefined) return
    this.state = state
    this.cb.onState(state, error)
  }

  private tick(): void {
    if (this.stopped || !this.channel || this.pending) return
    this.seq += 1
    const seq = this.seq
    const script = this.probed ? buildTmuxListFrame(seq) : buildTmuxProbeFrame(seq)
    void this.request(script, seq).then((body) => {
      if (body === null) {
        this.onFailure(t('err.net.collectTimeout'))
        return
      }
      const sections = splitSections(body)
      if (!this.probed) {
        if ((sections.get('HASTMUX') ?? '').includes('yes')) {
          this.probed = true
          // 探测通过：不等下一个 2s tick，立即补首帧列表，面板尽快有数据
          this.tick()
        } else {
          this.pause()
          this.setState('unsupported')
        }
        return
      }
      // ERROR 段 = 采集本身出错（权限 / socket / 配置损坏），透出前三行、面板置 failed。
      // 与 no-server 不同：no-server 走空正文，面板显示空列表加新建入口。
      if (sections.has('ERROR')) {
        this.pause()
        this.setState('failed', (sections.get('ERROR') ?? '').trim())
        return
      }
      try {
        const sessions = parseTmuxSessions(sections.get('SESSIONS') ?? '')
        const mouseOn = parseTmuxMouse(sections.get('MOUSE') ?? '')
        this.cb.onSnapshot({ ts: Date.now(), sessions, mouseOn })
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
      }, TMUX_FRAME_TIMEOUT_MS)
      this.channel.write(script)
    })
  }

  private onData(chunk: Buffer): void {
    this.buffer += chunk.toString('utf8')
    if (Buffer.byteLength(this.buffer, 'utf8') > TMUX_MAX_FRAME_BYTES) {
      this.buffer = ''
      const pending = this.pending
      if (pending) {
        this.pending = null
        if (this.frameTimer) clearTimeout(this.frameTimer)
        this.frameTimer = null
        pending.resolve(null)
      }
      this.onFailure('tmux list response exceeded the safety limit')
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
    log.debug(`session ${this.sessionId}: tmux panel failed (${this.failures}) ${reason}`)
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
    // 通道被杀（如用户附着后误杀了常驻 sh）：5s 后重建。
    // 整条 SSH 连接断了的情况由 TmuxManager.reattach 在重连成功后驱动。
    log.debug(`session ${this.sessionId}: tmux panel channel closed, retrying in 5s`)
    this.scheduleRestart()
  }

  /** 5s 后重建；open() 抛错也走这里重排，连续 MAX_CONSECUTIVE_FAILURES 次后由 onFailure 置 failed。 */
  private scheduleRestart(): void {
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
      // 不排下一次重试的话 collector 会停在 collecting 且无任何定时器，
      // 从未收到首帧的面板没有恢复入口（有快照的还能点刷新）。
      if (!this.stopped && this.failures < MAX_CONSECUTIVE_FAILURES) this.scheduleRestart()
    }
  }
}

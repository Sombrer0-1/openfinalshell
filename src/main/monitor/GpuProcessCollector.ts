import type { ClientChannel } from 'ssh2'
import type { GpuProcessSnapshot, GpuProcessState, SessionId } from '@shared/types'
import {
  GPU_PROCESS_FRAME_TIMEOUT_MS,
  GPU_PROCESS_INTERVAL_MS,
  GPU_PROCESS_MAX_FRAME_BYTES
} from '@shared/constants'
import { t } from '../services/i18n'
import { scopedLogger } from '../utils/logger'
import { assembleGpuProcesses } from './gpuProcessParse'
import { buildGpuProcessFrame } from './gpuProcessScript'
import { splitSections } from './script'

const log = scopedLogger('gpu-process')
const FRAME_RE = /@@OFS:BEGIN:(\d+)@@\r?\n([\s\S]*?)@@OFS:END:\1@@\r?\n?/
const MAX_CONSECUTIVE_FAILURES = 3

export interface GpuProcessCallbacks {
  onSnapshot: (snapshot: GpuProcessSnapshot) => void
  onState: (state: GpuProcessState, error?: string) => void
}

/**
 * 打开 GPU 进程页后才运行。不复用右侧监控通道，pmon 的两拍采样不会拖慢 CPU 和磁盘。
 */
export class GpuProcessCollector {
  private channel: ClientChannel | null = null
  private buffer = ''
  private seq = 0
  private timer: NodeJS.Timeout | null = null
  private frameTimer: NodeJS.Timeout | null = null
  private pending: { seq: number; resolve: (body: string | null) => void } | null = null
  private failures = 0
  private openGen = 0
  private stopped = false
  state: GpuProcessState = 'stopped'

  constructor(
    readonly sessionId: SessionId,
    private readonly openChannel: () => Promise<ClientChannel>,
    private readonly cb: GpuProcessCallbacks
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
    const gen = ++this.openGen
    this.pause()
    this.setState('running')
    let channel: ClientChannel
    try {
      channel = await this.openChannel()
    } catch (err) {
      if (this.stopped || gen !== this.openGen) return
      throw err
    }
    if (this.stopped || gen !== this.openGen) {
      channel.close()
      return
    }
    this.channel = channel
    channel.on('data', (chunk: Buffer) => this.onData(chunk))
    channel.stderr.on('data', () => {
      /* nvidia-smi 的报错留在 stderr，正文按缺失处理 */
    })
    channel.on('close', () => this.onChannelClosed(channel))
    this.timer = setInterval(() => this.tick(), GPU_PROCESS_INTERVAL_MS)
    this.tick()
  }

  private pause(): void {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    if (this.frameTimer) clearTimeout(this.frameTimer)
    this.frameTimer = null
  }

  private setState(state: GpuProcessState, error?: string): void {
    if (this.state === state && error === undefined) return
    this.state = state
    this.cb.onState(state, error)
  }

  private tick(): void {
    if (this.stopped || !this.channel || this.pending) return
    this.seq += 1
    const seq = this.seq
    void this.request(buildGpuProcessFrame(seq), seq).then((body) => {
      if (body === null) {
        this.onFailure(t('err.net.collectTimeout'))
        return
      }
      const sections = splitSections(body)
      if (!(sections.get('HASGPU') ?? '').includes('nvidia')) {
        this.pause()
        this.setState('unsupported')
        return
      }
      try {
        this.cb.onSnapshot({
          ts: Date.now(),
          gpus: assembleGpuProcesses(
            sections.get('GPUDEV') ?? '',
            sections.get('GPUAPPS') ?? '',
            sections.get('GPUMON') ?? '',
            sections.get('GPUUSER') ?? ''
          )
        })
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
      }, GPU_PROCESS_FRAME_TIMEOUT_MS)
      this.channel.write(script)
    })
  }

  private onData(chunk: Buffer): void {
    this.buffer += chunk.toString('utf8')
    if (Buffer.byteLength(this.buffer, 'utf8') > GPU_PROCESS_MAX_FRAME_BYTES) {
      this.buffer = ''
      const pending = this.pending
      if (pending) {
        this.pending = null
        if (this.frameTimer) clearTimeout(this.frameTimer)
        this.frameTimer = null
        pending.resolve(null)
      }
      this.onFailure('gpu process response exceeded the safety limit')
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
    log.debug(`session ${this.sessionId}: gpu process failed (${this.failures}) ${reason}`)
    if (this.failures >= MAX_CONSECUTIVE_FAILURES) {
      this.pause()
      this.setState('failed', reason)
    }
  }

  private onChannelClosed(channel: ClientChannel): void {
    if (this.channel !== channel) return
    this.channel = null
    this.pause()
    if (this.pending) {
      const { resolve } = this.pending
      this.pending = null
      resolve(null)
    }
    if (!this.stopped && this.state === 'running') this.onFailure('gpu process channel closed')
  }
}

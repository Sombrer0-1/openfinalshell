import type { SessionId, TermId } from '@shared/types'
import { TMUX_SESSION_NAME_MAX } from '@shared/constants'
import { instanceResource } from '../instance'
import { emit } from '../ipc/registry'
import { execOnce } from '../ssh/ExecRunner'
import { sshManager } from '../ssh/SshConnectionManager'
import { t } from '../services/i18n'
import { TmuxCollector, TMUX_SESSION_ID_RE } from './TmuxCollector'
import {
  buildTmuxHasSessionScript,
  buildTmuxKillSessionScript,
  buildTmuxNewSessionScript,
  buildTmuxSetMouseScript
} from './tmuxScript'

/** tmux 操作（new / attach / kill）的统一超时：都是毫秒级命令，10s 足够宽。 */
const TMUX_OP_TIMEOUT_MS = 10_000

function assertTmuxName(name: string): void {
  if (
    name.length >= 1 &&
    name.length <= TMUX_SESSION_NAME_MAX &&
    !name.startsWith('-') &&
    !/[\u0000-\u001f\u007f]/.test(name)
  ) {
    return
  }
  throw new Error(t('err.net.tmuxNameInvalid', { max: TMUX_SESSION_NAME_MAX }))
}

function assertTmuxTargetId(targetId: string): void {
  // targetId 只来自 main 自己发出去的列表（IPC 层另有 zod 正则卡形状），
  // 走到这里说明是调用方 bug，不是用户输入 —— 不需要本地化的用户文案。
  if (!TMUX_SESSION_ID_RE.test(targetId)) throw new Error('invalid tmux session id')
}

/** sessionId → 按需存在的 tmux 采集器；关闭面板标签或 SSH 会话时立刻释放。 */
class TmuxManager {
  private readonly collectors = new Map<SessionId, TmuxCollector>()

  async start(sessionId: SessionId): Promise<void> {
    const existing = this.collectors.get(sessionId)
    if (existing) {
      // collecting 与 running 都是活跃态：面板已开且在采，直接复用
      if (existing.state === 'running' || existing.state === 'collecting') return
      this.stop(sessionId)
    }
    const conn = sshManager.get(sessionId)
    const collector = new TmuxCollector(sessionId, () => conn.openMonitorChannel(), {
      onSnapshot: (snapshot) => emit('tmux:sessions', { sessionId, ...snapshot }),
      onState: (state, error) => emit('tmux:state', { sessionId, state, error })
    })
    this.collectors.set(sessionId, collector)
    try {
      await collector.start()
    } catch (err) {
      this.collectors.delete(sessionId)
      collector.stop()
      const message = err instanceof Error ? err.message : String(err)
      // stop() 会先推 stopped；再推 failed，避免渲染层停在「正在采集」无重试出口。
      emit('tmux:state', { sessionId, state: 'failed', error: message })
      throw err
    }
  }

  async reattach(sessionId: SessionId): Promise<void> {
    await this.collectors.get(sessionId)?.reattach()
  }

  stop(sessionId: SessionId): void {
    const collector = this.collectors.get(sessionId)
    if (!collector) return
    this.collectors.delete(sessionId)
    collector.stop()
  }

  stopAll(): void {
    for (const sessionId of [...this.collectors.keys()]) this.stop(sessionId)
  }

  /** 新建会话；成功返回新会话的 session_id（`$N`），同名冲突抛 duplicateSession 文案。 */
  async newSession(sessionId: SessionId, name: string): Promise<{ id: string }> {
    assertTmuxName(name)
    const conn = sshManager.get(sessionId)
    const result = await execOnce(conn, buildTmuxNewSessionScript(name), {
      timeoutMs: TMUX_OP_TIMEOUT_MS
    })
    if (result.code === 0) {
      const id = (result.stdout.split('\n', 1)[0] ?? '').trim()
      // rc=0 但 stdout 形状不对（如粘了欢迎语）：不当成功，让用户看重试而不是拿假 id 去附着
      if (TMUX_SESSION_ID_RE.test(id)) {
        this.poke(sessionId)
        return { id }
      }
      throw new Error(t('err.net.tmuxNewFailed', { reason: 'unexpected output' }))
    }
    const reason = (result.stderr || result.stdout || `exit ${result.code ?? 'null'}`).trim()
    if (/duplicate session/i.test(reason)) {
      throw new Error(t('err.net.duplicateSession', { name }))
    }
    throw new Error(t('err.net.tmuxNewFailed', { reason: reason.slice(0, 200) || 'unknown' }))
  }

  /** 关闭（kill）会话。目标已消失映射为 sessionGone —— 面板下一帧会移除该行。 */
  async killSession(sessionId: SessionId, targetId: string): Promise<void> {
    assertTmuxTargetId(targetId)
    const conn = sshManager.get(sessionId)
    const result = await execOnce(conn, buildTmuxKillSessionScript(targetId), {
      timeoutMs: TMUX_OP_TIMEOUT_MS
    })
    if (result.code === 0) {
      this.poke(sessionId)
      return
    }
    const reason = (result.stderr || result.stdout || `exit ${result.code ?? 'null'}`).trim()
    if (/can't find session/i.test(reason) || /no such session/i.test(reason)) {
      throw new Error(t('err.net.sessionGone'))
    }
    throw new Error(
      t('err.net.tmuxKillFailed', { reason: reason.slice(0, 200) || 'unknown' })
    )
  }

  /** 开启 / 关闭全局鼠标支持（提示条的一键开启）。 */
  async setMouse(sessionId: SessionId, enabled: boolean): Promise<void> {
    const conn = sshManager.get(sessionId)
    const result = await execOnce(conn, buildTmuxSetMouseScript(enabled), {
      timeoutMs: TMUX_OP_TIMEOUT_MS
    })
    if (result.code === 0) {
      this.poke(sessionId)
      return
    }
    const reason = (result.stderr || result.stdout || `exit ${result.code ?? 'null'}`).trim()
    throw new Error(
      t('err.net.tmuxSetMouseFailed', { reason: reason.slice(0, 200) || 'unknown' })
    )
  }

  /** 附着前检查。0 = 存在，1 = 不存在；其余（含 null = 断连）是错误而非「不存在」。 */
  async hasSession(sessionId: SessionId, targetId: string): Promise<boolean> {
    assertTmuxTargetId(targetId)
    const conn = sshManager.get(sessionId)
    const result = await execOnce(conn, buildTmuxHasSessionScript(targetId), {
      timeoutMs: TMUX_OP_TIMEOUT_MS
    })
    if (result.code === 0) return true
    if (result.code === 1) return false
    const reason = (result.stderr || result.stdout || `exit ${result.code ?? 'null'}`).trim()
    throw new Error(t('err.net.tmuxHasFailed', { reason: reason.slice(0, 200) || 'unknown' }))
  }

  /**
   * 打开附着通道：先 has-session 检查（命中失败 → sessionGone），再经
   * sshManager.openTmuxAttach 走 exec + pty 的 ShellSession（注册进 shells/terms）。
   * 检查通过但 attach 通道仍异步失败（进程启动后会话才消失）时，tmux 原始报错
   * 直接显示在终端里 —— 不伪装成同步成功（方案 §4）。
   */
  async attach(
    sessionId: SessionId,
    targetId: string,
    cols: number,
    rows: number
  ): Promise<{ termId: TermId }> {
    assertTmuxTargetId(targetId)
    if (!(await this.hasSession(sessionId, targetId))) {
      throw new Error(t('err.net.sessionGone'))
    }
    const { termId } = await sshManager.openTmuxAttach(sessionId, targetId, cols, rows)
    this.poke(sessionId)
    return { termId }
  }

  /** 操作后立即补一帧刷新（面板在 2s 轮询之外尽快看到新状态）。 */
  private poke(sessionId: SessionId): void {
    this.collectors.get(sessionId)?.refreshNow()
  }
}

export const tmuxManager = instanceResource('tmuxManager', () => new TmuxManager())

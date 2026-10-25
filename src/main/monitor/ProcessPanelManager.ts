import type { ProcessDetail, SessionId } from '@shared/types'
import { instanceResource } from '../instance'
import { emit } from '../ipc/registry'
import { execOnce } from '../ssh/ExecRunner'
import { sshManager } from '../ssh/SshConnectionManager'
import { t } from '../services/i18n'
import { ProcessPanelCollector } from './ProcessPanelCollector'
import { buildProcessDetailScript, buildProcessKillScript } from './processPanelScript'
import { splitSections } from './script'

function assertPid(pid: number): void {
  if (!Number.isInteger(pid) || pid <= 0 || !Number.isSafeInteger(pid)) {
    throw new Error(t('err.net.invalidPid'))
  }
}

/** sessionId → 按需存在的进程采集器；关闭进程标签或 SSH 会话时立刻释放。 */
class ProcessPanelManager {
  private readonly collectors = new Map<SessionId, ProcessPanelCollector>()

  async start(sessionId: SessionId): Promise<void> {
    const existing = this.collectors.get(sessionId)
    if (existing) {
      if (existing.state === 'running') return
      this.stop(sessionId)
    }
    const conn = sshManager.get(sessionId)
    const collector = new ProcessPanelCollector(sessionId, () => conn.openMonitorChannel(), {
      onSnapshot: (snapshot) => emit('processPanel:data', { sessionId, snapshot }),
      onState: (state, error) => emit('processPanel:state', { sessionId, state, error })
    })
    this.collectors.set(sessionId, collector)
    try {
      await collector.start()
    } catch (err) {
      this.collectors.delete(sessionId)
      collector.stop()
      const message = err instanceof Error ? err.message : String(err)
      // stop() 会先推 stopped；再推 failed，避免渲染层停在「正在采集」无重试出口。
      emit('processPanel:state', { sessionId, state: 'failed', error: message })
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

  async detail(sessionId: SessionId, pid: number): Promise<ProcessDetail | null> {
    assertPid(pid)
    const conn = sshManager.get(sessionId)
    const result = await execOnce(conn, buildProcessDetailScript(pid), { timeoutMs: 5000 })
    if (result.code === 3) return null
    // null = 未知退出码（哨兵缺失/断连），永不当成功 —— 对齐 ExecRunner 契约。
    if (result.code !== 0) return null
    const sections = splitSections(
      // detail 脚本没有 BEGIN/END 帧，只有段哨兵；给 splitSections 包一层假段也行，
      // 但段哨兵本身就能切开 —— 在正文前补一个哑段名无必要，直接喂即可。
      result.stdout
    )
    const name = (sections.get('NAME') ?? '').trim()
    const location = (sections.get('LOC') ?? '').trim()
    const cwd = (sections.get('CWD') ?? '').trim()
    const cmdline = (sections.get('CMDLINE') ?? '').trim()
    const env = (sections.get('ENV') ?? '').replace(/\0/g, '\n').trim()
    return {
      pid,
      name: name.slice(0, 256),
      location: location.slice(0, 4096),
      cwd: cwd.slice(0, 4096),
      cmdline: cmdline.slice(0, 4000),
      env: env.slice(0, 64 * 1024)
    }
  }

  async kill(sessionId: SessionId, pid: number, force = false): Promise<void> {
    assertPid(pid)
    const conn = sshManager.get(sessionId)
    const result = await execOnce(conn, buildProcessKillScript(pid, force), { timeoutMs: 5000 })
    if (result.code === 0) return
    if (result.code === 3) {
      throw new Error(t('err.net.processGone', { pid }))
    }
    const reason = (result.stderr || result.stdout || `exit ${result.code ?? 'null'}`).trim().slice(0, 200)
    throw new Error(t('err.net.killFailed', { pid, reason: reason || 'unknown' }))
  }
}

export const processPanelManager = instanceResource('processPanelManager', () => new ProcessPanelManager())

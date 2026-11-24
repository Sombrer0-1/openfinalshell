import type { SessionId } from '@shared/types'
import { instanceResource } from '../instance'
import { emit } from '../ipc/registry'
import { sshManager } from '../ssh/SshConnectionManager'
import { GpuProcessCollector } from './GpuProcessCollector'

/** sessionId → 打开 GPU 进程页期间存在的采集器。 */
class GpuProcessManager {
  private readonly collectors = new Map<SessionId, GpuProcessCollector>()

  async start(sessionId: SessionId): Promise<void> {
    const existing = this.collectors.get(sessionId)
    if (existing) {
      if (existing.state === 'running') return
      this.stop(sessionId)
    }
    const conn = sshManager.get(sessionId)
    const collector = new GpuProcessCollector(sessionId, () => conn.openMonitorChannel(), {
      onSnapshot: (snapshot) => emit('gpuProcess:data', { sessionId, snapshot }),
      onState: (state, error) => emit('gpuProcess:state', { sessionId, state, error })
    })
    this.collectors.set(sessionId, collector)
    try {
      await collector.start()
    } catch (err) {
      this.collectors.delete(sessionId)
      collector.stop()
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
}

export const gpuProcessManager = instanceResource('gpuProcessManager', () => new GpuProcessManager())

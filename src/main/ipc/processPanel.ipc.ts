import { z } from 'zod'
import { handle } from './registry'
import { processPanelManager } from '../monitor/ProcessPanelManager'

export function registerProcessPanelIpc(): void {
  handle('processPanel:start', (sessionId) => processPanelManager.start(sessionId), z.tuple([z.string()]))
  handle('processPanel:stop', (sessionId) => processPanelManager.stop(sessionId), z.tuple([z.string()]))
  handle(
    'processPanel:detail',
    ({ sessionId, pid }) => processPanelManager.detail(sessionId, pid),
    z.tuple([z.object({ sessionId: z.string(), pid: z.number().int().positive() })])
  )
  handle(
    'processPanel:kill',
    ({ sessionId, pid, force }) => processPanelManager.kill(sessionId, pid, force === true),
    z.tuple([
      z.object({
        sessionId: z.string(),
        pid: z.number().int().positive(),
        force: z.boolean().optional()
      })
    ])
  )
}

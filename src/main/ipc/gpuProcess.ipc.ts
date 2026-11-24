import { z } from 'zod'
import { handle } from './registry'
import { gpuProcessManager } from '../monitor/GpuProcessManager'

export function registerGpuProcessIpc(): void {
  handle('gpuProcess:start', (sessionId) => gpuProcessManager.start(sessionId), z.tuple([z.string()]))
  handle('gpuProcess:stop', (sessionId) => gpuProcessManager.stop(sessionId), z.tuple([z.string()]))
}

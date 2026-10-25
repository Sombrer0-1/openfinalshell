import { z } from 'zod'
import { TMUX_SESSION_NAME_MAX } from '@shared/constants'
import { handle } from './registry'
import { tmuxManager } from '../monitor/TmuxManager'

/** 与 term:open / term:resize 共用同一份尺寸约束 */
const sizeSchema = z.object({
  cols: z.number().int().min(2).max(1000),
  rows: z.number().int().min(1).max(500)
})

/** 名称入参约束（方案 §4）：非空、长度 ≤ 64、不含控制字符、不以 `-` 开头。 */
const nameSchema = z
  .string()
  .min(1)
  .max(TMUX_SESSION_NAME_MAX)
  .refine((v) => !v.startsWith('-'), 'no leading dash')
  .refine((v) => !/[\u0000-\u001f\u007f]/.test(v), 'no control chars')

/** 操作目标只认 session_id（`$N`）；名称只作显示，不作为寻址键。 */
const targetIdSchema = z.string().regex(/^\$\d+$/)

export function registerTmuxIpc(): void {
  handle('tmux:start', (sessionId) => tmuxManager.start(sessionId), z.tuple([z.string()]))
  handle('tmux:stop', (sessionId) => tmuxManager.stop(sessionId), z.tuple([z.string()]))
  handle(
    'tmux:new',
    ({ sessionId, name }) => tmuxManager.newSession(sessionId, name),
    z.tuple([z.object({ sessionId: z.string(), name: nameSchema })])
  )
  handle(
    'tmux:kill',
    ({ sessionId, targetId }) => tmuxManager.killSession(sessionId, targetId),
    z.tuple([z.object({ sessionId: z.string(), targetId: targetIdSchema })])
  )
  handle(
    'tmux:setMouse',
    ({ sessionId, enabled }) => tmuxManager.setMouse(sessionId, enabled),
    z.tuple([z.object({ sessionId: z.string(), enabled: z.boolean() })])
  )
  handle(
    'tmux:attach',
    ({ sessionId, targetId, cols, rows }) => tmuxManager.attach(sessionId, targetId, cols, rows),
    z.tuple([sizeSchema.extend({ sessionId: z.string(), targetId: targetIdSchema })])
  )
}

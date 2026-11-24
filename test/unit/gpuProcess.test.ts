import { describe, expect, it } from 'vitest'
import { assembleGpuProcesses } from '../../src/main/monitor/gpuProcessParse'
import { buildGpuProcessFrame } from '../../src/main/monitor/gpuProcessScript'

const DEVICES = [
  '0, GPU-aaa, NVIDIA GeForce RTX 4090, 86, 18000, 24564, 71',
  '1, GPU-bbb, NVIDIA A100, [N/A], 8200, 40960, 64'
].join('\n')

const APPS = [
  'GPU-aaa, 4201, python, 12288',
  'GPU-bbb, 4201, "python train.py", 6144',
  'GPU-missing, 9, ghost, 1'
].join('\n')

const USERS = '4201 demo\n8812 root\nPID USER'

const PMON = `
# gpu         pid   type     sm    command
    0       4201     C      -      python
    0       4201     C     62      python
    1       4201     C     38      python
    0       8812     G      4      Xorg
    0       4201     C      -      python
`.trim()

describe('gpu process assembly', () => {
  it('keeps the same process under each GPU it occupies', () => {
    const gpus = assembleGpuProcesses(DEVICES, APPS, PMON, USERS)
    expect(gpus.map((gpu) => gpu.index)).toEqual([0, 1])
    expect(gpus[0].utilPct).toBe(86)
    expect(gpus[1].utilPct).toBeNull()
    expect(gpus[0].processes.map((proc) => [proc.pid, proc.utilPct, proc.memBytes])).toEqual([
      [4201, 62, 12288 * 1024 * 1024],
      [8812, 4, null]
    ])
    expect(gpus[1].processes).toEqual([
      { pid: 4201, user: 'demo', name: 'python train.py', utilPct: 38, memBytes: 6144 * 1024 * 1024 }
    ])
    expect(gpus[0].processes.map((proc) => proc.user)).toEqual(['demo', 'root'])
  })

  it('uses a later numeric sample and ignores a dash after it', () => {
    const gpus = assembleGpuProcesses(DEVICES, '', PMON)
    expect(gpus[0].processes.find((proc) => proc.pid === 4201)?.utilPct).toBe(62)
    expect(gpus[0].processes.find((proc) => proc.pid === 8812)?.memBytes).toBeNull()
    expect(gpus[0].processes.find((proc) => proc.pid === 8812)?.user).toBe('')
  })

  it('returns no groups when nvidia-smi listed no devices', () => {
    expect(assembleGpuProcesses('', APPS, PMON)).toEqual([])
  })

  it('asks for devices, compute apps and two pmon samples in separate sections', () => {
    const frame = buildGpuProcessFrame(3)
    for (const name of ['HASGPU', 'GPUDEV', 'GPUAPPS', 'GPUMON', 'GPUUSER']) {
      expect(frame).toContain(`@@OFS:${name}@@`)
    }
    expect(frame).toContain('@@OFS:BEGIN:3@@')
    expect(frame).toContain('pmon -c 2 -d 1 -s u')
    expect(frame).toContain('ps -o pid=,user= -p "$ofs_gpu_pids"')
    expect(frame).not.toContain('utilization.memory')
  })
})

import type { GpuProcess, GpuProcessGroup } from '@shared/types'

interface DeviceRow {
  index: number
  uuid: string
  name: string
  utilPct: number | null
  memUsedMiB: number | null
  memTotalMiB: number | null
  tempC: number | null
}

interface AppRow {
  uuid: string
  pid: number
  name: string
  memMiB: number | null
}

interface PmonRow {
  index: number
  pid: number
  name: string
  utilPct: number | null
}

function splitCsv(line: string): string[] {
  const out: string[] = []
  let cur = ''
  let quoted = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (quoted) {
      if (ch === '"') {
        if (line[i + 1] === '"') {
          cur += '"'
          i++
        } else quoted = false
      } else cur += ch
    } else if (ch === '"') quoted = true
    else if (ch === ',') {
      out.push(cur.trim())
      cur = ''
    } else cur += ch
  }
  out.push(cur.trim())
  return out
}

function metric(raw: string | undefined): number | null {
  if (!raw || /N\/A|Not Supported/i.test(raw)) return null
  const value = Number(raw)
  if (!Number.isFinite(value) || value < 0) return null
  return value
}

function utilOf(raw: string | undefined): number | null {
  const value = metric(raw)
  return value == null ? null : Math.min(100, Number(value.toFixed(1)))
}

function parseDevices(text: string): DeviceRow[] {
  const out: DeviceRow[] = []
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const fields = splitCsv(line)
    const index = Number(fields[0])
    if (!Number.isInteger(index) || index < 0) continue
    out.push({
      index,
      uuid: fields[1] || '',
      name: fields[2] || `GPU ${index}`,
      utilPct: utilOf(fields[3]),
      memUsedMiB: metric(fields[4]),
      memTotalMiB: metric(fields[5]),
      tempC: metric(fields[6])
    })
  }
  return out
}

function parseApps(text: string): AppRow[] {
  const out: AppRow[] = []
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const fields = splitCsv(line)
    const pid = Number(fields[1])
    if (!fields[0] || !Number.isInteger(pid) || pid <= 0) continue
    out.push({
      uuid: fields[0],
      pid,
      name: fields[2] && fields[2] !== '[Not Found]' ? fields[2] : '',
      memMiB: metric(fields[3])
    })
  }
  return out
}

/** pmon 第一拍的占用是 `-`。后一拍的数字覆盖它，`-` 不把已有数字清掉。 */
function parsePmon(text: string): PmonRow[] {
  const byKey = new Map<string, PmonRow>()
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const fields = line.split(/\s+/)
    if (fields.length < 4) continue
    const index = Number(fields[0])
    const pid = Number(fields[1])
    if (!Number.isInteger(index) || index < 0 || !Number.isInteger(pid) || pid <= 0) continue
    const key = `${index}:${pid}`
    const prev = byKey.get(key)
    const utilPct = fields[3] === '-' ? null : utilOf(fields[3])
    const name = fields[fields.length - 1] || ''
    byKey.set(key, {
      index,
      pid,
      name: name && name !== '-' ? name : prev?.name || '',
      utilPct: utilPct ?? prev?.utilPct ?? null
    })
  }
  return [...byKey.values()]
}

function memBytes(mib: number | null): number | null {
  return mib == null ? null : Math.round(mib * 1024 * 1024)
}

/** `ps -o pid=,user=` 或 `/proc` 回落，一行一个 `pid user`。查不到的进程保持空用户。 */
function parseUsers(text: string): Map<number, string> {
  const out = new Map<number, string>()
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const [pidText, user] = line.split(/\s+/)
    const pid = Number(pidText)
    if (!Number.isInteger(pid) || pid <= 0 || !user || user === 'USER' || user.length > 64) continue
    out.set(pid, user)
  }
  return out
}

/**
 * 把三份 nvidia-smi 输出合成按卡分组的进程表。
 * 设备列表决定有哪些卡；进程只挂到这些卡上。
 */
export function assembleGpuProcesses(
  devicesText: string,
  appsText: string,
  pmonText: string,
  usersText = ''
): GpuProcessGroup[] {
  const devices = parseDevices(devicesText)
  const users = parseUsers(usersText)
  const byUuid = new Map(devices.filter((device) => device.uuid).map((device) => [device.uuid, device]))
  const byIndex = new Map(devices.map((device) => [device.index, device]))
  const procs = new Map<string, GpuProcess & { index: number }>()

  for (const app of parseApps(appsText)) {
    const device = byUuid.get(app.uuid)
    if (!device) continue
    procs.set(`${device.index}:${app.pid}`, {
      index: device.index,
      pid: app.pid,
      user: users.get(app.pid) ?? '',
      name: app.name || String(app.pid),
      utilPct: null,
      memBytes: memBytes(app.memMiB)
    })
  }

  for (const sample of parsePmon(pmonText)) {
    if (!byIndex.has(sample.index)) continue
    const key = `${sample.index}:${sample.pid}`
    const existing = procs.get(key)
    if (!existing) {
      procs.set(key, {
        index: sample.index,
        pid: sample.pid,
        user: users.get(sample.pid) ?? '',
        name: sample.name || String(sample.pid),
        utilPct: sample.utilPct,
        memBytes: null
      })
      continue
    }
    if (sample.utilPct != null) existing.utilPct = sample.utilPct
    if (sample.name && existing.name === String(existing.pid)) existing.name = sample.name
  }

  return devices
    .sort((a, b) => a.index - b.index)
    .map((device) => ({
      index: device.index,
      name: device.name,
      utilPct: device.utilPct,
      memUsedMiB: device.memUsedMiB,
      memTotalMiB: device.memTotalMiB,
      tempC: device.tempC,
      processes: [...procs.values()]
        .filter((row) => row.index === device.index)
        .map((row) => ({ pid: row.pid, user: row.user, name: row.name, utilPct: row.utilPct, memBytes: row.memBytes }))
        .sort((a, b) => (b.memBytes ?? -1) - (a.memBytes ?? -1) || (b.utilPct ?? -1) - (a.utilPct ?? -1) || a.pid - b.pid)
    }))
}

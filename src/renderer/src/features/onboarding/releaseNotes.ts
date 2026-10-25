/**
 * 更新说明登记表：版本变化后开机弹窗展示本次提供的能力。
 *
 * 发版时在最前面加一条当前版本（有护栏钉死 package.json 的版本必须在表里）。
 * 文案双语内联，按当前界面语言取；其余语言回退英文。
 */

export interface ReleaseNoteItem {
  type: 'feat' | 'fix'
  zh: string
  en: string
}
export interface ReleaseNote {
  version: string
  items: ReleaseNoteItem[]
}

export const RELEASE_NOTES: ReleaseNote[] = [
  {
    version: '0.30.31',
    items: [
      {
        type: 'feat',
        zh: '提供 SSH 终端、SFTP 文件管理、嵌入式 RDP、远程编辑、服务器监控、端口转发、快捷命令和传输队列',
        en: 'Ship SSH terminals, SFTP file management, embedded RDP, remote editing, server monitoring, port forwarding, command snippets, and a transfer queue'
      },
      {
        type: 'feat',
        zh: 'Windows 可开启多个独立窗口，共享连接配置和凭据，会话与传输队列保持隔离',
        en: 'On Windows, open independent windows that share connection settings and credentials while keeping sessions and transfer queues isolated'
      },
      {
        type: 'feat',
        zh: '支持加密导出导入、局域网同步，以及绑定当前系统账户的凭据存储',
        en: 'Support encrypted export and import, LAN sync, and credential storage bound to the current system account'
      }
    ]
  }
]

export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map((x) => parseInt(x, 10) || 0)
  const pb = b.split('.').map((x) => parseInt(x, 10) || 0)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d !== 0) return d > 0 ? 1 : -1
  }
  return 0
}

/**
 * 取 (from, to] 之间的更新说明，按新→旧排列（跨版本更新能一次看全跳过的那些）。
 * from 缺省（正常 update 一定有）时只给 to 那条。
 */
export function notesSince(from: string | undefined, to: string): ReleaseNote[] {
  return RELEASE_NOTES.filter(
    (n) =>
      compareVersions(n.version, to) <= 0 &&
      (from ? compareVersions(n.version, from) > 0 : compareVersions(n.version, to) === 0)
  ).sort((a, b) => compareVersions(b.version, a.version))
}

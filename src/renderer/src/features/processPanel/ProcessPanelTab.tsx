import { useEffect, useMemo, useState } from 'react'
import { App as AntdApp, Button, Dropdown, Empty, Input } from 'antd'
import { RefreshCw } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { ProcessDetail, ProcessEntry } from '@shared/types'
import type { SessionTab } from '@/stores/useSessionStore'
import { useProcessPanelStore } from '@/stores/useProcessPanelStore'
import { formatBytes, formatTimestamp } from '@/utils/format'
import styles from './ProcessPanelTab.module.css'

interface Props {
  tab: SessionTab
}

type SortKey = 'pid' | 'user' | 'memBytes' | 'cpu' | 'name' | 'location'
type SortDir = 'asc' | 'desc'

function compareEntries(a: ProcessEntry, b: ProcessEntry, key: SortKey, dir: SortDir): number {
  const sign = dir === 'asc' ? 1 : -1
  switch (key) {
    case 'pid':
      return (a.pid - b.pid) * sign
    case 'user':
      return a.user.localeCompare(b.user) * sign || (a.pid - b.pid) * sign
    case 'memBytes':
      return (a.memBytes - b.memBytes) * sign || (a.pid - b.pid) * sign
    case 'cpu':
      return (a.cpu - b.cpu) * sign || (a.pid - b.pid) * sign
    case 'name': {
      const left = a.cmdline || a.name
      const right = b.cmdline || b.name
      return left.localeCompare(right) * sign || (a.pid - b.pid) * sign
    }
    case 'location':
      return a.location.localeCompare(b.location) * sign || (a.pid - b.pid) * sign
    default:
      return 0
  }
}

/** 绑定已有 SSH 会话的进程管理工具页，不打开终端或创建第二个登录会话。 */
export function ProcessPanelTab({ tab }: Props): React.JSX.Element {
  const { t } = useTranslation()
  const { message, modal } = AntdApp.useApp()
  const sessionId = tab.sessionId
  const snapshot = useProcessPanelStore((s) => (sessionId ? s.latest[sessionId] : undefined))
  const state = useProcessPanelStore((s) => (sessionId ? s.state[sessionId] : undefined))
  const error = useProcessPanelStore((s) => (sessionId ? s.error[sessionId] : undefined))
  const start = useProcessPanelStore((s) => s.start)
  const fetchDetail = useProcessPanelStore((s) => s.fetchDetail)
  const kill = useProcessPanelStore((s) => s.kill)

  const [sortKey, setSortKey] = useState<SortKey>('cpu')
  const [sortDir, setSortDir] = useState<SortDir>('desc')
  const [selectedPid, setSelectedPid] = useState<number | null>(null)
  const [detail, setDetail] = useState<ProcessDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; entry: ProcessEntry } | null>(null)

  useEffect(() => {
    if (!sessionId || tab.state !== 'ready') return
    void start(sessionId).catch(() => {})
  }, [sessionId, start, tab.state])

  useEffect(() => {
    if (!contextMenu) return
    const close = (): void => setContextMenu(null)
    window.addEventListener('click', close)
    window.addEventListener('blur', close)
    window.addEventListener('scroll', close, true)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('blur', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [contextMenu])

  useEffect(() => {
    if (!sessionId || selectedPid === null) {
      setDetail(null)
      return
    }
    let cancelled = false
    setDetailLoading(true)
    void fetchDetail(sessionId, selectedPid)
      .then((next) => {
        if (!cancelled) setDetail(next)
      })
      .catch(() => {
        if (!cancelled) setDetail(null)
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sessionId, selectedPid, fetchDetail, snapshot?.ts])

  const rows = useMemo(() => {
    const list = snapshot?.processes ?? []
    return [...list].sort((a, b) => compareEntries(a, b, sortKey, sortDir))
  }, [snapshot?.processes, sortKey, sortDir])

  const selected = rows.find((row) => row.pid === selectedPid) ?? null

  const toggleSort = (key: SortKey): void => {
    if (sortKey === key) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    else {
      setSortKey(key)
      setSortDir(key === 'name' || key === 'user' || key === 'location' ? 'asc' : 'desc')
    }
  }

  const sortLabel = (key: SortKey, label: string): React.JSX.Element => (
    <span className={styles.sortable}>
      {label}
      {sortKey === key ? <span className={styles.sortMark}>{sortDir === 'asc' ? '↑' : '↓'}</span> : null}
    </span>
  )

  const copyText = (value: string): void => {
    const text = value || ''
    const clipboard = navigator.clipboard
    if (!clipboard) {
      message.error(t('processPanel.copyFailed'))
      return
    }
    void clipboard.writeText(text).then(
      () => message.success(t('processPanel.copied')),
      () => message.error(t('processPanel.copyFailed'))
    )
  }

  const confirmKill = (entry: ProcessEntry, force: boolean): void => {
    if (!sessionId) return
    modal.confirm({
      title: force
        ? t('processPanel.forceKillTitle', { pid: entry.pid })
        : t('processPanel.terminateTitle', { pid: entry.pid }),
      content: force
        ? t('processPanel.forceKillContent', { pid: entry.pid, name: entry.name })
        : t('processPanel.terminateContent', { pid: entry.pid, name: entry.name }),
      okText: force ? t('processPanel.forceKillOk') : t('processPanel.terminateOk'),
      okButtonProps: { danger: true },
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          await kill(sessionId, entry.pid, force)
          message.success(t('processPanel.killed', { pid: entry.pid }))
          if (selectedPid === entry.pid) setSelectedPid(null)
        } catch (err) {
          message.error(
            t('processPanel.killFailed', { reason: err instanceof Error ? err.message : String(err) })
          )
        }
      }
    })
  }

  if (state === 'unsupported') {
    return (
      <div className={styles.empty}>
        <Empty description={t('processPanel.unsupported')} />
      </div>
    )
  }
  if (state === 'failed') {
    return (
      <div className={styles.empty}>
        <Empty description={error || t('processPanel.failed')}>
          <Button type="primary" icon={<RefreshCw size={15} />} onClick={() => sessionId && void start(sessionId)}>
            {t('common.retry')}
          </Button>
        </Empty>
      </div>
    )
  }
  if (!snapshot) {
    return <div className={styles.empty}>{t('processPanel.collecting')}</div>
  }

  const detailName = detail?.name || selected?.name || ''
  const detailLocation = detail?.location || selected?.location || ''
  const detailCmd = detail?.cmdline || selected?.cmdline || ''
  const detailCwd = detail?.cwd || ''
  const detailEnv = detail?.env || ''

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <div className={styles.title}>{t('processPanel.title')}</div>
          <div className={styles.meta}>{t('processPanel.updated', { time: formatTimestamp(snapshot.ts) })}</div>
        </div>
        <div className={styles.totals}>
          <span>{t('processPanel.total', { count: rows.length })}</span>
        </div>
      </header>

      <div className={styles.body}>
        <div className={styles.tableWrap}>
          {rows.length === 0 ? (
            <Empty className={styles.empty} description={t('processPanel.empty')} />
          ) : (
            <table className={styles.table}>
              <thead>
                <tr>
                  <th className={styles.num} onClick={() => toggleSort('pid')}>
                    {sortLabel('pid', t('processPanel.pid'))}
                  </th>
                  <th onClick={() => toggleSort('user')}>{sortLabel('user', t('processPanel.user'))}</th>
                  <th className={styles.num} onClick={() => toggleSort('memBytes')}>
                    {sortLabel('memBytes', t('processPanel.memory'))}
                  </th>
                  <th className={styles.num} onClick={() => toggleSort('cpu')}>
                    {sortLabel('cpu', t('processPanel.cpu'))}
                  </th>
                  <th onClick={() => toggleSort('name')}>{sortLabel('name', t('processPanel.nameCmd'))}</th>
                  <th onClick={() => toggleSort('location')}>{sortLabel('location', t('processPanel.location'))}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((entry) => (
                  <tr
                    key={entry.pid}
                    className={entry.pid === selectedPid ? styles.selected : undefined}
                    onClick={() => setSelectedPid(entry.pid)}
                    onContextMenu={(e) => {
                      e.preventDefault()
                      setSelectedPid(entry.pid)
                      setContextMenu({ x: e.clientX, y: e.clientY, entry })
                    }}
                  >
                    <td className={styles.num}>{entry.pid}</td>
                    <td>{entry.user}</td>
                    <td className={styles.num}>{formatBytes(entry.memBytes)}</td>
                    <td className={styles.num}>{entry.cpu.toFixed(1)}</td>
                    <td className={styles.nameCell} title={entry.cmdline || entry.name}>
                      {entry.cmdline || entry.name}
                    </td>
                    <td title={entry.location}>{entry.location}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className={styles.detail}>
          {!selected ? (
            <div className={styles.detailEmpty}>{t('processPanel.selectHint')}</div>
          ) : (
            <>
              <div className={styles.detailHead}>
                {selected.pid}-{detailName || selected.name}
                {detailLoading ? ' …' : ''}
              </div>
              <div className={styles.detailFields}>
                <div className={styles.field}>
                  <span className={styles.fieldLabel}>{t('processPanel.pid')}</span>
                  <div className={styles.fieldValue}>
                    <Input value={String(selected.pid)} readOnly />
                  </div>
                </div>
                <div className={styles.field}>
                  <span className={styles.fieldLabel}>{t('processPanel.name')}</span>
                  <div className={styles.fieldValue}>
                    <Input value={detailName} readOnly />
                  </div>
                </div>
                <div className={styles.field}>
                  <span className={styles.fieldLabel}>{t('processPanel.location')}</span>
                  <div className={styles.fieldValue}>
                    <Input value={detailLocation} readOnly title={detailLocation} />
                  </div>
                </div>
                <div className={styles.field}>
                  <span className={styles.fieldLabel}>{t('processPanel.workingDir')}</span>
                  <div className={styles.fieldValue}>
                    <Input value={detailCwd} readOnly title={detailCwd} />
                  </div>
                </div>
                <div className={styles.field}>
                  <span className={styles.fieldLabel}>{t('processPanel.nameCmd')}</span>
                  <div className={styles.fieldValue}>
                    <Input.TextArea value={detailCmd} readOnly autoSize={{ minRows: 1, maxRows: 3 }} />
                  </div>
                </div>
              </div>
              <div className={styles.envPane}>
                <div className={styles.envTitle}>{t('processPanel.env')}</div>
                <textarea
                  readOnly
                  value={
                    detail
                      ? detailEnv
                      : detailLoading
                        ? ''
                        : selected
                          ? t('processPanel.detailUnavailable')
                          : ''
                  }
                  spellCheck={false}
                />
              </div>
            </>
          )}
        </div>
      </div>

      {contextMenu && (
        <Dropdown
          open
          trigger={['contextMenu']}
          key={`${contextMenu.x},${contextMenu.y}`}
          menu={{
            items: [
              { key: 'terminate', label: t('processPanel.terminate'), danger: true },
              { key: 'forceKill', label: t('processPanel.forceKill'), danger: true },
              { type: 'divider' },
              { key: 'copyPid', label: t('processPanel.copyPid') },
              { key: 'copyName', label: t('processPanel.copyName') },
              { key: 'copyCmdline', label: t('processPanel.copyCmdline') },
              { key: 'copyLocation', label: t('processPanel.copyLocation') }
            ],
            onClick: ({ key }) => {
              const entry = contextMenu.entry
              setContextMenu(null)
              if (key === 'terminate') confirmKill(entry, false)
              else if (key === 'forceKill') confirmKill(entry, true)
              else if (key === 'copyPid') copyText(String(entry.pid))
              else if (key === 'copyName') copyText(entry.name)
              else if (key === 'copyCmdline') copyText(entry.cmdline || entry.name)
              else if (key === 'copyLocation') copyText(detail?.pid === entry.pid ? detail.location : entry.location)
            }
          }}
        >
          <div
            style={{
              position: 'fixed',
              left: contextMenu.x,
              top: contextMenu.y,
              width: 1,
              height: 1,
              zIndex: 1050
            }}
          />
        </Dropdown>
      )}
    </div>
  )
}

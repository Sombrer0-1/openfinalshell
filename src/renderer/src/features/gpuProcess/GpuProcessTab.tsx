import { useEffect } from 'react'
import { Button, Empty } from 'antd'
import { RefreshCw } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { GpuProcessGroup } from '@shared/types'
import type { SessionTab } from '@/stores/useSessionStore'
import { useGpuProcessStore } from '@/stores/useGpuProcessStore'
import { formatBytes, formatTimestamp } from '@/utils/format'
import styles from './GpuProcessTab.module.css'

interface Props {
  tab: SessionTab
}

function percent(value: number | null): string {
  return value == null ? '—' : `${value.toFixed(0)}%`
}

function memory(value: number | null): string {
  return value == null ? '—' : formatBytes(value)
}

function deviceMemory(gpu: GpuProcessGroup): string {
  if (gpu.memUsedMiB == null || gpu.memTotalMiB == null) return '—'
  return `${formatBytes(gpu.memUsedMiB * 1024 * 1024)} / ${formatBytes(gpu.memTotalMiB * 1024 * 1024)}`
}

/** 按 GPU 分组的进程页。同一进程占多张卡时，在每张卡下各有一行。 */
export function GpuProcessTab({ tab }: Props): React.JSX.Element {
  const { t } = useTranslation()
  const sessionId = tab.sessionId
  const snapshot = useGpuProcessStore((s) => (sessionId ? s.latest[sessionId] : undefined))
  const state = useGpuProcessStore((s) => (sessionId ? s.state[sessionId] : undefined))
  const error = useGpuProcessStore((s) => (sessionId ? s.error[sessionId] : undefined))
  const start = useGpuProcessStore((s) => s.start)

  useEffect(() => {
    if (!sessionId || tab.state !== 'ready') return
    void start(sessionId).catch(() => {})
  }, [sessionId, start, tab.state])

  if (state === 'unsupported') {
    return (
      <div className={styles.empty}>
        <Empty description={t('gpuProcess.unsupported')} />
      </div>
    )
  }
  if (state === 'failed') {
    return (
      <div className={styles.empty}>
        <Empty description={error || t('gpuProcess.failed')}>
          <Button type="primary" icon={<RefreshCw size={15} />} onClick={() => sessionId && void start(sessionId)}>
            {t('common.retry')}
          </Button>
        </Empty>
      </div>
    )
  }
  if (!snapshot) {
    return <div className={styles.empty}>{t('gpuProcess.collecting')}</div>
  }

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <div className={styles.title}>{t('gpuProcess.title')}</div>
          <div className={styles.meta}>{t('gpuProcess.updated', { time: formatTimestamp(snapshot.ts) })}</div>
        </div>
      </header>
      <div className={styles.content}>
        {snapshot.gpus.length === 0 ? (
          <Empty className={styles.noGpu} description={t('gpuProcess.empty')} />
        ) : (
          snapshot.gpus.map((gpu) => (
            <section key={gpu.index} className={styles.gpu}>
              <div className={styles.gpuHead}>
                <span className={styles.gpuTitle}>
                  GPU {gpu.index}
                  <span className={styles.gpuName} title={gpu.name}>{gpu.name}</span>
                </span>
                <span className={styles.gpuStats}>
                  <span>{percent(gpu.utilPct)}</span>
                  <span>{deviceMemory(gpu)}</span>
                  {gpu.tempC != null && <span>{Math.round(gpu.tempC)}°C</span>}
                </span>
              </div>
              {gpu.processes.length === 0 ? (
                <div className={styles.noProcess}>{t('gpuProcess.noProcess')}</div>
              ) : (
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>{t('gpuProcess.pid')}</th>
                      <th>{t('gpuProcess.user')}</th>
                      <th>{t('gpuProcess.name')}</th>
                      <th>{t('gpuProcess.util')}</th>
                      <th>{t('gpuProcess.memory')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {gpu.processes.map((proc) => (
                      <tr key={proc.pid}>
                        <td>{proc.pid}</td>
                        <td>{proc.user || '—'}</td>
                        <td className={styles.name} title={proc.name}>{proc.name}</td>
                        <td>{percent(proc.utilPct)}</td>
                        <td>{memory(proc.memBytes)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
          ))
        )}
      </div>
    </div>
  )
}

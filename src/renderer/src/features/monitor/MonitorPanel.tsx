import { IconButton, PanelHeading } from '@/components/ui'
import { useEffect, useState } from 'react'
import { Empty } from 'antd'
import { ChevronDown, ChevronRight, RefreshCw, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { GpuSample, MonitorSnapshot } from '@shared/types'
import { historyOf, useMonitorStore } from '@/stores/useMonitorStore'
import { useSettingsStore } from '@/stores/useSettingsStore'
import { useSessionStore } from '@/stores/useSessionStore'
import type { SessionTab } from '@/stores/useSessionStore'
import { TitlebarSafeTooltip } from '@/components/TitlebarSafeTooltip'
import { formatBytes, formatDuration } from '@/utils/format'
import { MonitorGraph } from './MonitorGraph'
import styles from './MonitorPanel.module.css'

interface Props {
  tab: SessionTab
  onClose: () => void
}

function usePct(used: number, total: number): number {
  return total > 0 ? Number(((used / total) * 100).toFixed(1)) : 0
}

/** 占用条：>90% 变红，>75% 变黄 */
function usageColor(pct: number, accent: string): string {
  if (pct >= 90) return 'var(--ofs-error)'
  if (pct >= 75) return 'var(--ofs-warning)'
  return accent
}

/** 延迟分档配色：<100 绿 / <200 黄 / 其余红（对齐 FinalShell 肌肉记忆） */
function latencyColor(ms: number): string {
  if (ms < 100) return 'var(--ofs-success)'
  if (ms < 200) return 'var(--ofs-warning)'
  return 'var(--ofs-error)'
}

export function MonitorPanel({ tab, onClose }: Props): React.JSX.Element {
  const { t } = useTranslation()
  const settings = useSettingsStore((s) => s.settings)!
  const accent = 'var(--ofs-accent)'
  const sessionId = tab.sessionId
  const snapshot = useMonitorStore((s) => (sessionId ? s.latest[sessionId] : undefined))
  const staticInfo = useMonitorStore((s) => (sessionId ? s.staticInfo[sessionId] : undefined))
  const state = useMonitorStore((s) => (sessionId ? s.state[sessionId] : undefined))
  const start = useMonitorStore((s) => s.start)
  const openPortTrafficTab = useSessionStore((s) => s.openPortTrafficTab)
  const openProcessPanelTab = useSessionStore((s) => s.openProcessPanelTab)
  const [showCores, setShowCores] = useState(false)
  const sessionTitle = tab.customTitle ?? tab.title

  // 会话就绪 → 启动采集；关闭面板时由父组件调 stop
  useEffect(() => {
    if (!sessionId || tab.state !== 'ready') return
    void start(sessionId, settings.monitor.intervalMs).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, tab.state, tab.shellEpoch])

  const history = sessionId
    ? historyOf(sessionId)
    : { rxBps: [], txBps: [] }

  const header = (
    <PanelHeading title={t('monitor.title')} actions={<>
        {state === 'failed' && (
          <TitlebarSafeTooltip title={t('common.retry')}>
            <IconButton label={t('common.retry')}
              size="small"
              icon={<RefreshCw size={13} strokeWidth={1.75} />}
              onClick={() => sessionId && void start(sessionId, settings.monitor.intervalMs)}
            />
          </TitlebarSafeTooltip>
        )}
        <IconButton label={t('common.close')} size="small" icon={<X size={13} strokeWidth={1.75} />} onClick={onClose} />
      </>} />
  )

  if (state === 'unsupported') {
    return (
      <div className={styles.panel}>
        {header}
        <div className={styles.stateBox}>{t('monitor.unsupported')}</div>
      </div>
    )
  }
  if (state === 'failed') {
    return (
      <div className={styles.panel}>
        {header}
        <div className={styles.stateBox}>{t('monitor.failed')}</div>
      </div>
    )
  }
  if (!snapshot) {
    return (
      <div className={styles.panel}>
        {header}
        <div className={styles.stateBox}>
          {tab.state === 'ready' ? t('monitor.collecting') : t('monitor.waitingSession')}
        </div>
      </div>
    )
  }

  return (
    <div className={styles.panel}>
      {header}
      <div className={styles.body}>
        {/* 系统信息 */}
        {staticInfo && (
          <button
            type="button"
            className={`${styles.card} ${styles.clickableCard}`}
            onClick={() => openProcessPanelTab(tab.id, `${sessionTitle} · ${t('processPanel.tab')}`)}
          >
            <span className={styles.cardHead}>
              <span className={styles.cardHeadTitle}>{t('monitor.system')}</span>
              <span className={styles.cardActionHint}>
                {t('processPanel.open')} <ChevronRight size={13} aria-hidden="true" />
              </span>
            </span>
            <Kv k={t('monitor.hostname')} v={staticInfo.hostname} />
            <Kv k={t('monitor.distro')} v={staticInfo.distro} />
            <Kv k={t('monitor.kernel')} v={`${staticInfo.kernel} (${staticInfo.arch})`} />
            <Kv k={t('monitor.ip')} v={staticInfo.ips.join(', ') || '-'} />
            <Kv k={t('monitor.uptime')} v={formatDuration(snapshot.uptimeSec)} />
          </button>
        )}

        {/* CPU */}
        <div className={`${styles.card} ${styles.clickableCard}`}>
          <div className={styles.cardHead}>
            <span className={styles.cardHeadTitle}>{t('monitor.cpu')}</span>
            <span className={styles.subText}>
              {t('monitor.load')} {snapshot.cpu.loadAvg.map((n) => n.toFixed(2)).join(' ')}
            </span>
            <button
              type="button"
              className={`${styles.cardToggle} ${styles.cardActionHint}`}
              aria-label={t('monitor.cpuCores')}
              aria-expanded={showCores}
              onClick={() => setShowCores((v) => !v)}
            >
              {showCores ? <ChevronDown size={13} aria-hidden="true" /> : <ChevronRight size={13} aria-hidden="true" />}
            </button>
          </div>
          <div className={styles.bigNumber}>
            {snapshot.cpu.usagePct.toFixed(1)}
            <span className={styles.unit}>%</span>
          </div>
          <UsageBar label={t('monitor.cpu')} pct={snapshot.cpu.usagePct} color={usageColor(snapshot.cpu.usagePct, accent)} />
          {showCores && (
            <div className={styles.coreGrid}>
              {snapshot.cpu.perCore.map((pct, i) => (
                <div key={i} className={styles.coreItem}>
                  <span>{i}</span>
                  <UsageBar label={`${t('monitor.cpu')} ${i}`} pct={pct} color={usageColor(pct, accent)} className={styles.coreBar} />
                  <span style={{ width: 30, textAlign: 'right' }}>{pct.toFixed(0)}%</span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 内存 */}
        <MemCard snapshot={snapshot} accent={accent} />

        {snapshot.gpus && snapshot.gpus.length > 0 && <GpuCard gpus={snapshot.gpus} accent={accent} />}

        {/* 网络 */}
        <button
          type="button"
          className={`${styles.card} ${styles.clickableCard}`}
          onClick={() => openPortTrafficTab(tab.id, `${sessionTitle} · ${t('portTraffic.tab')}`)}
        >
          <span className={styles.cardHead}>
            <span className={styles.cardHeadTitle}>{t('monitor.network')}</span>
            <span className={styles.cardActionHint}>
              {t('portTraffic.open')} <ChevronRight size={13} aria-hidden="true" />
            </span>
          </span>
          <span className={styles.netLegend}>
            <span className={styles.netItem}>
              <span style={{ color: accent }}>↓</span>
              <span className={styles.bigNumber} style={{ fontSize: 16 }}>
                {formatBytes(snapshot.net.reduce((s, n) => s + n.rxBps, 0))}
                <span className={styles.unit}>/s</span>
              </span>
            </span>
            <span className={styles.netItem}>
              <span style={{ color: '#faad14' }}>↑</span>
              <span className={styles.bigNumber} style={{ fontSize: 16 }}>
                {formatBytes(snapshot.net.reduce((s, n) => s + n.txBps, 0))}
                <span className={styles.unit}>/s</span>
              </span>
            </span>
          </span>
          {/* 下行 = 渐变面积（绿→红按量），上行 = 橙线叠加，与图例配色一致 */}
          <MonitorGraph
            primary={[...history.rxBps]}
            secondary={[...history.txBps]}
            height={48}
            className={styles.graph}
          />
        </button>

        {/* 两种 RTT 并列：本机直连 ICMP 与当前 SSH 数据通道（含实际代理/隧道链路）。 */}
        {snapshot.connectionLatencyMs !== undefined && (
          <div className={styles.card}>
            <div className={styles.cardHead}>
              <span className={styles.cardHeadTitle}>{t('monitor.latency')}</span>
            </div>
            <div className={styles.latencyGrid}>
              <LatencyMetric
                label={t('monitor.directLatency')}
                value={snapshot.directLatencyMs}
              />
              <LatencyMetric
                label={t('monitor.connectionLatency')}
                value={snapshot.connectionLatencyMs}
              />
            </div>
          </div>
        )}

        {/* 连接数。措辞上要分清：UDP 是无连接的，/proc/net/udp 列的是**已打开的套接字** */}
        {snapshot.conns && (
          <div className={styles.card}>
            <div className={styles.cardHead}>
              <span className={styles.cardHeadTitle}>{t('monitor.conns')}</span>
              <span className={styles.subText}>
                {t('monitor.connsSockets')} {snapshot.conns.socketsUsed}
              </span>
            </div>
            <div className={styles.bigNumber}>
              {snapshot.conns.tcpInuse}
              <span className={styles.unit}>TCP</span>
            </div>
            {/* 状态明细只在低频 tick 采，缺失时不显示这几行（store 会沿用上次值） */}
            {snapshot.tcpStates &&
              (['ESTABLISHED', 'LISTEN', 'CLOSE_WAIT'] as const)
                .filter((name) => snapshot.tcpStates?.[name] !== undefined)
                .map((name) => (
                  <Kv key={name} k={name} v={String(snapshot.tcpStates?.[name] ?? 0)} />
                ))}
            {/* TIME_WAIT 用 sockstat 的 tw：它每 tick 都有，而明细每 5 tick 才刷 */}
            <Kv k="TIME_WAIT" v={String(snapshot.conns.tcpTw)} />
            {snapshot.conns.tcpOrphan > 0 && (
              <Kv k={t('monitor.connsOrphan')} v={String(snapshot.conns.tcpOrphan)} />
            )}
            <Kv k={t('monitor.connsUdp')} v={String(snapshot.conns.udpInuse)} />
          </div>
        )}

        {/* 磁盘 */}
        {snapshot.diskFs && snapshot.diskFs.length > 0 && (
          <div className={styles.card}>
            <div className={styles.cardHead}>
              <span className={styles.cardHeadTitle}>{t('monitor.disk')}</span>
            </div>
            {snapshot.diskFs.map((fs) => (
              <div key={fs.mount} style={{ marginBottom: 6 }}>
                <div className={styles.kvRow}>
                  <span className={styles.kvValue} title={`${fs.fs} → ${fs.mount}`}>
                    {fs.mount}
                  </span>
                  <span className={styles.subText}>
                    {formatBytes(fs.usedKb * 1024)} / {formatBytes(fs.totalKb * 1024)}
                  </span>
                </div>
                <UsageBar label={fs.mount} pct={fs.usePct} color={usageColor(fs.usePct, accent)} />
              </div>
            ))}
            {snapshot.diskIo.length > 0 && (
              <div className={styles.subText}>
                {snapshot.diskIo
                  .map(
                    (d) => `${d.dev} ↓${formatBytes(d.readBps)}/s ↑${formatBytes(d.writeBps)}/s`
                  )
                  .join(' · ')}
              </div>
            )}
          </div>
        )}

        {snapshot.net.length === 0 && (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('monitor.noIface')} />
        )}
      </div>
    </div>
  )
}

function LatencyMetric({
  label,
  value
}: {
  label: string
  value: number | undefined
}): React.JSX.Element {
  const { t } = useTranslation()
  return (
    <div className={styles.latencyMetric}>
      <span className={styles.subText}>{label}</span>
      {value === undefined ? (
        <div className={styles.latencyUnavailable}>{t('monitor.latencyUnavailable')}</div>
      ) : (
        <div className={styles.latencyValue} style={{ color: latencyColor(value) }}>
          {value}
          <span className={styles.unit}>ms</span>
        </div>
      )}
    </div>
  )
}

function Kv({ k, v }: { k: string; v: string }): React.JSX.Element {
  return (
    <span className={styles.kvRow}>
      <span className={styles.kvKey}>{k}</span>
      <span className={styles.kvValue} title={v}>
        {v}
      </span>
    </span>
  )
}

function formatMeasure(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

function gpuMemPct(gpu: GpuSample): number | null {
  if (gpu.memUsedMiB == null || gpu.memTotalMiB == null || gpu.memTotalMiB <= 0) return null
  return Number(((gpu.memUsedMiB / gpu.memTotalMiB) * 100).toFixed(1))
}

function GpuMemRows({ gpu, accent, label }: { gpu: GpuSample; accent: string; label: string }): React.JSX.Element | null {
  const pct = gpuMemPct(gpu)
  if (pct == null || gpu.memUsedMiB == null || gpu.memTotalMiB == null) return null
  const used = formatBytes(gpu.memUsedMiB * 1024 * 1024)
  const total = formatBytes(gpu.memTotalMiB * 1024 * 1024)
  return (
    <>
      <span className={styles.kvRow}>
        <span className={styles.kvKey}>{label}</span>
        <span className={styles.subText}>
          {used} / {total}
        </span>
      </span>
      <UsageBar label={label} pct={pct} color={usageColor(pct, accent)} />
    </>
  )
}

function GpuPower({ gpu }: { gpu: GpuSample }): React.JSX.Element | null {
  const { t } = useTranslation()
  if (gpu.powerW == null) return null
  const draw = `${formatMeasure(gpu.powerW)} W`
  const value = gpu.powerLimitW == null ? draw : `${draw} / ${formatMeasure(gpu.powerLimitW)} W`
  return <Kv k={t('monitor.gpuPower')} v={value} />
}

function GpuUtil({ gpu, accent, label }: { gpu: GpuSample; accent: string; label: string }): React.JSX.Element {
  const { t } = useTranslation()
  if (gpu.utilPct == null) {
    return <div className={styles.latencyUnavailable}>{t('monitor.gpuUnavailable')}</div>
  }
  return (
    <>
      <div className={styles.bigNumber}>
        {gpu.utilPct.toFixed(1)}
        <span className={styles.unit}>%</span>
      </div>
      <UsageBar label={label} pct={gpu.utilPct} color={usageColor(gpu.utilPct, accent)} />
    </>
  )
}

function GpuCard({ gpus, accent }: { gpus: GpuSample[]; accent: string }): React.JSX.Element {
  const { t } = useTranslation()
  const memoryLabel = t('monitor.gpuMemory')
  if (gpus.length === 1) {
    const gpu = gpus[0]
    return (
      <div className={styles.card}>
        <div className={styles.cardHead}>
          <span className={styles.cardHeadTitle}>{t('monitor.gpu')}</span>
          {gpu.tempC != null && <span className={styles.subText}>{formatMeasure(gpu.tempC)}°C</span>}
        </div>
        <span className={styles.kvRow}>
          <span className={styles.kvValue} title={gpu.name}>
            {gpu.name}
          </span>
        </span>
        <GpuUtil gpu={gpu} accent={accent} label={t('monitor.gpu')} />
        <GpuMemRows gpu={gpu} accent={accent} label={memoryLabel} />
        <GpuPower gpu={gpu} />
      </div>
    )
  }
  return (
    <div className={styles.card}>
      <div className={styles.cardHead}>
        <span className={styles.cardHeadTitle}>{t('monitor.gpu')}</span>
      </div>
      {gpus.map((gpu) => {
        const label = `${t('monitor.gpu')} ${gpu.index}`
        return (
          <div key={gpu.index} style={{ marginBottom: 6 }}>
            <span className={styles.kvRow}>
              <span className={styles.kvValue} title={gpu.name}>
                {gpu.index} {gpu.name}
              </span>
              {gpu.tempC != null && <span className={styles.subText}>{formatMeasure(gpu.tempC)}°C</span>}
            </span>
            {gpu.utilPct == null ? (
              <div className={styles.latencyUnavailable}>{t('monitor.gpuUnavailable')}</div>
            ) : (
              <div className={styles.coreItem}>
                <UsageBar
                  label={label}
                  pct={gpu.utilPct}
                  color={usageColor(gpu.utilPct, accent)}
                  className={styles.coreBar}
                />
                <span style={{ width: 36, textAlign: 'right' }}>{gpu.utilPct.toFixed(0)}%</span>
              </div>
            )}
            <GpuMemRows gpu={gpu} accent={accent} label={`${memoryLabel} ${gpu.index}`} />
            <GpuPower gpu={gpu} />
          </div>
        )
      })}
    </div>
  )
}

function MemCard({
  snapshot,
  accent
}: {
  snapshot: MonitorSnapshot
  accent: string
}): React.JSX.Element {
  const { t } = useTranslation()
  const memPct = usePct(snapshot.mem.usedKb, snapshot.mem.totalKb)
  const swapPct = usePct(snapshot.mem.swapUsedKb, snapshot.mem.swapTotalKb)
  return (
    <div className={styles.card}>
      <div className={styles.cardHead}>
        <span className={styles.cardHeadTitle}>{t('monitor.memory')}</span>
        <span className={styles.subText}>
          {formatBytes(snapshot.mem.usedKb * 1024)} / {formatBytes(snapshot.mem.totalKb * 1024)}
        </span>
      </div>
      <div className={styles.bigNumber}>
        {memPct.toFixed(1)}
        <span className={styles.unit}>%</span>
      </div>
      <UsageBar label={t('monitor.memory')} pct={memPct} color={usageColor(memPct, accent)} />
      {snapshot.mem.swapTotalKb > 0 && (
        <>
          <div className={styles.kvRow}>
            <span className={styles.kvKey}>Swap</span>
            <span className={styles.subText}>
              {formatBytes(snapshot.mem.swapUsedKb * 1024)} /{' '}
              {formatBytes(snapshot.mem.swapTotalKb * 1024)}
            </span>
          </div>
          <UsageBar label="Swap" pct={swapPct} color="#722ed1" />
        </>
      )}
    </div>
  )
}

function UsageBar({ label, pct, color, className = styles.bar }: {
  label: string
  pct: number
  color: string
  className?: string
}): React.JSX.Element {
  return (
    <div className={className} role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
      <span className={styles.barFill} style={{ width: `${pct}%`, background: color }} />
    </div>
  )
}

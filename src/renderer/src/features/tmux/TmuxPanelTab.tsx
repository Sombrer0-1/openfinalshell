import { useEffect, useMemo, useState } from 'react'
import { App as AntdApp, Button, Checkbox, Empty, Input, Tooltip } from 'antd'
import { Mouse, Plus, RefreshCw, SquareTerminal, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import i18n from '@/i18n'
import { TMUX_SESSION_NAME_MAX } from '@shared/constants'
import type { TmuxSessionInfo } from '@shared/types'
import type { SessionTab } from '@/stores/useSessionStore'
import { useSessionStore } from '@/stores/useSessionStore'
import { useTmuxStore } from '@/stores/useTmuxStore'
import { useConnectionStore } from '@/stores/useConnectionStore'
import { formatTimestamp } from '@/utils/format'
import styles from './TmuxPanelTab.module.css'

interface Props {
  tab: SessionTab
}

/** 与主进程 zod 同一份名称规则：非空、≤64、无控制字符、不以 `-` 开头。 */
function validateName(name: string): string | null {
  if (!name) return i18n.t('tmux.nameRequired')
  if (name.length > TMUX_SESSION_NAME_MAX || name.startsWith('-') || /[\u0000-\u001f\u007f]/.test(name)) {
    return i18n.t('tmux.nameInvalid', { max: TMUX_SESSION_NAME_MAX })
  }
  return null
}

/** 创建时间用相对时长展示（"2 小时前"）；超过一天退回绝对时间戳。 */
function formatCreated(epochSec: number): string {
  const ms = epochSec * 1000
  const minutes = Math.floor((Date.now() - ms) / 60000)
  const rel = new Intl.RelativeTimeFormat(i18n.language, { numeric: 'auto' })
  if (minutes < 1) return rel.format(0, 'minute')
  if (minutes < 60) return rel.format(-minutes, 'minute')
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return rel.format(-hours, 'hour')
  const days = Math.floor(hours / 24)
  if (days < 7) return rel.format(-days, 'day')
  return formatTimestamp(ms)
}

/**
 * Tmux 会话管理面板（管理台心智：看列表、附着、新建、关闭会话）。
 * 面板连接可能借自源终端（sourceTabId），也可能是面板自有的；两者对这里是透明的 ——
 * 采集与操作都只按 sessionId 寻址，连接的来去由 useSessionStore 负责。
 */
export function TmuxPanelTab({ tab }: Props): React.JSX.Element {
  const { t } = useTranslation()
  const { message, modal } = AntdApp.useApp()
  const sessionId = tab.sessionId
  const snapshot = useTmuxStore((s) => (sessionId ? s.latest[sessionId] : undefined))
  const state = useTmuxStore((s) => (sessionId ? s.state[sessionId] : undefined))
  const error = useTmuxStore((s) => (sessionId ? s.error[sessionId] : undefined))
  const mouseTipDismissed = useTmuxStore((s) => (sessionId ? s.mouseTipDismissed[sessionId] : undefined))
  const start = useTmuxStore((s) => s.start)
  const refresh = useTmuxStore((s) => s.refresh)
  const newSession = useTmuxStore((s) => s.newSession)
  const killSession = useTmuxStore((s) => s.killSession)
  const setMouse = useTmuxStore((s) => s.setMouse)
  const dismissMouseTip = useTmuxStore((s) => s.dismissMouseTip)

  const profile = useConnectionStore((s) => s.profiles.find((p) => p.id === tab.profileId))
  const openTmuxAttachTab = useSessionStore((s) => s.openTmuxAttachTab)
  const openForProfile = useSessionStore((s) => s.openForProfile)
  const reconnectTab = useSessionStore((s) => s.reconnectTab)

  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  // 「创建并进入」默认勾上（方案 §1.4）：多数时候新建就是为了用它
  const [enterAfterCreate, setEnterAfterCreate] = useState(true)
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [mouseBusy, setMouseBusy] = useState(false)

  useEffect(() => {
    if (!sessionId || tab.state !== 'ready') return
    void start(sessionId).catch(() => {})
  }, [sessionId, start, tab.state])

  const sessions = useMemo(() => snapshot?.sessions ?? [], [snapshot?.sessions])

  const doRefresh = (): void => {
    if (!sessionId || refreshing) return
    setRefreshing(true)
    void refresh(sessionId)
      .catch(() => {})
      .finally(() => setRefreshing(false))
  }

  const openCreateForm = (): void => {
    setCreating(true)
    setFormError(null)
  }

  const submitCreate = (): void => {
    const trimmed = name.trim()
    if (!sessionId) return
    const invalid = validateName(trimmed)
    if (invalid) {
      setFormError(invalid)
      return
    }
    setSubmitting(true)
    setFormError(null)
    void newSession(sessionId, trimmed)
      .then(({ id }) => {
        message.success(t('tmux.created', { name: trimmed }))
        setName('')
        setCreating(false)
        // 列表本身由主进程 poke 立即补帧；要进入时另开附着标签（自带新连接）
        if (enterAfterCreate) void openTmuxAttachTab(tab.profileId, id, trimmed)
      })
      .catch((err: Error) => {
        // 重名等错误就地报（duplicateSession 文案来自主进程），表单不关
        setFormError(err instanceof Error ? err.message : String(err))
      })
      .finally(() => setSubmitting(false))
  }

  const confirmKill = (row: TmuxSessionInfo): void => {
    if (!sessionId) return
    modal.confirm({
      title: t('tmux.killTitle', { name: row.name }),
      // 文案严格区分「关闭会话」（kill，远端会话结束）与「关闭标签」（detach，会话存活）
      content:
        row.attached > 0
          ? t('tmux.killAttachedContent', { count: row.attached })
          : t('tmux.killContent'),
      okText: t('tmux.killOk'),
      okButtonProps: { danger: true },
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          await killSession(sessionId, row.id)
          message.success(t('tmux.killed', { name: row.name }))
        } catch (err) {
          message.error(
            t('tmux.killFailed', { reason: err instanceof Error ? err.message : String(err) })
          )
        }
      }
    })
  }

  const enableMouse = (): void => {
    if (!sessionId) return
    setMouseBusy(true)
    void setMouse(sessionId, true)
      .catch((err: Error) => {
        message.error(
          t('tmux.setMouseFailed', { reason: err instanceof Error ? err.message : String(err) })
        )
      })
      .finally(() => setMouseBusy(false))
  }

  // ---- 自有连接还没建立 / 建立失败 ----
  if (!sessionId || tab.state !== 'ready') {
    if (tab.state === 'closed') {
      return (
        <div className={styles.empty}>
          <Empty description={tab.error ?? t('tmux.connectFailed')}>
            <Button
              type="primary"
              icon={<RefreshCw size={15} />}
              onClick={() => void reconnectTab(tab.id)}
            >
              {t('common.retry')}
            </Button>
          </Empty>
        </div>
      )
    }
    return <div className={styles.empty}>{t('tmux.connecting')}</div>
  }

  // ---- 采集状态机（三种异常态各自独立，方案 §1.6） ----
  if (state === 'unsupported') {
    return (
      <div className={styles.empty}>
        <Empty description={t('tmux.unsupported')} />
      </div>
    )
  }
  if (state === 'failed') {
    return (
      <div className={styles.empty}>
        <Empty description={error || t('tmux.failed')}>
          <Button type="primary" icon={<RefreshCw size={15} />} onClick={() => void start(sessionId)}>
            {t('common.retry')}
          </Button>
        </Empty>
      </div>
    )
  }
  // 手动刷新途中会经过 stopped：refreshing 为真时不当作异常态，保住表格
  if (state === 'stopped' && !refreshing) {
    return (
      <div className={styles.empty}>
        <Empty description={t('tmux.stopped')}>
          <Button type="primary" icon={<RefreshCw size={15} />} onClick={() => void start(sessionId)}>
            {t('common.retry')}
          </Button>
        </Empty>
      </div>
    )
  }
  // 首帧还没回来：不显示"空列表"（那是合法空态的文案），给采集中的过渡提示
  if (!snapshot) {
    return <div className={styles.empty}>{t('tmux.collecting')}</div>
  }

  const showMouseTip =
    state === 'running' && sessions.length > 0 && snapshot?.mouseOn === false && !mouseTipDismissed

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <div className={styles.title}>{tab.customTitle ?? tab.title}</div>
          <div className={styles.meta}>
            {snapshot
              ? t('tmux.updated', { time: formatTimestamp(snapshot.ts) })
              : t('tmux.collecting')}
          </div>
        </div>
        <div className={styles.actions}>
          <Button size="small" icon={<RefreshCw size={14} />} loading={refreshing} onClick={doRefresh}>
            {t('tmux.refresh')}
          </Button>
          <Button size="small" type="primary" icon={<Plus size={14} />} onClick={openCreateForm}>
            {t('tmux.newSession')}
          </Button>
          {profile && (
            <Button
              size="small"
              icon={<SquareTerminal size={14} />}
              onClick={() => void openForProfile(profile)}
            >
              {t('tmux.openTerminal')}
            </Button>
          )}
        </div>
      </header>

      {creating && (
        <div className={styles.createForm}>
          <Input
            className={styles.nameInput}
            size="small"
            autoFocus
            allowClear
            placeholder={t('tmux.newNamePlaceholder')}
            value={name}
            maxLength={TMUX_SESSION_NAME_MAX * 2}
            onChange={(e) => setName(e.target.value)}
            onPressEnter={submitCreate}
          />
          <Checkbox
            className={styles.enterCheck}
            checked={enterAfterCreate}
            onChange={(e) => setEnterAfterCreate(e.target.checked)}
          >
            {t('tmux.newEnter')}
          </Checkbox>
          <Button size="small" type="primary" loading={submitting} onClick={submitCreate}>
            {t('tmux.create')}
          </Button>
          <Button
            size="small"
            onClick={() => {
              setCreating(false)
              setFormError(null)
              setName('')
            }}
          >
            {t('common.cancel')}
          </Button>
          {formError && <div className={styles.formError}>{formError}</div>}
        </div>
      )}

      <div className={styles.body}>
        <div className={styles.tableWrap}>
          {sessions.length === 0 ? (
            <Empty className={styles.empty} description={t('tmux.empty')}>
              <Button type="primary" icon={<Plus size={15} />} onClick={openCreateForm}>
                {t('tmux.newSession')}
              </Button>
            </Empty>
          ) : (
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>{t('tmux.colName')}</th>
                  <th className={styles.num}>{t('tmux.colWindows')}</th>
                  <th className={styles.num}>{t('tmux.colAttached')}</th>
                  <th>{t('tmux.colCreated')}</th>
                  <th className={styles.num}>{t('tmux.colActions')}</th>
                </tr>
              </thead>
              <tbody>
                {sessions.map((row) => (
                  <tr key={row.id}>
                    <td className={styles.nameCell} title={row.name}>
                      {row.name}
                    </td>
                    <td className={styles.num}>{row.windows}</td>
                    <td className={styles.num}>
                      {row.attached > 0 ? (
                        <Tooltip title={t('tmux.attachedTip', { count: row.attached })}>
                          <span className={styles.attachedBadge}>{row.attached}</span>
                        </Tooltip>
                      ) : (
                        <span className={styles.attachedNone}>0</span>
                      )}
                    </td>
                    <td className={styles.createdCell}>{formatCreated(row.created)}</td>
                    <td>
                      <div className={styles.rowActions}>
                        <Button
                          size="small"
                          type="link"
                          onClick={() => void openTmuxAttachTab(tab.profileId, row.id, row.name)}
                        >
                          {t('tmux.attach')}
                        </Button>
                        <Button size="small" type="link" danger onClick={() => confirmKill(row)}>
                          {t('tmux.kill')}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {showMouseTip && (
          <div className={styles.mouseTip}>
            <span className={styles.mouseTipIcon}>
              <Mouse size={14} strokeWidth={1.75} />
            </span>
            <span className={styles.mouseTipText}>{t('tmux.mouseTip')}</span>
            <Button size="small" type="primary" loading={mouseBusy} onClick={enableMouse}>
              {t('tmux.enableMouse')}
            </Button>
            <button
              type="button"
              className={styles.mouseTipClose}
              aria-label={t('common.close')}
              title={t('common.close')}
              onClick={() => dismissMouseTip(sessionId)}
            >
              <X size={12} strokeWidth={2} />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

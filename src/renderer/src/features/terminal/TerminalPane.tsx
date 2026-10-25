import { IconButton } from '@/components/ui'
import { useCallback, useEffect, useRef, useState } from 'react'
import { App as AntdApp, Button, Dropdown, Spin } from 'antd'
import { Activity, Eraser, FolderTree, History, Search, Unplug } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import i18n from '@/i18n'
import { DEFAULT_SETTINGS, TERM_FONT_SIZE_MAX, TERM_FONT_SIZE_MIN } from '@shared/constants'
import { ofs } from '@/ipc/api'
import { useHistoryStore } from '@/stores/useHistoryStore'
import { useSettingsStore } from '@/stores/useSettingsStore'
import { useSessionStore, type SessionTab } from '@/stores/useSessionStore'
import { useConnectionStore } from '@/stores/useConnectionStore'
import { TitlebarSafeTooltip } from '@/components/TitlebarSafeTooltip'
import { bracketedUserPaste, plainUserPaste } from './bracketedUserPaste'
import { captureSubmitted, type PromptSnapshot } from './commandCapture'
import { emitShellCommand } from './commandEvents'
import { createTerminal, type TerminalBundle } from './createTerminal'
import { noteProgrammaticWrite, registerTerm, trackerFor, unregisterTerm } from './termRegistry'
import { HistoryOverlay } from './HistoryOverlay'
import { SearchOverlay } from './SearchOverlay'
import { resolveTerminalTheme } from '@/themes/terminal'
import { attachRemoteWheel } from './remoteWheel'
import { attachMouseCoordinates } from './mouseCoordinates'
import styles from './TerminalPane.module.css'

function isMacPlatform(): boolean {
  if (typeof navigator === 'undefined') return false
  const n = navigator as Navigator & { userAgentData?: { platform?: string } }
  return /mac/i.test([n.userAgentData?.platform, n.platform, n.userAgent].filter(Boolean).join(' '))
}

function hasPrimaryModifier(e: Pick<KeyboardEvent, 'ctrlKey' | 'metaKey'>): boolean {
  return isMacPlatform() ? e.metaKey : e.ctrlKey
}

interface Props {
  tab: SessionTab
  active: boolean
  uiMode: 'dark' | 'light'
}

/**
 * 回车之后等多久还没见到换行就放弃采集这一条。
 * 给得比一次往返宽裕（跨洋链路也够），但又不能长到让它跨到下一条命令上去。
 */
const SUBMIT_CAPTURE_WINDOW_MS = 3000

/**
 * 终端面板：一个 tab 一个常驻 xterm 实例（重连时复用，缓冲不丢）。
 * - 会话 ready（或 shellEpoch 递增）后量取 cols/rows 开 shell
 * - WebGL 仅在活动 tab 挂载
 * - ResizeObserver + 100ms 防抖 → fit → term:resize（尺寸为 0 时跳过，防 1×1 毁 vim 布局）
 */
export function TerminalPane({ tab, active, uiMode }: Props): React.JSX.Element {
  const { t } = useTranslation()
  const { modal } = AntdApp.useApp()
  const settings = useSettingsStore((s) => s.settings)!
  const bindTerm = useSessionStore((s) => s.bindTerm)
  const updateTab = useSessionStore((s) => s.updateTab)
  const closeTab = useSessionStore((s) => s.closeTab)
  const reconnectTab = useSessionStore((s) => s.reconnectTab)
  const toggleSftp = useSessionStore((s) => s.toggleSftp)
  const toggleMonitor = useSessionStore((s) => s.toggleMonitor)
  const profile = useConnectionStore((s) => s.profiles.find((p) => p.id === tab.profileId))

  const mountRef = useRef<HTMLDivElement>(null)
  const bundleRef = useRef<TerminalBundle | null>(null)
  const termIdRef = useRef<string | null>(null)
  const ptySizeRef = useRef<{ cols: number; rows: number } | null>(null)
  /**
   * 回车已按下、等换行回来采集的那一条。null = 此刻没有待采集的提交。
   * （为什么要等换行：见 commandCapture.ts 文件头）
   */
  const pendingSubmitRef = useRef<{ snap: PromptSnapshot; expiresAt: number } | null>(null)
  const openingRef = useRef(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [contextMenuOpen, setContextMenuOpen] = useState(false)
  const allowLocalMenuRef = useRef(true)
  const activeRef = useRef(active)
  activeRef.current = active

  const syncPtySize = useCallback((): void => {
    const term = bundleRef.current?.term
    const termId = termIdRef.current
    if (!term || !termId) return
    const { cols, rows } = term
    const previous = ptySizeRef.current
    if (previous?.cols === cols && previous.rows === rows) return
    ptySizeRef.current = { cols, rows }
    void ofs.invoke('term:resize', { termId, cols, rows })
  }, [])

  const sendResize = useCallback((): void => {
    const bundle = bundleRef.current
    const el = mountRef.current
    if (!bundle || !el || el.clientWidth === 0 || el.clientHeight === 0) return
    bundle.fit.fit()
    // Rebuild cached glyph positions even if a pixel resize keeps the same grid.
    bundle.term.clearTextureAtlas()
    bundle.term.refresh(0, bundle.term.rows - 1)
    syncPtySize()
  }, [syncPtySize])

  /**
   * 往当前终端写一段**程序生成**的文本（粘贴、命令历史回填）。
   *
   * 写之前先 `noteProgrammaticWrite`：这一行的"提示符末尾列"就此不可信 ——
   * 光标被写进去的内容推走了，而它不是用户敲的。不标记的话，用户在回填的命令后面
   * 再补几个字符然后回车，记进历史的就只有他补的那几个字符（语义见 commandCapture.ts）。
   */
  const writeToTerm = useCallback((data: string): void => {
    const termId = termIdRef.current
    if (!termId) return
    noteProgrammaticWrite(termId)
    ofs.send('term:input', { termId, data })
  }, [])

  /**
   * 调字号：不直接改本实例的 term.options，而是写回设置 ——
   * 所有 tab 经"字体热更新" effect 同步生效，且随设置持久化。
   * 钳制范围与设置面板的 InputNumber 是同一份常量。
   */
  const setFontSize = useCallback((next: number): void => {
    const store = useSettingsStore.getState()
    const terminal = store.settings?.terminal
    if (!terminal) return
    const clamped = Math.min(TERM_FONT_SIZE_MAX, Math.max(TERM_FONT_SIZE_MIN, next))
    if (clamped !== terminal.fontSize) store.patch({ terminal: { ...terminal, fontSize: clamped } })
  }, [])
  const adjustFontSize = useCallback(
    (delta: number): void => {
      const cur = useSettingsStore.getState().settings?.terminal.fontSize
      if (cur !== undefined) setFontSize(cur + delta)
    },
    [setFontSize]
  )

  const pasteText = useCallback((text: string): void => {
    if (!text || !termIdRef.current) return
    // 远端已要求整段粘贴时包进标记，换行不再逐行执行。
    // 没要求时仍逐行发送，但一行只发一个回车：剪贴板里的 \r\n 不能变成两次。
    const bracketed = bundleRef.current?.term.modes.bracketedPasteMode === true
    const payload = bracketed ? bracketedUserPaste(text) : plainUserPaste(text)
    if (!payload) return
    const target = termIdRef.current
    const focusTarget = (): void => {
      if (activeRef.current && termIdRef.current === target) bundleRef.current?.term.focus()
    }
    const doPaste = (): void => {
      if (termIdRef.current !== target) return
      writeToTerm(payload)
      focusTarget()
      // Let the menu's close/focus handling settle before restoring the caret.
      requestAnimationFrame(focusTarget)
    }
    if (!bracketed && useSettingsStore.getState().settings?.terminal.confirmMultilinePaste && /[\r\n]/.test(text)) {
      modal.confirm({
        title: t('terminal.multilinePasteTitle'),
        content: t('terminal.multilinePasteContent', { lines: text.split('\n').length }),
        okText: t('common.ok'),
        cancelText: t('common.cancel'),
        onOk: doPaste,
        afterClose: focusTarget
      })
    } else {
      doPaste()
    }
  }, [modal, t, writeToTerm])

  const pasteFromClipboard = useCallback(async (): Promise<void> => {
    const target = termIdRef.current
    if (!target) return
    const text = await navigator.clipboard.readText().catch(() => '')
    if (termIdRef.current === target) pasteText(text)
  }, [pasteText])
  const pasteTextRef = useRef(pasteText)
  const pasteClipboardRef = useRef(pasteFromClipboard)
  pasteTextRef.current = pasteText
  pasteClipboardRef.current = pasteFromClipboard

  // ---- 创建 xterm 实例（tab 存续期间常驻，重连不重建） ----
  useEffect(() => {
    const el = mountRef.current
    if (!el) return
    const bundle = createTerminal(settings, uiMode)
    bundleRef.current = bundle
    bundle.term.open(el)
    const mouseCoordinates = attachMouseCoordinates(bundle.term)
    bundle.term.onResize(syncPtySize)

    // Keyboard and IME edits affect prompt tracking; terminal protocol replies do not.
    const noteEditing = (): void => {
      const termId = termIdRef.current
      if (termId) trackerFor(termId).noteKeystroke(bundle.term.buffer.active)
    }
    bundle.term.onKey(noteEditing)
    el.addEventListener('compositionstart', noteEditing, true)
    el.addEventListener('beforeinput', noteEditing, true)

    bundle.term.onData((data) => {
      const termId = termIdRef.current
      if (!termId) return
      ofs.send('term:input', { termId, data })
    })

    bundle.term.onBinary((data) => {
      const termId = termIdRef.current
      if (termId) ofs.send('term:binary', { termId, data: Uint8Array.from(data, char => char.charCodeAt(0)) })
    })

    bundle.term.onSelectionChange(() => {
      if (useSettingsStore.getState().settings?.terminal.copyOnSelect) {
        const sel = bundle.term.getSelection()
        if (sel) void navigator.clipboard.writeText(sel).catch(() => {})
      }
    })

    const onPaste = (event: ClipboardEvent): void => {
      event.preventDefault()
      event.stopImmediatePropagation()
      if (event.clipboardData) pasteTextRef.current(event.clipboardData.getData('text/plain'))
      else void pasteClipboardRef.current()
    }
    el.addEventListener('paste', onPaste, true)
    const remoteWheel = attachRemoteWheel(el, bundle.term)

    // 组词中（IME）一律放行，避免候选上屏时误触快捷键
    const localKeys = new Set<string>()
    const consumeLocalKey = (event: KeyboardEvent): false => {
      event.preventDefault()
      localKeys.add(event.code)
      return false
    }
    bundle.term.attachCustomKeyEventHandler((ev) => {
      // Enhanced keyboard modes report releases too. A local shortcut must not
      // leak an orphan key release into the remote CLI.
      if (ev.type === 'keyup' && localKeys.delete(ev.code)) {
        ev.preventDefault()
        return false
      }
      if (ev.isComposing || ev.keyCode === 229) return true
      if (ev.type !== 'keydown') return true
      if (hasPrimaryModifier(ev) && ev.shiftKey && ev.code === 'KeyC') {
        const sel = bundle.term.getSelection()
        if (sel) {
          void navigator.clipboard.writeText(sel).catch(() => {})
          return consumeLocalKey(ev)
        }
        return true
      }
      if (hasPrimaryModifier(ev) && ev.shiftKey && ev.code === 'KeyV') {
        // return false 只阻止 xterm 处理按键；还要取消浏览器默认粘贴，
        // 否则随后触发的 paste 事件会经 xterm.onData 再发送同一份内容。
        void pasteClipboardRef.current()
        return consumeLocalKey(ev)
      }
      if (hasPrimaryModifier(ev) && !ev.shiftKey && ev.code === 'KeyF') {
        setSearchOpen(true)
        return consumeLocalKey(ev)
      }
      if (hasPrimaryModifier(ev) && ev.shiftKey && ev.code === 'KeyH') {
        setHistoryOpen(true)
        return consumeLocalKey(ev)
      }
      /*
       * Ctrl/Cmd+= / Ctrl/Cmd+- / Ctrl/Cmd+0 调字号（与 Ctrl/Cmd+滚轮同一条通路）。
       * 用 ev.key 而不是 code：数字键盘的 +/- 也该生效。
       * 两条刻意的排除：Ctrl/Cmd+Shift+-（key 为 '_'）是 readline 的 undo（0x1F），不许抢；
       * '+' 不排 shift —— 多数布局 '+' 本来就要按 shift 才打得出。
       */
      if (hasPrimaryModifier(ev) && !ev.altKey && (isMacPlatform() ? !ev.ctrlKey : !ev.metaKey)) {
        if (ev.key === '=' || ev.key === '+') {
          adjustFontSize(1)
          return consumeLocalKey(ev)
        }
        if (ev.key === '-' && !ev.shiftKey) {
          adjustFontSize(-1)
          return consumeLocalKey(ev)
        }
        // '0' 不排 shift：法语 AZERTY 等布局的数字本来就要按 Shift 才打得出，
        // 而美式布局 Ctrl+Shift+0 产生的 key 是 ')'，根本到不了这个分支 —— 排了纯属误伤
        if (ev.key === '0') {
          setFontSize(DEFAULT_SETTINGS.terminal.fontSize)
          return consumeLocalKey(ev)
        }
      }
      /*
       * 命令采集的第二半：回车**按下**的这一刻只做两件轻活 —— 把"提示符在第几列"
       * 快照下来，并让追踪器认为这一行已提交。**不在这里读屏**：屏幕上的字全靠
       * 服务器回显，此刻用户敲的最后几个字符（以及 Tab 补全的结果）可能还在路上，
       * 读到的会是被截断的命令（详见 commandCapture.ts 文件头那段事故记录）。
       * 真正的采集在下面 onLineFeed 里。
       *
       * 一律 `return true` —— 采集不许影响回车本身。真出了异常也只是这一条没记上，
       * 绝不能让终端吞掉一次回车（那是"这个软件坏了"级别的表现）。
       */
      if (ev.key === 'Enter' && !ev.ctrlKey && !ev.altKey && !ev.metaKey && !ev.shiftKey) {
        try {
          const termId = termIdRef.current
          if (termId) {
            const tracker = trackerFor(termId)
            pendingSubmitRef.current = {
              snap: tracker.snapshot(),
              // 只等这一小会儿：换行迟迟不来（比如 stty -echo 的口令提问）就放弃这一条，
              // 也免得它挂在那里，被后面某条无关输出的换行当成命令采走
              expiresAt: Date.now() + SUBMIT_CAPTURE_WINDOW_MS
            }
            tracker.noteSubmit()
          }
        } catch {
          /* 采集永不阻断按键 */
        }
        return true
      }
      return true
    })

    /*
     * 提交后的采集。服务器处理输入是有序的：先回显剩下的字符（含 Tab 补全结果），
     * 再回显命令行那个换行。所以**第一个换行到达**时，那一行必然已经完整。
     */
    bundle.term.onLineFeed(() => {
      const pending = pendingSubmitRef.current
      if (!pending) return
      pendingSubmitRef.current = null
      if (Date.now() > pending.expiresAt) return
      try {
        const command = captureSubmitted(bundle.term.buffer.active, pending.snap)
        if (!command) return
        // 采集一次、两处消费。「记录命令历史」的开关只管历史那一份 ——
        // SFTP 的 cd 跟随不归它管：关掉历史的用户不该连目录跟随一起失去
        if (useSettingsStore.getState().settings?.terminal.saveCommandHistory) {
          useHistoryStore.getState().push(command)
        }
        emitShellCommand(tab.id, command)
      } catch {
        /* 采集失败只是少记一条，绝不影响终端 */
      }
    })

    // Ctrl+滚轮调字号。capture 阶段接：xterm 自己的 viewport wheel 监听在后代节点上，
    // 不截住的话按着 Ctrl 滚动还会同时滚缓冲区。
    // deltaY 必须累积成"格"再走步：分立滚轮每格一个 ±100/120 的事件，一格一步没问题；
    // 精密触控板/捏合被 Chromium 合成为每秒几十个小 deltaY 的 ctrl+wheel 事件，
    // 按事件计步的话一次轻扫就把字号打到 8/32 端点，顺带几十次 settings 落库 + 全终端 refit
    let wheelAcc = 0
    const onWheel = (ev: WheelEvent): void => {
      if (!hasPrimaryModifier(ev) || ev.deltaY === 0) return
      ev.preventDefault()
      ev.stopPropagation()
      // 方向反转时旧余量作废，否则上一手势剩的零头会吃掉反方向的第一步
      if (Math.sign(ev.deltaY) !== Math.sign(wheelAcc)) wheelAcc = 0
      // DOM_DELTA_LINE（少见）约 3 行一格；像素模式按 100px 一格。
      // 累加原始 delta、走步时才除 —— 先除后加的话 0.1×10 是 0.999…，恰好整格的手势走不了步
      const unit = ev.deltaMode === WheelEvent.DOM_DELTA_LINE ? 3 : 100
      wheelAcc += ev.deltaY
      const steps = Math.trunc(wheelAcc / unit)
      if (steps !== 0) {
        wheelAcc -= steps * unit
        adjustFontSize(-steps) // deltaY 向上为负 = 放大
      }
    }
    el.addEventListener('wheel', onWheel, { passive: false, capture: true })

    let raf = 0
    let timer: ReturnType<typeof setTimeout> | null = null
    const observer = new ResizeObserver(() => {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        raf = requestAnimationFrame(() => sendResize())
      }, 100)
    })
    observer.observe(el)

    return () => {
      remoteWheel.dispose()
      mouseCoordinates.dispose()
      el.removeEventListener('paste', onPaste, true)
      el.removeEventListener('compositionstart', noteEditing, true)
      el.removeEventListener('beforeinput', noteEditing, true)
      el.removeEventListener('wheel', onWheel, { capture: true })
      observer.disconnect()
      if (timer) clearTimeout(timer)
      cancelAnimationFrame(raf)
      if (termIdRef.current) {
        const dying = termIdRef.current
        unregisterTerm(dying)
        void ofs.invoke('term:close', dying).catch(() => {})
        termIdRef.current = null
        ptySizeRef.current = null
        // 同步把 store 里的 termId 清掉。不清的话，万一本组件是被"重挂"而不是真的关掉
        // （父级结构变化就会这样），新实例会看到一个指向已关闭 shell 的 tab.termId，
        // 于是开 shell 的 effect 直接 return —— 留下一个永远空白、也不会自愈的终端。
        useSessionStore.setState((s) => ({
          tabs: s.tabs.map((t) => (t.termId === dying ? { ...t, termId: null } : t))
        }))
      }
      bundle.dispose()
      bundleRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const onContextMenu = useCallback(
    (e: React.MouseEvent): void => {
      const remoteOwnsMouse = bundleRef.current?.term.modes.mouseTrackingMode !== 'none' && !!bundleRef.current && !e.shiftKey
      allowLocalMenuRef.current = !remoteOwnsMouse
      if (remoteOwnsMouse) {
        e.preventDefault()
        e.stopPropagation()
        return
      }
      if (settings.terminal.rightClick === 'paste') {
        e.preventDefault()
        void pasteFromClipboard()
      }
    },
    [pasteFromClipboard, settings.terminal.rightClick]
  )

  // ---- 会话 ready / 重连（shellEpoch 变化）→ 开 shell ----
  useEffect(() => {
    const bundle = bundleRef.current
    if (!bundle || tab.state !== 'ready' || tab.termId || !tab.sessionId || openingRef.current) return
    openingRef.current = true
    sendResize()
    const { cols, rows } = bundle.term
    void ofs
      .invoke('term:open', { sessionId: tab.sessionId, cols, rows })
      .then(async ({ termId }) => {
        if (bundleRef.current !== bundle) {
          await ofs.invoke('term:close', termId)
          return
        }
        termIdRef.current = termId
        ptySizeRef.current = { cols, rows }
        registerTerm(termId, bundle.term)
        bindTerm(tab.id, termId)
        // Layout may have changed while the SSH shell request was in flight.
        sendResize()
        if (tab.shellEpoch > 0) {
          bundle.term.write(`\r\n\x1b[2m—— ${i18n.t('terminal.reconnected')} ——\x1b[0m\r\n`)
        }
        await ofs.invoke('term:ready', termId)
        if (active && bundleRef.current === bundle) bundle.term.focus()
      })
      .catch((err: Error) => {
        updateTab(tab.id, { state: 'closed', error: err.message })
      })
      .finally(() => {
        openingRef.current = false
      })
  }, [
    tab.state,
    tab.termId,
    tab.sessionId,
    tab.shellEpoch,
    tab.id,
    active,
    bindTerm,
    updateTab,
    sendResize
  ])

  // ---- WebGL 只挂活动 tab；激活后补 fit + focus ----
  useEffect(() => {
    const bundle = bundleRef.current
    if (!bundle) return
    if (active) {
      if (settings.terminal.webgl) bundle.attachWebgl()
      else bundle.detachWebgl()
      sendResize()
      bundle.term.focus()
    } else {
      bundle.detachWebgl()
    }
  }, [active, settings.terminal.webgl, sendResize])

  // ---- 主题热更新 ----
  useEffect(() => {
    const bundle = bundleRef.current
    if (bundle) {
      bundle.term.options.theme = resolveTerminalTheme(settings.terminal.themeId, uiMode)
    }
  }, [uiMode, settings.terminal.themeId])

  // ---- 字体热更新：设置改了要作用于**已开的**终端，不是只影响下一个 tab ----
  useEffect(() => {
    const bundle = bundleRef.current
    if (!bundle) return
    const t = settings.terminal
    const opts = bundle.term.options
    if (opts.fontSize !== t.fontSize) opts.fontSize = t.fontSize
    if (opts.fontFamily !== t.fontFamily) opts.fontFamily = t.fontFamily
    if (opts.lineHeight !== t.lineHeight) opts.lineHeight = t.lineHeight
    // cell 尺寸变了：refit 并把新 cols/rows 通知 PTY（sendResize 内部只在真变时 invoke）
    sendResize()
  }, [settings.terminal.fontSize, settings.terminal.fontFamily, settings.terminal.lineHeight, sendResize])

  const connecting = tab.state === 'connecting' || tab.state === 'authenticating'
  const reconnecting = tab.state === 'reconnecting'
  const closed = tab.state === 'closed'

  const menuItems =
    settings.terminal.rightClick === 'menu'
      ? [
          { key: 'copy', label: t('terminal.copy') },
          { key: 'paste', label: t('terminal.paste') },
          { key: 'selectAll', label: t('terminal.selectAll') },
          { type: 'divider' as const },
          { key: 'clear', label: t('terminal.clear') },
          { key: 'search', label: t('terminal.search') },
          { key: 'history', label: t('terminal.history') },
          { type: 'divider' as const },
          { key: 'disconnect', label: t('terminal.disconnect'), danger: true }
        ]
      : []

  const onMenuClick = (key: string): void => {
    const bundle = bundleRef.current
    if (!bundle) return
    if (key === 'copy') {
      const sel = bundle.term.getSelection()
      if (sel) void navigator.clipboard.writeText(sel).catch(() => {})
    } else if (key === 'paste') void pasteFromClipboard()
    else if (key === 'selectAll') bundle.term.selectAll()
    else if (key === 'clear') bundle.term.clear()
    else if (key === 'search') setSearchOpen(true)
    else if (key === 'history') setHistoryOpen(true)
    else if (key === 'disconnect') void closeTab(tab.id)
  }

  const paneBody = (
    <div className={styles.pane} onContextMenu={onContextMenu}>
      <div ref={mountRef} className={styles.mount} />

      {active && !connecting && !closed && (
        <div className={styles.hoverTools}>
          <TitlebarSafeTooltip title={tab.sftpOpen ? t('terminal.closeSftp') : t('terminal.openSftp')}>
            <IconButton label={tab.sftpOpen ? t('terminal.closeSftp') : t('terminal.openSftp')}
              size="small"
              selected={tab.sftpOpen}
              icon={<FolderTree size={14} strokeWidth={1.75} />}
              onClick={() => toggleSftp(tab.id)}
            />
          </TitlebarSafeTooltip>
          <TitlebarSafeTooltip title={tab.monitorOpen ? t('terminal.closeMonitor') : t('terminal.openMonitor')}>
            <IconButton label={tab.monitorOpen ? t('terminal.closeMonitor') : t('terminal.openMonitor')}
              size="small"
              selected={tab.monitorOpen}
              icon={<Activity size={14} strokeWidth={1.75} />}
              onClick={() => toggleMonitor(tab.id)}
            />
          </TitlebarSafeTooltip>
          <TitlebarSafeTooltip title={t('terminal.search')}>
            <IconButton label={t('terminal.search')}
              size="small"
              icon={<Search size={14} strokeWidth={1.75} />}
              onClick={() => setSearchOpen(true)}
            />
          </TitlebarSafeTooltip>
          <TitlebarSafeTooltip title={t('terminal.historyTip')}>
            <IconButton label={t('terminal.historyTip')}
              size="small"
              selected={historyOpen}
              icon={<History size={14} strokeWidth={1.75} />}
              onClick={() => setHistoryOpen((v) => !v)}
            />
          </TitlebarSafeTooltip>
          <TitlebarSafeTooltip title={t('terminal.clear')}>
            <IconButton label={t('terminal.clear')}
              size="small"
              icon={<Eraser size={14} strokeWidth={1.75} />}
              onClick={() => bundleRef.current?.term.clear()}
            />
          </TitlebarSafeTooltip>
          <TitlebarSafeTooltip title={t('terminal.disconnect')}>
            <IconButton label={t('terminal.disconnect')}
              size="small"
              danger
              icon={<Unplug size={14} strokeWidth={1.75} />}
              onClick={() => void closeTab(tab.id)}
            />
          </TitlebarSafeTooltip>
        </div>
      )}

      {historyOpen && (
        <HistoryOverlay
          onInsert={writeToTerm}
          onClose={() => {
            setHistoryOpen(false)
            bundleRef.current?.term.focus()
          }}
        />
      )}

      {searchOpen && bundleRef.current && (
        <SearchOverlay
          search={bundleRef.current.search}
          accent={settings.accent}
          onClose={() => {
            setSearchOpen(false)
            bundleRef.current?.term.focus()
          }}
        />
      )}

      {connecting && (
        <div className={styles.overlay}>
          <Spin />
          <div className={styles.overlayText}>{t('terminal.connecting', { target: tab.title })}</div>
          <Button size="small" onClick={() => void closeTab(tab.id)}>
            {t('common.cancel')}
          </Button>
        </div>
      )}

      {reconnecting && (
        <div className={styles.overlay}>
          <Spin />
          <div className={`${styles.overlayText} ${styles.overlayWarning}`}>
            {tab.error ?? t('terminal.reconnecting')}
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button size="small" type="primary" onClick={() => void reconnectTab(tab.id)}>
              {t('terminal.reconnectNow')}
            </Button>
            <Button size="small" onClick={() => void closeTab(tab.id)}>
              {t('common.close')}
            </Button>
          </div>
        </div>
      )}

      {closed && (
        <div className={styles.overlay}>
          <div className={`${styles.overlayText} ${styles.overlayError}`}>
            {tab.error ?? t('terminal.disconnected')}
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button
              size="small"
              type="primary"
              disabled={!profile}
              onClick={() => void reconnectTab(tab.id)}
            >
              {t('terminal.reconnect')}
            </Button>
            <Button size="small" onClick={() => void closeTab(tab.id)}>
              {t('common.close')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )

  return (
    <Dropdown
      trigger={menuItems.length > 0 ? ['contextMenu'] : []}
      open={menuItems.length > 0 && contextMenuOpen}
      onOpenChange={(open) => setContextMenuOpen(open && allowLocalMenuRef.current)}
      menu={{ items: menuItems, onClick: ({ key }) => onMenuClick(key) }}
    >
      {paneBody}
    </Dropdown>
  )
}

import { shQuote } from '../ssh/shellQuote'
import { SENTINEL } from './script'

/**
 * Tmux 会话管理的远端脚本。
 *
 * 列表帧走持久 sh 通道（与进程面板 / 端口流量同款），每 2s 一帧；
 * 新建 / 关闭 / 开鼠标 / has-session 是低频操作，走 ExecRunner.execOnce。
 *
 * 段名只能是大写字母（splitSections 约束）：HASTMUX / VERSION / SESSIONS /
 * ERROR / MOUSE。
 *
 * ExecRunner 会在脚本末尾追加 RC 哨兵，所以脚本里不要真 `exit`——想指定状态用
 * `(exit N)` 把码留在 `$?` 里。列表帧写在常驻 sh 的 stdin 上，不经过 ExecRunner，
 * 但同样不 `exit`（通道要活着跑下一帧）。
 */

/** 首帧探测：有没有 tmux。no → unsupported（面板显示未安装提示）。 */
export function buildTmuxProbeFrame(seq: number): string {
  return [
    `echo "${SENTINEL.begin(seq)}"`,
    `echo "${SENTINEL.section('HASTMUX')}"`,
    'if command -v tmux >/dev/null 2>&1; then echo yes; else echo no; fi',
    `echo "${SENTINEL.section('VERSION')}"`,
    'tmux -V 2>/dev/null',
    `echo "${SENTINEL.end(seq)}"`
  ].join('\n') + '\n'
}

/**
 * 常规列表帧：会话列表 + 全局 mouse 选项。
 *
 * 退出码 1 **不等于**「没有 server」—— tmux 把权限 / socket / 配置损坏也报成 1，
 * 必须按 stderr 文本区分：no-server 是合法空态（SESSIONS 段为空正文），其余错误
 * 打出 ERROR 段，采集器把面板置为 failed 并透出前三行。
 *
 * 用可打印的 `|` 分隔字段：C locale 下 tmux 会把格式中的制表符改成下划线。
 * session_name 放最后，名称中的 `|` 归入第五列；解析侧另校验 id 形如 `$N`。
 */
export function buildTmuxListFrame(seq: number): string {
  return [
    `echo "${SENTINEL.begin(seq)}"`,
    `echo "${SENTINEL.section('SESSIONS')}"`,
    "out=$(tmux list-sessions -F '#{session_id}|#{session_windows}|#{session_attached}|#{session_created}|#{session_name}' 2>&1)",
    'rc=$?',
    'if [ "$rc" -eq 0 ]; then',
    '  printf \'%s\\n\' "$out"',
    'else',
    '  case "$out" in',
    '    *"no server running"*|"error connecting to "**"No such file or directory"*) : ;;',
    `    *) echo "${SENTINEL.section('ERROR')}"; printf '%s\\n' "$out" | head -n 3 ;;`,
    '  esac',
    'fi',
    `echo "${SENTINEL.section('MOUSE')}"`,
    'tmux show-options -gv mouse 2>/dev/null',
    `echo "${SENTINEL.end(seq)}"`
  ].join('\n') + '\n'
}

/**
 * 新建会话（-d 不附着，-P 打印新会话 id）。stdout 第一行即 `#{session_id}`。
 * 名称已在上游过 zod（非空、≤64、无控制字符、不以 `-` 开头），这里再 shQuote 一道。
 */
export function buildTmuxNewSessionScript(name: string): string {
  return `tmux new-session -d -P -F '#{session_id}' -s ${shQuote(name)}`
}

/** 关闭会话。目标一律用 session_id（`$N`），名称只作显示。 */
export function buildTmuxKillSessionScript(targetId: string): string {
  return `tmux kill-session -t ${shQuote(targetId)}`
}

/** 开启 / 关闭全局鼠标支持（面板提示条的一键开启走 on=true）。 */
export function buildTmuxSetMouseScript(on: boolean): string {
  return `tmux set-option -g mouse ${on ? 'on' : 'off'}`
}

/** 附着前检查：退出码 0 = 存在，1 = 不存在（有竞态窗口，见方案 §4）。 */
export function buildTmuxHasSessionScript(targetId: string): string {
  return `tmux has-session -t ${shQuote(targetId)}`
}

/**
 * 附着命令（不是帧、也不是 execOnce 脚本）：整条交给 client.exec 并带 pty 对象。
 * pty 必须传对象 —— 传 true 时 ssh2 用默认值（TERM=vt100、80×24），tmux 会按
 * 80×24 协商窗口并可能因 vt100 降级 terminfo（方案 §5）。
 */
export function buildTmuxAttachCommand(targetId: string): string {
  return `tmux attach -t ${shQuote(targetId)}`
}

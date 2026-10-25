import { PROCESS_PANEL_MAX_PROCS } from '@shared/constants'
import { shQuote } from '../ssh/shellQuote'
import { SENTINEL } from './script'

/**
 * 进程列表面板的远端采集脚本。
 *
 * 列表帧走持久 sh 通道（与端口流量同款），避免每 2s 新建 exec 耗尽 MaxSessions。
 * 详情 / kill 是低频操作，走 ExecRunner.execOnce。
 *
 * 段名只能是大写字母（splitSections 约束）：PROCS / NOPS / HASPSSORT / NAME / LOC /
 * CWD / CMDLINE / ENV。
 *
 * ExecRunner 会在脚本末尾追加 RC 哨兵，所以脚本里不要真 `exit`——想指定状态用
 * `(exit N)` 把码留在 `$?` 里，否则哨兵永不输出、只能退化到可选的 SSH exit-status。
 */

/** 远端 head 截断：+1 给表头行；解析侧仍有 PROCESS_PANEL_MAX_PROCS 上限。 */
const LIST_HEAD_LINES = PROCESS_PANEL_MAX_PROCS + 1

/** 探测 + 首帧列表。HASPSSORT 决定后续用 procps 还是 BusyBox 回落。 */
export function buildProcessProbeFrame(seq: number): string {
  return [
    `echo "${SENTINEL.begin(seq)}"`,
    `echo "${SENTINEL.section('HASPSSORT')}"`,
    'ps -eo pid,user,rss,pcpu,args --sort=-pcpu >/dev/null 2>&1 && echo yes || echo no',
    `echo "${SENTINEL.section('PROCS')}"`,
    // 探测帧先按 procps 试一次；失败则正文为空，collector 会按 HASPSSORT=no 再采。
    // head 防止进程极多时单帧超 PROCESS_PANEL_MAX_FRAME_BYTES 后永久 failed。
    `ps -eo pid,user,rss,pcpu,args --sort=-pcpu 2>/dev/null | head -n ${LIST_HEAD_LINES}`,
    `echo "${SENTINEL.end(seq)}"`
  ].join('\n') + '\n'
}

/** 常规列表帧。hasPsSort=false 时用 POSIX 管线，兼容 BusyBox。 */
export function buildProcessListFrame(seq: number, hasPsSort: boolean): string {
  // procps：pid user rss(KB) pcpu args… —— args 吸收剩余列，解析侧 slice(4)。
  // BusyBox：完整 aux，解析侧走 parseProcessAux。
  const listCmd = hasPsSort
    ? `ps -eo pid,user,rss,pcpu,args --sort=-pcpu 2>/dev/null | head -n ${LIST_HEAD_LINES}`
    : `ps aux 2>/dev/null | head -n ${LIST_HEAD_LINES}`
  return [
    `echo "${SENTINEL.begin(seq)}"`,
    `echo "${SENTINEL.section('PROCS')}"`,
    // 没有可用 ps 时打 NOPS，让面板明确降级（Windows OpenSSH / 极简容器）。
    `if command -v ps >/dev/null 2>&1; then ${listCmd}; else echo "${SENTINEL.section('NOPS')}"; fi`,
    `echo "${SENTINEL.end(seq)}"`
  ].join('\n') + '\n'
}

/**
 * 按需拉单个进程详情。pid 必须已是十进制数字串（调用方校验），这里再 shQuote 一道。
 * LOC 而不是 LOCATION：段名只能大写字母，不能有下划线。
 */
export function buildProcessDetailScript(pid: number): string {
  const p = shQuote(String(pid))
  return [
    `PID=${p}`,
    'if [ ! -d "/proc/$PID" ]; then',
    '  (exit 3)',
    'else',
    `  echo "${SENTINEL.section('NAME')}"`,
    '  tr -d "\\n" < "/proc/$PID/comm" 2>/dev/null; echo',
    `  echo "${SENTINEL.section('LOC')}"`,
    '  readlink "/proc/$PID/exe" 2>/dev/null; echo',
    `  echo "${SENTINEL.section('CWD')}"`,
    '  readlink "/proc/$PID/cwd" 2>/dev/null; echo',
    `  echo "${SENTINEL.section('CMDLINE')}"`,
    '  tr "\\0" " " < "/proc/$PID/cmdline" 2>/dev/null; echo',
    `  echo "${SENTINEL.section('ENV')}"`,
    '  tr "\\0" "\\n" < "/proc/$PID/environ" 2>/dev/null',
    '  (exit 0)',
    'fi'
  ].join('\n')
}

/** SIGTERM（默认）或 SIGKILL。pid 已校验为有限正整数。 */
export function buildProcessKillScript(pid: number, force: boolean): string {
  const p = shQuote(String(pid))
  const sig = force ? 'KILL' : 'TERM'
  // `--` 防止异常 pid 被当成选项。保留 kill 的 stderr（权限不足时用户要看得到）。
  // exit 3 = 进程不存在；(exit N) 而非 exit，好让 ExecRunner 的 RC 哨兵跑到。
  return [
    `PID=${p}`,
    `kill -s ${sig} -- "$PID"`,
    '__ofs_kill=$?',
    'if [ "$__ofs_kill" -eq 0 ]; then (exit 0)',
    'elif [ ! -d "/proc/$PID" ] && [ ! -e "/proc/$PID" ]; then (exit 3)',
    'else (exit "$__ofs_kill")',
    'fi'
  ].join('\n')
}

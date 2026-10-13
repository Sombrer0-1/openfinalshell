/** Windows 剪贴板一行是 `\r\n`。两个都发出去，shell 会当成两次回车，heredoc 里就隔一行空一行。 */
function toCarriageReturns(text: string): string {
  return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').replace(/\n/g, '\r')
}

/** 远端没有要求整段粘贴时使用。每个换行只发一个 `\r`，和键盘回车相同。 */
export function plainUserPaste(text: string): string {
  return toCarriageReturns(text)
}

/**
 * 用户粘贴进终端时使用。只在远端 shell 已经打开 bracketed paste 时调用。
 * 换行收成 `\r` 放进标记里，末尾换行去掉，粘贴本身不会被当成回车。
 * 模式没打开时不能调用：那两个标记会变成屏幕上的可见字符。
 */
export function bracketedUserPaste(text: string): string {
  const body = toCarriageReturns(text).replace(/\r+$/, '')
  if (!body) return ''
  return `\x1b[200~${body}\x1b[201~`
}

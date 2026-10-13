import { describe, expect, it } from 'vitest'
import { bracketedUserPaste, plainUserPaste } from '@/features/terminal/bracketedUserPaste'
import { read, stripComments } from '../sourceGuard'

const heredoc = "sudo tee /usr/local/sbin/he-ipv6-up.sh > /dev/null <<'EOF'\r\n#!/usr/bin/env bash\r\nset -euo pipefail\r\n\r\n# Fremont-2 HE Tunnel\r\n\r\nEOF\r\n"

describe('用户粘贴的 bracketed paste', () => {
  it('把 CRLF 收成一段，末尾换行去掉，粘贴本身不提交', () => {
    expect(bracketedUserPaste(heredoc)).toBe(
      "\x1b[200~sudo tee /usr/local/sbin/he-ipv6-up.sh > /dev/null <<'EOF'\r#!/usr/bin/env bash\rset -euo pipefail\r\r# Fremont-2 HE Tunnel\r\rEOF\x1b[201~"
    )
  })

  it('只含换行时不产生标记', () => {
    expect(bracketedUserPaste('\r\n\n')).toBe('')
  })

  it('逐行粘贴时 CRLF 只算一次回车，heredoc 不会隔行变空', () => {
    const pasted = "sudo tee /home/zhuzetong/123.py > /dev/null <<'EOF'\r\n#hello world\r\n#hahaha\r\nEOF\r\n"
    expect(plainUserPaste(pasted)).toBe(
      "sudo tee /home/zhuzetong/123.py > /dev/null <<'EOF'\r#hello world\r#hahaha\rEOF\r"
    )
  })

  it('终端面板不借用 AI 填入的准备函数', () => {
    const pane = stripComments(read('src/renderer/src/features/terminal/TerminalPane.tsx'))
    const paste = stripComments(read('src/renderer/src/features/terminal/bracketedUserPaste.ts'))
    expect(pane).toContain('bracketedUserPaste(')
    expect(pane).not.toContain('prepareTerminalPaste')
    expect(pane).not.toContain('terminalPaste')
    expect(paste).not.toContain('prepareTerminalPaste')
    expect(paste).not.toContain('features/ai')
  })
})

// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { act, cleanup, render } from '@testing-library/react'
import { EditorView } from '@codemirror/view'
import { undo } from '@codemirror/commands'
import { CodeEditor } from '@/features/editor/CodeEditor'
import { applyCssVars } from '@/themes/applyTheme'

afterEach(cleanup)

it('keeps real CodeMirror drafts and undo history through file and theme switches', () => {
  const docRef = { current: null as (() => string) | null }
  const props = { language: 'plain' as const, readOnly: false, openKeys: ['a', 'b'], onDirty: vi.fn(), onSave: vi.fn(), docRef }
  const { container, rerender } = render(<CodeEditor {...props} fileKey="a" text="original" />)
  const element = container.querySelector('.cm-editor') as HTMLElement
  const view = EditorView.findFromDOM(element)!
  expect(view).toBeTruthy()
  act(() => view.dispatch({ changes: { from: 8, insert: ' draft' } }))
  expect(docRef.current?.()).toBe('original draft')
  for (let i = 0; i < 20; i++) {
    applyCssVars(i % 2 ? 'light' : 'dark', i % 2 ? '#faad14' : '#1677ff')
    rerender(<CodeEditor {...props} fileKey="b" text="other file" />)
    expect(docRef.current?.()).toBe('other file')
    rerender(<CodeEditor {...props} fileKey="a" text="original" />)
    expect(docRef.current?.()).toBe('original draft')
    expect(container.querySelector('.cm-editor')).toBe(element)
  }
  act(() => { expect(undo(view)).toBe(true) })
  expect(docRef.current?.()).toBe('original')
})

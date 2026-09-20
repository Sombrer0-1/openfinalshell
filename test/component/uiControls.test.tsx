// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { IconButton, UiButton } from '@/components/ui'

afterEach(cleanup)

it('blocks repeat actions while loading and becomes actionable after failure/retry', () => {
  const click = vi.fn()
  const { rerender } = render(<UiButton onClick={click}>Save</UiButton>)
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  rerender(<UiButton loading onClick={click}>Save</UiButton>)
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  expect(click).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button').getAttribute('aria-busy')).toBe('true')
  rerender(<UiButton onClick={click}>Save</UiButton>)
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
  expect(click).toHaveBeenCalledTimes(2)
})

it('forwards tooltip focus events and ref to a named native button without submitting forms', () => {
  const ref = createRef<HTMLButtonElement>()
  const focus = vi.fn(), submit = vi.fn((e: React.FormEvent) => e.preventDefault())
  render(<form onSubmit={submit}><IconButton ref={ref} label="Refresh" onFocus={focus} selected icon={<svg />} /></form>)
  const button = screen.getByRole('button', { name: 'Refresh' })
  expect(ref.current).toBe(button)
  expect(button.getAttribute('aria-pressed')).toBe('true')
  fireEvent.focus(button)
  fireEvent.click(button)
  expect(focus).toHaveBeenCalledTimes(1)
  expect(submit).not.toHaveBeenCalled()
})

import type { OfsTheme } from './types'

/**
 * 两套 UI 主题的语法配色分别对齐 **One Dark**（深色）与 **GitHub Light**（浅色）——
 * 也就是这两个主题各自的 `terminalThemeId`。这样内置编辑器与它旁边的终端并排时
 * 是同一套色感，而不是两个各自好看、放一起打架的配色。
 *
 * 选中色与光标用 `color-mix(… var(--ofs-accent) …)` 跟着用户选的强调色走：
 * 8 种强调色都要能在这两个背景上读得清，所以混的是**透明度**而不是替换色相。
 */
export const darkTheme: OfsTheme = {
  id: 'dark',
  ui: {
    bgBase: '#14171d',
    bgPanel: '#1b2028',
    bgElevated: '#242b35',
    bgInset: '#101319',
    bgRaised: '#2c3541',
    bgHover: 'rgba(255,255,255,.06)',
    bgActive: 'rgba(255,255,255,.10)',
    glassSurface: 'rgba(27,32,40,.88)',
    glassSurfaceStrong: 'rgba(36,43,53,.97)',
    solidSurface: '#1b2028',
    border: '#35404e',
    borderFaint: '#2a323e',
    borderStrong: '#8492a5',
    glassBorder: 'rgba(255,255,255,.12)',
    textPrimary: '#edf1f7',
    textSecondary: '#b8c2d1',
    textSubtle: '#a0adbf',
    textDisabled: '#707783',
    success: '#88d99a',
    warning: '#f2c66d',
    error: '#ff9aab',
    shadowPanel: 'inset 0 1px 0 rgba(255,255,255,.035)',
    shadowModal: '0 12px 40px rgba(0,0,0,.32)'
  },
  syntax: {
    comment: '#6b727f',
    keyword: '#c678dd',
    string: '#98c379',
    number: '#d19a66',
    constant: '#d19a66',
    property: '#e06c75',
    variable: '#e5c07b',
    function: '#61afef',
    type: '#e5c07b',
    operator: '#56b6c2',
    punctuation: '#9aa1ab',
    tag: '#e06c75',
    attribute: '#d19a66',
    invalid: '#ff6b6b',
    meta: '#56b6c2'
  },
  editor: {
    bg: '#16181d',
    fg: '#abb2bf',
    gutterBg: 'transparent',
    gutterFg: '#4b5263',
    gutterActiveFg: '#9aa1ab',
    activeLine: 'rgba(255,255,255,.035)',
    selection: 'color-mix(in srgb, var(--ofs-accent) 32%, transparent)',
    selectionMatch: 'color-mix(in srgb, var(--ofs-accent) 16%, transparent)',
    searchMatch: 'rgba(250,173,20,.28)',
    searchMatchActive: 'rgba(250,173,20,.55)',
    cursor: 'var(--ofs-accent)',
    foldPlaceholder: '#5c636e',
    specialChar: '#f5222d'
  },
  terminalThemeId: 'one-dark'
}

export const lightTheme: OfsTheme = {
  id: 'light',
  ui: {
    bgBase: '#e9eef4',
    bgPanel: '#f6f8fb',
    bgElevated: '#ffffff',
    bgInset: '#e2e8f0',
    bgRaised: '#ffffff',
    bgHover: 'rgba(0,0,0,.04)',
    bgActive: 'rgba(0,0,0,.08)',
    glassSurface: 'rgba(246,248,251,.88)',
    glassSurfaceStrong: 'rgba(255,255,255,.97)',
    solidSurface: '#f6f8fb',
    border: '#cbd4e0',
    borderFaint: '#dde4ed',
    borderStrong: '#748297',
    glassBorder: 'rgba(31,35,41,.13)',
    textPrimary: '#1c2736',
    textSecondary: '#46566b',
    textSubtle: '#56657a',
    textDisabled: '#737b88',
    success: '#236638',
    warning: '#795000',
    error: '#b4233e',
    shadowPanel: 'inset 0 1px 0 rgba(255,255,255,.7)',
    shadowModal: '0 12px 40px rgba(28,39,54,.15)'
  },
  syntax: {
    comment: '#6a737d',
    keyword: '#d73a49',
    string: '#032f62',
    number: '#005cc5',
    constant: '#005cc5',
    property: '#005cc5',
    variable: '#e36209',
    function: '#6f42c1',
    type: '#6f42c1',
    operator: '#d73a49',
    punctuation: '#57606a',
    tag: '#22863a',
    attribute: '#6f42c1',
    invalid: '#b31d28',
    meta: '#005cc5'
  },
  editor: {
    bg: '#ffffff',
    fg: '#24292e',
    gutterBg: 'transparent',
    gutterFg: '#b1b7c0',
    gutterActiveFg: '#57606a',
    activeLine: 'rgba(0,0,0,.028)',
    selection: 'color-mix(in srgb, var(--ofs-accent) 22%, transparent)',
    selectionMatch: 'color-mix(in srgb, var(--ofs-accent) 12%, transparent)',
    searchMatch: 'rgba(250,173,20,.36)',
    searchMatchActive: 'rgba(250,173,20,.7)',
    cursor: 'var(--ofs-accent)',
    foldPlaceholder: '#a0a6b0',
    specialChar: '#d1242f'
  },
  terminalThemeId: 'github-light'
}

export const themes = { dark: darkTheme, light: lightTheme } as const

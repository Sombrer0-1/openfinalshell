# Desktop UI design system

This refresh adapts the WebUI design reference to the existing Electron/React workstation. It retains session tabs, resizable workspaces, Ant Design's complex interaction primitives, xterm, CodeMirror, the native RDP renderer, and all ten interface languages.

## Theme ownership

`src/renderer/src/themes/applyTheme.ts` is the common output for custom CSS and Ant Design. Theme values are defined in TypeScript; components must not introduce a second color palette or persist a separate theme in localStorage. Main and editor windows consume the same window-shell hooks and settings broadcasts.

| Purpose | Tokens / rules |
| --- | --- |
| Surfaces | `--ofs-bg-inset`, `--ofs-bg-base`, `--ofs-bg-panel`, `--ofs-bg-elevated`, `--ofs-bg-raised` |
| Text | `--ofs-text-1`, `--ofs-text-2`, `--ofs-text-3` for readable content; `--ofs-text-disabled` for disabled controls |
| Lines | `--ofs-border-faint` for decoration, `--ofs-border` for separation, `--ofs-border-strong` for control boundaries |
| Accent | Preserve the saved seed and eight existing choices. Derive `--ofs-accent` to reach 4.5:1 against all five surfaces, then choose a contrasting `--ofs-on-accent`. `--ofs-accent-soft` is the selected surface. Connection label colors remain unchanged. |
| Semantics | Success, warning and error colors are independent of the accent; accompany states with text/icons. |
| Spacing | 4 / 8 / 12 / 16 / 20 / 24 / 32px |
| Typography | Existing platform font stacks, 13px body, 12px supporting text, 14–16px headings, 24px welcome title |
| Corners | Existing platform control radii; 12px welcome cards |
| Motion | 120–180ms interaction transitions; no continuous ambient animation or animated workspace dimensions |

Legacy `--ofs-*` variable names remain supported. Editor syntax palettes and terminal themes remain separate from UI surface colors.

## Styles and shared controls

`styles/global.css` imports static tokens, base/layout accessibility rules, and shared control/portal styles in that order. Feature-specific layouts stay in CSS Modules.

`src/renderer/src/components/ui.tsx` contains native buttons, icon buttons, panel headings, static/action cards, empty/error states, loading placeholders and status badges. These components take props and callbacks; they do not access stores or IPC. Icon buttons forward refs and events to support existing Tooltip wrappers. Loading disables repeat actions, and non-submit buttons explicitly use `type="button"`.

Ant Design retains forms, connection trees, virtual tables, menus, tooltips, modal focus management and drawers. File and transfer rows retain their virtualization and fixed-height contracts. Terminal tools keep the titlebar-safe tooltip placement.

## Desktop behavior contracts

- Sessions remain mounted by stable tab ID. Changing theme, active tab or SFTP visibility must not recreate terminal instances, close shells or register extra data subscriptions.
- CodeMirror keeps each file's draft and undo history through tab and theme changes. Editor close/save guards remain authoritative.
- Workspaces and scrolling data surfaces are opaque. Glass is limited to shell surfaces and overlays; Linux, reduced transparency, increased contrast and forced colors disable backdrop filtering.
- Panels retain independent scrolling and bounded flex heights. Dialogs and drawers use the effective viewport (`physical CSS viewport / UI zoom`), keeping controls reachable at 150% zoom.
- Native window-control space is compensated for UI zoom. Connection drawers reserve the titlebar and subtract it from their available height.
- Settings continue to use the existing IPC persistence and conflict handling. No settings schema, database or public IPC changes are introduced.
- New or changed user-facing labels go through the shared locale files. No remote fonts or additional runtime libraries are introduced.

## Verification

Run the existing type, i18n, test, build and bundle checks. `npm run smoke:ui -- after` drives the real built Electron app with isolated data, local SSH/SFTP fixtures and the production CSP. It records screenshots, assertions and performance samples under the ignored `.local/ui-refresh/after/` directory. Pass a packaged executable as the second argument to test the packaged application.

The full packaged SSH/SFTP regression remains `npm run smoke:packaged -- <exe> <fixture-port> <cdp-port>`. It uses the local `testSshServer.mjs` fixture. UI smoke does not connect to production servers. RDP interoperability and macOS/Linux native-window acceptance require their respective environments.

Implementation milestones, measured bundle sizes, local evidence and remaining platform acceptance are recorded in [desktop-ui-refresh-validation.md](desktop-ui-refresh-validation.md).

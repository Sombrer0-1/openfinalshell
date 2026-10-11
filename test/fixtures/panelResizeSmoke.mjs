// Real Electron drag/SFTP regression. Uses isolated settings and loopback SSH.
// Usage: node test/fixtures/panelResizeSmoke.mjs [label] [packaged executable]
import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const label = process.argv[2] ?? 'after'
if (!/^[a-z-]+$/.test(label)) throw new Error('Invalid report label')
const output = resolve('.local/panel-resize', label)
mkdirSync(output, { recursive: true })
const dataDir = mkdtempSync(join(tmpdir(), 'ofs-resize-data-'))
const filesDir = mkdtempSync(join(tmpdir(), 'ofs-resize-files-'))
for (let i = 0; i < 120; i++) writeFileSync(join(filesDir, `file-${String(i).padStart(3, '0')}.txt`), `fixture ${i}\n`)
mkdirSync(join(filesDir, 'small'))
for (let i = 0; i < 3; i++) writeFileSync(join(filesDir, 'small', `short-${i}.txt`), 'small directory\n')
const port = 21000 + process.pid % 5000
const executable = process.argv[3] ?? require('electron')
const env = { ...process.env }
delete env.ELECTRON_RUN_AS_NODE
const app = spawn(executable, [...(process.argv[3] ? [] : [resolve('.')]), `--remote-debugging-port=${port}`, `--user-data-dir=${dataDir}`], { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
const fixture = spawn(process.execPath, ['test/fixtures/testSshServer.mjs', String(port + 5000), filesDir], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, ...(label.startsWith('terminal') ? { OFS_FIXTURE_SHELL_OPEN_DELAY_MS: '1800' } : {}) } })
let log = '', ready = false, shellOpening = false, socket, sequence = 0
for (const child of [app, fixture]) {
  child.stdout.on('data', (data) => { log += data; if (child === fixture && String(data).includes('listening')) ready = true; if (String(data).includes('shell-open-pending')) shellOpening = true })
  child.stderr.on('data', (data) => { log += data })
}
const pause = (ms) => new Promise((resolvePause) => setTimeout(resolvePause, ms))
const pending = new Map(), errors = [], checks = [], geometry = []
let passed = false, failure
function check(condition, name) { checks.push({ name, passed: !!condition }) }
async function send(method, params = {}) {
  return new Promise((resolveCall, reject) => {
    const id = ++sequence
    const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 15000)
    pending.set(id, { resolve: resolveCall, reject, timeout })
    socket.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression: `(async()=>{${expression}})()`, awaitPromise: true, returnByValue: true })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text)
  return result.result.value
}
async function waitFor(expression) {
  for (let i = 0; i < 100; i++) {
    if (await evaluate(`return !!(${expression})`)) return
    await pause(100)
  }
  throw new Error(`UI not ready: ${expression}`)
}
async function click(labelText) {
  await evaluate(`const button=[...document.querySelectorAll('button')].find(b=>b.getBoundingClientRect().height>0&&(b.getAttribute('aria-label')===${JSON.stringify(labelText)}||b.textContent.trim()===${JSON.stringify(labelText)}));if(!button)throw new Error('Missing button');button.click()`)
}
async function clickRow(path, modifiers = 0, button = 'left', clickCount = 1) {
  const box = await evaluate(`const row=[...document.querySelectorAll('.ant-table-row')].find(r=>r.dataset.rowKey===${JSON.stringify(path)});if(!row)throw new Error('Missing row');const r=row.getBoundingClientRect();return {x:r.left+55,y:r.top+r.height/2}`)
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...box, modifiers })
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...box, button, buttons: button === 'right' ? 2 : 1, modifiers, clickCount })
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...box, button, buttons: 0, modifiers, clickCount })
  await pause(100)
}
async function key(keyText, code, keyCode, modifiers = 0) {
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: keyText, code, windowsVirtualKeyCode: keyCode, modifiers })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: keyText, code, windowsVirtualKeyCode: keyCode, modifiers })
  await pause(100)
}
async function selectedRows() {
  return evaluate(`return [...document.querySelectorAll('.ant-table-row[aria-selected="true"]')].map(r=>r.dataset.rowKey)`)
}
async function settings(patch) {
  await evaluate(`const saved=await window.ofs.invoke('settings:get');await window.ofs.invoke('settings:set',${JSON.stringify(patch)},saved)`)
  await pause(300)
}
async function drag(direction, delta, index = 0) {
  const box = await evaluate(`const handle=document.querySelectorAll('[data-panel-group-direction="${direction}"][data-panel-resize-handle-id]')[${index}];if(!handle)throw new Error('Missing resize handle');const r=handle.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}`)
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...box })
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...box, button: 'left', buttons: 1, clickCount: 1 })
  for (let i = 1; i <= 24; i++) {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x + (direction === 'horizontal' ? delta * i / 24 : 0), y: box.y + (direction === 'vertical' ? delta * i / 24 : 0), button: 'left', buttons: 1 })
    await pause(8)
  }
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x + (direction === 'horizontal' ? delta : 0), y: box.y + (direction === 'vertical' ? delta : 0), button: 'left', buttons: 0, clickCount: 1 })
  await pause(400)
}
async function measure(name) {
  const result = await evaluate(`
    const holder=document.querySelector('.ant-table-tbody-virtual-holder');
    const area=holder.closest('[data-panel-id="sftp"]').querySelector('[style*="--ofs-sftp-table-body-height"]') ?? holder.parentElement.parentElement.parentElement.parentElement;
    const panel=holder.closest('[data-panel-id="sftp"]');
    const header=document.querySelector('.ant-table-header');
    const rect=e=>{const r=e.getBoundingClientRect();return {top:r.top,bottom:r.bottom,height:r.height}};
    return {holder:rect(holder),area:rect(area),panel:rect(panel),header:rect(header),rowHeight:document.querySelector('.ant-table-row')?.getBoundingClientRect().height,cssHeight:holder.clientHeight,zoom:document.documentElement.style.zoom,settings:(await window.ofs.invoke('settings:get')).layout,conflicts:window.__resizeConflicts.slice()}
  `)
  geometry.push({ name, ...result })
  check(Math.abs(result.holder.bottom - result.panel.bottom) <= 3, `${name}: file viewport reaches panel bottom`)
  check(result.holder.top >= result.header.bottom - 1, `${name}: header stays outside scroll viewport`)
  const screenshot = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(output, `${name}.png`), Buffer.from(screenshot.data, 'base64'))
  return result
}
async function terminalGeometry(name) {
  const result = await evaluate(`
    const {bundle}=window.__terminalRig();const {term,fit}=bundle;
    const renderer=term._core._renderService._renderer.value;
    const glyph=renderer._glyphRenderer?.value;
    const errors=[];
    if(glyph){const attrs=glyph._vertices.attributes,model=renderer._model;for(let y=0;y<term.rows;y++)for(let x=0;x<term.cols;x++){
      if(!model.cells[(y*term.cols+x)*4])continue;
      const i=(y*term.cols+x)*11;
      if(Math.abs(attrs[i+9]-x/term.cols)>0.00001||Math.abs(attrs[i+10]-y/term.rows)>0.00001)errors.push({x,y,drawX:attrs[i+9],drawY:attrs[i+10]});
    }}
    const lines=[];const b=term.buffer.active;
    for(let i=0;i<b.length;i++){const line=b.getLine(i);const text=line?.translateToString(true)||'';if(line?.isWrapped&&lines.length)lines[lines.length-1]+=text;else lines.push(text)}
    return {cols:term.cols,rows:term.rows,suggested:fit.proposeDimensions(),renderer:glyph?'WebGL':'DOM',glyphErrors:errors.slice(0,8),text:lines.join(String.fromCharCode(10))};
  `)
  geometry.push({ name, ...result })
  check(result.cols === result.suggested?.cols && result.rows === result.suggested?.rows, `${name}: grid fits the terminal container`)
  check(result.glyphErrors.length === 0, `${name}: cached glyph positions match the current grid`)
  return result
}
try {
  let target
  for (let i = 0; i < 100 && !target; i++) {
    await pause(150)
    try { target = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t => t.type === 'page') } catch { /* starting */ }
  }
  if (!target) throw new Error('Electron page unavailable')
  socket = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolveConnect, reject) => { socket.addEventListener('open', resolveConnect, { once: true }); socket.addEventListener('error', reject, { once: true }) })
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    const call = pending.get(message.id)
    if (call) {
      clearTimeout(call.timeout); pending.delete(message.id)
      if (message.error) call.reject(new Error(message.error.message)); else call.resolve(message.result)
    } else if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails)
  })
  await send('Runtime.enable')
  await waitFor('window.ofs && document.querySelector("nav button")')
  await settings({ language: 'en-US', themeMode: 'light', uiZoom: 100 })
  await evaluate(`document.querySelector('.ant-modal-close')?.click();window.__resizeConflicts=[];window.addEventListener('ofs:configConflict',e=>window.__resizeConflicts.push(e.detail));`)
  for (let i = 0; i < 100 && !ready; i++) await pause(100)
  if (!ready) throw new Error('SSH fixture unavailable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await evaluate(`await window.ofs.invoke('conn:save',{name:'Resize fixture',groupId:null,host:'127.0.0.1',port:${port + 5000},username:'test',auth:{method:'password',password:'test123'},terminal:{charset:'utf-8',termType:'xterm-256color'},options:{keepaliveInterval:15000,readyTimeout:10000,legacyAlgorithms:false,autoReconnect:false,monitorEnabled:true,compress:false}})`)
  await waitFor(`[...document.querySelectorAll('span')].some(e=>e.textContent==='Resize fixture')`)
  await evaluate(`const e=[...document.querySelectorAll('span')].find(e=>e.textContent==='Resize fixture');e.dispatchEvent(new MouseEvent('dblclick',{bubbles:true}))`)
  await waitFor(`[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='Trust and save')`)
  await click('Trust and save')
  if (label.startsWith('terminal')) {
    for (let i = 0; i < 80 && !shellOpening; i++) await pause(50)
    if (!shellOpening) throw new Error('Delayed shell was not requested')
    await drag('horizontal', -180, 1)
    await evaluate(`window.__terminalRig=()=>{
      const mount=document.querySelector('.xterm')?.parentElement;
      const key=mount&&Object.keys(mount).find(k=>k.startsWith('__reactFiber$'));
      for(let fiber=key&&mount[key];fiber;fiber=fiber.return){
        const refs=[];
        for(let hook=fiber.memoizedState;hook;hook=hook.next){if(hook.memoizedState&&typeof hook.memoizedState==='object'&&'current' in hook.memoizedState)refs.push(hook.memoizedState.current)}
        const bundle=refs.find(ref=>ref?.term&&ref?.fit);
        if(bundle)return {bundle,termId:refs.find(ref=>typeof ref==='string'&&ref.length===36&&ref[8]==='-')};
      }
      return null;
    }`)
    await waitFor('window.__terminalRig()?.termId')
    await pause(250)
    const size = await evaluate(`const {bundle,termId}=window.__terminalRig();await window.ofs.invoke('term:exec',{termId,command:'size'+String.fromCharCode(13)});return {cols:bundle.term.cols,rows:bundle.term.rows}`)
    await pause(200)
    const remote = await evaluate(`const {term}=window.__terminalRig().bundle;const b=term.buffer.active;return Array.from({length:b.length},(_,i)=>b.getLine(i)?.translateToString(true)||'').filter(line=>{const p=line.trim().split('x');return p.length===2&&p.every(n=>n!==''&&String(Number(n))===n)}).at(-1)`)
    geometry.push({ name: 'shell-open-resize', local: size, remote })
    check(remote === `${size.cols}x${size.rows}`, 'A panel resize during shell opening reaches the remote PTY')
  }
  await waitFor('document.querySelector(".ant-table-tbody-virtual-holder") && document.querySelector(".ant-table-row")')
  await pause(500)
  await measure('initial')
  if (label.startsWith('monitor')) {
    await waitFor('document.querySelector("[data-panel-id=monitor] [class*=bigNumber]")')
    await evaluate(`window.__monitorNextFrame=false;window.ofs.on('monitor:data',()=>{window.__monitorNextFrame=true})`)
    await waitFor('window.__monitorNextFrame')
    await evaluate(`const p=document.querySelector('[data-panel-id="monitor"]');const head=[...p.querySelectorAll('[class*=cardHeadTitle]')].find(e=>e.textContent==='CPU');head.parentElement.parentElement.querySelector('[class*=cardToggle]').click()`)
    for (const themeMode of ['light', 'dark']) {
      await settings({ themeMode })
      const bars = await evaluate(`return [...document.querySelectorAll('[data-panel-id="monitor"] [class*=barFill]')].map(fill=>{const track=fill.parentElement;const f=fill.getBoundingClientRect(),t=track.getBoundingClientRect();return {label:track.getAttribute('aria-label'),expected:parseFloat(fill.style.width),fillWidth:f.width,fillHeight:f.height,trackWidth:t.width,display:getComputedStyle(fill).display,color:getComputedStyle(fill).backgroundColor}})`)
      geometry.push({ name: `monitor-bars-${themeMode}`, bars })
      check(bars.length > 0 && bars.every(bar => bar.fillHeight > 0), `${themeMode}: progress fills have visible height`)
      check(bars.every(bar => Math.abs(bar.fillWidth - bar.trackWidth * bar.expected / 100) <= 1), `${themeMode}: visible progress fills match their percentages`)
      check(bars.some(bar => bar.label === 'CPU'), `${themeMode}: aggregate CPU uses a progress bar`)
      check(await evaluate(`return document.querySelectorAll('[data-panel-id="monitor"] canvas').length===1`), `${themeMode}: only network keeps its history graph`)
      const clip = await evaluate(`const r=document.querySelector('[data-panel-id="monitor"]').getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,scale:1}`)
      const screenshot = await send('Page.captureScreenshot', { format: 'png', clip })
      writeFileSync(join(output, `monitor-${themeMode}.png`), Buffer.from(screenshot.data, 'base64'))
    }
    await evaluate(`const p=document.querySelector('[data-panel-id="monitor"]');const head=[...p.querySelectorAll('[class*=cardHeadTitle]')].find(e=>e.textContent==='CPU');head.parentElement.parentElement.querySelector('[class*=cardToggle]').click()`)
    await pause(200)
    const cards = await evaluate(`const p=document.querySelector('[data-panel-id="monitor"]');return [...p.querySelectorAll('[class*=cardHeadTitle]')].map(head=>{const card=head.parentElement.parentElement;return {title:head.textContent,height:card.getBoundingClientRect().height,graphs:card.querySelectorAll('canvas').length,text:card.textContent}})`)
    geometry.push({ name: 'monitor-cards', cards })
    check(cards.find(card => card.title === 'CPU')?.height < 95, 'CPU card is compact without its historical graph')
    check(cards.find(card => card.title === 'Memory')?.height < 125, 'Memory card is compact without its historical graph')
    check(cards.find(card => card.title === 'Latency')?.height < 105 && cards.find(card => card.title === 'Latency')?.graphs === 0, 'Latency card shows only current values')
    check(cards.find(card => card.title === 'Network')?.graphs === 1, 'Network retains its original graph')
    await settings({ themeMode: 'light' })
  }
  if (label.startsWith('selection')) {
    check(await evaluate(`const row=document.querySelector('.ant-table-row');const height=row.getBoundingClientRect().height;return height>=24&&height<=29`), 'File rows use compact desktop spacing')
    check(await evaluate(`return !document.querySelector('[data-panel-id="sftp"] input[type="checkbox"]')`), 'File list has no selector column')
    await clickRow('/file-000.txt')
    check(JSON.stringify(await selectedRows()) === JSON.stringify(['/file-000.txt']), 'Native single click selects a row')
    await clickRow('/file-002.txt', 2)
    check((await selectedRows()).length === 2, 'Native Ctrl-click adds a file')
    await clickRow('/file-000.txt', 2)
    check(JSON.stringify(await selectedRows()) === JSON.stringify(['/file-002.txt']), 'Native Ctrl-click removes a file')
    await clickRow('/file-001.txt')
    await clickRow('/file-003.txt', 8)
    check(JSON.stringify(await selectedRows()) === JSON.stringify(['/file-001.txt', '/file-002.txt', '/file-003.txt']), 'Native Shift-click selects the displayed range')
    await clickRow('/file-002.txt', 0, 'right')
    check((await selectedRows()).length === 3, 'Right-click inside the group preserves selection')
    check(await evaluate(`return [...document.querySelectorAll('.ant-dropdown-menu-item')].some(e=>e.textContent==='Rename'&&e.getAttribute('aria-disabled')==='true')`), 'Batch selection disables single-file rename')
    await key('Escape', 'Escape', 27)
    check((await selectedRows()).length === 3, 'Closing the context menu preserves selection')
    await clickRow('/file-000.txt', 0, 'right')
    check(JSON.stringify(await selectedRows()) === JSON.stringify(['/file-000.txt']), 'Right-click outside the group selects that file')
    await key('Escape', 'Escape', 27)
    await key('a', 'KeyA', 65, 2)
    check(await evaluate(`return [...document.querySelectorAll('.ant-table-row')].every(e=>e.getAttribute('aria-selected')==='true')`), 'Native Ctrl+A selects all displayed rows')
    await evaluate(`const h=document.querySelector('.ant-table-tbody-virtual-holder');h.scrollTop=1e7;h.dispatchEvent(new Event('scroll',{bubbles:true}))`)
    await waitFor(`[...document.querySelectorAll('.ant-table-row')].some(e=>e.dataset.rowKey==='/file-119.txt')`)
    check(await evaluate(`return [...document.querySelectorAll('.ant-table-row')].find(e=>e.dataset.rowKey==='/file-119.txt')?.getAttribute('aria-selected')==='true'`), 'Ctrl+A includes virtualized rows outside the viewport')
    await key('Home', 'Home', 36)
    await waitFor(`[...document.querySelectorAll('.ant-table-row')].some(e=>e.dataset.rowKey==='/small')`)
    await key('End', 'End', 35)
    await waitFor(`[...document.querySelectorAll('.ant-table-row')].some(e=>e.dataset.rowKey==='/file-119.txt'&&e.getAttribute('aria-selected')==='true')`)
    check(true, 'End reaches and selects the last virtualized file')
    await key('Home', 'Home', 36)
    await waitFor(`[...document.querySelectorAll('.ant-table-row')].some(e=>e.dataset.rowKey==='/small')`)
    await key('ArrowDown', 'ArrowDown', 40)
    check(JSON.stringify(await selectedRows()) === JSON.stringify(['/file-000.txt']), 'Arrow navigation selects and scrolls the file list')
    await key('ArrowDown', 'ArrowDown', 40, 8)
    check((await selectedRows()).length === 2, 'Shift+Arrow extends selection')
    await clickRow('/file-000.txt')
    await key('F2', 'F2', 113)
    await waitFor('document.querySelector(".ant-table-row input")')
    await key('a', 'KeyA', 65, 2)
    check((await selectedRows()).length === 1, 'Ctrl+A in a rename field does not select files')
    await key('Escape', 'Escape', 27)
    await key('Delete', 'Delete', 46)
    await waitFor('document.querySelector(".ant-modal-confirm")')
    check(true, 'Delete keeps the existing confirmation dialog')
    await click('Cancel')
    await waitFor('!document.querySelector(".ant-modal-confirm")')
    await evaluate(`document.querySelector('.xterm-helper-textarea').focus()`)
    await key('a', 'KeyA', 65, 2)
    check((await selectedRows()).length === 1, 'Terminal Ctrl+A does not affect file selection')
    await clickRow('/file-001.txt')
    await clickRow('/file-003.txt', 8)
    await measure('selection-light')
    await settings({ themeMode: 'dark' })
    await measure('selection-dark')
    await settings({ themeMode: 'light' })
  }
  await evaluate(`window.__resizeConflicts=[];window.__term=document.querySelector('.xterm')`)
  await drag('horizontal', 60)
  await drag('horizontal', -35)
  await drag('horizontal', -40, 1)
  await drag('vertical', -220)
  const expanded = await measure('expanded')
  await drag('vertical', 110)
  const shrunk = await measure('shrunk')
  check(expanded.holder.height > shrunk.holder.height + 60, 'Virtual viewport follows vertical drag')
  check(await evaluate(`return window.__resizeConflicts.length===0`), 'No settings errors after repeated real mouse drags')
  check(await evaluate(`return document.querySelector('.xterm')===window.__term`), 'Resizing preserves the terminal instance')
  if (label.startsWith('terminal')) {
    await evaluate(`const {termId}=window.__terminalRig();await window.ofs.invoke('term:exec',{termId,command:'resize-stream'+String.fromCharCode(13)})`)
    for (let i = 0; i < 8; i++) {
      await drag('horizontal', i % 2 ? 115 : -115, 1)
      await terminalGeometry(`terminal-drag-${i}`)
    }
    await waitFor(`(()=>{const b=window.__terminalRig().bundle.term.buffer.active;return Array.from({length:b.length},(_,i)=>b.getLine(i)?.translateToString(true)||'').some(line=>line.includes('[resize-stream-done]'))})()`)
    const streamed = await terminalGeometry('terminal-stream-complete')
    check((streamed.text.match(/resize-row-/g) ?? []).length === 500, 'Resizing preserves all 500 incoming output rows')
  }
  for (const zoom of [90, 125, 150]) {
    await settings({ uiZoom: zoom })
    await measure(`zoom-${zoom}`)
    if (label.startsWith('terminal')) await terminalGeometry(`terminal-zoom-${zoom}`)
  }
  await settings({ uiZoom: 100 })
  await send('Emulation.setDeviceMetricsOverride', { width: 940, height: 600, deviceScaleFactor: 1, mobile: false })
  await drag('horizontal', 30)
  await drag('vertical', 45)
  await measure('minimum-window')
  await evaluate(`const h=document.querySelector('.ant-table-tbody-virtual-holder');h.scrollTop=1e7;h.dispatchEvent(new Event('scroll',{bubbles:true}))`)
  await waitFor(`[...document.querySelectorAll('.ant-table-row')].some(e=>e.textContent.includes('file-119.txt'))`)
  check(true, 'Last of 120 directory entries is reachable')
  const horizontal = await evaluate(`const bar=document.querySelector('.ant-table-tbody-virtual-scrollbar-horizontal');const panel=document.querySelector('[data-panel-id="sftp"]');if(!bar)return null;const b=bar.getBoundingClientRect(),p=panel.getBoundingClientRect();return {bottom:b.bottom,panelBottom:p.bottom,height:b.height}`)
  geometry.push({ name: 'horizontal-scrollbar', ...horizontal })
  check(horizontal && Math.abs(horizontal.bottom - horizontal.panelBottom) <= 3, 'Horizontal scrollbar stays at panel bottom')
  await measure('last-entry')
  await evaluate(`const h=document.querySelector('.ant-table-tbody-virtual-holder');h.scrollTop=0;h.dispatchEvent(new Event('scroll',{bubbles:true}))`)
  await waitFor(`[...document.querySelectorAll('.ant-table-row')].some(e=>e.textContent.includes('small'))`)
  await evaluate(`const row=[...document.querySelectorAll('.ant-table-row')].find(e=>e.textContent.includes('small'));row.dispatchEvent(new MouseEvent('dblclick',{bubbles:true}))`)
  await waitFor(`[...document.querySelectorAll('.ant-table-row')].some(e=>e.textContent.includes('short-2.txt'))`)
  await measure('short-directory')
  if (label.startsWith('selection')) check((await selectedRows()).length === 0, 'Changing directory clears the previous selection')
  check(await evaluate(`const b=document.querySelector('.ant-table-tbody-virtual-scrollbar-horizontal')?.getBoundingClientRect();const p=document.querySelector('[data-panel-id="sftp"]').getBoundingClientRect();return !!b&&Math.abs(b.bottom-p.bottom)<=3`), 'Short directory horizontal scrollbar stays at panel bottom')
  check(errors.length === 0, 'No renderer runtime exceptions')
  passed = checks.every(check => check.passed)
  if (!passed) throw new Error(checks.filter(check => !check.passed).map(check => check.name).join('\n'))
  console.log(`Panel resize smoke passed: ${checks.length} checks. ${output}`)
} catch (error) {
  failure = error.stack ?? String(error)
  console.error(failure)
  process.exitCode = 1
} finally {
  writeFileSync(join(output, 'report.json'), JSON.stringify({ label, passed, failure, checks, geometry, errors }, null, 2))
  writeFileSync(join(output, 'electron.log'), log)
  if (socket?.readyState === WebSocket.OPEN) {
    try { await send('Browser.close') } catch { /* process already closed */ }
  }
  for (const call of pending.values()) clearTimeout(call.timeout)
  socket?.close()
  app.kill()
  fixture.kill()
}

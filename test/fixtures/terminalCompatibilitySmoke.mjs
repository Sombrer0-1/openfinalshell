// Real Electron + local SSH protocol regression. No user hosts or configuration.
import assert from 'node:assert/strict'
import { generateKeyPairSync } from 'node:crypto'
import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { createServer } from 'node:net'
import { createRequire } from 'node:module'
import ssh2 from 'ssh2'

const require = createRequire(import.meta.url)
const root = mkdtempSync(join(tmpdir(), 'ofs-terminal-compat-'))
const output = resolve('.local/terminal-compatibility')
mkdirSync(output, { recursive: true })
mkdirSync(join(root, 'config'))
writeFileSync(join(root, 'config', 'settings.json'), JSON.stringify({ language: 'en-US', autoCheckUpdate: false,
  terminal: { webgl: false }, sftp: { autoOpenOnConnect: false } }))
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(fn, timeout = 15000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { if (fixtureError) throw fixtureError; const value = await fn(); if (value) return value; await pause(50) }
  throw new Error('Smoke condition timed out')
}
async function freePort() {
  const socket = createServer()
  await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve))
  const port = socket.address().port
  await new Promise(resolve => socket.close(resolve))
  return port
}
let stream, app, ws, incoming = Buffer.alloc(0), appLog = ''
const clients = [], checks = [], pending = new Map()
let closing = false, fixtureError
const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048,
  privateKeyEncoding: { type: 'pkcs1', format: 'pem' }, publicKeyEncoding: { type: 'pkcs1', format: 'pem' } })
const ssh = new ssh2.Server({ hostKeys: [privateKey] }, client => {
  clients.push(client)
  client.on('error', error => { if (!closing) fixtureError = error })
  client.on('authentication', context => context.method === 'password' && context.password === 'fixture'
    ? context.accept() : context.reject())
  client.on('ready', () => client.on('session', accept => {
    const session = accept()
    session.on('pty', acceptPty => acceptPty?.())
    session.on('window-change', acceptResize => acceptResize?.())
    session.on('shell', acceptShell => {
      stream = acceptShell()
      stream.on('data', data => { incoming = Buffer.concat([incoming, data]) })
      // Emit immediately, before renderer registration, to exercise readiness.
      stream.write('\x1b[?u')
    })
  }))
})
await new Promise(resolve => ssh.listen(0, '127.0.0.1', resolve))
const sshPort = ssh.address().port, cdpPort = await freePort()
let sequence = 0
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 15000)
    pending.set(id, { resolve, reject, timer })
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(body) {
  const result = await send('Runtime.evaluate', { expression: `(async()=>{${body}})()`, awaitPromise: true, returnByValue: true })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text)
  return result.result.value
}
async function key(modifiers, code = 'Enter', key = 'Enter', keyCode = 13) {
  await evaluate('window.__terminalRig().bundle.term.focus()')
  await send('Input.dispatchKeyEvent', { type: 'keyDown', modifiers, code, key, windowsVirtualKeyCode: keyCode })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', modifiers, code, key, windowsVirtualKeyCode: keyCode })
  await pause(150)
}
function passed(name) { checks.push(name); console.log(`PASS ${name}`) }
try {
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE
  const executable = process.argv[2] ?? require('electron')
  app = spawn(executable, [...(process.argv[2] ? [] : [resolve('.')]), `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${root}`],
    { env, windowsHide: false, stdio: ['ignore', 'pipe', 'pipe'] })
  app.stdout.on('data', data => { appLog += data }); app.stderr.on('data', data => { appLog += data })
  const target = await until(async () => {
    try { return (await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json()).find(t => t.type === 'page') }
    catch { return null }
  }, 30000)
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }) })
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data), call = pending.get(message.id)
    if (call) { pending.delete(message.id); clearTimeout(call.timer); message.error ? call.reject(new Error(message.error.message)) : call.resolve(message.result) }
  })
  await until(() => evaluate('return !!document.querySelector("nav button")').catch(() => false))
  await evaluate(`await window.ofs.invoke('conn:save',{name:'Terminal compatibility fixture',groupId:null,host:'127.0.0.1',port:${sshPort},username:'fixture',
    auth:{method:'password',password:'fixture'},terminal:{charset:'utf-8',termType:'xterm-256color'},
    options:{keepaliveInterval:0,readyTimeout:10000,legacyAlgorithms:false,autoReconnect:false,monitorEnabled:false,compress:false}})`)
  await send('Page.reload')
  await until(() => evaluate(`return [...document.querySelectorAll('span')].some(e=>e.textContent==='Terminal compatibility fixture')`).catch(() => false))
  await evaluate(`document.querySelector('.ant-modal-close')?.click();const row=[...document.querySelectorAll('span')].find(e=>e.textContent==='Terminal compatibility fixture');row.dispatchEvent(new MouseEvent('dblclick',{bubbles:true}))`)
  await until(() => evaluate(`return [...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='Trust and save')`))
  await evaluate(`document.querySelectorAll('button').forEach(b=>{if(b.textContent.trim()==='Trust and save')b.click()})`)
  await until(() => evaluate('return !!document.querySelector(".xterm")'))
  await evaluate(`window.__terminalRig=()=>{
    const mount=document.querySelector('.xterm')?.parentElement;
    const key=mount&&Object.keys(mount).find(k=>k.startsWith('__reactFiber$'));
    for(let fiber=key&&mount[key];fiber;fiber=fiber.return){const refs=[];
      for(let hook=fiber.memoizedState;hook;hook=hook.next){if(hook.memoizedState&&typeof hook.memoizedState==='object'&&'current' in hook.memoizedState)refs.push(hook.memoizedState.current)}
      const bundle=refs.find(ref=>ref?.term&&ref?.fit);
      if(bundle)return {bundle,termId:refs.find(ref=>typeof ref==='string'&&ref.length===36&&ref[8]==='-')};
    } return null;
  }`)
  await until(() => incoming.includes(Buffer.from('\x1b[?0u')))
  passed('startup keyboard query survives SSH open/readiness')

  await evaluate('window.__terminalRig().bundle.term.focus()')
  const text = '远端 OSC52 复制🙂'
  const osc = `\x1b]52;c;${Buffer.from(text).toString('base64')}\x07`
  stream.write(osc.slice(0, 7)); await pause(30); stream.write(osc.slice(7))
  await until(() => evaluate(`return await navigator.clipboard.readText()===${JSON.stringify(text)}`))
  passed('remote OSC52 writes the real local clipboard')

  incoming = Buffer.alloc(0)
  stream.write('\x1b[>1u\x1b[?u')
  await until(() => incoming.includes(Buffer.from('\x1b[?1u')))
  incoming = Buffer.alloc(0)
  await key(2)
  assert.equal(incoming.toString(), '\x1b[13;5u')
  incoming = Buffer.alloc(0)
  await key(8)
  assert.equal(incoming.toString(), '\x1b[13;2u')
  passed('Ctrl/Shift Enter retain their modifiers over SSH')
  incoming = Buffer.alloc(0)
  stream.write('\x1b[<u\x1b[>3u\x1b[?u\x1b[?2004h')
  await until(() => incoming.includes(Buffer.from('\x1b[?3u')))
  for (const modifiers of [10]) {
    await evaluate(`await navigator.clipboard.writeText('paste-once-一次')`)
    incoming = Buffer.alloc(0)
    await key(modifiers, 'KeyV', 'v', 86)
    assert.equal(incoming.toString(), '\x1b[200~paste-once-一次\x1b[201~')
  }
  incoming = Buffer.alloc(0)
  await evaluate(`const data=new DataTransfer();data.setData('text/plain','paste-once-一次');window.__terminalRig().bundle.term.textarea.dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}))`)
  await pause(150)
  assert.equal(incoming.toString(), '\x1b[200~paste-once-一次\x1b[201~')
  passed('Ctrl+Shift+V and DOM paste events each paste exactly once')

  incoming = Buffer.alloc(0)
  stream.write('\x1b[<u\x1b[?u')
  await until(() => incoming.includes(Buffer.from('\x1b[?0u')))
  await evaluate(`const settings=await window.ofs.invoke('settings:get');await window.ofs.invoke('settings:set',{terminal:{...settings.terminal,rightClick:'menu'}})`)
  async function pasteFromMenu(text) {
    await evaluate(`await navigator.clipboard.writeText(${JSON.stringify(text)});const t=window.__terminalRig().bundle.term;const r=t.element.getBoundingClientRect();t.element.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true,button:2,clientX:r.x+80,clientY:r.y+80}))`)
    await until(() => evaluate(`return [...document.querySelectorAll('[role="menuitem"]')].some(e=>e.textContent.trim()==='Paste')`))
    await evaluate(`const item=[...document.querySelectorAll('[role="menuitem"]')].find(e=>e.textContent.trim()==='Paste');item.focus();item.click()`)
  }
  async function enterWithoutRefocus() {
    for (const type of ['keyDown','keyUp']) await send('Input.dispatchKeyEvent',{type,key:'Enter',code:'Enter',windowsVirtualKeyCode:13})
    await pause(150)
  }
  incoming = Buffer.alloc(0)
  await pasteFromMenu('focus-menu')
  await until(() => evaluate('return document.activeElement===window.__terminalRig().bundle.term.textarea'))
  await pause(300)
  await enterWithoutRefocus()
  assert.equal(incoming.toString(), '\x1b[200~focus-menu\x1b[201~\r')
  passed('context menu paste accepts Enter without another terminal click')
  stream.write('\x1b[?2004l')
  await until(() => evaluate('return !window.__terminalRig().bundle.term.modes.bracketedPasteMode'))
  incoming = Buffer.alloc(0)
  await pasteFromMenu('one\ntwo')
  await until(() => evaluate(`return !!document.querySelector('.ant-modal-confirm-btns .ant-btn-primary')`))
  await evaluate(`const ok=document.querySelector('.ant-modal-confirm-btns .ant-btn-primary');ok.focus();ok.click()`)
  await until(() => evaluate('return !document.querySelector(".ant-modal-confirm")'))
  await until(() => evaluate('return document.activeElement===window.__terminalRig().bundle.term.textarea'))
  await enterWithoutRefocus()
  assert.equal(incoming.toString(), 'one\rtwo\r')
  passed('confirmed multiline paste accepts Enter after the dialog animation')

  stream.write('\x1b[?1049h\x1b[?1002h\x1b[?1006h')
  await until(() => evaluate('return window.__terminalRig().bundle.term.modes.mouseTrackingMode!=="none"'))
  for (const uiZoom of [80, 100, 125, 150]) {
    await evaluate(`await window.ofs.invoke('settings:set',{uiZoom:${uiZoom}})`)
    await pause(250)
    const geometry = await evaluate(`const r=document.querySelector('.xterm-screen').getBoundingClientRect();const t=window.__terminalRig().bundle.term;return {x:r.x+r.width/2,y:r.y+r.height/2,cols:t.cols,rows:t.rows}`)
    const point = { x: geometry.x, y: geometry.y }
    incoming = Buffer.alloc(0)
    await send('Input.dispatchMouseEvent', { type: 'mouseWheel', ...point, deltaY: 100, deltaX: 0 })
    await pause(150)
    assert.equal((incoming.toString().match(/\x1b\[<65;\d+;\d+M/g) ?? []).length, 3)
    const coordinates = incoming.toString().match(/\x1b\[<65;(\d+);(\d+)M/)
    assert.ok(Math.abs(Number(coordinates[1])-(geometry.cols/2+1))<=1, `Mouse column at ${uiZoom}%: ${coordinates[1]} for ${geometry.cols} cols`)
    assert.ok(Math.abs(Number(coordinates[2])-(geometry.rows/2+1))<=1, `Mouse row at ${uiZoom}%: ${coordinates[2]} for ${geometry.rows} rows`)
  }
  passed('remote wheel sends three reports at 80/100/125/150 percent zoom')

  stream.write('\x1b[?1006l\x1b[?1000h')
  await pause(100)
  const point = await evaluate(`const r=document.querySelector('.xterm-screen').getBoundingClientRect();return {x:r.x+50,y:r.y+50}`)
  incoming = Buffer.alloc(0)
  for (const type of ['mousePressed', 'mouseReleased']) await send('Input.dispatchMouseEvent', { type, ...point, button: 'left', buttons: type==='mousePressed'?1:0, clickCount: 1 })
  await until(() => incoming.includes(Buffer.from('\x1b[M')))
  passed('legacy binary mouse reports survive the complete SSH path')

  stream.write('\x1b[?1000l\x1b[?1049l' + Array.from({length:120},(_,i)=>`old-${i}\r\n`).join(''))
  await until(() => evaluate('return window.__terminalRig().bundle.term.buffer.active.baseY>50'))
  await evaluate('window.__terminalRig().bundle.term.scrollLines(-20)')
  await until(() => evaluate('const b=window.__terminalRig().bundle.term.buffer.active;return b.viewportY<b.baseY'))
  stream.write('\x1b[3J'+Array.from({length:100},(_,i)=>`new-${i}\r\n`).join(''))
  await until(() => evaluate('const b=window.__terminalRig().bundle.term.buffer.active;return b.baseY>30&&b.viewportY===b.baseY'))
  passed('ED3 restores viewport following in the real browser renderer')

  await evaluate(`window.__compatRenders=[];window.__terminalRig().bundle.term.onRender(e=>window.__compatRenders.push(e))`)
  stream.write('\x1b[?2026h\x1b[2J\x1b[Hhalf-frame')
  await until(() => evaluate('return window.__terminalRig().bundle.term.modes.synchronizedOutputMode'))
  await pause(100)
  assert.equal(await evaluate('return window.__compatRenders.length'), 0)
  stream.write('\x1b[?2026l')
  await until(() => evaluate('return window.__compatRenders.length>0'))
  passed('synchronized output holds intermediate frames until completion')
  await evaluate(`const settings=await window.ofs.invoke('settings:get');await window.ofs.invoke('settings:set',{terminal:{...settings.terminal,webgl:true}})`)
  await pause(300)
  const webgl = await evaluate(`return !!window.__terminalRig().bundle.term._core._renderService._renderer.value._glyphRenderer`)
  await evaluate('window.__compatRenders=[]')
  stream.write('\x1b[?2026h\x1b[2J\x1b[Hsecond-frame')
  await until(() => evaluate('return window.__terminalRig().bundle.term.modes.synchronizedOutputMode'))
  await pause(100)
  assert.equal(await evaluate('return window.__compatRenders.length'), 0)
  stream.write('\x1b[?2026l')
  await until(() => evaluate('return window.__compatRenders.length>0'))
  passed(webgl ? 'synchronized output also works with WebGL rendering' : 'WebGL unavailable: DOM fallback remains functional')
  writeFileSync(join(output, 'smoke-results.json'), JSON.stringify({ passed: true, checks }, null, 2))
} catch (error) {
  writeFileSync(join(output, 'smoke-results.json'), JSON.stringify({ passed: false, checks, error: String(error), appLog }, null, 2))
  throw error
} finally {
  closing = true
  ws?.close()
  for (const client of clients) client.end()
  ssh.close()
  app?.kill()
}


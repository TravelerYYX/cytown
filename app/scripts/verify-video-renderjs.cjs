// Run after build:app and build:h5 with --mode development. Exercises the App bridge
// with App's comment-node $el and callMethod channel, using real HLS playback.
const { chromium } = require('playwright-core')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const app = path.join(root, 'dist/build/app')
const fixtures = path.join(root, 'artifacts/media-ui/renderjs-fixtures')
fs.mkdirSync(fixtures, { recursive: true })
const generated = spawnSync(process.env.FFMPEG_PATH || 'D:/ffmpeg-8.1.1-essentials_build/bin/ffmpeg.exe', [
 '-y', '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=12', '-t', '8',
 '-c:v', 'libx264', '-preset', 'ultrafast', '-g', '24', '-pix_fmt', 'yuv420p',
 '-f', 'hls', '-hls_time', '2', '-hls_playlist_type', 'vod',
 '-hls_segment_filename', path.join(fixtures, 'segment-%03d.ts'), path.join(fixtures, 'index.m3u8')
], { windowsHide: true, encoding: 'utf8' })
assert.equal(generated.status, 0, generated.stderr)
const config = fs.readFileSync(path.join(app, 'app-config-service.js'), 'utf8')
const routes = JSON.parse(config.match(/const __uniRoutes = (\[.*?\])\.map/)[1])
assert(!routes.find(route => route.path === 'pages/post/index').meta.disableScroll, 'App detail still installs the passive global scroll blocker')

;(async () => {
 const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
 const errors = []
 try {
  const page = await browser.newPage()
  page.on('pageerror', error => errors.push(error.message))
  await page.route('http://renderjs.test/**', route => {
   const relative = new URL(route.request().url()).pathname.slice(1)
   if (!relative) return route.fulfill({ contentType: 'text/html', body: '<html><body></body></html>' })
   const file = path.resolve(fixtures, relative)
   assert(file.startsWith(fixtures + path.sep))
   return route.fulfill({ body: fs.readFileSync(file), contentType: file.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t' })
  })
  await page.goto('http://renderjs.test/')
  await page.addScriptTag({ content: fs.readFileSync(path.join(app, 'app-renderjs.js'), 'utf8') })
  await page.evaluate(() => {
   window.bridge = Object.values(window.__renderjsModules).map(module => module.default).find(module => module.methods?.update)
   window.instances = {}
   window.events = []
   window.mountPlayer = (id, delayed = false) => {
    const instance = {
     $el: document.createComment('renderjs'),
     $nextTick: callback => Promise.resolve().then(callback),
     $ownerInstance: { callMethod: (method, event) => window.events.push({ id, method, ...event }) },
     $: { exposed: { onEngine: () => { throw new Error('App callback bypassed callMethod') } } }
    }
    const insert = () => {
     const host = document.createElement('div')
     host.id = id
     host.style.cssText = 'width:320px;height:180px'
     document.body.appendChild(host)
    }
    const value = { hostId: id, source: 'http://renderjs.test/index.m3u8', qualities: [], quality: 'auto', rate: 1, active: true, autoplay: false, command: { id: 0, type: '' } }
    window.instances[id] = { instance, value }
    if (!delayed) insert()
    window.bridge.methods.update.call(instance, value)
    window.bridge.mounted.call(instance)
    if (delayed) insert()
   }
   window.command = (id, type, value) => {
    const state = window.instances[id]
    state.value = { ...state.value, command: { id: state.value.command.id + 1, type, value } }
    window.bridge.methods.update.call(state.instance, state.value)
   }
   window.mountPlayer('player-a', true)
   window.mountPlayer('player-b')
  })
  await page.waitForFunction(() => ['player-a', 'player-b'].every(id => document.querySelector('#' + id + ' video')?.readyState >= 2))
  assert.equal(await page.locator('video').count(), 2, 'duplicate or missing players')
  await page.evaluate(() => window.command('player-a', 'play'))
  await page.waitForFunction(() => document.querySelector('#player-a video').currentTime > 0.3)
  assert(await page.locator('#player-b video').evaluate(video => video.paused), 'commands leaked into another player')
  await page.evaluate(() => window.command('player-a', 'pause'))
  await page.waitForFunction(() => document.querySelector('#player-a video').paused)
  await page.evaluate(() => window.command('player-a', 'seek', 4))
  await page.waitForFunction(() => document.querySelector('#player-a video').currentTime >= 4)
  const events = await page.evaluate(() => window.events)
  assert(events.some(event => event.id === 'player-a' && event.playing === true && event.time > 0), 'playback did not reach logic layer')
  assert(events.every(event => event.method === 'onPlayback'), 'wrong logic-layer callback')
  await page.evaluate(() => {
   const state = window.instances['player-a']
   state.value = { ...state.value, active: false, suspendId: 1 }
   window.bridge.methods.update.call(state.instance, state.value)
   const final = window.events.at(-1)
   if (final.id !== 'player-a' || final.suspended !== 1) throw new Error('Navigation suspension was not acknowledged')
   const count = window.events.length
   document.querySelector('#player-a video').dispatchEvent(new Event('timeupdate'))
   window.bridge.methods.update.call(state.instance, state.value)
   if (window.events.length !== count) throw new Error('Suspended player sent another callback or duplicate acknowledgement')
   state.value = { ...state.value, active: true, suspendId: 0 }
   window.bridge.methods.update.call(state.instance, state.value)
   if (window.events.length <= count) throw new Error('Resumed player did not restore its state callback')
  })
  await page.evaluate(() => {
   window.removedVideo = document.querySelector('#player-a video')
   window.eventsBeforeRemoval = window.events.length
   document.getElementById('player-a').remove()
   // An event can arrive before MutationObserver has destroyed the player.
   window.removedVideo.dispatchEvent(new Event('timeupdate'))
   if (window.events.length !== window.eventsBeforeRemoval) throw new Error('Removed host sent a stale callMethod callback')
  })
  await page.waitForFunction(() => !window.removedVideo.parentNode && !window.removedVideo.getAttribute('src') && window.removedVideo.paused)
  assert.equal(await page.locator('#player-b video').count(), 1, 'cleanup removed another player')
  await page.evaluate(async () => {
   const stale = window.instances['player-a']
   const host = document.createElement('div')
   host.id = 'player-a'
   document.body.appendChild(host)
   window.bridge.methods.update.call(stale.instance, stale.value)
   window.bridge.mounted.call(stale.instance)
   await Promise.resolve()
   if (host.querySelector('video')) throw new Error('Disposed instance recreated its player')
   host.remove()
  })
  await page.evaluate(() => window.mountPlayer('player-a', true))
  await page.waitForFunction(() => document.querySelector('#player-a video')?.readyState >= 2)
  await page.evaluate(() => window.command('player-a', 'play'))
  await page.waitForFunction(() => document.querySelector('#player-a video').currentTime > 0.3)
  await page.evaluate(() => document.body.replaceChildren())
  await page.close()

  // Verify the actual Vue controls and exposed-state callback in the H5 build.
  const ui = await browser.newPage({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
  ui.on('pageerror', error => errors.push(error.message))
  const h5 = path.join(root, 'dist/build/h5')
  await ui.route('http://video-ui.test/**', route => {
   const relative = new URL(route.request().url()).pathname.slice(1)
   if (relative.startsWith('api/')) return route.fulfill({ json: {} })
   // Development-mode builds serve static assets from the source directory.
   const base = relative.startsWith('static/') ? root : h5
   const file = path.resolve(base, relative || 'index.html')
   assert(file.startsWith(base + path.sep))
   const types = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' }
   return route.fulfill({ body: fs.readFileSync(file), contentType: types[path.extname(file)] || 'application/octet-stream' })
  })
  await ui.route('http://media.test/**', route => {
   const file = path.resolve(fixtures, new URL(route.request().url()).pathname.slice(1))
   assert(file.startsWith(fixtures + path.sep))
   return route.fulfill({ body: fs.readFileSync(file), contentType: file.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t', headers: { 'Access-Control-Allow-Origin': '*' } })
  })
  await ui.route('**/app/**', route => {
   const url = new URL(route.request().url()).pathname
   let data = {}
   if (url.endsWith('/app/post/2')) data = { id: 2, authorId: '100', authorNickname: '播放验收', title: '视频播放回归', mode: 'video', status: 1, media: [{ id: 12, assetId: 22, type: 'video', status: 'ready', aspectRatio: 16 / 9, hlsUrl: 'http://media.test/index.m3u8', durationMs: 8000, qualities: [] }] }
   if (url.includes('/comments')) data = { records: [], total: 0 }
   return route.fulfill({ json: { code: '0000', data }, headers: { 'Access-Control-Allow-Origin': '*' } })
  })
  await ui.goto('http://video-ui.test/#/pages/post/index?id=2')
  await ui.waitForSelector('.player-host video')
  await ui.getByLabel('播放', { exact: true }).tap()
  await ui.waitForFunction(() => document.querySelector('.player-host video').currentTime > 0.3)
  await ui.getByLabel('暂停', { exact: true }).tap()
  await ui.waitForFunction(() => document.querySelector('.player-host video').paused)
  await ui.getByLabel('播放', { exact: true }).waitFor()
  const track = await ui.locator('.inline-track').boundingBox()
  await ui.touchscreen.tap(track.x + track.width * 0.6, track.y + track.height / 2)
  await ui.waitForFunction(() => document.querySelector('.player-host video').currentTime >= 4)
  await ui.getByLabel('全屏', { exact: true }).tap()
  await ui.waitForSelector('.is-fullscreen')
  await ui.getByLabel('返回', { exact: true }).tap()
  await ui.waitForFunction(() => !document.querySelector('.is-fullscreen'))
  await ui.getByLabel('播放', { exact: true }).tap()
  await ui.getByLabel('视频更多操作').tap()
  await ui.getByText('倍速', { exact: true }).tap()
  await ui.getByText('1.5×', { exact: true }).tap()
  await ui.waitForFunction(() => document.querySelector('.player-host video').playbackRate === 1.5)
  await ui.locator('.player-back').tap()
  await ui.waitForFunction(() => !document.querySelector('.player-host video'))
  await ui.close()
  assert.deepEqual(errors, [])
  console.log('App renderjs passed: comment-node mount, delayed host, HLS play/pause/seek, callMethod, player isolation, DOM-removal cleanup and re-entry.')
  console.log('H5 video UI passed: touch play/pause/seek, status callback, fullscreen, speed and navigation cleanup.')
 } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

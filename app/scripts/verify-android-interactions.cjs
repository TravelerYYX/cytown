// Exercises the actual project's HBuilder WebView. Each toggle is restored.
const { connect } = require('./android-cdp.cjs')
const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const output = path.resolve(__dirname, '../artifacts/interactions')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const adb = process.env.ADB_PATH || 'D:/Game/MuMu/MuMuPlayer/nx_main/adb.exe'
async function waitFor(read, predicate, timeout = 15000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { const value = await read(); if (predicate(value)) return value; await pause(100) }
  throw new Error('Android wait timed out')
}
async function screenshot(client, name) { const { data } = await client.call('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(output, name + '.png'), Buffer.from(data, 'base64')) }
async function togglePair(client, selector, name) {
  const read = () => client.evaluate(`(()=>{const b=document.querySelector(${JSON.stringify(selector)});return b?{active:b.getAttribute('aria-label').includes('取消'),count:b.innerText,playing:b.querySelectorAll('.interaction-icon.playing').length,frames:b.querySelectorAll('.interaction-frames').length}:null})()`)
  const initial = await waitFor(read, value => value !== null)
  const click = () => client.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`)
  let changed = false
  try {
    // Two rapid DOM events go through the native view/service bridge.
    await client.evaluate(`(()=>{const b=document.querySelector(${JSON.stringify(selector)});b.click();b.click()})()`)
    let state = await waitFor(read, value => value && value.active !== initial.active)
    changed = true
    if (state.active) {
      await waitFor(read, value => value.playing > 0)
      await screenshot(client, 'android-' + name + '-playing')
      await pause(1600)
      state = await read(); assert.equal(state.playing, 0, name + ': animation looped'); assert.equal(state.frames, 0, name + ': stale sprite node remained')
    } else assert.equal(state.playing, 0, name + ': cancel animated')
    await click()
    state = await waitFor(read, value => value && value.active === initial.active)
    changed = false
    if (state.active) { await waitFor(read, value => value.playing > 0); await screenshot(client, 'android-' + name + '-playing'); await pause(1600) }
    else assert.equal(state.playing, 0)
    assert.equal((await read()).count, initial.count, name + ': counter not restored')
    assert.equal((await read()).frames, 0, name + ': static icon retained animation class')
    const source = await client.evaluate(`document.querySelector(${JSON.stringify(selector)}).querySelector('.interaction-static img')?.getAttribute('src')`)
    assert(source && source.includes(initial.active ? '-active.png' : (name === 'feed-like' ? 'feed-like.png' : '-idle.png')), name + ': incorrect static asset')
    await screenshot(client, 'android-' + name + '-restored')
    return { restored: true, rapidTaps: true, singlePlay: true, cancelWithoutAnimation: true }
  } finally { if (changed) { await click(); await waitFor(read, value => value && value.active === initial.active) } }
}
;(async () => {
  const client = await connect()
  try {
    const sprites = await client.evaluate(`Promise.all(['like-frames.png','favorite-frames.png'].map(name=>new Promise(resolve=>{const image=new Image;image.onload=()=>resolve({name,width:image.naturalWidth});image.onerror=()=>resolve({name,width:0});image.src='file:///storage/emulated/0/Android/data/io.dcloud.HBuilder/apps/HBuilder/www/static/images/interactions/'+name})))`)
    assert(sprites.every(sprite => sprite.width > 0), 'Android sprite asset failed to decode: ' + JSON.stringify(sprites))
    await client.evaluate(`(()=>{const b=document.querySelector('.load-tip uni-button');if(b)b.click()})()`)
    await waitFor(() => client.evaluate(`document.querySelectorAll('.home-view .post-card').length`), count => count > 0)
    const likes = await togglePair(client, '.home-view .post-card .action[aria-label$="点赞"]', 'feed-like')
    await client.evaluate(`document.querySelector('.home-view .post-card .post-copy').click()`)
    const detail = await waitFor(async () => { try { return await connect('pages/post/index') } catch { return null } }, value => value !== null)
    try {
      await waitFor(() => detail.evaluate(`document.querySelectorAll('.post-action-bar').length`), count => count > 0)
      const favorites = await togglePair(detail, '.post-action-bar [aria-label$="收藏"]', 'favorite')
      // Check the actual sprite on dark and gray surfaces using the app's CSS.
      await detail.evaluate(`(()=>{const root=document.createElement('div');root.id='social-transparency-check';root.style='position:fixed;top:100px;left:10px;right:10px;z-index:99999;display:flex;gap:8px;background:transparent';for(const bg of ['#fff','#aaa','#000']){const box=document.createElement('div');box.style='background:'+bg+';padding:12px;flex:1';for(const kind of ['like','favorite']){const im=document.createElement('img');im.src='/storage/emulated/0/Android/data/io.dcloud.HBuilder/apps/HBuilder/www/static/images/interactions/'+kind+'-active.png';im.style='width:40px;height:40px;display:block';box.append(im)}root.append(box)}document.body.append(root)})()`)
      await pause(400); await screenshot(detail, 'android-transparency')
      await detail.evaluate(`document.getElementById('social-transparency-check').remove()`)
      fs.writeFileSync(path.join(output, 'android-results.json'), JSON.stringify({ passed: true, runtime: 'Android HBuilder native WebView', likes, favorites }, null, 2))
      console.log('Android native WebView interaction checks passed')
    } finally { detail.close(); execFileSync(adb, ['shell', 'input', 'keyevent', '4']) }
  } finally { client.close() }
})().catch(error => { console.error(error); process.exit(1) })

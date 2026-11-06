// Read/scroll/navigate only. Does not publish, comment, like, follow or change accounts.
const { connect } = require('./android-cdp.cjs')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const output = path.resolve(__dirname, '../artifacts/performance')
const scrollExpression = `(()=>{const host=document.querySelector('.home-view .screen-scroll');return host&&[...host.querySelectorAll('.uni-scroll-view')].find(el=>/auto|scroll/.test(getComputedStyle(el).overflowY))})()`
async function ready(title, selector) {
  for (let i = 0; i < 100; i++) {
    let client
    try { client = await connect(title); if (await client.evaluate(`!!document.querySelector(${JSON.stringify(selector)})`)) return client; client.close() } catch { client?.close() }
    await pause(150)
  }
  throw new Error('Android page not ready: ' + title)
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const home = await ready('pages/index', '.virtual-post-column')
  try {
    for (let i = 0; i < 100 && !await home.evaluate(`document.querySelectorAll('.home-view [data-virtual-id]').length`); i++) await pause(150)
    assert(await home.evaluate(`document.querySelectorAll('.home-view [data-virtual-id]').length`) > 0, 'Android local feed is empty/unavailable; cannot measure performance')
    // Warm the lazily mounted follow pane before comparing retained resources.
    await home.evaluate(`document.querySelectorAll('.tab-item')[1].click()`); await pause(300)
    await home.evaluate(`document.querySelectorAll('.tab-item')[0].click()`); await pause(300)
    await home.call('Performance.enable')
    await home.call('HeapProfiler.collectGarbage')
    const before = await home.call('Performance.getMetrics')
    const frames = await home.evaluate(`new Promise(resolve=>{const el=${scrollExpression},samples=[];let start=performance.now(),last=start;const target=Math.min(700,el.scrollHeight-el.clientHeight);function step(now){samples.push(now-last);last=now;el.scrollTop=Math.min(1,(now-start)/1800)*target;if(now-start<1800)requestAnimationFrame(step);else{samples.sort((a,b)=>a-b);resolve({samples:samples.length,p95Ms:samples[Math.floor(samples.length*.95)],over50ms:samples.filter(n=>n>50).length,top:el.scrollTop,collapsed:getComputedStyle(document.querySelector('.home-compact')).visibility==='visible'})}}requestAnimationFrame(step)})`)
    console.log('Android scroll sample captured.')
    await pause(250)
    const position = await home.evaluate(`${scrollExpression}.scrollTop`)
    const tabs = []
    for (let i = 0; i < 20; i++) {
      await home.evaluate(`document.querySelectorAll('.tab-item')[1].click()`)
      await pause(100)
      const start = Date.now()
      await home.evaluate(`document.querySelectorAll('.tab-item')[0].click()`)
      let top = await home.evaluate(`${scrollExpression}.scrollTop`)
      while (Math.abs(top - position) > 2 && Date.now() - start < 2000) { await pause(20); top = await home.evaluate(`${scrollExpression}.scrollTop`) }
      assert(Math.abs(top - position) <= 2, 'tab switch lost reading position: ' + top + '/' + position)
      tabs.push(Date.now() - start)
    }
    console.log('Android 20 tab cycles completed.')
    const detailResults = []
    // Select image/text cards, then video cards when available; never require autoplay.
    const ids = await home.evaluate(`[...document.querySelectorAll('.home-view [data-post-id]')].map(el=>({id:el.dataset.postId,video:!!el.querySelector('.waterfall-play')}))`)
    assert(ids.length, 'Android waterfall cards are required for detail cycles')
    for (let i = 0; i < 20 && ids.length; i++) {
      const selected = ids[i % ids.length]
      await home.evaluate(`document.querySelector(${JSON.stringify('[data-post-id="' + selected.id + '"]')}).click()`)
      const detail = await ready('pages/post/index', '.post-detail [aria-label="返回"]')
      try {
        // CDP can see content before the native opening animation accepts touches.
        await pause(500)
        await detail.evaluate(`document.querySelector('.player-back, .detail-author [aria-label="返回"], .post-detail [aria-label="返回"]')?.click()`)
      } finally { detail.close() }
      await pause(500)
      assert(Math.abs(await home.evaluate(`${scrollExpression}.scrollTop`) - position) <= 2, 'detail return lost reading position')
      detailResults.push({ video: selected.video, restored: true })
      if ((i + 1) % 5 === 0) console.log('Android detail cycles: ' + (i + 1))
    }
    await home.call('HeapProfiler.collectGarbage')
    const after = await home.call('Performance.getMetrics')
    const dom = await home.evaluate(`({nodes:document.querySelectorAll('*').length,mounted:document.querySelectorAll('.home-view [data-virtual-id]').length,loaded:[...document.querySelectorAll('.home-view .virtual-post-column')].reduce((n,el)=>n+Number(el.dataset.loadedCount),0),players:document.querySelectorAll('video').length,webview:navigator.userAgent})`)
    const result = { environment: 'One connected Android HBuilder 5.15 debug base, real local data; not multi-device release acceptance', frames, position, tabCycles: tabs.length, tabRestoreObservedMs: tabs, detailResults, dom, before, after }
    const screenshot = await home.call('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(output, 'android-after.png'), Buffer.from(screenshot.data, 'base64'))
    fs.writeFileSync(path.join(output, 'android-after.json'), JSON.stringify(result, null, 2))
    console.log(JSON.stringify({ frames, tabCycles: tabs.length, detailCycles: detailResults.length, dom }, null, 2))
  } finally { home.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const mode = process.argv[2] || 'after'
const layout = process.env.PERF_LAYOUT || 'waterfall'
const cardSelector = '.home-view .waterfall-post, .home-view .post-card'
const output = path.resolve(__dirname, '../artifacts/performance')
fs.mkdirSync(output, { recursive: true })
const origin = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
const count = 300
const records = Array.from({ length: count }, (_, i) => ({
  id: String(i + 1000), authorId: String(20 + i % 12), authorNickname: '性能验收作者',
  title: '混合内容与长列表流畅度验证 ' + i, content: '保持原有布局和阅读位置。'.repeat(i % 5),
  mode: i % 4 === 0 ? 'video' : 'image', status: 1, tags: i % 3 ? [] : ['测试话题'],
  media: i % 6 === 0 ? [] : [{ id: i, type: i % 4 === 0 ? 'video' : 'image',
    aspectRatio: [1, 16 / 9, 3 / 4, 1 / 4, 0][i % 5], layout: 'wide',
    coverUrl: `http://performance.test/${i}.svg`, resourceVersion: 'performance-v1' }],
  likeCount: i, commentCount: i % 9, liked: false, favorited: true, date: '10-17'
}))
async function homeCall(page, name) {
  return page.locator('.home-view').evaluate(async (el, method) => {
    let vm = el.__vueParentComponent
    while (vm && !vm.setupState[method]) vm = vm.parent
    if (!vm) throw new Error('Home method missing: ' + method)
    await vm.setupState[method]()
  }, name)
}
async function fixtures(page, requests = [], feedLayout = 'waterfall') {
    await page.route('**/app/**', async route => {
      const url = new URL(route.request().url()); requests.push(url.pathname)
      let data = []
      if (url.pathname === '/app/home/config') data = { channels: [{ id: 1, name: '推荐' }], entries: [], news: { enabled: false, interval: 3, items: [] }, defaultFeedLayout: feedLayout }
      else if (/\/post\/(list|mine|favorites|following)$/.test(url.pathname)) {
        const n = Number(url.searchParams.get('pageNum') || 1)
        data = { records: records.slice((n - 1) * 10, n * 10), total: count, current: n, pages: count / 10 }
      } else if (/\/post\/\d+$/.test(url.pathname)) data = records.find(p => p.id === url.pathname.split('/').pop())
      else if (url.pathname.includes('/comments')) data = { records: [], current: 1, pages: 0 }
      else if (url.pathname === '/app/user/statistics') data = { followerCount: 0, followingCount: 0, receivedLikeCount: 0 }
      else if (url.pathname === '/app/media/config') data = { cacheMaxBytes: 16777216 }
      else if (url.pathname === '/app/comment/config') data = { imageMax: 3, contentMax: 1000, emojis: [], emojiGroups: [] }
      await route.fulfill({ json: { code: '0000', data } })
    })
    await page.route('http://performance.test/**', route => route.fulfill({ contentType: 'image/svg+xml', headers: { 'Access-Control-Allow-Origin': '*' }, body: '<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400"><rect width="300" height="400" fill="#d5e8ee"/></svg>' }))
}
module.exports = { fixtures, records }
if (require.main === module) (async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
    const errors = [], requests = []
    page.on('pageerror', e => errors.push(e.message))
    await fixtures(page, requests, layout)
    await page.goto(origin)
    await page.evaluate(() => {
      uni.setStorageSync('app_token', 'performance-fixture')
      uni.setStorageSync('app_user_info', { userId: '10', nickname: '性能测试', tags: [] })
    })
    requests.length = 0
    const started = Date.now()
    await page.reload()
    await page.waitForSelector(cardSelector)
    const firstContentMs = Date.now() - started
    const initialRequests = [...requests]
    for (let n = 1; n < 30; n++) await homeCall(page, 'loadMore')
    await page.waitForTimeout(500)
    const before = await page.evaluate(() => ({ nodes: document.querySelectorAll('*').length, cards: document.querySelectorAll('.home-view .waterfall-post, .home-view .post-card').length, heap: performance.memory?.usedJSHeapSize }))
    const scrolling = await page.locator('.home-view > .screen-scroll').evaluate(async host => {
      const el = [...host.querySelectorAll('*')].find(n => ['auto', 'scroll'].includes(getComputedStyle(n).overflowY))
      if (!el) throw new Error('Scroll container missing')
      const frames = []; let last = performance.now(), start = last
      await new Promise(resolve => { function step(now) { frames.push(now - last); last = now; el.scrollTop = (now - start) / 2500 * Math.min(el.scrollHeight - el.clientHeight, 18000); if (now - start < 2500) requestAnimationFrame(step); else resolve() } requestAnimationFrame(step) })
      frames.sort((a, b) => a - b)
      return { frameP95Ms: frames[Math.floor(frames.length * .95)], framesOver50ms: frames.filter(x => x > 50).length, samples: frames.length, top: el.scrollTop }
    })
    await page.waitForTimeout(300)
    const targetTop = scrolling.top
    const tabTimes = []
    for (let i = 0; i < 20; i++) {
      await page.locator('.tab-item').nth(1).click()
      const t = await page.locator('.tab-item').nth(0).evaluate(async el => { const start = performance.now(); el.click(); await new Promise(requestAnimationFrame); return performance.now() - start })
      tabTimes.push(t)
      await page.waitForTimeout(100)
      if (mode !== 'baseline') {
        const top = await page.locator('.home-view > .screen-scroll').evaluate(host => [...host.querySelectorAll('*')].find(n => ['auto', 'scroll'].includes(getComputedStyle(n).overflowY)).scrollTop)
        assert(Math.abs(top - targetTop) < 3, 'warm tab lost scroll position: ' + top + '/' + targetTop)
      }
    }
    await page.locator('.tab-item').first().click()
    await page.waitForTimeout(300)
    const after = await page.evaluate(() => ({ nodes: document.querySelectorAll('*').length, cards: document.querySelectorAll('.home-view .waterfall-post, .home-view .post-card').length, heap: performance.memory?.usedJSHeapSize }))
    tabTimes.sort((a,b) => a-b)
    const result = { mode, layout, environment: 'Desktop Chromium, H5 development, synthetic 300 posts; not Android frame-rate acceptance', firstContentMs, initialRequests, before, scrolling, warmTabP95Ms: tabTimes[18], after, requests: requests.reduce((all, key) => ({ ...all, [key]: (all[key] || 0) + 1 }), {}), errors }
    if (mode !== 'baseline') { assert(before.cards < 100, '300 posts must not all be mounted'); assert(after.cards < 100); assert.equal(requests.filter(x => x === '/app/post/list').length, 30, 'fresh tab switches reloaded feed'); assert.equal(errors.length, 0, errors.join('\n')) }
    await page.screenshot({ path: path.join(output, mode + (layout === 'single' ? '-single' : '') + '.png') })
    fs.writeFileSync(path.join(output, mode + (layout === 'single' ? '-single' : '') + '.json'), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result, null, 2))
  } finally { await browser.close() }
})().catch(e => { console.error(e); process.exitCode = 1 })

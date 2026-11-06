const { chromium } = require('playwright-core')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const output = path.resolve(__dirname, '../artifacts/home-news')
const url = process.env.UI_TEST_URL || 'http://127.0.0.1:15273/'
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const empty = '暂无资讯，敬请期待'
const fixture = n => ({ channels: [{ id: 1, name: '推荐' }], entries: Array.from({ length: n }, (_, i) => ({
  id: i + 1, name: ['每日签到', '攻略合集', '版本资讯', '旅行工具', '原神战绩', '这是一个特别长的入口标题', '更多工具'][i % 7],
  iconUrl: '/test-entry.png', linkUrl: 'https://example.test/', openMode: 'inapp', showTopbar: true
})), news: { enabled: true, interval: 2, emptyText: empty, items: [] } })
const post = { id: '11', authorId: null, authorNickname: '测试作者', authorLevel: 1, title: '轮播跳转测试', content: '内容', date: '09-08', createTime: '2026-09-08 00:00:00', media: [], tags: [], likeCount: 0, commentCount: 0 }
async function setNews(page, news) {
  await page.locator('.home-header').evaluate((el, value) => {
    let instance = el.__vueParentComponent
    while (instance && !('news' in instance.setupState)) instance = instance.parent
    if (!instance) throw new Error('HomeView not found')
    instance.setupState.news = value
  }, news)
  await pause(80)
}
async function translateY(page) {
  return page.locator('.news-track').evaluate(el => new DOMMatrix(getComputedStyle(el).transform).m42)
}
async function refresh(page) {
  await page.locator('.home-view').evaluate(el => {
    let instance = el.__vueParentComponent
    while (instance && !instance.setupState.refreshAll) instance = instance.parent
    if (!instance) throw new Error('HomeView refresh not found')
    return instance.setupState.refreshAll()
  })
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const errors = [], results = []
  try {
    for (const width of [320, 375, 390, 430]) {
      const context = await browser.newContext({ viewport: { width, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 })
      let config = fixture(1), failConfig = false
      const page = await context.newPage()
      page.on('pageerror', error => errors.push(error.message))
      await page.route('http://127.0.0.1:8080/**', async route => {
        const pathname = new URL(route.request().url()).pathname
        if (pathname === '/test-entry.png') {
          const reference = process.env.UI_ENTRY_IMAGE
          return route.fulfill(reference ? { path: reference, contentType: reference.endsWith('.webp') ? 'image/webp' : 'image/png' } : { path: path.resolve(__dirname, '../原神.png'), contentType: 'image/png' })
        }
        if (pathname === '/app/home/config' && failConfig) return route.fulfill({ status: 503, body: '{}' })
        const data = pathname === '/app/home/config' ? config : pathname === '/app/notice/categories' || pathname.includes('category') ? [] : /^\/app\/post\/\d+$/.test(pathname) ? post : { records: [], total: 0, pages: 0, current: 1, size: 10 }
        await route.fulfill({ json: { code: '0000', msg: 'ok', data } })
      })
      for (const count of [1, 3, 7]) {
        config = fixture(count)
        await page.goto(url)
        await page.waitForSelector('.entry-image')
        await page.locator('.entry-image img').evaluateAll(els => Promise.all(els.map(el => el.decode().catch(() => {}))))
        const geometry = await page.locator('.entries-track').evaluate(el => {
          const rect = e => { const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height } }
          return [...el.querySelectorAll('.entry')].map(entry => ({ entry: rect(entry), icon: rect(entry.querySelector('.entry-image')), label: rect(entry.querySelector('.entry-label')), font: parseFloat(getComputedStyle(entry.querySelector('.entry-label')).fontSize) }))
        })
        const scale = width / 750
        for (const item of geometry) {
          assert(Math.abs(item.icon.width - 88 * scale) < 1, 'icon width')
          assert(Math.abs(item.label.width - 124 * scale) < 1, 'label width')
          assert(Math.abs(item.icon.x + item.icon.width / 2 - item.label.x - item.label.width / 2) < 1, 'icon/label center')
          assert(Math.abs(item.label.y - item.icon.y - item.icon.height - 24 * scale) < 1, 'image label gap')
          assert(Math.abs(item.font - 23 * scale) < .2, 'font size unchanged')
        }
        assert(Math.abs(geometry[0].entry.x - 26 * scale) < 1, 'entries must start from the left')
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width, 'page overflow')
        const header = await page.locator('.home-header').boundingBox()
        assert(Math.abs(header.height - 452 * scale) < 1, 'header height unchanged')
        const label = geometry[0].label
        const bar = await page.locator('.news-bar').boundingBox()
        assert(label.y + label.height < bar.y, 'labels overlap news')
        assert.equal(await page.locator('.news-empty').innerText(), empty)
        await page.locator('.home-header').screenshot({ path: path.join(output, `${width}-${count}-entries.png`) })
        if (count === 7) {
          await page.locator('.entries .uni-scroll-view').evaluateAll(els => { for (const el of els) el.scrollLeft = 9999 })
          await pause(100)
          const last = await page.locator('.entry').last().boundingBox()
          assert(last.x >= 0 && last.x + last.width <= width + 1, 'last entry reachable')
          await page.locator('.home-header').screenshot({ path: path.join(output, `${width}-scrolled.png`) })
        }
        results.push({ width, count, geometry: 'passed' })
      }
      if (width === 390) {
        // Empty text is visible and inert; one item remains static.
        const before = page.url()
        await page.locator('.news-window').click()
        assert.equal(page.url(), before)
        let news = { enabled: true, interval: 2, emptyText: '后台配置的占位文案', items: [{ postId: 11, title: '第一条资讯' }] }
        await setNews(page, news)
        await pause(2200)
        assert(Math.abs(await translateY(page)) < 1, 'static/wrapped position')
        // Exercise transition end and wrap-around with real browser timers.
        news.items.push({ postId: 12, title: '第二条资讯' })
        await setNews(page, news)
        await pause(2400)
        assert(Math.abs(await translateY(page) + 68 * width / 750) < 1, 'second item position')
        await pause(2100)
        assert(Math.abs(await translateY(page)) < 1, 'static/wrapped position')
        // Shrinking data clears the old timer and returns to a static first item.
        news.items = [{ postId: 11, title: '更新后的单条' }]
        await setNews(page, news)
        await pause(2200)
        assert(Math.abs(await translateY(page)) < 1, 'static/wrapped position')
        await page.locator('.news-window').click()
        await page.waitForURL('**/pages/post/index?id=11')
        await page.goto(url)
        await page.waitForSelector('.entry')
        // 「全部」直接跳转后台配置的首页资讯轮播帖子详情（单条配置 → 帖子 11）。
        config.news = { enabled: true, interval: 2, emptyText: empty, items: [{ postId: 11, title: '第一条资讯' }] }
        await refresh(page)
        await page.locator('.news-all').click()
        await page.waitForURL('**/pages/post/index?id=11')
        // 未配置资讯（items 为空）时「全部」不跳转。
        config.news = { enabled: true, interval: 2, emptyText: empty, items: [] }
        await page.goto(url)
        await page.waitForSelector('.news-empty')
        await refresh(page)
        const beforeAll = page.url()
        await page.locator('.news-all').click()
        await pause(300)
        assert.equal(page.url(), beforeAll, 'empty news 全部 must not navigate')
        failConfig = true
        await refresh(page)
        assert.equal(await page.locator('.entry').count(), 7, 'refresh failure preserves entries')
        assert.equal(await page.locator('.news-empty').innerText(), empty)
        failConfig = false
        config.news = { ...config.news, enabled: false }
        await refresh(page)
        assert.equal(await page.locator('.news-bar').count(), 0)
        failConfig = true
        await refresh(page)
        assert.equal(await page.locator('.news-bar').count(), 0, 'refresh failure preserves disabled setting')
        await page.goto(url)
        await page.waitForSelector('.news-empty')
        assert.equal(await page.locator('.news-empty').innerText(), empty, 'initial request failure fallback')
        await page.locator('.home-header').screenshot({ path: path.join(output, '390-initial-failure.png') })
      }
      await context.close()
    }
    assert.deepEqual(errors, [], 'browser runtime errors')
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ results, interactions: 'passed', errors }, null, 2))
    console.log(JSON.stringify({ cases: results.length, interactions: 'passed', errors }))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

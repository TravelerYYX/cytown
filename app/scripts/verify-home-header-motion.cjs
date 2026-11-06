const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const { fixtures } = require('./verify-performance.cjs')
const output = path.resolve(__dirname, '../artifacts/header-motion')
const base = process.env.UI_TEST_URL || 'http://127.0.0.1:15273/'
async function scroll(page, top) {
  await page.locator('.home-view > .screen-scroll').evaluate((host, top) => {
    const scroller = [...host.querySelectorAll('*')].find(node => ['auto', 'scroll'].includes(getComputedStyle(node).overflowY))
    scroller.scrollTop = top
  }, top)
  await page.waitForTimeout(65)
}
async function pose(page) {
  return page.evaluate(() => {
    const root = document.querySelector('.home-view'), compact = root.querySelector('.home-compact')
    const original = root.querySelector('[id$="-original-tabs"]'), pinned = root.querySelector('[id$="-pinned-tabs"]')
    const search = root.querySelector('.search-box-motion'), avatar = root.querySelector('.search-avatar-motion')
    return { opacity: getComputedStyle(compact).visibility === 'hidden' ? 0 : +getComputedStyle(search).opacity, visible: getComputedStyle(compact).visibility,
      original: getComputedStyle(original).visibility, pinned: getComputedStyle(pinned).visibility,
      searchY: new DOMMatrix(getComputedStyle(search).transform).m42, avatarY: new DOMMatrix(getComputedStyle(avatar).transform).m42,
      seam: pinned.getBoundingClientRect().top - root.querySelector('.search-header').getBoundingClientRect().bottom,
      searchDisabled: compact.querySelector('.search-box').getAttribute('disabled') === 'true',
      pinnedDisabled: pinned.querySelector('.channel-tab').getAttribute('disabled') === 'true',
      stable: window.__headerNode === compact }
  })
}
async function press(page, selector, cancel = false) {
  const button = page.locator(selector)
  await button.evaluate(node => {
    const touch = new Touch({ identifier: 1, target: node, clientX: 50, clientY: 20 })
    node.dispatchEvent(new TouchEvent('touchstart', { touches: [touch], bubbles: true }))
  })
  await page.waitForTimeout(120)
  const scale = await button.evaluate(node => new DOMMatrix(getComputedStyle(node).transform).m11)
  await button.evaluate((node, cancel) => node.dispatchEvent(new TouchEvent(cancel ? 'touchcancel' : 'touchend', { touches: [], bubbles: true })), cancel)
  await page.waitForTimeout(180)
  const restored = await button.evaluate(node => new DOMMatrix(getComputedStyle(node).transform).m11)
  assert(Math.abs(scale - .97) < .002, 'Pressed scale: ' + scale)
  assert(Math.abs(restored - 1) < .002, 'Release/cancel restores scale')
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const results = [], errors = []
  try {
    for (const width of [320, 390, 430]) for (const layout of ['single', 'waterfall']) for (const news of [true, false]) {
      const context = await browser.newContext({ viewport: { width, height: 844 }, isMobile: true, hasTouch: true })
      const page = await context.newPage()
      page.on('pageerror', error => errors.push(error.message))
      await fixtures(page, [], layout)
      await page.route('**/app/home/config', route => route.fulfill({ json: { code: '0000', data: {
        channels: [{ id: 1, name: '推荐' }, { id: 2, name: '官方' }], entries: [],
        news: { enabled: news, interval: 3, items: [] }, defaultFeedLayout: layout
      } } }))
      await page.goto(base)
      await page.locator('.home-view .post-card,.home-view .waterfall-post').first().waitFor()
      await page.waitForTimeout(150)
      const threshold = await page.evaluate(() => {
        const root = document.querySelector('.home-view')
        window.__headerNode = root.querySelector('.home-compact')
        return root.querySelector('.feed-card').getBoundingClientRect().top - root.querySelector('.search-header').getBoundingClientRect().bottom
      })
      assert.equal((await pose(page)).searchDisabled, true)
      const samples = []
      for (const progress of [0, .25, .5, .75, 1]) {
        await scroll(page, progress === 1 ? Math.ceil(threshold) : Math.max(0, threshold - (1 - progress) * width * 108 / 750))
        samples.push(await pose(page))
      }
      assert(samples[0].opacity < .005)
      assert(samples[1].opacity > 0 && samples[1].opacity < samples[2].opacity)
      assert(samples[2].opacity < samples[3].opacity && samples[3].opacity < 1)
      assert.equal(samples[4].opacity, 1)
      assert(samples.every(sample => sample.stable), 'Scroll must retain the same header node')
      assert(samples.slice(0, 4).every(sample => sample.pinned === 'hidden' && sample.original === 'visible'), 'Only original tabs are visible before the seam')
      assert.equal(samples[4].pinned, 'visible'); assert.equal(samples[4].original, 'hidden')
      assert(Math.abs(samples[4].seam) < 1, 'Search/channel seam')
      assert.equal(samples[4].searchY, 0); assert.equal(samples[4].avatarY, 0)
      assert.equal(samples[4].searchDisabled, false); assert.equal(samples[4].pinnedDisabled, false)
      await page.screenshot({ path: path.join(output, `${width}-${layout}-news-${news}-pinned.png`) })
      for (const top of [threshold - 2, threshold + 2, threshold - 2, threshold + 2]) { await scroll(page, top); assert.equal((await pose(page)).pinned, top >= threshold ? 'visible' : 'hidden') }
      await scroll(page, threshold - width * 54 / 750)
      const mid = await pose(page)
      assert(mid.opacity > .4 && mid.opacity < .6, 'Reverse scroll remains continuous')
      await page.screenshot({ path: path.join(output, `${width}-${layout}-news-${news}-middle.png`) })
      await scroll(page, threshold + 30)
      await press(page, '.home-compact .search-box')
      await press(page, '.home-compact .search-avatar', true)
      // Search is an existing no-op destination; verify it stays on this page.
      const homeURL = page.url()
      await page.locator('.home-compact .search-box').click()
      assert.equal(page.url(), homeURL)
      // Avatar has an actual route; use it to verify return restoration.
      await page.locator('.home-compact .search-avatar').click()
      await page.locator('.auth-page').waitFor()
      await page.evaluate(() => uni.navigateBack())
      await page.locator('.home-view').waitFor(); await page.waitForTimeout(200)
      assert.equal((await pose(page)).opacity, 1)
      await page.locator('.tab-bar .tab-item').nth(1).click()
      await page.locator('.follow-view').waitFor()
      await page.locator('.tab-bar .tab-item').first().click()
      await page.waitForTimeout(200); assert.equal((await pose(page)).opacity, 1)
      await page.locator('.tab-bar .tab-item').first().click()
      await page.waitForTimeout(200); const reset = await pose(page)
      assert.equal(reset.opacity, 0); assert.equal(reset.visible, 'hidden'); assert.equal(reset.stable, true)
      await press(page, '.home-view .game-row [aria-label="游戏列表"]', true)
      await page.locator('.home-view .game-row [aria-label="游戏列表"]').click()
      assert.equal(page.url(), homeURL)
      if (width === 390 && layout === 'single' && news) {
        await page.emulateMedia({ reducedMotion: 'reduce' })
        await scroll(page, threshold - width * 54 / 750)
        const reduced = await pose(page)
        assert.equal(reduced.searchY, 0); assert.equal(reduced.avatarY, 0)
        const button = page.locator('.home-compact .search-box')
        await button.dispatchEvent('mousedown'); await page.waitForTimeout(120)
        assert.equal(await button.evaluate(node => new DOMMatrix(getComputedStyle(node).transform).m11), 1)
        await button.dispatchEvent('mouseup')
        await page.emulateMedia({ reducedMotion: 'no-preference' })
        await scroll(page, threshold + 30)
        await page.setViewportSize({ width: 430, height: 844 }); await page.waitForTimeout(200)
        assert(Math.abs((await pose(page)).seam) < 1, 'Resize preserves channel seam')
      }
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
      results.push({ width, layout, news, threshold, samples, status: 'passed' })
      await context.close()
    }
    assert.deepEqual(errors, [])
    fs.writeFileSync(path.join(output, 'h5-report.json'), JSON.stringify({ status: 'passed', scope: 'H5 fixture data; Android is verified separately', results, errors }, null, 2))
    console.log('Home header motion passed: 12 viewport/layout/news combinations; continuous/reverse progress, stable mounting, tab seam, touch press/cancel, navigation, tab restoration, return to top, resize and reduced motion.')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

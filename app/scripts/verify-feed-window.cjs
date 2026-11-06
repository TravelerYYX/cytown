const { chromium } = require('playwright-core')
const { fixtures } = require('./verify-performance.cjs')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
async function state(page, pane, action) {
  return page.locator(pane).evaluate(async (el, action) => {
    let instance = el.__vueParentComponent
    while (instance && !instance.setupState.loadMore) instance = instance.parent
    if (action === 'more') await instance.setupState.loadMore()
    return instance.setupState.posts.length
  }, action)
}
const scroller = (page, pane) => page.locator(pane + ' > .screen-scroll > .uni-scroll-view > .uni-scroll-view')
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
    const errors = [], results = []
    page.on('pageerror', error => errors.push(error.message))
    await fixtures(page)
    await page.goto(process.env.UI_TEST_URL || 'http://127.0.0.1:15273')
    await page.evaluate(() => { uni.setStorageSync('app_token', 'window-fixture'); uni.setStorageSync('app_user_info', { userId: '10', nickname: '窗口测试', tags: [] }) })
    await page.reload()
    for (const [index, pane] of [[0, '.home-view'], [1, '.follow-view'], [4, '.mine-view']]) {
      if (index) await page.locator('.tab-item').nth(index).click()
      await page.locator(pane + ' [data-virtual-id]').first().waitFor()
      while (await state(page, pane) < 300) await state(page, pane, 'more')
      for (const top of [0, 15000, 40000, 2500]) {
        await scroller(page, pane).evaluate((el, top) => { el.scrollTop = top }, top)
        await page.waitForTimeout(180)
        const snapshot = await page.locator(pane).evaluate(el => {
          const scroll = [...el.querySelector('.screen-scroll').querySelectorAll('.uni-scroll-view')].find(n => /auto|scroll/.test(getComputedStyle(n).overflowY))
          const rect = scroll.getBoundingClientRect()
          const rows = [...el.querySelectorAll('[data-virtual-id]')]
          return { loaded: [...el.querySelectorAll('[data-loaded-count]')].reduce((n, row) => n + Number(row.dataset.loadedCount), 0), mounted: rows.length, top: scroll.scrollTop, visible: rows.filter(row => { const r = row.getBoundingClientRect(); return r.bottom > rect.top && r.top < rect.bottom }).length }
        })
        assert.equal(snapshot.loaded, 300)
        assert(snapshot.mounted < 100, pane + ': unbounded rendering')
        assert(snapshot.visible > 0, pane + ': blank viewport after jump')
        results.push({ pane, ...snapshot })
      }
    }
    await page.locator('.tab-item').first().click()
    await page.waitForTimeout(180)
    const position = await scroller(page, '.home-view').evaluate(el => el.scrollTop)
    for (let i = 0; i < 20; i++) {
      // A known nonvideo row, clicked directly so automation does not change reading position.
      const id = await page.locator('.home-view .waterfall-post').evaluateAll(nodes => nodes.find(n => !n.querySelector('.waterfall-play')).dataset.postId)
      await page.locator(`[data-post-id="${id}"]`).evaluate(el => el.click())
      await page.locator('.post-detail [aria-label="返回"]').first().waitFor()
      await page.locator('.post-detail [aria-label="返回"]').first().click()
      await page.locator('.home-view').waitFor({ state: 'visible' })
      await page.waitForTimeout(80)
      assert(Math.abs(await scroller(page, '.home-view').evaluate(el => el.scrollTop) - position) < 3, 'detail return lost scroll anchor')
    }
    for (const width of [320, 430, 390]) {
      await page.setViewportSize({ width, height: 844 }); await page.waitForTimeout(200)
      assert(await page.locator('.home-view [data-virtual-id]').count() < 100)
    }
    assert.deepEqual(errors, [])
    fs.writeFileSync(path.resolve(__dirname, '../artifacts/performance/window-regression.json'), JSON.stringify({ passed: true, scenarios: results, detailCycles: 20, widths: [320, 430, 390], errors }, null, 2))
    console.log('Feed windows passed: 300 rows in home/follow/mine, fast jumps, bounded mounts, visible content, 20 detail returns, resize and no runtime errors.')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

const { chromium } = require('playwright-core')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const url = (process.env.UI_TEST_URL || 'http://127.0.0.1:15273/').replace(/\/$/, '').split('#')[0]
const output = path.resolve(__dirname, '../artifacts/post-ui')
fs.mkdirSync(output, { recursive: true })
const errors = []
const failures = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function screenshot(page, name) {
  await page.locator('img').evaluateAll(els => Promise.all(els.map(img => img.decode().catch(() => {}))))
  await pause(60)
  await page.screenshot({ path: path.join(output, name + '.png') })
}
async function go(page, id) {
  const serverIds = { 'example-mixed': '1', 'example-wide-group': '2', 'example-landscape': '3', 'example-portrait': '4', 'example-video-wide': '5', 'example-video-portrait': '6' }
  id = serverIds[id] || id
  // A fresh document exercises each deep link; changing only the hash query keeps uni's page instance.
  await page.goto('about:blank')
  await page.goto(url + '/#/pages/post/index?id=' + id)
  await page.waitForSelector('.post-detail .detail-scroll')
  await pause(250)
}
async function checkLayout(page, width) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width, 'horizontal document overflow')
  const footer = await page.locator('.post-action-bar').boundingBox()
  const scroll = await page.locator('.detail-scroll').boundingBox()
  assert(Math.abs(scroll.y + scroll.height - footer.y) < 2, 'footer overlaps scroll area')
  const media = await page.locator('.post-detail .media-fill, .post-detail .skeleton-block').evaluateAll(els => els.map(el => getComputedStyle(el).backgroundColor))
  for (const value of media) {
    const [r, g, b] = value.match(/\d+/g).map(Number)
    assert(r === g && g === b, 'non-gray content placeholder')
  }
  assert.equal(await page.locator('.post-detail video').count(), 0, 'unexpected video implementation')
  const imageSources = await page.locator('.post-detail img').evaluateAll(els => els.map(el => el.src))
  assert(imageSources.every(src => src.includes('/static/icons/') || src.includes('/static/images/') || src.includes('/uploads/')), 'unexpected image origin')
}
;(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.UI_BROWSER_PATH ? { executablePath: process.env.UI_BROWSER_PATH } : {}) })
  const results = []
  try {
    for (const width of [390, 320, 375, 393, 414, 430]) {
      const context = await browser.newContext({ viewport: { width, height: Math.round(width * 2.2) }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 })
      const page = await context.newPage()
      page.on('pageerror', error => errors.push(error.message))
      page.on('response', res => { if (res.url().includes('/static/') && res.status() >= 400) failures.push(res.url()) })
      for (const [id, ratio] of [['example-landscape', 4 / 3], ['example-portrait', 2 / 3], ['example-video-wide', 2.2], ['example-video-portrait', 9 / 16]]) {
        await go(page, id)
        await checkLayout(page, width)
        const block = await page.locator('.media-skeleton').first().boundingBox()
        assert(Math.abs(block.width / block.height - ratio) < .015, id + ': incorrect media ratio ' + JSON.stringify(block))
        if (id.includes('video')) {
          assert.equal(await page.locator('.video-navigation').count(), 1)
          assert.equal(await page.locator('.player-controls').count(), 1)
          await page.locator('.video-stage').click({ position: { x: 20, y: 20 } })
          assert.equal(await page.locator('.player-controls').count(), 0)
          if (width === 390) await screenshot(page, id + '-hidden')
          await page.locator('.video-stage').click({ position: { x: 20, y: 20 } })
          assert.equal(await page.locator('.player-controls').count(), 1)
        }
        if (width === 390) await screenshot(page, id)
        await page.locator('[aria-label="查看评论"]').click()
        await pause(250)
        const heading = await page.locator('.comment-heading').boundingBox()
        const area = await page.locator('.detail-scroll').boundingBox()
        const atEnd = await page.locator('.detail-scroll > .uni-scroll-view > .uni-scroll-view').evaluate(el => Math.abs(el.scrollTop + el.clientHeight - el.scrollHeight) < 2)
        assert(heading.y >= area.y - 1 && (heading.y < area.y + 4 || atEnd) && heading.y < area.y + area.height, id + ': comment anchor not visible')
        if (width === 390) await screenshot(page, id + '-comments')
      }
      await go(page, 'example-landscape')
      await page.locator('.gallery-dots uni-button').nth(1).click()
      await pause(350)
      assert.equal(await page.locator('.gallery-counter').innerText(), '2/2')
      const gallery = await page.locator('.image-swiper').boundingBox()
      assert(Math.abs(gallery.width / gallery.height - 2 / 3) < .015, 'mixed gallery must retain original portrait proportions in detail')
      if (width === 390) await screenshot(page, 'mixed-gallery-second')
      results.push({ width, details: 4, ratios: 'passed', commentAnchors: 'passed' })
      await context.close()
    }
    const context = await browser.newContext({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
    const page = await context.newPage()
    page.on('pageerror', error => errors.push(error.message))
    await page.goto(url)
    await page.waitForSelector('.home-view .post-card')
    const card = title => page.locator('.home-view .post-card').filter({ has: page.locator('.post-title', { hasText: title }) }).first()
    const mixed = card('等待亲亲ing')
    assert.equal(await mixed.locator('.media-slot').count(), 2)
    const sizes = await mixed.locator('.media-skeleton').evaluateAll(els => els.map(el => ({ width: el.getBoundingClientRect().width, height: el.getBoundingClientRect().height })))
    assert(sizes.every(item => Math.abs(item.width / item.height - 1) < .015), 'mixed thumbnails must follow first square image')
    const grouped = card('那只飞过一切的火蝶')
    assert.equal(await grouped.locator('.media-slot').count(), 2)
    assert.equal(await grouped.locator('.media-count').count(), 0)
    const wideMixed = card('菲林斯1命')
    assert.equal(await wideMixed.locator('.media-slot').count(), 2)
    const wideSizes = await wideMixed.locator('.media-skeleton').evaluateAll(els => els.map(el => el.getBoundingClientRect().width / el.getBoundingClientRect().height))
    assert(wideSizes.every(ratio => Math.abs(ratio - 4 / 3) < .015), 'mixed landscape/portrait thumbnails must follow first landscape')
    const portraitGroup = card('芙宁娜深海来信')
    const portraitCount = await portraitGroup.evaluate(el => {
      let instance = el.__vueParentComponent
      while (instance && !instance.props.post) instance = instance.parent
      return Math.min(instance.props.post.media.length, 3)
    })
    assert.equal(await portraitGroup.locator('.media-slot').count(), portraitCount)
    const portraitSizes = await portraitGroup.locator('.media-skeleton').evaluateAll(els => els.map(el => el.getBoundingClientRect().width / el.getBoundingClientRect().height))
    assert(portraitSizes.every(ratio => Math.abs(ratio - 3 / 4) < .015), 'portrait feed thumbnails must use 3:4 frames')
    await mixed.locator('.post-copy').scrollIntoViewIfNeeded()
    await pause(200)
    await screenshot(page, 'home-media-groups')
    const scroll = page.locator('.home-view > .screen-scroll > .uni-scroll-view > .uni-scroll-view')
    const before = await scroll.evaluate(el => el.scrollTop)
    await mixed.locator('.media-skeleton').first().click()
    await page.waitForSelector('.post-detail')
    await page.locator('.post-detail [aria-label="返回"]').click()
    await page.waitForSelector('.app-shell')
    await pause(250)
    assert(Math.abs(await scroll.evaluate(el => el.scrollTop) - before) < 2, 'detail return lost feed position')
    await go(page, 'mine-1-page-1')
    assert.equal(await page.locator('.detail-title').innerText(), '求助力')
    await page.locator('.post-detail').evaluate(el => {
      let instance = el.__vueParentComponent
      while (instance && !('statusBarHeight' in instance.setupState)) instance = instance.parent
      if (!instance) throw new Error('Detail inset state not found')
      instance.setupState.statusBarHeight = 44
      instance.setupState.bottomInset = 34
    })
    await pause(100)
    const safeBar = await page.locator('.post-action-bar').boundingBox()
    assert(Math.abs(safeBar.height - (390 * 112 / 750 + 34)) < 2, 'detail bottom safe inset')
    const status = await page.locator('.detail-status').boundingBox()
    assert.equal(status.height, 44)
    await screenshot(page, 'detail-safe-insets')
    await page.setViewportSize({ width: 844, height: 390 })
    await pause(250)
    await checkLayout(page, 844)
    await page.setViewportSize({ width: 390, height: 858 })
    await page.goto('about:blank')
    await page.goto(url + '/#/pages/post/index?id=missing')
    await page.waitForSelector('.missing-post')
    await page.locator('.missing-post uni-button').last().click()
    await page.waitForSelector('.app-shell')
    await context.close()
    assert.deepEqual(errors, [], 'runtime errors')
    assert.deepEqual(failures, [], 'failed icon resources')
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ results, feedGrouping: 'passed', routeReturn: 'passed', errors, failures }, null, 2))
    console.log(JSON.stringify({ viewports: results.length, detailStates: results.length * 4, feedGrouping: 'passed', routeReturn: 'passed', runtimeErrors: errors.length }, null, 2))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

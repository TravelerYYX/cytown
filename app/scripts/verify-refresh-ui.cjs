const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const output = path.resolve(__dirname, '../artifacts/refresh-ui')
const origin = process.env.UI_TEST_URL || 'http://127.0.0.1:15273/'
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    let hold = false, release, refreshRequests = 0
    await page.route('**/app/**', async route => {
      const endpoint = new URL(route.request().url()).pathname
      let data = []
      if (endpoint === '/app/home/config' || endpoint === '/app/user/followers' || endpoint === '/app/user/following') {
        if (hold) { refreshRequests++; await new Promise(resolve => { release = resolve }) }
      }
      if (endpoint === '/app/home/config') data = { channels: [{ id: 1, name: '发现' }, { id: 2, name: '官方' }], entries: [], news: { enabled: false, items: [] }, defaultFeedLayout: 'list' }
      if (endpoint === '/app/post/list') data = { records: [{ id: 'refresh-post', authorId: '20', authorNickname: '测试作者', title: '下拉刷新样式', content: '刷新时保留帖子', media: [], tags: [], likeCount: 0, commentCount: 0 }], current: 1, pages: 1, total: 1 }
      if (endpoint === '/app/user/followers' || endpoint === '/app/user/following') data = { records: [{ userId: '20', nickname: '测试用户', following: true, followedBy: true }], current: 1, pages: 1, total: 1 }
      await route.fulfill({ json: { code: '0000', msg: 'ok', data } })
    })
    await page.goto(origin)
    await page.locator('.home-view .post-card').waitFor()
    await page.evaluate(() => {
      uni.setStorageSync('app_token', 'refresh-ui-fixture')
      uni.setStorageSync('app_user_info', { userId: '10', nickname: '测试用户' })
    })
    await page.reload()
    await page.locator('.home-view .post-card').waitFor()
    for (const [label, screen] of [['关注', '.follow-view'], ['消息', '.message-view'], ['我的', '.mine-view']]) {
      await page.locator(`.tab-item[aria-label="${label}"]`).click()
      await page.locator(screen).waitFor({ state: 'visible' })
      assert.equal(await page.locator(screen + ' .refresh-indicator').count(), 0, label + ': disabled refresh mounted an animation')
      assert.equal(await page.locator(screen + ' .uni-scroll-view-refresher').count(), 0, label + ': disabled refresh occupied space')
    }
    await page.locator('.tab-item[aria-label="首页"]').click()
    const cdp = await page.context().newCDPSession(page)
    async function drag(distance) {
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 195, y: 400 }] })
      for (let step = 1; step <= 12; step++) {
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 195, y: 400 + distance * step / 12 }] })
        await pause(16)
      }
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    }
    async function closed(selector) {
      await page.waitForFunction(selector => document.querySelector(selector).getBoundingClientRect().height < 1, selector)
    }
    const indicator = '.home-view .feed-card > .refresh-indicator'
    // A short pull cancels without starting a request or leaving a gap.
    await drag(45)
    await closed(indicator)
    assert.equal(refreshRequests, 0)
    const geometry = await page.locator('.home-view .feed-card .channel-tabs').boundingBox()
    hold = true
    await drag(200)
    await page.waitForFunction(selector => document.querySelector(selector).getBoundingClientRect().height > 100, indicator)
    for (let attempt = 0; !release && attempt < 100; attempt++) await pause(20)
    assert(release, 'The pull gesture did not start a refresh')
    await pause(350)
    const tabs = await page.locator('.home-view .feed-card .channel-tabs').boundingBox()
    const area = await page.locator(indicator).boundingBox()
    const image = await page.locator(indicator + ' .refresh-character').boundingBox()
    assert(Math.abs(tabs.y - geometry.y) < 1, 'Refresh displaced the homepage header')
    assert(Math.abs(area.y - tabs.y - tabs.height) < 1, 'Refresh must appear directly below channels')
    assert(Math.abs(area.height - 109) < 2)
    assert(Math.abs(image.x + image.width / 2 - 195) < 1, 'Animation is not horizontally centered')
    assert.equal(await page.locator('.home-view .uni-scroll-view-refresh').count(), 0, 'Default spinner still rendered')
    assert.equal(await page.locator(indicator).evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(247, 247, 247)')
    const asset = await page.request.get(new URL('/static/images/mihoyo_refresh_loading.gif', origin).href)
    assert.equal(asset.status(), 200)
    assert.equal((await asset.body()).subarray(0, 6).toString(), 'GIF89a')
    await page.screenshot({ path: path.join(output, 'home-refresh.png') })
    hold = false; release(); release = undefined
    await closed(indicator)
    await page.locator('.home-view .post-card').waitFor()
    await page.evaluate(() => {
      uni.setStorageSync('app_token', 'refresh-ui-fixture')
      uni.setStorageSync('app_user_info', { userId: '10', nickname: '测试用户' })
      uni.navigateTo({ url: '/pages/relationships/index?type=followers' })
    })
    await page.locator('.relationships-page .relationship-row').waitFor()
    for (const kind of ['followers', 'following']) {
      if (kind === 'following') {
        await page.locator('.relationship-tabs uni-button').nth(1).click()
        await page.locator('.relationship-row').waitFor()
      }
      hold = true
      await drag(200)
      for (let attempt = 0; !release && attempt < 100; attempt++) await pause(20)
      assert(release, kind + ': refresh did not start')
      const nativeArea = '.relationships-page .uni-scroll-view-refresher'
      await page.waitForFunction(selector => document.querySelector(selector).getBoundingClientRect().height > 100, nativeArea)
      const loader = page.locator(nativeArea + ' .refresh-character')
      assert.equal(await loader.count(), 1)
      assert.equal(await page.locator('.relationships-page .uni-scroll-view-refresh').count(), 0)
      await pause(350)
      await page.screenshot({ path: path.join(output, kind + '-refresh.png') })
      hold = false; release(); release = undefined
      await closed(nativeArea)
      await page.locator('.relationship-row').waitFor()
    }
    assert.equal(refreshRequests, 3)
    assert.deepEqual(errors, [])
    console.log('Refresh UI passed: real pull gestures, cancel, inline homepage position, centered GIF, both relationship tabs and collapse after completion.')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

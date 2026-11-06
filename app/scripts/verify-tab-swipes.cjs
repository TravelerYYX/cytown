const { chromium } = require('playwright-core')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const output = path.resolve(__dirname, '../artifacts/tab-swipes')
const origin = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const config = {
  rules: ['image', 'video', 'post'].map(mode => ({ mode, titleMin: 0, titleMax: 30, contentMin: 0, contentMax: 2000, imageMax: 9, videoMax: 1, totalMediaMax: 10, maxVideoMb: 300 })),
  channels: [{ id: 1, name: '交流区', isDefault: true }], defaultChannelId: 1,
  creatorPolicies: { allowDownload: { defaultValue: true, userEditable: true }, downloadWatermark: { defaultValue: false, userEditable: true }, original: { defaultValue: false, userEditable: true } },
  reviewRequired: true, featureFlags: {}, pollRule: { minOptions: 2, maxOptions: 10 }, topics: [], emojiGroups: [], emojis: [], colors: [], dividers: []
}
async function gesture(page, locator, dx, dy = 0, cancel = false) {
  const box = await locator.boundingBox()
  assert(box && box.height > 0, 'Gesture target must be laid out')
  const isPublish = await locator.evaluate(el => !!el.closest('.publish-page'))
  const x = box.x + box.width * (dx < 0 ? .8 : .2), y = isPublish ? Math.max(30, box.y) + Math.min(20, box.height / 2) : Math.max(150, box.y + 20)
  assert(y < 800, 'Gesture target must be visible')
  const session = await page.context().newCDPSession(page)
  try {
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] })
    for (let i = 1; i <= 6; i++) {
      await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx * i / 6, y: y + dy * i / 6 }] })
      await pause(15)
    }
    await session.send('Input.dispatchTouchEvent', { type: cancel ? 'touchCancel' : 'touchEnd', touchPoints: [] })
  } finally { await session.detach() }
  await pause(280)
}
async function selected(tabs, name) { await tabs.locator('.channel-tab.active').filter({ hasText: name }).waitFor({ state: 'attached' }) }
async function scrollPane(page, selector) {
  await page.locator(selector + ' > .screen-scroll > .uni-scroll-view > .uni-scroll-view').evaluate(el => { el.scrollTop = 320 })
  await pause(200)
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const results = []
  try {
    for (const width of [320, 390, 430]) {
      const context = await browser.newContext({ viewport: { width, height: 858 }, isMobile: true, hasTouch: true })
      const page = await context.newPage(), errors = []
      page.on('pageerror', error => errors.push(error.message))
      await page.route('**/*', async route => {
        if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
        const url = new URL(route.request().url())
        let data = []
        if (url.pathname === '/app/home/config') data = { channels: [1, 2, 3].map(id => ({ id, name: '板块' + id })), entries: [], news: { enabled: false, items: [] } }
        else if (url.pathname === '/app/publish/config') data = config
        else if (url.pathname.includes('/post/')) data = { records: [], current: 1, pages: 0 }
        else if (url.pathname === '/app/user/statistics') data = { followerCount: 0, followingCount: 0, receivedLikeCount: 0 }
        return route.fulfill({ json: { code: '0000', data } })
      })
      try {
        await page.goto(origin)
        const home = page.locator('.home-view'), homeTabs = home.locator('.feed-card .channel-tabs'), homeBody = home.locator('.feed-body')
        await selected(homeTabs, '板块1')
        await scrollPane(page, '.home-view')
        await gesture(page, homeBody, -140); await selected(homeTabs, '板块2')
        await gesture(page, homeBody, -140); await selected(homeTabs, '板块3')
        await gesture(page, homeBody, -140); await selected(homeTabs, '板块3')
        await gesture(page, homeBody, 140); await selected(homeTabs, '板块2')
        await gesture(page, homeBody, -12); await selected(homeTabs, '板块2')
        await gesture(page, homeBody, -140, 0, true); await selected(homeTabs, '板块2')
        await gesture(page, homeBody, 2, -100); await selected(homeTabs, '板块2')
        await home.locator('.home-pinned-tabs .channel-tab').first().click(); await selected(homeTabs, '板块1')
        await page.locator('.tab-item[aria-label="我的"]').click()
        for (const member of [false, true]) {
          if (member) {
            await page.evaluate(() => { uni.setStorageSync('app_token', 'swipe-fixture'); uni.setStorageSync('app_user_info', { userId: '10', nickname: '滑动验证', tags: [] }) })
            await page.reload(); await page.locator('.tab-item[aria-label="我的"]').click()
          }
          const selector = member ? '.mine-view' : '.guest-mine-view', pane = page.locator(selector)
          const tabs = pane.locator(member ? '.profile-panel > .channel-tabs' : '.guest-panel > .channel-tabs')
          const body = pane.locator(member ? '.mine-channel-content' : '.guest-empty')
          await selected(tabs, '发布'); await scrollPane(page, selector)
          await gesture(page, body, 140); await selected(tabs, '发布')
          for (const name of ['评论', '合集', '收藏']) { await gesture(page, body, -140); await selected(tabs, name) }
          await gesture(page, body, -140); await selected(tabs, '收藏')
          await gesture(page, body, 140); await selected(tabs, '合集')
          await gesture(page, body, 2, -100); await selected(tabs, '合集')
          await page.screenshot({ path: path.join(output, width + (member ? '-member.png' : '-guest.png')) })
        }
        await page.goto(origin + '/#/pages/publish/index'); await page.locator('.options').waitFor()
        const editor = page.locator('#publish-reference-body'), mode = page.locator('.mode-button.active')
        assert.equal((await mode.textContent()).trim(), '图文')
        await gesture(page, editor, 140); assert.equal((await mode.textContent()).trim(), '图文')
        await gesture(page, editor, -140); assert.equal((await mode.textContent()).trim(), '视频')
        await gesture(page, editor, -140); assert.equal((await mode.textContent()).trim(), '帖子')
        await gesture(page, page.locator('.options'), -140); assert.equal((await mode.textContent()).trim(), '帖子')
        await page.locator('.title input').fill('切换后保留标题')
        await page.locator('.post-text textarea').fill('切换后保留帖子正文')
        await gesture(page, page.locator('.post-text'), -140); assert.equal((await mode.textContent()).trim(), '帖子')
        // Start in the options region so text-selection gestures stay inside the editor.
        await gesture(page, page.locator('.options'), 140); assert.equal((await mode.textContent()).trim(), '视频')
        await page.locator('.mode-button').filter({ hasText: '帖子' }).click()
        assert.equal(await page.locator('.title input').inputValue(), '切换后保留标题')
        assert.equal(await page.locator('.post-text textarea').inputValue(), '切换后保留帖子正文')
        assert.deepEqual(errors, [])
        results.push({ width, home: true, guest: true, member: true, publish: true, bounds: true, verticalScroll: true, canceledGesture: true, inputsPreserved: true })
      } catch (error) { await page.screenshot({ path: path.join(output, width + '-failure.png') }); throw error }
      finally { await context.close() }
    }
    fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(results, null, 2))
    console.log(JSON.stringify(results))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

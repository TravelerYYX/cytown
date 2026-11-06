const { chromium } = require('playwright-core')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { fixtures } = require('./verify-waterfall.cjs')

const output = path.resolve(__dirname, '../artifacts/settings-ui')
const url = process.env.UI_TEST_URL || 'http://127.0.0.1:15273/'
const browserPath = process.env.UI_BROWSER_PATH
const errors = []
const resourceFailures = []
fs.mkdirSync(output, { recursive: true })

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const toast = (page, text) => page.waitForFunction(
  value => [...document.querySelectorAll('[class*="toast"]')].some(element => (element.textContent || '').includes(value)),
  text,
  { timeout: 5000 }
)

async function openSettings(page) {
  await page.goto(url)
  await page.waitForSelector('.home-view .post-card')
  await page.locator('.tab-bar .tab-item').nth(4).click()
  await sleep(160)
  await page.locator(':is(.mine-view, .guest-mine-view) [aria-label="设置"]').click()
  await page.waitForSelector('.settings-page')
  await sleep(220)
}

async function openSheet(page, rowText) {
  await page.locator('.settings-row', { hasText: rowText }).click()
  await page.waitForSelector('.sheet-layer.open')
  await sleep(300)
}

async function closeSheetByChoice(page, choice) {
  await page.locator('.sheet-option', { hasText: choice }).click()
  assert.equal(await page.locator('.sheet-layer').count(), 1, 'sheet remains mounted during close animation')
  await page.waitForSelector('.sheet-layer', { state: 'detached', timeout: 1500 })
}

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: browserPath })
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 })
    const page = await context.newPage()
    await fixtures(page)
    page.on('pageerror', error => errors.push(error.message))
    page.on('response', response => {
      if (response.url().includes('/static/') && response.status() >= 400) resourceFailures.push(response.url())
    })

    await openSettings(page)

    const rows = await page.locator('.settings-page .settings-row .row-title').allInnerTexts()
    assert.deepEqual(rows, [
      '通行证账号与安全', '系统推送及提醒设置', '隐私设置', '屏蔽设置',
      '护眼模式', '首页帖子布局', '列表视频自动播放', '动图自动播放', '首页频道设置',
      '关于次元町', '清除缓存', '建议与反馈', '检查更新',
      '用户协议', '隐私政策', '个人信息收集清单', '第三方共享个人信息清单'
    ])
    assert.equal(await page.locator('.settings-card').count(), 6, 'six reference card groups')
    assert.equal(await page.locator('.settings-page uni-switch').count(), 0, 'eye care uses bottom sheet instead of native switch')
    assert.equal(await page.locator('.account-actions .settings-row').nth(0).innerText(), '切换账号')
    assert.equal(await page.locator('.account-actions .settings-row').nth(1).innerText(), '退出登录')
    assert.equal(await page.locator('.update-dot').count(), 1)
    const firstCard = await page.locator('.settings-card').first().boundingBox()
    const firstTitle = await page.locator('.settings-card').first().locator('.row-title').boundingBox()
    const firstArrow = await page.locator('.settings-card').first().locator('.row-arrow').boundingBox()
    assert(firstTitle.x - firstCard.x >= 14, 'row text needs visible left padding')
    assert(firstCard.x + firstCard.width - firstArrow.x - firstArrow.width >= 14, 'arrow needs visible right padding')
    const titleFontSize = await page.locator('.settings-card').first().locator('.row-title').evaluate(node => parseFloat(getComputedStyle(node).fontSize))
    assert(titleFontSize <= 14, 'settings text should use the compact reference size')
    const arrowBoxes = await page.locator('.row-arrow').evaluateAll(nodes => nodes.map(node => {
      const rect = node.getBoundingClientRect()
      return { width: rect.width, height: rect.height }
    }))
    assert(arrowBoxes.length >= 16)
    assert(arrowBoxes.every(box => Math.abs(box.width - box.height) < 0.5), 'all arrows use 1:1 boxes')
    await page.screenshot({ path: path.join(output, 'settings.png') })

    await openSheet(page, '护眼模式')
    assert.equal(await page.locator('.sheet-heading').innerText(), '护眼模式')
    assert.deepEqual(await page.locator('.sheet-option').allInnerTexts(), ['开启', '关闭'])
    await page.screenshot({ path: path.join(output, 'eye-care-sheet.png') })
    await closeSheetByChoice(page, '开启')
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_eye_care')), true)
    assert.equal(await page.locator('.eye-care-overlay').count(), 1)
    assert.equal(await page.locator('.eye-care-overlay').evaluate(node => getComputedStyle(node).pointerEvents), 'none')

    // Use real touch input: a DOM click or computed style cannot detect a blocking overlay.
    await page.locator('.settings-row', { hasText: '护眼模式' }).tap()
    await page.waitForSelector('.sheet-layer.open')
    await sleep(300)
    await page.locator('.sheet-option', { hasText: '关闭' }).tap()
    await page.waitForSelector('.sheet-layer', { state: 'detached' })
    assert.equal(await page.locator('.eye-care-overlay').count(), 0)
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_eye_care')), false)
    await openSheet(page, '护眼模式')
    await closeSheetByChoice(page, '开启')

    await openSheet(page, '列表视频自动播放')
    assert.deepEqual(await page.locator('.sheet-option').allInnerTexts(), ['移动网络和Wi-Fi', '仅Wi-Fi', '关闭'])
    await closeSheetByChoice(page, '移动网络和Wi-Fi')
    await openSheet(page, '动图自动播放')
    assert.deepEqual(await page.locator('.sheet-option').allInnerTexts(), ['智能模式', '始终播放', '关闭'])
    await closeSheetByChoice(page, '始终播放')
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_video_autoplay')), 'all-networks')
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_gif_autoplay')), 'always')

    await page.reload()
    await page.waitForSelector('.settings-page')
    assert((await page.locator('.settings-row', { hasText: '护眼模式' }).innerText()).includes('开启'))
    assert((await page.locator('.settings-row', { hasText: '列表视频自动播放' }).innerText()).includes('移动网络和Wi-Fi'))
    assert((await page.locator('.settings-row', { hasText: '动图自动播放' }).innerText()).includes('始终播放'))
    assert.equal(await page.locator('.eye-care-overlay').count(), 1, 'eye care survives reload')

    await page.locator('.settings-header [aria-label="返回"]').click()
    await page.waitForSelector('.app-shell')
    assert.equal(await page.locator('.eye-care-overlay').count(), 1, 'eye care remains on another page')
    await page.locator('.tab-bar .tab-item').nth(4).click()
    await page.locator(':is(.mine-view, .guest-mine-view) [aria-label="设置"]').click()
    await page.waitForSelector('.settings-page')

    await page.evaluate(() => uni.setStorageSync('demo_cache_key', 'cache-bytes'))
    await page.locator('.settings-row', { hasText: '清除缓存' }).click()
    await page.waitForSelector('.dialog-layer.open')
    await sleep(230)
    assert.equal(await page.locator('.dialog-title').innerText(), '清除缓存')
    assert.equal(await page.locator('.dialog-message').innerText(), '确定要清除缓存数据吗？')
    const dialogBox = await page.locator('.confirm-dialog').boundingBox()
    assert(dialogBox.width / 390 <= .81 && dialogBox.width / 390 >= .76, 'dialog width should match the compact reference')
    assert(dialogBox.height < 145, 'dialog height should remain compact')
    await page.screenshot({ path: path.join(output, 'cache-modal.png') })
    await page.locator('.dialog-button.cancel').click()
    await page.waitForSelector('.dialog-layer', { state: 'detached' })
    assert.equal(await page.evaluate(() => uni.getStorageSync('demo_cache_key')), 'cache-bytes')

    await page.locator('.settings-row', { hasText: '清除缓存' }).click()
    await page.waitForSelector('.dialog-layer.open')
    await page.locator('.dialog-button.confirm').click()
    await page.waitForSelector('.dialog-layer', { state: 'detached' })
    await toast(page, '缓存已清除')
    assert.equal(await page.evaluate(() => uni.getStorageSync('demo_cache_key')), '')
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_eye_care')), true, 'clear cache preserves app settings')

    await page.evaluate(() => {
      uni.setStorageSync('app_token', 'test-token')
      uni.setStorageSync('app_refresh_token', 'test-refresh')
      uni.setStorageSync('app_user_info', { userId: 'u1', publicUid: '438599494', qqNumber: '123456', nickname: '幻海落梦', avatar: '', level: 1 })
    })
    await page.reload()
    await page.waitForSelector('.settings-page')
    const logout = page.locator('.account-actions .settings-row').nth(1)
    await logout.scrollIntoViewIfNeeded()
    await logout.click()
    await page.waitForSelector('.dialog-layer.open')
    await sleep(230)
    assert.equal(await page.locator('.dialog-title').innerText(), '温馨提示')
    assert.equal(await page.locator('.dialog-message').innerText(), '您确定要退出登录吗？')
    await page.screenshot({ path: path.join(output, 'logout-modal.png') })
    await page.locator('.dialog-button.confirm').click()
    await page.waitForSelector('.app-shell', { timeout: 6000 })
    await sleep(450)
    assert.equal(await page.evaluate(() => uni.getStorageSync('app_token')), '')
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_eye_care')), true, 'logout preserves visual settings')

    for (const width of [320, 375, 390, 414, 430]) {
      await page.setViewportSize({ width, height: 858 })
      await openSettings(page)
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
      assert.equal(overflow, false, `${width}px must not overflow horizontally`)
      await page.screenshot({ path: path.join(output, `settings-${width}.png`) })
    }

    assert.equal(errors.length, 0, errors.join('\n'))
    assert.equal(resourceFailures.length, 0, resourceFailures.join('\n'))
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({
      settings: 'passed',
      bottomSheets: 'passed',
      eyeCare: 'passed',
      dialogs: 'passed',
      persistence: 'passed',
      responsiveWidths: [320, 375, 390, 414, 430],
      runtimeErrors: errors,
      resourceFailures
    }, null, 2))
    console.log(JSON.stringify({ settings: 'passed', bottomSheets: 'passed', eyeCare: 'passed', dialogs: 'passed', persistence: 'passed', runtimeErrors: 0 }, null, 2))
    await context.close()
  } finally {
    await browser.close()
  }
})().catch(error => { console.error(error); process.exitCode = 1 })

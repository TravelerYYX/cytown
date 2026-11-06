const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const output = path.resolve(__dirname, '../artifacts/mine-entries')
const appUrl = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
const adminUrl = process.env.ADMIN_UI_TEST_URL || 'http://127.0.0.1:9527'
const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="m12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3Z" fill="none" stroke="#222" stroke-width="1.5"/></svg>'
const iconUrl = '/mine-test-icon.svg'
const fixture = n => Array.from({ length: n }, (_, i) => ({ id: i + 1, title: ['创作中心', '次元町周边', '兑换中心', '奇匠中心'][i % 4], description: '后台配置的入口介绍', iconUrl, linkUrl: 'https://example.test/entry?i=' + i, openMode: i === 1 ? 'browser' : 'inapp', showTopbar: i !== 2, enabled: true, sortOrder: i }))
const envelope = data => ({ json: { code: '0000', msg: 'ok', data } })
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))

async function appChecks(browser) {
  const results = []
  for (const width of [320, 390, 430]) {
    const context = await browser.newContext({ viewport: { width, height: 858 }, isMobile: true, hasTouch: true })
    const page = await context.newPage(), errors = []
    page.on('pageerror', e => errors.push(e.message))
    let entries = [], failEntries = false, entryCalls = 0
    await page.route('**/*', async route => {
      const pathname = new URL(route.request().url()).pathname
      if (pathname.endsWith(iconUrl)) return route.fulfill({ contentType: 'image/svg+xml', body: svg })
      if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
      if (pathname === '/api/locate') return route.fulfill({ json: { code: 0, data: { country: '中国', province: '上海' } } })
      if (!pathname.startsWith('/app/')) return route.continue()
      if (pathname === '/app/mine/entries') { entryCalls++; return failEntries ? route.fulfill({ status: 503, body: '{}' }) : route.fulfill(envelope(entries)) }
      if (pathname === '/app/home/config') return route.fulfill(envelope({ channels: [{ id: 1, name: '推荐' }], entries: [], news: { enabled: false, items: [], interval: 3, emptyText: '' } }))
      if (pathname === '/app/user/statistics') return route.fulfill(envelope({ followerCount: 0, followingCount: 0, receivedLikeCount: 0 }))
      if (pathname === '/app/media/config') return route.fulfill(envelope({ cacheMaxBytes: 16777216 }))
      if (pathname.includes('/post/')) return route.fulfill(envelope({ records: [], pages: 0, current: 1, total: 0, size: 10 }))
      return route.fulfill(envelope([]))
    })
    await page.goto(appUrl)
    await page.locator('.tab-item[aria-label="我的"]').click()
    await page.locator('.guest-mine-view').waitFor({ state: 'visible' })
    for (const loggedIn of [false, true]) {
      if (loggedIn) {
        await page.evaluate(() => {
          uni.setStorageSync('app_token', 'ui-test-token')
          uni.setStorageSync('app_refresh_token', 'ui-test-refresh')
          uni.setStorageSync('app_user_info', { userId: '10', publicUid: '100', nickname: '入口测试', avatar: '', level: 1, signature: '', tags: [] })
        })
        await page.reload()
        await page.locator('.tab-item[aria-label="我的"]').click()
      }
      for (const count of [0, 1, 7]) {
        entries = fixture(count)
        const before = entryCalls
        await page.locator('.tab-item[aria-label="首页"]').click()
        await page.locator('.tab-item[aria-label="我的"]').click()
        await page.waitForFunction(() => !!document.querySelector('.app-shell'))
        for (let i = 0; entryCalls === before && i < 20; i++) await pause(50)
        await pause(150)
        const pane = page.locator(loggedIn ? '.mine-view' : '.guest-mine-view')
        assert.equal(await pane.locator('.mine-quick-entry').count(), count)
        const panel = pane.locator(loggedIn ? '.profile-panel' : '.guest-panel')
        assert(await panel.evaluate(el => {
          const box = el.getBoundingClientRect(), style = getComputedStyle(el)
          const corner = document.elementFromPoint(box.left + 1, box.top + 1)
          return parseFloat(style.borderTopLeftRadius) > 10 && style.overflow === 'hidden' && !el.contains(corner)
        }), 'panel children must not cover the rounded top corner, including when entries are empty')
        if (!count) {
          assert.equal(await pane.locator('.mine-quick-zone').count(), 0)
          if (!loggedIn) assert.equal(await pane.locator('.guest-panel').evaluate(el => getComputedStyle(el).paddingTop), '0px')
          else {
            const gap = await pane.evaluate(el => el.querySelector('.profile-panel').firstElementChild.className)
            assert(gap.includes('channel-tabs'), 'channel tabs must immediately follow profile when entries are empty')
          }
        } else {
          assert.equal(await pane.locator('.mine-quick-sub').first().innerText(), '后台配置的入口介绍')
          await pane.locator('.mine-quick-icon img').first().evaluate(el => el.decode())
          assert.equal(await pane.locator('.mine-quick-icon img').first().evaluate(el => el.naturalWidth > 0), true)
          assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width)
          if (count === 7) {
            await pane.locator('.mine-quick-scroll .uni-scroll-view').evaluateAll(elements => elements.forEach(el => { el.scrollLeft = 9999 }))
            await pause(100)
            const last = await pane.locator('.mine-quick-entry').last().boundingBox()
            assert(last.x >= 0 && last.x + last.width <= width + 1, 'last entry must be reachable by horizontal scroll')
          }
        }
        await pane.screenshot({ path: path.join(output, `${width}-${loggedIn ? 'member' : 'guest'}-${count}.png`) })
        if (loggedIn) {
          // Scroll across the point where the original tabs meet the compact header.
          const tabs = pane.locator('.profile-panel > .channel-tabs')
          const tabTop = (await tabs.boundingBox()).y
          const threshold = tabTop - 96 * width / 750
          const scroller = pane.locator('.screen-scroll > .uni-scroll-view > .uni-scroll-view')
          await scroller.evaluate((el, y) => { el.scrollTop = y }, threshold - 48 * width / 750)
          await pause(160)
          const midOpacity = await pane.locator('.compact-header').evaluate(el => parseFloat(getComputedStyle(el).opacity))
          assert(midOpacity > .1 && midOpacity < .9, 'compact header must fade progressively during scroll')
          await scroller.evaluate((el, y) => { el.scrollTop = y }, threshold - 4)
          await pause(100)
          assert.equal(await pane.locator('.mine-compact.is-collapsed').count(), 0, 'sticky tabs appeared too early')
          await scroller.evaluate((el, y) => { el.scrollTop = y }, threshold + 4)
          await pause(220)
          assert.equal(await pane.locator('.mine-compact.is-collapsed').count(), 1, 'sticky header must follow the actual tabs position')
          const compactPanel = pane.locator('.compact-channels-panel')
          assert(await compactPanel.evaluate(el => {
            const box = el.getBoundingClientRect()
            return !el.contains(document.elementFromPoint(box.left + 1, box.top + 1))
          }), 'pinned tabs must also retain the rounded top corner')
          if (width === 390) await pane.screenshot({ path: path.join(output, `390-member-${count}-pinned.png`) })
          await scroller.evaluate(el => { el.scrollTop = 0 })
          await pause(220)
          assert.equal(await pane.locator('.mine-compact').evaluate(el => getComputedStyle(el).visibility), 'hidden')
        }
        if (count === 0) {
          const channelButtons = pane.locator(loggedIn ? '.profile-panel > .channel-tabs .channel-tab' : '.guest-panel > .channel-tabs .channel-tab')
          const content = pane.locator(loggedIn ? '.mine-channel-content' : '.guest-empty')
          await channelButtons.nth(1).click()
          await content.locator('xpath=self::*[contains(@class,"from-right")]').waitFor()
          assert((await content.evaluate(el => getComputedStyle(el).animationName)).includes('from-right'))
          await channelButtons.nth(0).click()
          await content.locator('xpath=self::*[contains(@class,"from-left")]').waitFor()
          assert((await content.evaluate(el => getComputedStyle(el).animationName)).includes('from-left'))
          await pause(250)
        }
        results.push({ width, loggedIn, count })
      }
      // Tab activation refreshes the shared list; failed refresh preserves successful content.
      failEntries = true
      await page.locator('.tab-item[aria-label="首页"]').click()
      await page.locator('.tab-item[aria-label="我的"]').click()
      await pause(250)
      assert.equal(await page.locator(loggedIn ? '.mine-view .mine-quick-entry' : '.guest-mine-view .mine-quick-entry').count(), 7)
      failEntries = false
    }
    // Verify actual navigation arguments without opening third-party pages.
    const pane = page.locator('.mine-view')
    await pane.locator('.mine-quick-scroll .uni-scroll-view').evaluateAll(elements => elements.forEach(el => { el.scrollLeft = 0 }))
    await page.evaluate(() => { window.__links = []; uni.navigateTo = options => window.__links.push(options.url); window.open = (...args) => window.__links.push(args) })
    await pane.locator('.mine-quick-entry').nth(0).click()
    await pane.locator('.mine-quick-entry').nth(1).click()
    const links = await page.evaluate(() => window.__links)
    assert(links[0].includes('/pages/webview/index?url=https%3A%2F%2Fexample.test') && links[0].endsWith('topbar=1'))
    assert.equal(links[1][0], entries[1].linkUrl)
    assert.equal(links[1][1], '_blank')
    await pane.locator('.mine-quick-entry').nth(2).click()
    assert((await page.evaluate(() => window.__links[2])).endsWith('topbar=0'))
    // A fresh failed configuration request leaves posts usable and no empty entry strip.
    failEntries = true
    await page.reload()
    await page.locator('.tab-item[aria-label="我的"]').click()
    await pause(250)
    assert.equal(await page.locator('.mine-view .mine-quick-zone').count(), 0)
    assert(await page.locator('.mine-view .empty-copy').isVisible())
    assert.deepEqual(errors, [])
    await context.close()
  }
  return results
}

async function adminChecks(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }), errors = []
  page.on('pageerror', e => errors.push(e.message))
  await page.addInitScript(() => localStorage.setItem('SOY_token', JSON.stringify('ui-test-token')))
  let entries = [], id = 0, codeCalls = 0, uploadCalls = 0, failSave = false
  await page.route('**/*', async route => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname.endsWith(iconUrl)) return route.fulfill({ contentType: 'image/svg+xml', body: svg })
    if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
    let data = []
    if (pathname.endsWith('/auth/getUserInfo')) data = { userId: '1', userName: '测试管理员', nickName: '测试管理员', roles: ['R_SUPER'], buttons: [], userType: 0 }
    else if (pathname.endsWith('/mine/entry/list')) data = entries
    else if (pathname.endsWith('/mine/entry/icon/code')) { codeCalls++; data = { url: iconUrl } }
    else if (pathname.endsWith('/mine/entry/icon/upload')) { uploadCalls++; data = { url: iconUrl } }
    else if (pathname.endsWith('/mine/entry/create') || pathname.endsWith('/mine/entry/update')) {
      if (failSave) return route.fulfill({ json: { code: '1000', msg: '模拟保存失败', data: null } })
      data = route.request().postDataJSON()
      if (!data.id) { data.id = ++id; entries.push(data) } else entries = entries.map(row => row.id === data.id ? data : row)
    } else if (pathname.endsWith('/mine/entry/delete')) { entries = entries.filter(row => row.id !== route.request().postDataJSON().id); data = null }
    else if (pathname.endsWith('/mine/entry/sort')) { const ids = route.request().postDataJSON().ids; entries.sort((a, b) => ids.indexOf(a.id) - ids.indexOf(b.id)); data = null }
    else if (pathname.endsWith('/home/layout/get')) data = { defaultFeedLayout: 'single' }
    else if (pathname.endsWith('/home/news/get')) data = { enabled: true, interval: 3, emptyText: '暂无资讯，敬请期待' }
    else if (pathname.endsWith('/publish/health')) data = null
    else if (!pathname.includes('/home/')) return route.continue()
    return route.fulfill(envelope(data))
  })
  await page.goto(adminUrl + '/home-config')
  const card = page.locator('.n-card').filter({ has: page.getByText('我的页面入口管理', { exact: true }) })
  await card.getByRole('button', { name: '新增我的入口' }).click()
  let modal = page.locator('.n-modal')
  await modal.getByRole('button', { name: '保存', exact: true }).click()
  assert.equal(entries.length, 0)
  await modal.getByPlaceholder('请输入入口标题').fill('后台创作入口')
  await modal.getByPlaceholder('请输入入口介绍').fill('标题和介绍均来自后台')
  await modal.getByText('粘贴 SVG 代码', { exact: true }).click()
  await modal.locator('textarea').fill(svg)
  await modal.getByRole('button', { name: '应用 SVG 代码' }).click()
  await modal.locator('img').waitFor()
  await modal.getByPlaceholder('https:// 开头的完整链接').fill('https://example.test/')
  failSave = true
  await modal.getByRole('button', { name: '保存', exact: true }).click()
  await page.getByText('模拟保存失败', { exact: true }).waitFor()
  assert.equal(entries.length, 0)
  assert(await modal.isVisible())
  failSave = false
  await modal.getByRole('button', { name: '保存', exact: true }).click()
  await card.getByText('后台创作入口', { exact: true }).waitFor()
  await card.getByRole('button', { name: '编辑', exact: true }).click()
  await modal.getByPlaceholder('请输入入口介绍').fill('修改后的介绍')
  await modal.getByRole('button', { name: '保存', exact: true }).click()
  await card.getByText('修改后的介绍', { exact: true }).waitFor()
  await card.getByRole('button', { name: '新增我的入口' }).click()
  await modal.getByPlaceholder('请输入入口标题').fill('上传图标入口')
  await modal.getByPlaceholder('请输入入口介绍').fill('上传文件方式配置SVG')
  await modal.locator('input[type=file]').setInputFiles({ name: 'icon.svg', mimeType: 'image/svg+xml', buffer: Buffer.from(svg) })
  await modal.locator('img').waitFor()
  await modal.getByPlaceholder('https:// 开头的完整链接').fill('https://example.test/shop')
  await modal.getByText('外部浏览器打开', { exact: true }).click()
  await modal.getByRole('button', { name: '保存', exact: true }).click()
  await card.getByText('上传图标入口', { exact: true }).waitFor()
  await card.getByRole('switch').first().click()
  await card.getByText('已停用', { exact: true }).waitFor()
  await pause(300)
  await card.locator('img').first().evaluate(el => el.decode())
  await card.screenshot({ path: path.join(output, 'admin-mine-entries.png') })
  await card.getByRole('button', { name: '删除', exact: true }).last().click()
  await page.getByRole('button', { name: /^(确认|确定|OK)$/ }).click()
  await pause(200)
  assert.equal(entries.length, 1)
  assert.equal(codeCalls, 1); assert.equal(uploadCalls, 1)
  assert.deepEqual(errors, [])
  await page.close()
  return { codeCalls, uploadCalls, createEditToggleDelete: true, failedSaveRetainsForm: true }
}

;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const app = await appChecks(browser), admin = await adminChecks(browser)
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ app, admin }, null, 2))
    console.log('Mine entries: guest/member layouts at 3 widths, rounded panels, progressive sticky header, channel transitions, 0/1/7 entries, refresh, SVG rendering, link navigation, admin CRUD and both SVG inputs passed')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

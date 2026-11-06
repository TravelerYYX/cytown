const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const output = path.resolve(__dirname, '../artifacts/entry-targets')
const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M2 2h20v20H2z" fill="#22b8bb"/></svg>'
const icon = '/test-entry-target.svg'
const envelope = data => ({ json: { code: '0000', msg: 'ok', data } })
// Exercise the exact catalog shipped by the backend, including parameter templates.
const source = fs.readFileSync(path.resolve(__dirname, '../../ciyuantoen-back/ciyuantown-back/src/main/java/com/yyx/ciyuantown/business/homeconfig/EntryTarget.java'), 'utf8')
const pages = [...source.matchAll(/new Page\("([^"]*)", "([^"]*)", "([^"]*)"\)/g)].map(([, label, value, hint]) => ({ label, value, hint }))
const fixture = { id: 1, name: '测试入口', title: '测试入口', description: '跳转功能测试', iconUrl: icon, linkUrl: 'https://example.test/', openMode: 'inapp', showTopbar: true, enabled: true, sortOrder: 0 }

async function adminChecks(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }), errors = []
  page.on('pageerror', e => errors.push(e.message))
  await page.addInitScript(() => localStorage.setItem('SOY_token', JSON.stringify('ui-test-token')))
  const rows = { home: [{ ...fixture }], mine: [{ ...fixture }] }
  let saves = 0, failPages = false
  await page.route('**/*', async route => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname.endsWith(icon)) return route.fulfill({ contentType: 'image/svg+xml', body: svg })
    if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
    let data = []
    if (pathname.endsWith('/auth/getUserInfo')) data = { userId: '1', userName: '测试管理员', nickName: '测试管理员', roles: ['R_SUPER'], buttons: [], userType: 0 }
    else if (pathname.endsWith('/home/entry/pages')) {
      if (failPages) return route.fulfill({ json: { code: '1000', msg: '模拟页面加载失败', data: null } })
      data = pages
    } else if (pathname.endsWith('/home/entry/list')) data = rows.home
    else if (pathname.endsWith('/mine/entry/list')) data = rows.mine
    else if (/\/(home|mine)\/entry\/update$/.test(pathname)) {
      saves++; const group = pathname.includes('/home/') ? 'home' : 'mine'
      data = route.request().postDataJSON(); rows[group] = [data]
    } else if (pathname.endsWith('/home/layout/get')) data = { defaultFeedLayout: 'single' }
    else if (pathname.endsWith('/home/news/get')) data = { enabled: true, interval: 3, emptyText: '暂无资讯，敬请期待' }
    else if (pathname.endsWith('/publish/health')) data = null
    return route.fulfill(envelope(data))
  })
  await page.goto((process.env.ADMIN_UI_TEST_URL || 'http://127.0.0.1:9527') + '/home-config')
  for (const [group, title] of [['home', '快捷入口管理'], ['mine', '我的页面入口管理']]) {
    const card = page.locator('.n-card').filter({ has: page.getByText(title, { exact: true }) })
    await card.getByRole('button', { name: '编辑', exact: true }).click()
    const modal = page.locator('.n-modal')
    assert(await modal.getByPlaceholder('https:// 开头的完整链接').isVisible(), 'legacy entries default to web links')
    await modal.getByText('跳转 APP 页面', { exact: true }).click()
    assert.equal(await modal.getByText('外部浏览器打开', { exact: true }).count(), 0)
    await modal.locator('.n-base-selection').last().click()
    await modal.locator('.n-base-selection-input').last().fill('帖子详情')
    await page.getByText('帖子详情', { exact: true }).last().click()
    const address = modal.getByPlaceholder('选择页面后，可填写 ID 等页面参数')
    assert.equal(await address.inputValue(), '/pages/post/index?id=')
    const before = saves
    await modal.getByRole('button', { name: '保存', exact: true }).click()
    await page.getByText('请填写页面所需的有效 ID 参数', { exact: true }).last().waitFor()
    assert.equal(saves, before)
    await address.fill('/pages/post/index?id=123&anchor=comments')
    await page.waitForTimeout(350) // Let the select menu's closing transition finish before visual capture.
    await page.screenshot({ path: path.join(output, `admin-${group}-app.png`) })
    await modal.getByRole('button', { name: '保存', exact: true }).click()
    await modal.waitFor({ state: 'hidden' })
    assert.equal(rows[group][0].targetType, 'app')
    await card.getByText('APP 页面', { exact: true }).waitFor()
    await card.getByRole('button', { name: '编辑', exact: true }).click()
    assert.equal(await address.inputValue(), '/pages/post/index?id=123&anchor=comments')
    await modal.getByText('跳转链接', { exact: true }).click()
    await modal.getByPlaceholder('https:// 开头的完整链接').fill('https://example.test/return')
    await modal.getByText('外部浏览器打开', { exact: true }).click()
    await modal.getByRole('button', { name: '保存', exact: true }).click()
    await modal.waitFor({ state: 'hidden' })
    assert.equal(rows[group][0].targetType, 'link')
    assert.equal(rows[group][0].openMode, 'browser')
  }
  failPages = true
  await page.reload()
  await page.getByRole('button', { name: '新增我的入口', exact: true }).click()
  const modal = page.locator('.n-modal')
  await modal.getByText('跳转 APP 页面', { exact: true }).click()
  await modal.getByText('页面选项加载失败。', { exact: false }).waitFor()
  failPages = false
  await modal.getByRole('button', { name: '重试', exact: true }).click()
  await modal.locator('.n-base-selection').last().click()
  await page.getByText('设置', { exact: true }).last().click()
  assert.equal(await modal.getByPlaceholder('选择页面后，可填写 ID 等页面参数').inputValue(), '/pages/settings/index')
  assert.deepEqual(errors, [])
  await page.close()
  return { saves, bothGroups: true, appLinkSwitchAndEdit: true, missingIdBlocked: true, catalogRetry: true }
}

async function appChecks(browser) {
  const context = await browser.newContext({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
  const page = await context.newPage(), errors = []
  page.on('pageerror', e => errors.push(e.message))
  const homeEntries = [
    { ...fixture, targetType: 'app', linkUrl: '/pages/settings/index', name: '打开设置' },
    { ...fixture, id: 2, targetType: 'app', linkUrl: '/pages/index/index?tab=message', name: '打开消息' }
  ]
  const mineEntries = [
    { ...fixture, targetType: 'app', linkUrl: '/pages/index/index?tab=message', title: '跳转消息' },
    { ...fixture, id: 2, targetType: 'app', linkUrl: '/pages/post/index?id=123&anchor=comments', title: '打开帖子' },
    { ...fixture, id: 3, title: '原网页入口' }
  ]
  await page.route('**/*', async route => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname.endsWith(icon)) return route.fulfill({ contentType: 'image/svg+xml', body: svg })
    if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
    if (pathname === '/api/locate') return route.fulfill({ json: { code: 0, data: { country: '中国', province: '上海' } } })
    if (!pathname.startsWith('/app/')) return route.continue()
    let data = []
    if (pathname === '/app/home/config') data = { channels: [{ id: 1, name: '推荐' }], entries: homeEntries, news: { enabled: false, items: [], interval: 3, emptyText: '' } }
    else if (pathname === '/app/mine/entries') data = mineEntries
    else if (pathname === '/app/media/config') data = { cacheMaxBytes: 16777216 }
    else if (pathname.includes('/post/')) data = { records: [], pages: 0, current: 1, total: 0, size: 10 }
    return route.fulfill(envelope(data))
  })
  const base = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
  await page.goto(base)
  await page.locator('.home-header .entry[aria-label="打开设置"]').click()
  await page.waitForURL(/pages\/settings\/index/)
  await page.screenshot({ path: path.join(output, 'app-settings.png') })
  await page.evaluate(() => uni.navigateBack())
  await page.locator('.home-header .entry[aria-label="打开消息"]').click()
  await page.locator('.message-view').waitFor({ state: 'visible' })
  assert(await page.locator('.tab-item[aria-label="消息"]').evaluate(el => el.classList.contains('selected')))
  await page.locator('.tab-item[aria-label="我的"]').click()
  await page.locator('.mine-quick-entry[aria-label="跳转消息"]').click()
  await page.locator('.message-view').waitFor({ state: 'visible' })
  // Selecting the same destination again still works after manually returning.
  await page.locator('.tab-item[aria-label="我的"]').click()
  await page.locator('.mine-quick-entry[aria-label="跳转消息"]').click()
  await page.locator('.message-view').waitFor({ state: 'visible' })
  await page.locator('.tab-item[aria-label="我的"]').click()
  // Capture exact route parameters without introducing unrelated post fixtures.
  await page.evaluate(() => { window.__entryLinks = []; uni.navigateTo = options => { window.__entryLinks.push(options.url); options.complete?.() } })
  await page.locator('.mine-quick-entry[aria-label="打开帖子"]').click()
  await page.locator('.mine-quick-scroll .uni-scroll-view').evaluateAll(elements => elements.forEach(el => { el.scrollLeft = 9999 }))
  await page.locator('.mine-quick-entry[aria-label="原网页入口"]').click()
  const links = await page.evaluate(() => window.__entryLinks)
  assert.equal(links[0], '/pages/post/index?id=123&anchor=comments')
  assert(links[1].startsWith('/pages/webview/index?url=https%3A%2F%2Fexample.test'))
  assert(links[1].endsWith('topbar=1'))
  await page.evaluate(() => uni.reLaunch({ url: '/pages/index/index?tab=message' }))
  await page.locator('.message-view').waitFor({ state: 'visible' })
  assert(await page.locator('.tab-item[aria-label="消息"]').evaluate(el => el.classList.contains('selected')))
  assert.deepEqual(errors, [])
  await context.close()
  return { homeSettings: true, bothGroupsSwitchMainTab: true, repeatedSwitch: true, mainPageQuery: true, parametersPreserved: true, legacyWebLink: true }
}

;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const admin = await adminChecks(browser), app = await appChecks(browser)
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ admin, app }, null, 2))
    console.log('Entry targets: admin edit/save/type switching, required ID, catalog retry, actual app settings/tab navigation and legacy web links passed')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

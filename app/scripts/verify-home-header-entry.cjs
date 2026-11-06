const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const output = path.resolve(__dirname, '../artifacts/home-header-entry')
const envelope = data => ({ json: { code: '0000', msg: 'ok', data } })
const icon = '/test-header-entry.svg'
const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M4 3h16v18H4z" fill="white"/></svg>'
const fixture = { name: '顶部测试入口', iconUrl: icon, targetType: 'link', linkUrl: 'https://example.test/header?a=1', openMode: 'inapp', showTopbar: false, enabled: true }
async function adminChecks(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = []; page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('SOY_token', JSON.stringify('ui-test-token')))
  let stored = { ...fixture }, saves = 0, failLoad = false, failSave = false, uploads = 0
  await page.route('**/*', async route => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname.endsWith(icon)) return route.fulfill({ contentType: 'image/svg+xml', body: svg })
    if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
    let data = []
    if (pathname.endsWith('/auth/getUserInfo')) data = { userId: '1', userName: '测试管理员', nickName: '测试管理员', roles: ['R_SUPER'], buttons: [], userType: 0 }
    else if (pathname.endsWith('/home/header-entry/get')) {
      if (failLoad) return route.fulfill({ json: { code: '1000', msg: '模拟读取失败', data: null } })
      data = stored
    } else if (pathname.endsWith('/home/header-entry/update')) {
      saves++
      if (failSave) return route.fulfill({ json: { code: '1000', msg: '模拟保存失败', data: null } })
      stored = route.request().postDataJSON(); data = stored
    } else if (pathname.endsWith('/home/entry/pages')) data = [{ label: '设置', value: '/pages/settings/index', hint: '' }]
    else if (pathname.endsWith('/image/upload')) { uploads++; data = { url: icon, originalBytes: 2048, processedBytes: 1024 } }
    else if (pathname.endsWith('/home/layout/get')) data = { defaultFeedLayout: 'single' }
    else if (pathname.endsWith('/home/news/get')) data = { enabled: false, interval: 3, emptyText: '暂无资讯' }
    else if (pathname.endsWith('/mine/entry/membership-target')) data = { target: '/pages/membership/index' }
    else if (pathname.endsWith('/publish/health')) data = null
    return route.fulfill(envelope(data))
  })
  const base = process.env.ADMIN_UI_TEST_URL || 'http://127.0.0.1:9527'
  await page.goto(base + '/home-config')
  const card = page.locator('.n-card').filter({ has: page.getByText('搜索左侧入口', { exact: true }) })
  const save = card.getByRole('button', { name: '保存搜索左侧入口', exact: true })
  await save.waitFor()
  await card.locator('input[type=file]').setInputFiles(path.resolve(__dirname, '../static/native-raster/icons/menu-white.png'))
  await page.getByText('图片上传成功', { exact: true }).waitFor()
  await card.getByPlaceholder('请输入入口名称').fill('  顶部新入口  ')
  await card.getByText('外部浏览器打开', { exact: true }).click(); await save.click()
  await page.getByText('搜索左侧入口已保存', { exact: true }).last().waitFor()
  assert.equal(stored.openMode, 'browser'); assert.equal(stored.name, '顶部新入口')
  await page.reload(); await save.waitFor()
  assert.equal(await card.getByPlaceholder('请输入入口名称').inputValue(), '顶部新入口')
  assert(await card.getByText('外部浏览器打开', { exact: true }).locator('..').evaluate(el => el.classList.contains('n-radio--checked')))
  await card.getByText('软件内打开', { exact: true }).click(); await save.click()
  await page.getByText('搜索左侧入口已保存', { exact: true }).last().waitFor()
  assert.equal(stored.openMode, 'inapp'); assert.equal(stored.showTopbar, false)
  await card.getByText('跳转 APP 页面', { exact: true }).click()
  await card.locator('.n-base-selection').last().click()
  await page.getByText('设置', { exact: true }).last().click()
  await save.click(); await page.getByText('搜索左侧入口已保存', { exact: true }).last().waitFor()
  assert.equal(stored.targetType, 'app'); assert.equal(stored.linkUrl, '/pages/settings/index')
  await card.screenshot({ path: path.join(output, 'admin-app-page.png') })
  const previousSaves = saves
  await card.getByPlaceholder('请输入入口名称').fill(' '); await save.click()
  await page.getByText('请完整填写名称、图标和跳转地址', { exact: true }).waitFor(); assert.equal(saves, previousSaves)
  await card.getByPlaceholder('请输入入口名称').fill('失败时不覆盖')
  failSave = true; await save.click(); await page.getByText('模拟保存失败', { exact: true }).waitFor()
  assert.equal(stored.name, '顶部新入口')
  failSave = false; failLoad = true; await page.reload()
  await card.getByText('入口配置加载失败。', { exact: false }).waitFor()
  assert.equal(await save.count(), 0)
  failLoad = false; await card.getByRole('button', { name: '重试', exact: true }).click(); await save.waitFor()
  await card.getByRole('switch').first().click(); await save.click()
  await page.getByText('搜索左侧入口已保存', { exact: true }).last().waitFor(); assert.equal(stored.enabled, false)
  assert.equal(uploads, 1); assert.deepEqual(errors, [])
  await page.close(); return { saves, uploads, allThreeModes: true, persisted: true, loadRetry: true, failedSavePreservesStored: true, disable: true }
}
async function appChecks(browser) {
  const context = await browser.newContext({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
  const page = await context.newPage(), errors = []
  page.on('pageerror', error => errors.push(error.message))
  let headerEntry = null
  await page.route('**/*', async route => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname.endsWith(icon)) return route.fulfill({ contentType: 'image/svg+xml', body: svg })
    if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
    if (pathname === '/api/locate') return route.fulfill({ json: { code: 0, data: { country: '中国', province: '上海' } } })
    if (!pathname.startsWith('/app/')) return route.continue()
    let data = []
    if (pathname === '/app/home/config') data = { channels: [{ id: 1, name: '推荐' }], entries: [], headerEntry, news: { enabled: false, items: [], interval: 3, emptyText: '' } }
    else if (pathname === '/app/media/config') data = { cacheMaxBytes: 16777216 }
    else if (pathname.includes('/post/')) data = { records: [], pages: 0, current: 1, total: 0, size: 10 }
    return route.fulfill(envelope(data))
  })
  const base = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
  const search = page.locator('.home-header .icon-button[aria-label="搜索"]')
  const entry = page.locator('.home-header .icon-button[aria-label="顶部测试入口"]')
  await page.goto(base); await search.waitFor()
  assert.equal(await page.locator('.home-header [aria-label="游戏列表"]').count(), 0)
  assert.equal(await entry.count(), 0)
  for (const [width, mode] of [[320, 'inapp'], [390, 'browser'], [521, 'app']]) {
    headerEntry = { ...fixture, targetType: mode === 'app' ? 'app' : 'link', openMode: mode === 'browser' ? 'browser' : 'inapp', linkUrl: mode === 'app' ? '/pages/settings/index' : fixture.linkUrl }
    await page.setViewportSize({ width, height: 858 }); await page.reload(); await entry.waitFor()
    assert.equal(await entry.innerText(), '', 'only the configured icon should be visible')
    const box = await entry.boundingBox(), searchBox = await search.boundingBox()
    assert(box.x + box.width <= searchBox.x + 1, 'entry must remain left of search')
    assert((await entry.locator('img').getAttribute('src')).endsWith(icon))
    await page.screenshot({ path: path.join(output, `home-${width}-${mode}.png`) })
    if (mode === 'app') {
      await entry.click(); await page.waitForURL(/pages\/settings\/index/)
    } else {
      await page.evaluate(() => {
        window.__headerLinks = []; window.__headerBrowser = []
        uni.navigateTo = options => { window.__headerLinks.push(options.url); options.complete?.() }
        window.open = (...args) => { window.__headerBrowser.push(args); return null }
      })
      await entry.click()
      const actual = await page.evaluate(() => ({ links: window.__headerLinks, browser: window.__headerBrowser }))
      if (mode === 'browser') assert.deepEqual(actual.browser[0], [fixture.linkUrl, '_blank', 'noopener,noreferrer'])
      else assert.equal(actual.links[0], '/pages/webview/index?url=' + encodeURIComponent(fixture.linkUrl) + '&title=' + encodeURIComponent(fixture.name) + '&topbar=0')
    }
  }
  assert.deepEqual(errors, [])
  await context.close(); return { allThreeModes: true, actualAppPage: true, iconOnly: true, missingConfigHidden: true, widths: [320, 390, 521] }
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const admin = await adminChecks(browser), app = await appChecks(browser)
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ admin, app }, null, 2))
    console.log('Home header entry: admin upload, persistence, three modes, retry/failed save, disable, icon-only layout and app navigation passed')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

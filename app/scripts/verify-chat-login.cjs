// Run against dev:h5; all API calls use local fixtures and no account is changed.
const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const base = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
const output = path.resolve(__dirname, '../artifacts/chat-login')
const browserPath = process.env.UI_BROWSER_PATH || [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
].find(file => fs.existsSync(file))
const user = { userId: '1001', nickname: '聊天登录测试', avatar: '', level: 1 }
const checks = []

async function createApp(browser, loggedIn) {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  if (loggedIn) await context.addInitScript(user => {
    localStorage.setItem('app_token', 'chat-login-test')
    localStorage.setItem('app_user_info', JSON.stringify({ type: 'object', data: user }))
  }, user)
  const page = await context.newPage(), errors = [], chatRequests = []
  page.on('pageerror', error => errors.push(error.message))
  await context.route('**/api/locate*', route => route.fulfill({ json: { code: 0, province: '上海' } }))
  await context.route('**/app/**', async route => {
    const request = route.request(), endpoint = new URL(request.url()).pathname
    const headers = { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'GET,POST,OPTIONS' }
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers })
    if (endpoint.includes('/chat/')) chatRequests.push(endpoint)
    let data = []
    if (endpoint.endsWith('/home/config')) data = { channels: [{ id: 'all', name: '推荐' }], entries: [], news: { enabled: false, items: [], interval: 5, emptyText: '' } }
    else if (endpoint.endsWith('/notice/summary')) data = { unreadCount: 0, categories: [] }
    else if (endpoint.includes('/post/')) data = { records: [], total: 0, pages: 0 }
    else if (endpoint.endsWith('/user/me')) data = user
    else if (endpoint.endsWith('/media/config')) data = { cacheMaxBytes: 10000000 }
    else if (endpoint.endsWith('/notice/push-config')) data = { enabled: false }
    await route.fulfill({ headers, json: { code: '0000', msg: 'ok', data } })
  })
  await page.goto(base)
  await page.locator('.app-shell').waitFor()
  return { context, page, errors, chatRequests }
}

async function closeLogin(page) {
  await page.getByRole('button', { name: '关闭登录', exact: true }).click()
  await page.locator('.auth-page').waitFor({ state: 'detached' })
  await page.locator('.app-shell').waitFor()
}

async function waitForLogin(page) {
  await page.locator('.auth-page').waitFor()
  await page.waitForFunction(() => {
    const card = document.querySelector('.auth-page'), title = document.querySelector('.auth-title')
    return card && title && Math.abs(card.getBoundingClientRect().top) < 1 && title.getBoundingClientRect().bottom < innerHeight
  })
}

async function main() {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, ...(browserPath ? { executablePath: browserPath } : {}) })
  try {
    const guest = await createApp(browser, false), page = guest.page
    const message = page.locator('.tab-bar .tab-item[aria-label="消息"]')
    await message.click()
    await waitForLogin(page)
    assert.equal(await page.locator('.auth-page').count(), 1)
    assert.equal(await page.locator('.message-view').count(), 0, 'guest login must open before the message pane mounts')
    await page.screenshot({ path: path.join(output, 'guest-chat-login.png') })
    checks.push('guest tapping the bottom message entry opens login immediately')
    await closeLogin(page)
    assert.match(await page.locator('.tab-item[aria-label="首页"]').getAttribute('class'), /selected/)
    await message.evaluate(element => { element.click(); element.click() })
    await waitForLogin(page)
    assert.equal(await page.locator('.auth-page').count(), 1, 'repeated taps must not stack login dialogs')
    await closeLogin(page)
    assert.equal(guest.chatRequests.length, 0, 'guest must not request private chat data')
    checks.push('repeated bottom-entry taps open one dialog; cancel stays on home')
    await page.getByRole('button', { name: '我的', exact: true }).click()
    await page.locator('.guest-mine-view').waitFor()
    await message.click()
    await waitForLogin(page)
    await closeLogin(page)
    assert.match(await page.locator('.tab-item[aria-label="我的"]').getAttribute('class'), /selected/)
    await page.waitForFunction(() => getComputedStyle(document.querySelector('#ct-main-shell')).transform === 'matrix(1, 0, 0, 1, 0, 0)')
    checks.push('bottom message entry also opens login from guest profile; cancel restores the original tab and scale')
    assert.deepEqual(guest.errors, [])
    await guest.context.close()

    const member = await createApp(browser, true)
    await member.page.getByRole('button', { name: '消息', exact: true }).click()
    await member.page.locator('.message-header').waitFor()
    await member.page.getByRole('button', { name: '通知', exact: true }).click()
    await member.page.getByRole('button', { name: '聊天', exact: true }).click()
    await member.page.waitForFunction(() => document.querySelector('.message-tab[aria-label="聊天"]')?.classList.contains('active'))
    assert.equal(await member.page.locator('.auth-page').count(), 0)
    assert.deepEqual(member.errors, [])
    checks.push('logged-in users enter messages from the bottom and switch top tabs without login')
    await member.context.close()
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ status: 'passed', checks }, null, 2))
    console.log(JSON.stringify({ status: 'passed', checks }))
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })

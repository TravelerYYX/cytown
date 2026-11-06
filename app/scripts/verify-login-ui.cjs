// Authentication / guest profile regression, with local API mocks (no real email or account writes).
// Run dev:h5 on 15273, then npm run check:login. UI_TEST_URL / UI_BROWSER_PATH may override defaults.
const { chromium } = require('playwright-core')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const output = path.resolve(__dirname, '../artifacts/login-ui')
const base = (process.env.UI_TEST_URL || 'http://127.0.0.1:15273/').replace(/#.*$/, '').replace(/\/$/, '')
const loginUrl = base + '/#/pages/login/index'
const homeUrl = base + '/#/pages/index/index'
const browserPath = process.env.UI_BROWSER_PATH || [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
].find(file => fs.existsSync(file))
fs.mkdirSync(output, { recursive: true })
const results = []
const errors = []
const resourceFailures = []
const calls = []
const behavior = { failSend: false, failLogin: false, identity: 'new', delay: 0 }
const emptyPage = { records: [], current: 1, size: 10, pages: 0, total: 0 }
const users = {
  new: { userId: '1001', publicUid: '2000', qqNumber: '12345678', nickname: '新町友', avatar: '', level: 1, signature: '', gender: 0, cardBackground: '01.png', tags: [] },
  existing: { userId: '1002', publicUid: '2001', qqNumber: '87654321', nickname: '老町友', avatar: '', level: 3, signature: '原个人主页', gender: 0, cardBackground: '02.png', tags: [], hasPassword: true },
  official: { userId: '1003', publicUid: '2002', qqNumber: null, account: 'cyt0001', isOfficial: true, nickname: '官方町友', avatar: '', level: 1, signature: '', gender: 0, cardBackground: '01.png', tags: [], hasPassword: true }
}
const reply = data => ({ code: '0000', msg: 'ok', data })
async function mock(context) {
  await context.route('**/api/locate*', route => route.fulfill({ json: { code: 0, province: '上海' } }))
  await context.route('**/app/**', async route => {
    const request = route.request(), endpoint = new URL(request.url()).pathname
    const headers = { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'GET,POST,OPTIONS' }
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers })
    const body = request.postDataJSON()
    calls.push({ endpoint, body, method: request.method() })
    let value = reply(null)
    if (endpoint.endsWith('/auth/sendCode')) {
      value = behavior.failSend ? { code: '1000', msg: '验证码发送失败，请重试', data: null } : reply(null)
    } else if (endpoint.endsWith('/auth/login') || endpoint.endsWith('/auth/loginByPassword')) {
      const identity = endpoint.endsWith('/loginByPassword') ? 'official' : behavior.identity
      value = behavior.failLogin ? { code: '1000', msg: '验证码错误', data: null } : reply({ token: 'test-' + identity, refreshToken: 'refresh-' + identity, userInfo: users[identity] })
    } else if (endpoint.endsWith('/home/config')) {
      value = reply({ channels: [{ id: 'all', name: '推荐' }], entries: [], news: { enabled: false, interval: 5, emptyText: '暂无资讯', items: [] } })
    } else if (/\/post\/(list|mine|favorites)$/.test(endpoint)) {
      value = reply(emptyPage)
    } else if (endpoint.includes('statistics')) {
      value = reply({ postCount: 0, followerCount: 2, followingCount: 3, receivedLikeCount: 4, favoriteCount: 0 })
    } else if (endpoint.endsWith('/user/me')) {
      value = reply(users[behavior.identity])
    } else if (endpoint.includes('categor')) {
      value = reply([])
    } else if (endpoint.includes('media')) {
      value = reply({})
    }
    if (behavior.delay && endpoint.includes('/auth/')) await new Promise(resolve => setTimeout(resolve, behavior.delay))
    await route.fulfill({ status: 200, headers, json: value })
  })
}
function observe(page) {
  page.on('pageerror', error => errors.push(String(error)))
  page.on('response', response => {
    if (response.url().includes('/static/') && response.status() >= 400) resourceFailures.push(response.url())
  })
}
async function until(check, message) {
  for (let i = 0; i < 80; i++) {
    if (await check()) return
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(message)
}
async function disabled(page, selector = '.login-button') {
  return page.locator(selector).evaluate(el => {
    // uni-app x exposes a uni-button with ARIA rather than an HTML disabled property.
    const ariaDisabled = el.getAttribute('aria-disabled')
    if (ariaDisabled !== null) return ariaDisabled === 'true'
    const button = el.tagName.toLowerCase() === 'button' ? el : el.querySelector('button')
    return button ? button.disabled : el.hasAttribute('disabled') && el.getAttribute('disabled') !== 'false'
  })
}
const accountInput = page => page.locator('.account-field input')
const codeInput = page => page.locator('.code-field input')
const authCalls = endpoint => calls.filter(call => call.endpoint.endsWith(endpoint))
async function openLogin(page) {
  await page.goto(loginUrl)
  await page.waitForSelector('.auth-title')
  await until(async () => (await page.locator('.auth-title').textContent()).includes('QQ验证码登录'), 'default QQ mode')
}
async function agree(page) {
  await page.locator('.agreement-toggle').click()
  await until(async () => page.locator('.agreement-toggle.checked').count(), 'agreement checked')
}
async function imagesLoaded(page, selector) {
  const images = selector.split(',').map(part => part.trim() + ' img').join(',')
  await page.waitForFunction(selector => [...document.querySelectorAll(selector)].every(img => img.complete && img.naturalWidth > 0), images)
}
async function screenshot(page, name) {
  await imagesLoaded(page, '.auth-page, .guest-mine-view, .tab-bar')
  await page.screenshot({ path: path.join(output, name + '.png') })
}
async function noOverflow(page, width, selector) {
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width, selector + ': document overflow')
  const bounds = await page.locator(selector).boundingBox()
  assert(Math.abs(bounds.width - width) < 1, selector + ': fills viewport width')
}
async function mine(page) {
  await page.locator('.tab-item[aria-label="我的"]').click()
  await page.waitForSelector('.guest-mine-view, .mine-view')
  // Wait for selected tab animation to finish before visual comparisons.
  await page.waitForTimeout(700)
}
async function loginWithCode(page, qq, identity) {
  behavior.identity = identity
  await openLogin(page)
  await accountInput(page).fill(qq)
  await agree(page)
  await page.locator('.login-button').click()
  await page.waitForSelector('.code-field')
  await codeInput(page).fill('123456')
  await until(async () => !(await disabled(page)), 'code submit ready')
  await page.locator('.login-button').click()
  await page.waitForSelector('.app-shell')
  await mine(page)
  assert.equal(await page.locator('.guest-mine-view').count(), 0)
  assert.equal((await page.locator('.mine-view .nickname-row').textContent()).trim(), users[identity].nickname)
  assert.equal(await page.locator('.mine-view .role-scroll').count(), 1, 'logged-in role cards retained')
  assert.equal(await page.locator('.mine-view .game-chips').count(), 0, 'game filters removed from logged-in profile')
  assert.equal(await page.evaluate(() => localStorage.getItem('app_token')), 'test-' + identity)
  assert.equal(await page.evaluate(() => localStorage.getItem('app_refresh_token')), 'refresh-' + identity)
}
;(async () => {
  const browser = await chromium.launch({ headless: true, ...(browserPath ? { executablePath: browserPath } : {}) })
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 837 }, isMobile: true, hasTouch: true })
    await mock(context)
    const page = await context.newPage()
    observe(page)
    await page.clock.install()
    await openLogin(page)
    assert.equal(await accountInput(page).getAttribute('type'), 'number')
    assert.equal(await accountInput(page).getAttribute('maxlength'), '11')
    assert.equal(await page.locator('.code-field').count(), 0, 'first step has no code field')
    await accountInput(page).pressSequentially('cyt0001')
    assert.equal(await accountInput(page).inputValue(), '0001')
    assert.equal(await disabled(page), true)
    await accountInput(page).fill('123')
    assert.equal(await page.locator('.field-hint').count(), 1)
    await agree(page)
    assert.equal(await disabled(page), true, 'invalid QQ still disabled after agreement')
    await accountInput(page).fill('12345678')
    await until(async () => !(await disabled(page)), 'valid QQ allows sending')
    await page.locator('.agreement-toggle').click()
    assert.equal(await disabled(page), true, 'agreement required')
    await agree(page)
    behavior.failSend = true
    await page.locator('.login-button').click()
    await until(async () => !(await disabled(page)), 'send failure permits retry')
    assert.equal(await page.locator('.code-field').count(), 0, 'failed send stays at first step')
    behavior.failSend = false
    behavior.delay = 250
    const before = authCalls('/auth/sendCode').length
    await page.locator('.login-button').click()
    await page.locator('.login-button').dispatchEvent('click')
    await page.waitForSelector('.code-field')
    assert.equal(authCalls('/auth/sendCode').length, before + 1, 'double send blocked')
    assert.equal(await disabled(page, '.send-button'), true, 'resend blocked during cooldown')
    assert.match(await page.locator('.send-button').textContent(), /\d+s后重发/)
    await codeInput(page).fill('123')
    assert.equal(await disabled(page), true, 'six digits required')
    await codeInput(page).fill('123456')
    await until(async () => !(await disabled(page)), 'six digit code allows submit')
    behavior.failLogin = true
    await page.locator('.login-button').click()
    await until(async () => !(await disabled(page)), 'incorrect code permits retry')
    assert.equal(await codeInput(page).inputValue(), '123456', 'error preserves verification input')
    behavior.failLogin = false
    await accountInput(page).fill('87654321')
    await until(async () => !(await page.locator('.code-field').count()), 'recipient change resets verification')
    assert.equal(await disabled(page), true, 'recipient change must not bypass cooldown')
    await page.clock.fastForward(61000)
    await until(async () => !(await disabled(page)), 'cooldown expires')
    await page.locator('.login-button').click()
    await page.waitForSelector('.code-field')
    assert.equal(await codeInput(page).inputValue(), '')
    assert.deepEqual(authCalls('/auth/sendCode').at(-1).body, { qqNumber: '87654321' })
    await page.locator('.mode-item').click()
    assert.equal(await accountInput(page).getAttribute('type'), 'text')
    assert.equal(await accountInput(page).getAttribute('maxlength'), '32')
    await accountInput(page).fill('cyt0001')
    assert.equal(await disabled(page), true, 'password required')
    const password = page.locator('.password-field input')
    await password.fill('cyt0001@abc')
    await until(async () => !(await disabled(page)), 'official account accepts password')
    assert.equal(await password.getAttribute('type'), 'password')
    await page.locator('.password-toggle').click()
    assert.equal(await password.getAttribute('type'), 'text')
    assert.equal(await password.inputValue(), 'cyt0001@abc')
    await page.locator('.password-toggle').click()
    assert.equal(await password.getAttribute('type'), 'password')
    await accountInput(page).fill('abc')
    assert.equal(await disabled(page), false, 'password account format remains unrestricted')
    await accountInput(page).fill('')
    await until(async () => await disabled(page), 'empty password account disables submit')
    await accountInput(page).fill('cyt0001')
    await until(async () => !(await disabled(page)), 'official account submit ready')
    const beforePassword = authCalls('/auth/loginByPassword').length
    await page.locator('.login-button').click()
    await page.locator('.login-button').dispatchEvent('click')
    await page.waitForSelector('.app-shell')
    assert.equal(authCalls('/auth/loginByPassword').length, beforePassword + 1, 'double login blocked')
    assert.deepEqual(authCalls('/auth/loginByPassword').at(-1).body, { qqNumber: 'cyt0001', password: 'cyt0001@abc' })
    await mine(page)
    assert.match(await page.locator('.mine-view .nickname-row').textContent(), /官方町友/)
    results.push('agreement-and-validation', 'send-failure-retry', 'send-and-login-deduplication', 'verification-error-retry', 'recipient-change-and-cooldown', 'password-and-official-account')
    behavior.delay = 0
    await loginWithCode(page, '12345678', 'new')
    await screenshot(page, 'logged-in-new-390')
    await loginWithCode(page, '87654321', 'existing')
    await screenshot(page, 'logged-in-existing-390')
    assert.deepEqual(authCalls('/auth/login').at(-1).body, { qqNumber: '87654321', code: '123456' })
    await page.locator('.mine-view [aria-label="设置"]').first().click()
    await page.waitForSelector('.settings-page')
    await page.locator('.settings-row').filter({ hasText: /^退出登录$/ }).click()
    await page.locator('.confirm-dialog .confirm').click()
    await page.waitForSelector('.app-shell')
    await mine(page)
    assert.equal(await page.locator('.guest-mine-view').count(), 1)
    assert.equal(await page.evaluate(() => localStorage.getItem('app_token')), null)
    assert.equal(await page.evaluate(() => localStorage.getItem('app_refresh_token')), null)
    assert.equal(await page.evaluate(() => localStorage.getItem('app_user_info')), null)
    results.push('new-and-existing-code-login-mocks', 'session-storage', 'logged-in-profile-retained', 'logout-restores-guest')
    await context.close()

    // Reference size strips only OS status / gesture bars (50px + 22px); no fabricated system UI.
    for (const width of [320, 375, 390, 430, 861]) {
      const height = Math.round(width * 1848 / 861)
      const visualContext = await browser.newContext({ viewport: { width, height }, isMobile: true, hasTouch: true })
      await mock(visualContext)
      const visual = await visualContext.newPage()
      observe(visual)
      await visual.goto(homeUrl)
      await mine(visual)
      assert.equal(await visual.locator('.guest-stat-value').allTextContents().then(values => values.join(',')), '0,0,0')
      assert.equal(await visual.locator('.guest-mine-view .role-scroll, .guest-mine-view .identity-meta, .guest-mine-view .game-chips').count(), 0)
      assert.equal(await visual.locator('.tab-bar .red-dot').count(), 0)
      await noOverflow(visual, width, '.guest-mine-view')
      const tab = await visual.locator('.tab-bar').boundingBox()
      const pane = await visual.locator('.guest-mine-view').boundingBox()
      assert(Math.abs(pane.height - tab.y) < 1, 'guest pane ends above tab bar')
      // Shortcuts may scroll horizontally; everything else stays within viewport.
      for (const channel of ['评论', '合集', '收藏', '发布']) {
        await visual.locator('.guest-mine-view .channel-tab').filter({ hasText: channel }).click()
        assert.equal(await visual.locator('.guest-empty').count(), 1)
      }
      await screenshot(visual, 'guest-' + width)
      await visual.locator('.guest-login').click()
      await visual.waitForSelector('.auth-title')
      assert.match(await visual.locator('.auth-title').textContent(), /QQ验证码登录/)
      await noOverflow(visual, width, '.auth-page')
      assert.equal(await disabled(visual), true)
      await screenshot(visual, 'code-' + width)
      await visual.locator('.mode-item').click()
      await screenshot(visual, 'password-' + width)
      await noOverflow(visual, width, '.auth-page')
      const agreement = await visual.locator('.agreement-copy').boundingBox()
      assert(agreement.x + agreement.width <= width + 1, 'agreement fits viewport')
      await visual.locator('.legal-link').first().click()
      await until(async () => (await visual.locator('.uni-simple-toast__text').textContent()).includes('用户协议待上线'), 'legal placeholder toast')
      await visual.locator('.auth-footer [aria-label="登录帮助"]').click()
      await until(async () => (await visual.locator('.uni-simple-toast__text').textContent()).includes('登录帮助待上线'), 'help placeholder toast')
      assert.equal(await visual.locator('.agreement-toggle.checked').count(), 0, 'legal links do not check agreement')
      await visual.locator('.auth-close').click()
      await visual.waitForSelector('.guest-mine-view')
      if (width === 390) {
        await visual.locator('.guest-mine-view [aria-label="设置"]').click()
        await visual.waitForSelector('.settings-page')
        await visual.locator('.settings-header [aria-label="返回"]').click()
        await visual.waitForSelector('.guest-mine-view')
        await visual.locator('.guest-login').click()
        await visual.waitForSelector('.auth-title')
        await visual.setViewportSize({ width, height: 400 })
        await visual.locator('.mode-item').click()
        await visual.locator('.password-field input').focus()
        await visual.locator('.login-button').scrollIntoViewIfNeeded()
        const button = await visual.locator('.login-button').boundingBox()
        assert(button.y >= 0 && button.y + button.height <= 401, 'short viewport keeps button reachable')
        await screenshot(visual, 'keyboard-height-390')
        await noOverflow(visual, width, '.auth-page')
      }
      await visualContext.close()
      results.push('visual-and-navigation-' + width)
    }
    const report = { results, runtimeErrors: errors, resourceFailures, authRequests: calls.filter(call => call.endpoint.includes('/auth/')), verification: 'API responses are mocked; no SMS/email sent and no real accounts created.' }
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify(report, null, 2))
    assert.equal(errors.length, 0, errors.join('\n'))
    assert.equal(resourceFailures.length, 0, resourceFailures.join('\n'))
    console.log(JSON.stringify({ results, runtimeErrors: errors.length, resourceFailures: resourceFailures.length }, null, 2))
  } catch (error) {
    for (const context of browser.contexts()) for (const page of context.pages()) {
      console.error('Failed page:', page.url())
      await page.screenshot({ path: path.join(output, 'failure.png') }).catch(() => {})
    }
    fs.writeFileSync(path.join(output, 'failure.json'), JSON.stringify({ error: String(error), results, runtimeErrors: errors, resourceFailures, authRequests: calls.filter(call => call.endpoint.includes('/auth/')) }, null, 2))
    throw error
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

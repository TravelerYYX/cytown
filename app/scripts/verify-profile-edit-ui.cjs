const { chromium } = require('playwright-core')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')

const baseUrl = process.env.UI_TEST_URL || 'http://127.0.0.1:15273/'
const browserPath = process.env.UI_BROWSER_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const output = path.resolve(__dirname, '../artifacts/profile-edit-ui')
fs.mkdirSync(output, { recursive: true })

const user = {
  userId: 'u1', publicUid: '10001', qqNumber: '10001', nickname: '旅行者',
  avatar: '/avatar/current.png', level: 60, signature: '', gender: 0,
  tags: [
    { tagId: 'game-1', name: '旅行者', custom: false },
    { tagId: 'game-2', name: '60级萌新', custom: false }
  ]
}
const avatars = {
  dynamic: [
    { id: 'dynamic-1', url: '/avatar/dynamic-1.png' },
    { id: 'dynamic-2', url: '/avatar/dynamic-2.png' }
  ],
  static: Array.from({ length: 16 }, (_, index) => ({ id: `static-${index}`, url: `/avatar/static-${index}.png` }))
}
const gameTags = ['旅行者', '60级萌新', '核爆玩家', '原牌', '原壶', '原摄', '原琴', '原锄', '240/240', '大数字', '崇高道德的赞许', '手动挡', '自动挡', '无敌破坏王', '大乐透爱好者', '热血开拓者', '悲观开拓者', '沉默开拓者', '咕咕钟收藏家', '隧洞守门人', '银河球棒侠', '群欢愉', '群巡猎', '群丰饶']
const tags = ['游戏', '本体', '兴趣', 'MBTI'].map((category, groupIndex) => ({
  category,
  tags: gameTags.slice(groupIndex * 6, groupIndex * 6 + (groupIndex ? 6 : 24)).map((name, index) => ({ id: `${category}-${index + 1}`, name }))
}))

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: browserPath })
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('http://127.0.0.1:9999/**', async route => {
    const pathname = new URL(route.request().url()).pathname
    let data = null
    if (pathname === '/app/user/systemAvatars') data = avatars
    else if (pathname === '/app/user/tags') data = tags
    else if (pathname === '/app/user/selectSystemAvatar' || pathname === '/app/user/updateTags' || pathname === '/app/user/updateNickname' || pathname === '/app/user/updateSignature') data = user
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: '0000', msg: '', data }) })
  })
  await page.goto(baseUrl)
  await page.evaluate(value => {
    uni.setStorageSync('app_token', 'test-token')
    uni.setStorageSync('app_refresh_token', 'test-refresh')
    uni.setStorageSync('app_user_info', value)
  }, user)

  await page.goto(baseUrl + '#/pages/profile-edit/index')
  await page.waitForSelector('.profile-card')
  assert.equal(await page.locator('.profile-nav').innerText(), '修改信息')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false)
  assert.equal(await page.locator('.avatar-button').evaluate(node => getComputedStyle(node, '::after').borderTopWidth), '0px')
  await page.screenshot({ path: path.join(output, 'profile-390.png'), fullPage: true })

  await page.goto(baseUrl + '#/pages/profile-edit/nickname')
  await page.waitForSelector('.nickname-page')
  assert.equal(await page.locator('.nav-save').isVisible(), true)
  assert.equal(await page.locator('.nav-save').isEnabled(), true)
  assert.equal(await page.locator('.input-wrap input').evaluate(node => getComputedStyle(node).color), 'rgb(32, 36, 38)')
  await page.screenshot({ path: path.join(output, 'nickname-390.png'), fullPage: true })

  await page.goto(baseUrl + '#/pages/profile-edit/signature')
  await page.waitForSelector('.signature-page')
  assert.equal(await page.locator('.nav-save').isVisible(), true)
  assert.equal(await page.locator('.nav-save').isEnabled(), true)
  assert((await page.locator('.counter').innerText()).endsWith('/48'))
  await page.screenshot({ path: path.join(output, 'signature-390.png'), fullPage: true })

  await page.goto(baseUrl + '#/pages/profile-edit/avatar')
  await page.waitForSelector('.static-grid .avatar-cell')
  assert.equal(await page.locator('.static-grid .avatar-cell').count(), 16)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false)
  await page.screenshot({ path: path.join(output, 'avatar-390.png'), fullPage: true })
  await page.locator('.static-grid .avatar-cell').first().click()
  assert.equal(await page.locator('.confirm-button').isEnabled(), true)

  await page.goto(baseUrl + '#/pages/profile-edit/tags')
  await page.waitForSelector('.tag-option')
  assert.equal(await page.locator('.selected-chip').count(), 2)
  assert.equal(await page.locator('.tag-option').count(), 24)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false)
  await page.screenshot({ path: path.join(output, 'tags-390.png'), fullPage: true })
  await page.locator('.category-button').nth(1).click()
  assert.equal(await page.locator('.tag-option').count(), 6)
  await page.locator('.category-button').last().click()
  await page.locator('.custom-editor input').fill('自定义测试')
  await page.locator('.custom-add').click()
  assert.equal(await page.locator('.selected-chip').count(), 3)
  assert.equal(await page.locator('.tag-option', { hasText: '自定义测试' }).count(), 1)
  assert.equal(errors.length, 0, errors.join('\n'))
  await browser.close()
  console.log(JSON.stringify({ profile: 'passed', nickname: 'passed', signature: 'passed', avatar: 'passed', tags: 'passed', runtimeErrors: 0 }))
}

main().catch(error => { console.error(error); process.exitCode = 1 })

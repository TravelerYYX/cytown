const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const output = path.resolve(__dirname, '../artifacts/channel-admin')
  fs.mkdirSync(output, { recursive: true })
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
    const errors = [], writes = []
    page.on('pageerror', e => errors.push(e.message))
    await page.addInitScript(() => localStorage.setItem('SOY_token', JSON.stringify('ui-test-token')))
    const channels = [
      { id: 1, name: '交流区', sortOrder: 0, enabled: true, isDefault: true, adminOnly: false, showAllPosts: false, allowPublish: true },
      { id: 2, name: '官方', sortOrder: 1, enabled: true, isDefault: false, adminOnly: false, showAllPosts: false, allowPublish: true }
    ]
    await page.route('**/*', async route => {
      if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
      const pathname = new URL(route.request().url()).pathname
      let data = {}
      if (pathname.endsWith('/auth/getUserInfo')) data = { userId: '1', userName: 'UI测试', nickName: '测试管理员', avatar: '', roles: ['R_SUPER'], buttons: [] }
      else if (pathname.endsWith('/home/channel/list')) data = channels
      else if (pathname.endsWith('/home/channel/update') || pathname.endsWith('/home/channel/create')) {
        const body = route.request().postDataJSON(); writes.push(body)
        if (pathname.endsWith('/create')) { data = { ...body, id: channels.length + 1 }; channels.push(data) }
        else { data = channels.find(c => c.id === body.id); Object.assign(data, body) }
      } else if (pathname.endsWith('/home/entry/list')) data = []
      else if (pathname.endsWith('/home/news/get')) data = { enabled: true, interval: 3, emptyText: '暂无资讯，敬请期待' }
      else if (pathname.endsWith('/health')) data = { redis: { available: true, message: 'test' }, ffmpeg: { available: true, message: 'test', ffmpegPath: 'test' } }
      return route.fulfill({ json: { code: '0000', msg: 'ok', data } })
    })
    await page.goto((process.env.ADMIN_UI_TEST_URL || 'http://127.0.0.1:15372') + '/home-config')
    const card = page.locator('.n-card').filter({ has: page.getByText('频道管理', { exact: true }) }).first()
    await card.getByRole('button', { name: '编辑', exact: true }).nth(1).click()
    const modal = page.locator('.n-modal')
    const allPosts = modal.locator('.n-form-item').filter({ has: page.getByText('显示全部帖子', { exact: true }) }).getByRole('switch')
    const publishing = modal.locator('.n-form-item').filter({ has: page.getByText('允许发布', { exact: true }) }).getByRole('switch')
    const restriction = modal.locator('.n-form-item').filter({ has: page.getByText('只能管理员发布', { exact: true }) }).getByRole('switch')
    assert.equal(await restriction.getAttribute('aria-checked'), 'false')
    await restriction.click()
    await allPosts.click(); await publishing.click()
    await page.waitForTimeout(250)
    await modal.getByText('开启后仅官方账号可发布，普通用户选择版区时不显示').waitFor()
    await modal.screenshot({ path: path.join(output, 'edit-channel-admin-only.png') })
    await modal.getByRole('button', { name: '保存', exact: true }).click()
    await modal.waitFor({ state: 'detached' })
    assert.equal(channels[1].adminOnly, true)
    assert.equal(channels[1].showAllPosts, true); assert.equal(channels[1].allowPublish, false)
    await card.getByText('仅管理员发布', { exact: true }).waitFor()
    await card.getByRole('switch').nth(1).click()
    await page.waitForFunction(() => [...document.querySelectorAll('.n-card')].some(c => c.textContent.includes('官方') && c.textContent.includes('已停用')))
    assert.equal(channels[1].showAllPosts, true); assert.equal(channels[1].allowPublish, false); assert.equal(channels[1].adminOnly, true); assert.equal(channels[1].enabled, false)
    await card.getByRole('switch').nth(1).click()
    await page.waitForFunction(() => !document.body.textContent.includes('已停用'))
    assert.equal(channels[1].showAllPosts, true); assert.equal(channels[1].allowPublish, false); assert.equal(channels[1].adminOnly, true); assert.equal(channels[1].enabled, true)
    await card.getByRole('button', { name: '新增频道', exact: true }).click()
    assert.equal(await restriction.getAttribute('aria-checked'), 'false', 'new channel resets the prior edit setting')
    await modal.locator('input').first().fill('新开放频道')
    await modal.getByRole('button', { name: '保存', exact: true }).click(); await modal.waitFor({ state: 'detached' })
    assert.equal(channels[2].adminOnly, false)
    assert.equal(channels[2].allowPublish, false); assert.equal(channels[2].showAllPosts, false)
    await card.getByRole('button', { name: '新增频道', exact: true }).click()
    await modal.locator('input').first().fill('新官方频道'); await publishing.click(); await restriction.click()
    await modal.getByRole('button', { name: '保存', exact: true }).click(); await modal.waitFor({ state: 'detached' })
    assert.equal(channels[3].adminOnly, true)
    await page.reload(); await card.getByRole('button', { name: '编辑', exact: true }).nth(1).click()
    assert.equal(await restriction.getAttribute('aria-checked'), 'true', 'setting survives reload')
    assert.equal(await allPosts.getAttribute('aria-checked'), 'true')
    assert.equal(await publishing.getAttribute('aria-checked'), 'false')
    await modal.getByRole('button', { name: '取消', exact: true }).click(); await modal.waitFor({ state: 'detached' })
    await card.screenshot({ path: path.join(output, 'channel-list-admin-only.png') })
    assert.deepEqual(errors, [])
    const result = { createDefaultOff: true, createRestricted: true, editPersisted: true, quickTogglePreserved: true, scopeAndPublishingPersisted: true, newChannelPublishingDefaultOff: true, writes: writes.length, errors }
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify(result, null, 2))
    console.log(JSON.stringify(result, null, 2))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

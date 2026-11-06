// Management UI verification using deterministic API fixtures; requires the admin Vite server.
// ADMIN_UI_TEST_URL=http://127.0.0.1:15273 node scripts/verify-user-management.cjs
const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const base = process.env.ADMIN_UI_TEST_URL || 'http://127.0.0.1:15273'
const output = path.resolve(__dirname, '../../ciyuantoen-back/ciyuantown-admin/artifacts/user-management')
const browserPath = process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const ordinary = { id: '10', publicUid: '1009', account: 'user_demo', officialAdminId: null, qqNumber: '12345678', nickname: '测试町友', avatar: '', status: 0, lastLoginTime: '2026-10-09 10:00', createTime: '2026-09-01 09:00' }
const linked = { ...ordinary, id: '20', publicUid: '1019', account: 'official_demo', officialAdminId: '2', qqNumber: null, nickname: '官方町友' }
const statistics = { postCount: 3, followerCount: 12, followingCount: 4, receivedLikeCount: 8, favoriteCount: 2 }
const detail = row => ({ ...row, email: row.qqNumber ? `${row.qqNumber}@qq.com` : 'official-2@official.local', signature: '测试签名', gender: 0, level: 3, cardBackground: '01.png', tags: [{ tagId: '1', name: '二次元', custom: false }], hasPassword: true, updateTime: '2026-10-09 10:00', statistics })
const official = { id: '2', username: 'official_demo', nickname: '测试官方', status: 0, userType: 1, createdBy: '1', createTime: '2026-09-01 09:00' }

;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: browserPath })
  const errors = [], requests = [], passed = []
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
    await context.addInitScript(() => localStorage.setItem('SOY_token', JSON.stringify('ui-test-token')))
    let failDetail = true
    let failDelete = true
    let appRows = [ordinary, linked]
    let officialRows = [official]
    let paginationFixture = false
    const listRequests = []
    await context.route('**/*', async route => {
      if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
      const url = new URL(route.request().url()), pathname = url.pathname.replace(/^\/proxy-default/, '')
      let data = null
      if (pathname.endsWith('/auth/getUserInfo')) data = { userId: '1', userName: 'admin', nickName: '测试管理员', avatar: '', userType: 0, roles: ['R_SUPER'], buttons: [] }
      else if (pathname.endsWith('/app-user/list')) {
        const body = route.request().postDataJSON()
        listRequests.push({ pathname, pageNum: body.pageNum })
        const fixture = paginationFixture ? [...Array.from({ length: 10 }, (_, i) => ({ ...ordinary, id: String(100 + i), account: `page_user_${i}` })), ...appRows] : appRows
        data = { records: fixture.slice((body.pageNum - 1) * body.pageSize, body.pageNum * body.pageSize), total: fixture.length, current: body.pageNum, size: body.pageSize, pages: Math.ceil(fixture.length / body.pageSize) }
      } else if (pathname.endsWith('/admin-user/list')) data = { records: officialRows, total: officialRows.length, current: 1, size: 10, pages: 1 }
      else if (pathname.endsWith('/app-user/detail')) {
        if (failDetail) { failDetail = false; return route.fulfill({ json: { code: '1000', msg: '模拟加载失败', data: null } }) }
        data = detail(url.searchParams.get('id') === '20' ? linked : ordinary)
      } else if (pathname.endsWith('/admin-user/detail')) data = { ...official, avatar: '', hasPassword: true, createdByName: '测试管理员', updateTime: '2026-10-09 10:00', community: detail(linked), statistics }
      else if (/\/(app-user|admin-user)\/(updateAccount|resetPassword|create|updateStatus|delete)$/.test(pathname)) {
        const body = route.request().postDataJSON()
        requests.push({ pathname, body })
        await new Promise(resolve => setTimeout(resolve, 350))
        if (pathname.endsWith('/delete')) {
          if (failDelete) {
            failDelete = false
            return route.fulfill({ json: { code: '1000', msg: '模拟删除失败', data: null } })
          }
          if (pathname.includes('/app-user/')) {
            const target = appRows.find(row => row.id === body.id)
            appRows = appRows.filter(row => row.id !== body.id)
            if (target?.officialAdminId) officialRows = officialRows.filter(row => row.id !== target.officialAdminId)
          } else {
            officialRows = officialRows.filter(row => row.id !== body.id)
            appRows = appRows.filter(row => row.officialAdminId !== body.id)
          }
        }
        if (pathname.endsWith('/updateAccount')) {
          if (pathname.includes('/app-user/')) ordinary.account = body.account
          else official.username = body.account
        }
        if (pathname.endsWith('/create')) data = pathname.includes('/app-user/') ? { ...ordinary, id: '30', account: body.account || body.qqNumber } : { ...official, id: '3', username: body.account }
      } else return route.fulfill({ json: { code: '0000', msg: 'ok', data: {} } })
      return route.fulfill({ json: { code: '0000', msg: 'ok', data } })
    })
    const page = await context.newPage()
    page.on('pageerror', e => errors.push(e.message))
    await page.goto(base + '/app-user-manage')
    await page.getByRole('button', { name: '详情', exact: true }).first().waitFor()
    await page.getByRole('button', { name: '详情', exact: true }).first().click()
    await page.getByText('用户详情加载失败').waitFor()
    await page.getByRole('button', { name: '重试', exact: true }).click()
    await page.getByText('业务统计', { exact: true }).waitFor()
    assert.equal(await page.getByText('测试签名', { exact: true }).count(), 1)
    assert.equal(await page.getByText('男', { exact: true }).count(), 1)
    await page.evaluate(() => window.$message?.destroyAll())
    await page.screenshot({ path: path.join(output, 'app-user-detail.png') })
    await page.keyboard.press('Escape')
    await page.locator('.n-modal').waitFor({ state: 'hidden' })
    passed.push('detail-load-retry-profile-and-statistics')

    await page.getByRole('button', { name: '编辑', exact: true }).first().click()
    const modal = page.locator('.n-modal')
    await modal.locator('input').fill('abc')
    await modal.getByRole('button', { name: '保存账号', exact: true }).click()
    assert.equal(requests.length, 0)
    await modal.locator('input').fill('user_new')
    await modal.getByText('修改密码', { exact: true }).click()
    await modal.locator('input').nth(0).fill('12345678')
    await modal.locator('input').nth(1).fill('87654321')
    await modal.getByRole('button', { name: '保存密码', exact: true }).click()
    assert.equal(requests.length, 0)
    await modal.locator('input').nth(1).fill('12345678')
    await modal.getByText('修改账号', { exact: true }).click()
    await page.evaluate(() => window.$message?.destroyAll())
    await page.screenshot({ path: path.join(output, 'edit-account.png') })
    await modal.getByRole('button', { name: '保存账号', exact: true }).click()
    await modal.waitFor({ state: 'hidden' })
    assert.deepEqual(requests.at(-1), { pathname: '/app-user/updateAccount', body: { id: '10', account: 'user_new' } })
    passed.push('tab-save-isolated-account-validation')

    await page.getByRole('button', { name: '编辑', exact: true }).first().click()
    await modal.getByText('修改密码', { exact: true }).click()
    assert.equal(await modal.locator('input').nth(0).inputValue(), '')
    assert.equal(await modal.locator('input').nth(1).inputValue(), '')
    await modal.locator('input').nth(0).fill('12345678')
    await modal.locator('input').nth(1).fill('12345678')
    await page.screenshot({ path: path.join(output, 'edit-password.png') })
    const before = requests.length
    await modal.getByRole('button', { name: '保存密码', exact: true }).evaluate(button => { button.click(); button.click() })
    await modal.waitFor({ state: 'hidden' })
    assert.equal(requests.length, before + 1)
    assert.deepEqual(requests.at(-1), { pathname: '/app-user/resetPassword', body: { id: '10', password: '12345678' } })
    passed.push('password-reset-no-old-password-and-double-submit-guard')

    await page.getByRole('button', { name: '新建用户', exact: true }).click()
    await modal.getByPlaceholder('4-20位字母、数字或下划线，留空使用QQ号').fill('new_user')
    await modal.getByPlaceholder('5-11位数字', { exact: true }).fill('23456789')
    await modal.getByRole('button', { name: '创建', exact: true }).click()
    await modal.waitFor({ state: 'hidden' })
    assert.equal(requests.at(-1).body.account, 'new_user')
    passed.push('create-app-custom-account')

    await page.getByText('官方账号', { exact: true }).first().click()
    await page.getByRole('button', { name: '新建官方账号', exact: true }).waitFor()
    await page.getByRole('button', { name: '详情', exact: true }).click()
    await modal.getByText('业务统计', { exact: true }).waitFor()
    await page.screenshot({ path: path.join(output, 'official-detail.png') })
    await page.keyboard.press('Escape'); await modal.waitFor({ state: 'hidden' })
    await page.getByRole('button', { name: '编辑', exact: true }).click()
    await modal.locator('input').fill('123456789')
    await modal.getByRole('button', { name: '保存账号', exact: true }).click()
    await modal.waitFor({ state: 'hidden' })
    assert.deepEqual(requests.at(-1), { pathname: '/admin-user/updateAccount', body: { id: '2', account: '123456789' } })
    passed.push('official-details-and-numeric-rename')

    // Cancelling must not mutate. Failure keeps the row; repeated confirmation sends one request.
    await page.evaluate(() => window.$message?.destroyAll())
    await page.getByRole('button', { name: '删除', exact: true }).click()
    await page.getByText('关联社区身份将一起删除', { exact: false }).waitFor()
    await page.screenshot({ path: path.join(output, 'official-delete-confirmation.png'), animations: 'disabled' })
    const confirmationBounds = await page.locator('.n-popover').filter({ has: page.getByRole('button', { name: '确认删除', exact: true }) }).boundingBox()
    assert(confirmationBounds.width <= 400 && confirmationBounds.x >= 0 && confirmationBounds.x + confirmationBounds.width <= 1440)
    const beforeCancel = requests.length
    await page.getByRole('button', { name: '取消', exact: true }).click()
    assert.equal(requests.length, beforeCancel)
    passed.push('official-delete-associated-identity-warning-and-cancel')

    await page.getByText('APP 用户', { exact: true }).first().click()
    await page.getByRole('button', { name: '删除', exact: true }).first().click()
    await page.getByText('原登录账号和QQ号可重新注册', { exact: false }).waitFor()
    await page.getByRole('button', { name: '确认删除', exact: true }).click()
    await page.getByText('删除失败，请重试', { exact: true }).waitFor()
    assert.equal(await page.getByRole('button', { name: '删除', exact: true }).count(), 2)
    assert.deepEqual(requests.at(-1), { pathname: '/app-user/delete', body: { id: '10' } })
    passed.push('delete-failure-keeps-row')
    await page.evaluate(() => window.$message?.destroyAll())
    await page.getByRole('button', { name: '删除', exact: true }).first().click()
    const beforeDelete = requests.length
    await page.getByRole('button', { name: '确认删除', exact: true }).evaluate(button => { button.click(); button.click() })
    await page.waitForFunction(() => [...document.querySelectorAll('.n-data-table-tbody button')].filter(button => button.textContent.trim() === '删除').every(button => button.disabled))
    await page.waitForFunction(() => document.querySelectorAll('.n-data-table-tbody tr').length === 1)
    assert.equal(requests.length, beforeDelete + 1)
    assert.equal(await page.getByRole('button', { name: '删除', exact: true }).count(), 1)
    passed.push('delete-success-refresh-and-double-submit-guard')

    await page.getByRole('button', { name: '删除', exact: true }).click()
    await page.getByText('关联官方账号及社区身份将一起删除', { exact: false }).waitFor()
    await page.getByRole('button', { name: '取消', exact: true }).click()
    passed.push('app-community-delete-cascade-warning')

    // Exercise the official entrypoint; the community row disappears on the next APP list load.
    await page.getByText('官方账号', { exact: true }).first().click()
    await page.getByRole('button', { name: '删除', exact: true }).click()
    await page.getByRole('button', { name: '确认删除', exact: true }).click()
    await page.getByText('官方账号已删除', { exact: true }).waitFor()
    await page.getByRole('button', { name: '删除', exact: true }).waitFor({ state: 'hidden' })
    assert.deepEqual(requests.at(-1), { pathname: '/admin-user/delete', body: { id: '2' } })
    await page.getByText('APP 用户', { exact: true }).first().click()
    await page.getByRole('button', { name: '删除', exact: true }).waitFor({ state: 'hidden' })
    passed.push('official-delete-refreshes-both-lists')

    // Last row on page two: refresh page two, then fall back to page one with corrected total.
    appRows = [{ ...ordinary, id: '40', account: 'last_page_user' }]
    paginationFixture = true
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await page.getByText('last_page_user', { exact: true }).waitFor({ state: 'hidden' })
    await page.locator('.n-pagination-item').filter({ hasText: /^2$/ }).click()
    await page.getByText('last_page_user', { exact: true }).waitFor()
    await page.getByRole('button', { name: '删除', exact: true }).click()
    await page.getByRole('button', { name: '确认删除', exact: true }).click()
    await page.getByText('page_user_0', { exact: true }).waitFor()
    assert.deepEqual(listRequests.slice(-2).map(r => r.pageNum), [2, 1])
    assert.equal(await page.getByRole('button', { name: '删除', exact: true }).count(), 10)
    passed.push('delete-last-page-row-corrects-pagination')
    await page.screenshot({ path: path.join(output, 'user-delete-actions.png') })

    // A separate official session exposes APP details, with all writes and the official tab hidden.
    const readonly = await browser.newContext({ viewport: { width: 1280, height: 900 } })
    await readonly.addInitScript(() => localStorage.setItem('SOY_token', JSON.stringify('readonly-test-token')))
    await readonly.route('**/*', async route => {
      if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
      const url = new URL(route.request().url())
      let data = {}
      if (url.pathname.endsWith('/auth/getUserInfo')) data = { userId: '2', userName: 'official', nickName: '只读账号', avatar: '', userType: 1, roles: ['R_OFFICIAL'], buttons: [] }
      if (url.pathname.endsWith('/app-user/list')) data = { records: [ordinary], total: 1, current: 1, size: 10, pages: 1 }
      if (url.pathname.endsWith('/app-user/detail')) data = detail(ordinary)
      return route.fulfill({ json: { code: '0000', msg: 'ok', data } })
    })
    const readPage = await readonly.newPage()
    readPage.on('pageerror', e => errors.push(e.message))
    await readPage.goto(base + '/app-user-manage')
    await readPage.getByRole('button', { name: '详情', exact: true }).waitFor()
    assert.equal(await readPage.getByRole('button', { name: '编辑', exact: true }).count(), 0)
    assert.equal(await readPage.getByRole('button', { name: '删除', exact: true }).count(), 0)
    assert.equal(await readPage.getByRole('button', { name: '新建用户', exact: true }).count(), 0)
    assert.equal(await readPage.getByText('官方账号', { exact: true }).count(), 0)
    await readPage.getByRole('button', { name: '详情', exact: true }).click()
    await readPage.getByText('业务统计', { exact: true }).waitFor()
    passed.push('readonly-details-only')
    await readPage.setViewportSize({ width: 390, height: 844 })
    await readPage.waitForTimeout(300)
    await readPage.screenshot({ path: path.join(output, 'detail-mobile.png') })
    const bounds = await readPage.locator('.n-modal').boundingBox()
    assert(bounds.x >= 0 && bounds.x + bounds.width <= 391)
    assert(bounds.y >= 0 && bounds.y + bounds.height <= 845)
    await readPage.screenshot({ path: path.join(output, 'detail-mobile.png') })
    passed.push('responsive-detail-fits-viewport')
    await readonly.close()
    assert.deepEqual(errors, [])
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ passed, runtimeErrors: errors, mutationCount: requests.length }, null, 2))
    console.log(JSON.stringify({ passed, runtimeErrors: errors.length }, null, 2))
    await context.close()
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

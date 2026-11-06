const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const output = path.resolve(__dirname, '../artifacts/home-news')
  fs.mkdirSync(output, { recursive: true })
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
    const errors = []
    page.on('pageerror', e => errors.push(e.message))
    await page.addInitScript(() => localStorage.setItem('SOY_token', JSON.stringify('ui-test-token')))
    const row = { id: '1', title: '后台轮播测试帖子', authorNickname: '测试作者', createTime: '2026-09-08 00:00:00', status: 1, homeNewsEnabled: false, tags: [], media: [], likeCount: 0, commentCount: 0, content: '测试正文' }
    let failToggle = false, toggleCalls = 0, saveCalls = 0
    let news = { enabled: true, interval: 3, emptyText: '暂无资讯，敬请期待' }
    let layout = { defaultFeedLayout: 'single' }, layoutSaveCalls = 0, failLayoutSave = false
    await page.route('**/*', async route => {
      if (!['xhr', 'fetch'].includes(route.request().resourceType())) return route.continue()
      const pathname = new URL(route.request().url()).pathname
      let data
      if (pathname.endsWith('/auth/getUserInfo')) data = { userId: '1', userName: 'UI测试', nickName: '测试管理员', avatar: '', roles: ['R_SUPER'], buttons: [] }
      else if (pathname.endsWith('/post/list')) data = { records: [row], total: 1, current: 1, size: 10, pages: 1 }
      else if (pathname.endsWith('/post/1')) data = row
      else if (pathname.endsWith('/post/updateHomeNews')) {
        toggleCalls++
        await new Promise(resolve => setTimeout(resolve, 500))
        if (failToggle) return route.fulfill({ json: { code: '1000', msg: '模拟保存失败', data: null } })
        row.homeNewsEnabled = route.request().postDataJSON().homeNewsEnabled
        data = null
      } else if (pathname.endsWith('/home/layout/get')) data = layout
      else if (pathname.endsWith('/home/layout/update')) {
        layoutSaveCalls++
        if (failLayoutSave) return route.fulfill({ json: { code: '1000', msg: '模拟布局保存失败', data: null } })
        layout = route.request().postDataJSON(); data = layout
      } else if (pathname.endsWith('/home/news/get')) data = news
      else if (pathname.endsWith('/home/news/update')) { saveCalls++; news = route.request().postDataJSON(); data = news }
      else if (pathname.endsWith('/home/channel/list') || pathname.endsWith('/home/entry/list') || pathname.endsWith('/mine/entry/list')) data = []
      else return route.continue()
      return route.fulfill({ json: { code: '0000', msg: 'ok', data } })
    })
    const base = process.env.ADMIN_UI_TEST_URL || 'http://127.0.0.1:15273'
    await page.goto(base + '/post-manage')
    const toggle = page.getByRole('switch')
    await toggle.waitFor({ timeout: 10000 }).catch(async error => { console.log(page.url(), (await page.locator('body').innerText()).slice(0, 2000), errors); throw error })
    assert.equal(await toggle.getAttribute('aria-checked'), 'false')
    await toggle.click()
    assert((await toggle.getAttribute('class')).includes('n-switch--disabled'), 'pending save must disable switch')
    await page.waitForFunction(() => document.querySelector('[role=switch]')?.getAttribute('aria-checked') === 'true')
    assert.equal(toggleCalls, 1)
    await page.getByRole('button', { name: '详情', exact: true }).click()
    await page.getByText('首页资讯轮播：已开启（仅已发布帖子展示）').waitFor()
    await page.keyboard.press('Escape')
    await page.waitForTimeout(300)
    failToggle = true
    await toggle.click()
    await page.getByText('模拟保存失败', { exact: true }).waitFor()
    assert.equal(await toggle.getAttribute('aria-checked'), 'true', 'failed save must preserve state')
    assert.equal(row.homeNewsEnabled, true)
    await page.screenshot({ path: path.join(output, 'admin-post-news.png') })
    await page.goto(base + '/home-config')
    const layoutButton = page.getByRole('button', { name: '保存默认帖子布局', exact: true })
    await layoutButton.waitFor()
    await page.getByText('双列瀑布布局', { exact: true }).click()
    await layoutButton.click()
    await page.getByText('默认帖子布局已保存', { exact: true }).waitFor()
    assert.equal(layout.defaultFeedLayout, 'waterfall')
    await page.reload()
    await layoutButton.waitFor()
    await page.waitForFunction(() => [...document.querySelectorAll('.n-radio')].some(el => el.textContent.includes('双列瀑布布局') && el.classList.contains('n-radio--checked')))
    failLayoutSave = true
    await page.getByText('单列布局', { exact: true }).click()
    await layoutButton.click()
    await page.getByText('模拟布局保存失败', { exact: true }).waitFor()
    assert.equal(layout.defaultFeedLayout, 'waterfall', 'failed save keeps stored default')
    await page.reload(); await layoutButton.waitFor()
    await page.waitForFunction(() => [...document.querySelectorAll('.n-radio')].some(el => el.textContent.includes('双列瀑布布局') && el.classList.contains('n-radio--checked')))
    await page.waitForTimeout(400)
    await page.locator('.n-card').filter({ has: page.getByText('默认帖子布局', { exact: true }) }).screenshot({ path: path.join(output, 'admin-feed-layout.png') })
    const text = page.getByPlaceholder('暂无资讯，敬请期待')
    await text.waitFor()
    await text.fill(' ')
    await page.getByRole('button', { name: '保存资讯栏配置' }).click()
    await page.getByText('无资讯时显示文案需为1-100个字符', { exact: true }).waitFor()
    assert.equal(saveCalls, 0, 'blank text must not be saved')
    await text.fill('  欢迎来到次元町  ')
    await page.getByRole('button', { name: '保存资讯栏配置' }).click()
    await page.getByText('资讯栏配置已保存', { exact: true }).waitFor()
    assert.equal(news.emptyText, '欢迎来到次元町')
    await page.reload()
    await page.waitForFunction(() => [...document.querySelectorAll('input')].some(el => el.value === '欢迎来到次元町'))
    await page.locator('.n-card').filter({ has: page.getByText('资讯栏配置', { exact: true }) }).screenshot({ path: path.join(output, 'admin-news-config.png') })
    assert.deepEqual(errors, [])
    fs.writeFileSync(path.join(output, 'admin-verification.json'), JSON.stringify({ toggleCalls, saveCalls, savedText: news.emptyText, failedSavePreservesState: true, errors }, null, 2))
    assert.equal(layoutSaveCalls, 2)
    console.log('Admin default layout persistence/failed save, news toggle, pending state, detail and empty text configuration: passed')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

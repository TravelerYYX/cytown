const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const { chromium } = require('playwright-core')
const out = path.resolve(__dirname, '../artifacts/mine-panel-style')
const fixture = n => Array.from({ length: n }, (_, i) => ({ id: i + 1, title: ['创作中心', '米游铺', '兑换中心', '奇匠中心'][i % 4], description: ['作品收益管理', '官方周边店', '周边积分兑换', '查看创作'][i % 4], iconUrl: '/panel-test-' + ['creator', 'shop', 'shop', 'sparkle'][i % 4] + '.svg', linkUrl: 'https://example.test/entry/' + i, openMode: 'inapp', showTopbar: true }))
const near = (a, b, label, tolerance = .15) => assert(Math.abs(a - b) < tolerance, `${label}: ${a} vs ${b}`)
;(async () => {
  fs.mkdirSync(out, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' }), results = []
  try {
    for (const width of [320, 390, 430]) {
      const context = await browser.newContext({ viewport: { width, height: 858 }, deviceScaleFactor: width === 390 ? 1080 / 390 : 1 }), page = await context.newPage(), errors = []
      let count = 4
      page.on('pageerror', error => errors.push(error.message))
      await page.route('**/*', route => {
        const pathname = new URL(route.request().url()).pathname, icon = pathname.match(/panel-test-(\w+)\.svg$/)
        if (icon) return route.fulfill({ contentType: 'image/svg+xml', body: fs.readFileSync(path.resolve(__dirname, '../static/icons/' + icon[1] + '-dark.svg')) })
        if (!['fetch', 'xhr'].includes(route.request().resourceType())) return route.continue()
        let data = []
        if (pathname === '/app/mine/entries') data = fixture(count)
        else if (pathname === '/app/home/config') data = { channels: [{ id: 1, name: '推荐' }], entries: [], news: { enabled: false, items: [] } }
        else if (pathname.includes('/post/')) data = { records: [], pages: 0, current: 1, total: 0, size: 10 }
        else if (pathname === '/app/user/statistics') data = { followerCount: 0, followingCount: 0, receivedLikeCount: 0 }
        return route.fulfill({ json: { code: '0000', data } })
      })
      await page.goto(process.env.UI_TEST_URL || 'http://127.0.0.1:15273')
      for (const member of [false, true]) {
        if (member) {
          await page.evaluate(() => { uni.setStorageSync('app_token', 'panel-ui-fixture'); uni.setStorageSync('app_refresh_token', 'panel-ui-refresh'); uni.setStorageSync('app_user_info', { userId: '10', nickname: '样式验证', tags: [] }) })
          await page.reload()
        }
        await page.locator('.tab-item[aria-label="我的"]').click()
        const pane = page.locator(member ? '.mine-view' : '.guest-mine-view'), panel = pane.locator(member ? '.profile-panel' : '.guest-panel'), tabs = panel.locator(':scope > .channel-tabs'), buttons = tabs.locator('.channel-tab'), carrier = tabs.locator('.profile-indicator'), bar = tabs.locator('.profile-indicator-bar')
        await panel.locator('.mine-quick-entry').first().waitFor()
        await panel.locator('.mine-quick-icon img').first().evaluate(el => el.decode())
        const geometry = await panel.evaluate(el => { const box = n => n.getBoundingClientRect().toJSON(); return { panel: box(el), cards: [...el.querySelectorAll('.mine-quick-entry')].map(box), tabs: [...el.querySelectorAll('.channel-tab')].map(box), bar: box(el.querySelector('.profile-indicator')), radius: parseFloat(getComputedStyle(el).borderTopLeftRadius) } })
        const scale = width / 750, card = geometry.cards[0]
        for (const [actual, expected, name] of [[card.x, 33.333, 'card left'], [card.y - geometry.panel.y, 41.667, 'card top'], [card.width, 195.833, 'card width'], [card.height, 91, 'card height'], [geometry.cards[1].x - card.x, 212.5, 'card pitch'], [geometry.tabs[1].x - geometry.tabs[0].x, 102.778, 'tab pitch'], [geometry.bar.x, 38.889, 'bar left'], [geometry.bar.y - geometry.panel.y, 223.611, 'bar top'], [geometry.radius, 41.667, 'corner']]) near(actual, expected * scale, name)
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width)
        const initialX = (await carrier.boundingBox()).x
        await bar.evaluate(el => { window.__indicatorNode = el })
        await buttons.nth(3).click()
        await tabs.evaluate(el => el.getAnimations({ subtree: true }).forEach(a => { a.pause(); a.currentTime = 120 }))
        const halfway = await carrier.boundingBox(), contracted = await bar.boundingBox()
        assert(halfway.x > initialX && halfway.x < initialX + 3 * 102.778 * scale, 'indicator must slide')
        near(contracted.width / (41.667 * scale), .42, 'contraction', .03)
        await tabs.evaluate(el => el.getAnimations({ subtree: true }).forEach(a => a.finish()))
        await page.waitForTimeout(30)
        near((await carrier.boundingBox()).x, initialX + 3 * 102.778 * scale, 'final position')
        near((await bar.boundingBox()).width, 41.667 * scale, 'restored width')
        assert(await bar.evaluate(el => el === window.__indicatorNode), 'switching must preserve the indicator node')
        await buttons.nth(0).click(); await page.waitForTimeout(40); await buttons.nth(2).click(); await buttons.nth(1).click(); await page.waitForTimeout(380)
        near((await carrier.boundingBox()).x, initialX + 102.778 * scale, 'rapid reverse switching')
        assert(await bar.evaluate(el => el === window.__indicatorNode), 'rapid switching must not recreate the bar')
        await page.emulateMedia({ reducedMotion: 'reduce' }); await buttons.nth(0).click()
        near((await carrier.boundingBox()).x, initialX, 'reduced motion')
        assert.equal(await bar.evaluate(el => getComputedStyle(el).animationName), 'none')
        await page.emulateMedia({ reducedMotion: 'no-preference' }); await page.waitForTimeout(380)
        const panelBox = await panel.boundingBox()
        await pane.locator('.screen-scroll > .uni-scroll-view > .uni-scroll-view').evaluate((el, y) => { el.scrollTop = y }, panelBox.y - 22 * width / 1080)
        await page.waitForTimeout(250)
        await page.screenshot({ path: path.join(out, `${width}-${member ? 'member' : 'guest'}.png`) })
        if (width === 390 && !member) await page.screenshot({ path: path.join(out, 'reference-1080.png'), clip: { x: 0, y: (await panel.boundingBox()).y - 22 * width / 1080, width, height: 393 * width / 1080 } })
        await panel.locator('.mine-quick-scroll .uni-scroll-view').evaluateAll(elements => elements.forEach(el => { el.scrollLeft = 9999 }))
        await page.waitForTimeout(100)
        const last = await panel.locator('.mine-quick-entry').last().boundingBox()
        assert(last.x >= 0 && last.x + last.width <= width + 1, 'last entry must be reachable: ' + JSON.stringify({ width, member, last, scroll: await panel.locator('.mine-quick-scroll').evaluate(el => [...el.querySelectorAll('*')].filter(n=>n.className.includes('scroll')).map(n=>({cls:n.className,scrollLeft:n.scrollLeft,scrollWidth:n.scrollWidth,clientWidth:n.clientWidth}))) }))
        results.push({ width, member, geometry, slideContractExpand: true, rapidSwitching: true, reducedMotion: true, horizontalScroll: true })
      }
      count = 0; await page.reload(); await page.locator('.tab-item[aria-label="我的"]').click(); await page.waitForTimeout(300)
      assert.equal(await page.locator('.mine-view .mine-quick-zone').count(), 0)
      assert.equal(await page.locator('.mine-view .profile-panel').evaluate(el => el.firstElementChild.classList.contains('channel-tabs')), true)
      assert.deepEqual(errors, []); await context.close()
    }
    fs.writeFileSync(path.join(out, 'verification.json'), JSON.stringify({ status: 'passed', scope: 'H5 fixtures, no business writes; native compilation checked separately.', results }, null, 2))
    console.log('PASS: three widths, guest/member geometry, slide/contract/expand, rapid reversal, reduced motion, horizontal scrolling and empty configuration.')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

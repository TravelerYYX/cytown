const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const url = process.env.UI_TEST_URL || 'http://127.0.0.1:15273/'
const output = path.resolve(__dirname, '../artifacts/discover-channel')
const post = (id, channelId) => ({ id, channelId, channelName: channelId === 2 ? '酒馆' : '二创',
  status: 1, authorId: '100', authorNickname: '频道测试', title: `帖子 ${id}`, content: '',
  date: '10-23', media: [], tags: [], likeCount: 0, commentCount: 0 })
async function invoke(page, method) {
  await page.locator('.home-view').evaluate((el, name) => {
    let instance = el.__vueParentComponent
    while (instance && !instance.setupState[name]) instance = instance.parent
    if (!instance) throw new Error('HomeView method missing: ' + name)
    void instance.setupState[name]()
  }, method)
}
async function settled(page) {
  await page.waitForFunction(() => {
    let instance = document.querySelector('.home-view').__vueParentComponent
    while (instance && !instance.setupState.refreshAll) instance = instance.parent
    return !instance.setupState.refreshing && !instance.setupState.loading
  })
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
    const errors = [], requests = []
    page.on('pageerror', error => errors.push(error.message))
    let failConfig = false, holdPageTwo = false, releasePageTwo, refreshed = false
    let channels = [{ id: 9, name: '发现', showAllPosts: true }, { id: 2, name: '酒馆' }, { id: 3, name: '官方' }, { id: 8, name: '二创' }, { id: 6, name: '同人图' }, { id: 7, name: 'COS' }]
    await page.route('**/app/**', async route => {
      const request = new URL(route.request().url())
      let data = { records: [], total: 0, pages: 0, current: 1 }
      if (request.pathname === '/app/home/config') {
        if (failConfig) return route.fulfill({ json: { code: '1000', msg: '配置暂不可用' } })
        data = { channels, entries: [], news: { enabled: false, interval: 3, items: [] }, defaultFeedLayout: 'waterfall' }
      } else if (request.pathname === '/app/post/list') {
        const channel = request.searchParams.get('channelId'), current = Number(request.searchParams.get('pageNum'))
        requests.push({ channel, current })
        if (channel && !channels.find(item => String(item.id) === channel)?.showAllPosts) {
          data = { records: [post(`channel-${channel}`, Number(channel))], total: 1, current, pages: 1 }
        } else if (current === 2) {
          if (holdPageTwo) await new Promise(resolve => { releasePageTwo = resolve })
          data = { records: [post('all-b', 8), post('all-c', 2)], total: 3, current, pages: 2 }
        } else {
          data = { records: refreshed ? [post('all-new', 8)] : [post('all-a', 2), post('all-b', 8)], total: 3, current, pages: 2 }
        }
      } else if (request.pathname.includes('/notice/')) data = []
      await route.fulfill({ json: { code: '0000', msg: 'ok', data }, headers: { 'Access-Control-Allow-Origin': '*' } })
    })
    const cards = page.locator('.home-view .waterfall-post')
    const originalTabs = page.locator('.home-view [id$="-original-tabs"] .channel-tabs')
    async function select(name) {
      await originalTabs.getByRole('button', { name, exact: true }).click()
      await settled(page)
    }
    await page.goto(url)
    await cards.first().waitFor()
    await settled(page)
    assert.deepEqual(requests, [{ channel: '9', current: 1 }], 'Configured discovery must query its persisted channel ID once')
    assert.equal(await originalTabs.locator('.channel-tab').first().innerText(), '发现')
    assert.equal(await originalTabs.locator('.channel-tab.active').innerText(), '发现')
    assert.equal(await cards.count(), 2, 'Discovery must include posts from both channels')
    for (const width of [320, 375, 390, 393, 414, 430]) {
      await page.setViewportSize({ width, height: 844 })
      const tabs = await originalTabs.locator('.channel-tab').evaluateAll(elements => elements.map(el => {
        const rect = el.getBoundingClientRect()
        const range = document.createRange(); range.selectNodeContents(el.querySelector('.channel-tab-label'))
        const label = range.getBoundingClientRect()
        return { x: rect.x, right: rect.right, width: rect.width, labelWidth: label.width }
      }))
      assert.equal(tabs.length, 6)
      assert(tabs.every(tab => tab.x >= -1 && tab.right <= width + 1 && tab.width >= tab.labelWidth), `Channel tabs overflow at ${width}px`)
    }
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: path.join(output, 'discover-390.png') })
    await invoke(page, 'loadMore'); await settled(page)
    assert.deepEqual(requests.at(-1), { channel: '9', current: 2 })
    assert.equal(await cards.count(), 3, 'Pagination should append and deduplicate all-channel posts')
    await select('酒馆')
    assert.deepEqual(requests.at(-1), { channel: '2', current: 1 })
    assert.equal(await cards.count(), 1)
    await select('二创')
    assert.deepEqual(requests.at(-1), { channel: '8', current: 1 })
    await select('发现')
    assert.deepEqual(requests.at(-1), { channel: '9', current: 1 })

    // Switching channels must reject a late discovery pagination response.
    holdPageTwo = true
    await invoke(page, 'loadMore')
    for (let attempt = 0; !releasePageTwo && attempt < 250; attempt++) await new Promise(resolve => setTimeout(resolve, 20))
    assert(releasePageTwo, 'Discovery pagination did not start')
    await select('酒馆')
    releasePageTwo()
    await page.waitForTimeout(100)
    assert.equal(await cards.count(), 1)
    assert.equal(await cards.first().getAttribute('data-post-id'), 'channel-2')
    holdPageTwo = false

    // Removing the selected category falls back to discovery, with one global refresh.
    channels = channels.filter(channel => channel.id !== 2)
    refreshed = true
    const beforeRefresh = requests.length
    await invoke(page, 'refreshAll'); await settled(page)
    assert.deepEqual(requests.slice(beforeRefresh), [{ channel: '9', current: 1 }])
    assert.equal(await originalTabs.locator('.channel-tab.active').innerText(), '发现')
    assert.equal(await cards.first().getAttribute('data-post-id'), 'all-new')
    await invoke(page, 'revalidate'); await settled(page)
    assert.equal(requests.at(-1).channel, '9', 'Revalidation retains the configured channel ID')

    // Changing the scope of the current channel reloads the feed without changing its ID.
    channels[0].showAllPosts = false
    await invoke(page, 'loadConfig'); await settled(page)
    assert.equal(await cards.first().getAttribute('data-post-id'), 'channel-9')
    channels[0].showAllPosts = true
    await invoke(page, 'refreshAll'); await settled(page)
    assert.equal(await cards.first().getAttribute('data-post-id'), 'all-new')

    // Channels are entirely configured by the server; no fixed discovery tab is injected.
    failConfig = true
    await page.reload(); await cards.first().waitFor(); await settled(page)
    assert.equal(await originalTabs.locator('.channel-tab').count(), 0)
    assert.equal(requests.at(-1).channel, null)
    assert.deepEqual(errors, [])
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ passed: true, widths: [320, 375, 390, 393, 414, 430], requests, errors }, null, 2))
    console.log('Discovery passed: default selection, all-channel queries, pagination, category filtering, stale response rejection, refresh fallback, revalidation, config failure and six viewport widths.')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const url = process.env.UI_TEST_URL || 'http://127.0.0.1:15273/'
const post = id => ({ id, authorId: '100', authorNickname: '刷新测试', title: `帖子 ${id}`, content: '', date: '10-11', media: [], tags: [], likeCount: 0, commentCount: 0 })
const config = channel => ({ channels: [{ id: channel, name: `频道 ${channel}` }], entries: [], news: { enabled: false, interval: 3, emptyText: '暂无资讯', items: [] }, defaultFeedLayout: 'waterfall' })
async function invoke(page, name) {
  await page.locator('.home-view').evaluate((el, method) => {
    let instance = el.__vueParentComponent
    while (instance && !instance.setupState[method]) instance = instance.parent
    if (!instance) throw new Error('HomeView method missing: ' + method)
    void instance.setupState[method]()
  }, name)
}
async function settled(page) {
  await page.waitForFunction(() => {
    let instance = document.querySelector('.home-view').__vueParentComponent
    while (instance && !instance.setupState.refreshAll) instance = instance.parent
    return !instance.setupState.refreshing && !instance.setupState.loading
  })
}
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
    const errors = [], requests = []
    page.on('pageerror', error => errors.push(error.message))
    let channel = 1, ids = ['a', 'b'], failFeed = false, holdConfig = false, releaseConfig, releasePageTwo
    await page.route('**/app/**', async route => {
      const requestUrl = new URL(route.request().url())
      let data = { records: [], total: 0, pages: 0, current: 1 }
      if (requestUrl.pathname === '/app/home/config') {
        requests.push({ type: 'config' })
        if (holdConfig) await new Promise(resolve => { releaseConfig = resolve })
        data = config(channel)
      } else if (requestUrl.pathname === '/app/post/list') {
        const pageNum = Number(requestUrl.searchParams.get('pageNum'))
        requests.push({ type: 'feed', pageNum, channel: Number(requestUrl.searchParams.get('channelId')) })
        if (pageNum === 2) {
          await new Promise(resolve => { releasePageTwo = resolve })
          data = { records: [post('stale-page-two')], total: 3, current: 2, pages: 2 }
        } else {
          if (failFeed) return route.abort('failed')
          data = { records: ids.map(post), total: ids.length + 1, current: 1, pages: 2 }
        }
      } else if (requestUrl.pathname.includes('/notice/')) data = []
      await route.fulfill({ json: { code: '0000', msg: 'ok', data }, headers: { 'Access-Control-Allow-Origin': '*' } })
    })
    await page.goto(url)
    const cards = page.locator('.home-view .waterfall-post')
    await cards.first().waitFor()
    await settled(page)
    assert.equal(await cards.count(), 2)

    // Refresh is single-flight and keeps both existing cards and channel controls mounted.
    holdConfig = true; failFeed = true
    await page.evaluate(() => { window.originalChannel = document.querySelector('.home-view .channel-tabs') })
    const configsBefore = requests.filter(request => request.type === 'config').length
    await invoke(page, 'refreshAll')
    await page.waitForFunction(() => {
      let instance = document.querySelector('.home-view').__vueParentComponent
      while (instance && !instance.setupState.refreshAll) instance = instance.parent
      return instance.setupState.refreshing
    })
    await invoke(page, 'refreshAll')
    await invoke(page, 'revalidate')
    assert.equal(await cards.count(), 2)
    assert(await page.evaluate(() => window.originalChannel === document.querySelector('.home-view .channel-tabs')))
    assert.equal(requests.filter(request => request.type === 'config').length, configsBefore + 1)
    holdConfig = false; releaseConfig()
    await settled(page)
    assert.equal(await cards.count(), 2, 'Failed refresh erased the successful feed')
    assert((await page.locator('.home-view .load-tip').innerText()).includes('网络异常'))

    // Retry refreshes page one and replaces the snapshot after success.
    failFeed = false; ids = ['fresh-a', 'fresh-b']
    await page.locator('.home-view .load-tip uni-button').click()
    await settled(page)
    await page.waitForSelector('.waterfall-post[data-post-id="fresh-a"]')
    assert.equal(requests.filter(request => request.type === 'feed').at(-1).pageNum, 1)
    assert(!(await page.locator('.home-view .load-tip').innerText()).includes('网络异常'))

    // A pre-refresh pagination response cannot append stale data to the refreshed list.
    await invoke(page, 'loadMore')
    for (let attempt = 0; !releasePageTwo && attempt < 250; attempt++) await new Promise(resolve => setTimeout(resolve, 20))
    assert(releasePageTwo, 'Pagination request did not start')
    ids = ['latest']
    await invoke(page, 'refreshAll'); await settled(page)
    releasePageTwo(); await new Promise(resolve => setTimeout(resolve, 150))
    assert.equal(await cards.count(), 1)
    assert.equal(await page.locator('[data-post-id="stale-page-two"]').count(), 0)

    // Updated configuration selects the valid channel and loads it once.
    channel = 2; ids = ['channel-two']
    const feedsBefore = requests.filter(request => request.type === 'feed').length
    await invoke(page, 'refreshAll'); await settled(page)
    await page.waitForSelector('.waterfall-post[data-post-id="channel-two"]')
    const newFeeds = requests.filter(request => request.type === 'feed').slice(feedsBefore)
    assert.deepEqual(newFeeds, [{ type: 'feed', pageNum: 1, channel: 2 }])
    assert.deepEqual(errors, [])
    console.log('Home refresh passed: mounted controls, retained feed on failure, page-one retry, single flight, stale pagination rejection and channel updates.')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

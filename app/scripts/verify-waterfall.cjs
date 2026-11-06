const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const origin = (process.env.UI_TEST_URL || 'http://127.0.0.1:15273').replace(/\/$/, '')
const output = path.resolve(__dirname, '../artifacts/waterfall')
const media = (id, ratio, type = 'image') => ({ id, type, aspectRatio: ratio, layout: ratio < 1 ? 'portrait' : 'wide',
  url: type === 'image' ? `http://waterfall.test/${id}.svg` : '', coverUrl: `http://waterfall.test/${id}.svg`,
  duration: type === 'video' ? '00:16' : undefined, status: 'ready', resourceVersion: 'waterfall-v1' })
const makePost = (id, title, images, content = '') => ({ id: String(id), title, content, media: images,
  authorId: '20', authorNickname: '次元町创作者', authorAvatar: '', authorLevel: 8,
  mode: images[0]?.type === 'video' ? 'video' : 'image', status: 1, tags: [],
  likeCount: 525, commentCount: 7, date: '10-10', liked: false })
const posts = [
  makePost(101, '【作品分享】今天的创作记录', [media('wide', 16 / 9), media('extra', 1)]),
  makePost(102, '要和我一起跳舞吗？', [media('square-video', 1, 'video')]),
  makePost(103, '又是拍摄的一天～', [media('landscape', 3 / 2)]),
  makePost(104, '这个角色真可爱！', [media('portrait', 3 / 4)]),
  makePost(105, '这是哪位创作者？精彩的竖屏视频分享', [media('vertical-video', 9 / 16, 'video')]),
  makePost(106, '旅行者的小日常：完整长漫画', [media('comic', 1 / 4)]),
  makePost(107, '超宽风景图', [media('panorama', 4)]),
  makePost(108, '方图作品分享', [media('square', 1)]),
  makePost(109, '纯文字帖子也会保留', [], '没有图片的日常分享。这段正文应该最多显示两行。'.repeat(5)),
  makePost(110, '长标题验证'.repeat(15), [media('long-title', 1)]),
  makePost(111, '封面加载失败', [{ ...media('failed', 1), coverUrl: 'http://waterfall.test/failed.svg' }]),
  makePost(112, '', [], '只有正文的文字卡片'),
  makePost(113, '', [media('no-title', 0)]),
  makePost(114, '没有视频封面', [{ type: 'video', aspectRatio: 16 / 9, durationMs: 11000, status: 'processing', layout: 'wide' }])
]
const originals = JSON.stringify(posts.map(post => post.media))
const errors = []
let serverDefault = 'single', failConfig = false, failPageTwo = false, listCalls = 0

async function fixtures(page) {
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/app/**', async route => {
    const url = new URL(route.request().url()), endpoint = url.pathname
    let data = {}
    if (endpoint === '/app/home/config') {
      if (failConfig) return route.fulfill({ json: { code: '1000', msg: '配置暂不可用' } })
      data = { channels: [{ id: 1, name: '发现' }, { id: 2, name: '同人图' }, { id: 3, name: 'COS' }], entries: [],
        news: { enabled: false, interval: 3, items: [] }, ...(serverDefault === undefined ? {} : { defaultFeedLayout: serverDefault }) }
    } else if (endpoint === '/app/post/list') {
      listCalls++
      const pageNum = Number(url.searchParams.get('pageNum') || 1)
      if (pageNum === 2 && failPageTwo) return route.fulfill({ json: { code: '1000', msg: '分页暂不可用' } })
      data = { records: pageNum === 1 ? posts.slice(0, 10) : [posts[9], ...posts.slice(10)], total: posts.length, current: pageNum, pages: 2, size: 10 }
    } else if (/^\/app\/post\/\d+$/.test(endpoint)) data = posts.find(post => post.id === endpoint.split('/').pop())
    else if (endpoint === '/app/post/following') data = { records: posts.slice(0, 2), total: 2, current: 1, pages: 1, size: 10 }
    else if (endpoint === '/app/interaction/toggleLike') {
      const post = posts.find(post => post.id === route.request().postDataJSON().postId)
      post.liked = !post.liked; post.likeCount += post.liked ? 1 : -1
      data = { active: post.liked, count: post.likeCount }
    } else if (endpoint === '/app/user/me') data = { userId: '10', publicUid: '100', nickname: '测试用户', avatar: '', level: 1, tags: [] }
    else if (endpoint === '/app/user/statistics') data = { fans: 0, following: 1, likes: 0 }
    else if (endpoint === '/app/media/config') data = { cacheMaxBytes: 3 * 1024 ** 3 }
    else if (endpoint === '/app/comment/config') data = { imageMax: 3, contentMax: 1000, emojis: [], emojiGroups: [] }
    else if (endpoint.includes('/comments') || endpoint.includes('/mine') || endpoint.includes('/favorites')) data = { records: [], total: 0, pages: 0, current: 1 }
    else if (endpoint.includes('categories') || endpoint.includes('conversations')) data = []
    await route.fulfill({ json: { code: '0000', msg: 'ok', data }, headers: { 'Access-Control-Allow-Origin': '*' } })
  })
  await page.route('http://waterfall.test/**', async route => {
    const id = new URL(route.request().url()).pathname.slice(1, -4)
    if (id === 'failed') return route.fulfill({ status: 404, body: '' })
    const image = posts.flatMap(post => post.media).find(image => image.id === id)
    const ratio = image?.coverAspectRatio || (image?.aspectRatio > 0 ? image.aspectRatio : 1)
    const width = 600, height = width / ratio
    const colors = ['#e4cfdf', '#c8e2ef', '#d4e3d8', '#e9ddc4']
    const color = colors[posts.findIndex(post => post.media.includes(image)) % colors.length] || colors[0]
    await route.fulfill({ contentType: 'image/svg+xml', headers: { 'Access-Control-Allow-Origin': '*' },
      body: `<svg xmlns="http://www.w3.org/2000/svg" width="600" height="${height}"><rect width="100%" height="100%" fill="${color}"/><circle cx="300" cy="${height / 2}" r="130" fill="white" fill-opacity=".35"/><path d="M0 ${height}L220 ${height / 2}L400 ${height * .85}L600 ${height / 3}V${height}Z" fill="white" fill-opacity=".35"/><text x="300" y="${height / 2}" text-anchor="middle" font-size="36" fill="#576778">${id}</text></svg>` })
  })
}
async function login(page) {
  await page.evaluate(() => {
    uni.setStorageSync('app_token', 'fixture-token')
    uni.setStorageSync('app_user_info', { userId: '10', publicUid: '100', nickname: '测试用户', avatar: '', level: 1, tags: [] })
  })
  await page.reload()
}
async function settings(page) {
  await page.locator('.tab-bar .tab-item').nth(4).click()
  await page.locator('.mine-view [aria-label="设置"]:visible, .guest-mine-view [aria-label="设置"]:visible').first().click()
  await page.locator('.settings-page').waitFor()
}
async function choose(page, label) {
  await page.locator('.settings-row', { hasText: '首页帖子布局' }).click()
  await page.locator('.sheet-layer.open').waitFor()
  await page.locator('.sheet-option', { hasText: label }).click()
  await page.locator('.sheet-layer').waitFor({ state: 'detached' })
}
async function home(page) {
  await page.locator('.settings-header [aria-label="返回"]').click()
  await page.locator('.app-shell').waitFor()
  await page.locator('.tab-bar .tab-item').first().click()
}
const cards = page => page.locator('.home-view .waterfall-post')
async function ready(page, count = 10) { await page.waitForFunction(count => [...document.querySelectorAll('.home-view [data-loaded-count]')].reduce((n, column) => n + Number(column.dataset.loadedCount), 0) >= count && document.querySelectorAll('.home-view .waterfall-post').length > 0, count) }
async function membership(page) { return page.locator('.home-view .waterfall-feed').evaluate(el => {
  let instance = el.__vueParentComponent
  while (instance && !instance.setupState.columns) instance = instance.parent
  return instance.setupState.columns.map(column => [...column])
}) }
const scroll = page => page.locator('.home-view > .screen-scroll > .uni-scroll-view > .uni-scroll-view')

async function run() {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
    const page = await context.newPage(); await fixtures(page); await page.goto(origin)
    await page.locator('.home-view .post-card').first().waitFor()
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_feed_layout')), '')
    await login(page); await settings(page)
    const callsBefore = listCalls
    await choose(page, '双列瀑布布局'); await home(page); await ready(page)
    assert(listCalls - callsBefore <= 1, 'switch only permits the existing home revalidation, not a new feed fetch')
    assert.equal(await page.locator('.home-view .post-card').count(), 0)
    assert.equal(await scroll(page).evaluate(el => el.scrollTop), 0)
    const before = await membership(page)
    assert(before.every(column => column.length > 0), 'both columns need cards after switching while home is hidden')
    assert.equal(await page.locator('.waterfall-image-count').first().innerText(), '2')
    assert.equal(await page.locator('.waterfall-duration').first().innerText(), '00:16')
    assert.equal(await page.locator('.waterfall-feed video').count(), 0)
    const likeCard = page.locator('[data-post-id="101"]')
    await likeCard.locator('.waterfall-like').click()
    await page.waitForFunction(() => document.querySelector('[data-post-id="101"] .waterfall-like')?.classList.contains('liked'))
    assert.equal(await likeCard.locator('.waterfall-like').innerText(), '526')
    assert.equal(await page.locator('.post-detail').count(), 0)
    assert.deepEqual(await membership(page), before, 'likes must not move cards')
    await page.locator('[data-post-id="106"] .waterfall-cover').scrollIntoViewIfNeeded()
    const position = await scroll(page).evaluate(el => el.scrollTop)
    await page.locator('[data-post-id="106"] .waterfall-cover').click()
    await page.locator('.post-detail .detail-scroll').waitFor()
    assert(!page.url().includes('view=media'), 'waterfall image opens post detail')
    assert.equal(await page.locator('.image-viewer').count(), 0)
    assert.equal(await page.locator('.detail-title').innerText(), posts[5].title)
    await page.locator('.detail-image').first().click()
    await page.locator('.image-counter').waitFor()
    await page.locator('.full-image img').waitFor()
    await page.waitForFunction(() => { const image = document.querySelector('.full-image img'); return image?.naturalHeight > 0 })
    const originalRatio = await page.locator('.full-image img').evaluate(image => image.naturalWidth / image.naturalHeight)
    assert(Math.abs(originalRatio - .25) < .001, 'detail retains the full long image')
    await page.locator('.viewer-top [aria-label="返回"]').click()
    await page.locator('.image-viewer').waitFor({ state: 'detached' })
    await page.locator('.post-detail [aria-label="返回"]').first().click(); await ready(page)
    assert(Math.abs(await scroll(page).evaluate(el => el.scrollTop) - position) <= 1, 'detail return preserves scroll')
    assert.deepEqual(await membership(page), before)
    await page.locator('[data-post-id="102"] .waterfall-cover').click()
    await page.locator('.post-detail .detail-scroll').waitFor()
    assert(!page.url().includes('view=media'), 'waterfall video opens post detail')
    assert.equal(await page.locator('.detail-title').innerText(), posts[1].title)
    await page.locator('.post-detail [aria-label="返回"]').first().click(); await ready(page)
    await page.locator('[data-post-id="103"] .waterfall-author').click()
    await page.locator('.post-detail .detail-scroll').waitFor()
    assert(!page.url().includes('view=media'), 'waterfall author strip opens post detail')
    assert.equal(await page.locator('.detail-title').innerText(), posts[2].title)
    await page.locator('.post-detail [aria-label="返回"]').first().click(); await ready(page)
    await page.locator('[data-post-id="104"] .waterfall-identity-name').click()
    await page.locator('.post-detail .detail-scroll').waitFor()
    assert.equal(await page.locator('.detail-title').innerText(), posts[3].title, 'waterfall nickname opens its own post detail')
    await page.locator('.post-detail [aria-label="返回"]').first().click(); await ready(page)
    failPageTwo = true
    await scroll(page).evaluate(el => { el.scrollTop = el.scrollHeight })
    await page.locator('.home-view .load-tip').filter({ hasText: '分页暂不可用' }).waitFor()
    assert.deepEqual(await membership(page), before)
    failPageTwo = false
    await page.locator('.home-view .load-tip').locator('[aria-label="重试"]').click(); await ready(page, posts.length)
    const after = await membership(page)
    before.forEach((column, index) => assert.deepEqual(after[index].slice(0, column.length), column, 'pagination keeps existing membership'))
    assert.equal(new Set(after.flat()).size, posts.length, 'duplicate page record is ignored')
    await page.waitForFunction(() => document.querySelector('[data-post-id="111"] .waterfall-cover-placeholder'))
    assert.equal(await page.locator('[data-post-id="109"] .waterfall-cover').count(), 0)
    assert.equal(await page.locator('[data-post-id="114"] .waterfall-duration').innerText(), '00:11')
    const channelRequest = page.waitForRequest(request => request.url().includes('/app/post/list') && new URL(request.url()).searchParams.get('channelId') === '2')
    await page.locator('.home-compact .channel-tab').nth(1).click()
    await channelRequest; await ready(page)
    assert.equal(await page.locator('.feed-card .channel-tabs .channel-tab.active').textContent(), '同人图')
    await page.evaluate(() => {
      document.querySelector('.home-view .screen-scroll').__vueParentComponent.emit('refresherrefresh')
    })
    await ready(page)
    assert.equal(await page.locator('.home-view .post-card').count(), 0, 'refresh keeps waterfall layout')
    await page.locator('.tab-bar .tab-item').nth(1).click()
    await page.locator('.follow-view .post-card').first().waitFor()
    assert.equal(await page.locator('.follow-view .waterfall-post').count(), 0)
    await settings(page); await choose(page, '单列布局'); await home(page)
    await page.locator('.home-view .post-card').first().waitFor()
    assert.equal(await page.locator('.home-view .post-card .action.liked').first().innerText(), '526')
    serverDefault = 'waterfall'; await page.reload()
    await page.locator('.home-view .post-card').first().waitFor()
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_feed_layout')), 'single', 'explicit single wins over waterfall default')
    await settings(page); await choose(page, '双列瀑布布局')
    await page.locator('.settings-row', { hasText: '清除缓存' }).click()
    await page.locator('.dialog-layer.open').waitFor()
    await page.locator('.dialog-button.confirm').click()
    await page.waitForTimeout(400)
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_feed_layout')), 'waterfall')
    await page.locator('.settings-row', { hasText: '退出登录' }).click()
    await page.locator('.dialog-layer.open').waitFor()
    await page.locator('.dialog-button.confirm').click(); await ready(page)
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_feed_layout')), 'waterfall', 'logout preserves preference')
    await page.reload(); await ready(page)
    await page.evaluate(() => uni.removeStorageSync('settings_feed_layout'))
    serverDefault = 'single'; await page.reload()
    await page.locator('.home-view .post-card').first().waitFor()
    serverDefault = 'waterfall'; await settings(page); await home(page); await ready(page)
    assert.equal(await page.evaluate(() => uni.getStorageSync('settings_feed_layout')), '', 'server default is not persisted as manual preference')
    failConfig = true; await page.reload(); await ready(page)
    failConfig = false; serverDefault = undefined; await page.reload()
    await page.locator('.home-view .post-card').first().waitFor()
    serverDefault = 'invalid'; await page.reload()
    await page.locator('.home-view .post-card').first().waitFor()
    await context.close()
    serverDefault = 'waterfall'
    const widths = [320, 375, 390, 393, 414, 430]
    for (const width of widths) {
      const context = await browser.newContext({ viewport: { width, height: Math.round(width * 2.2) }, isMobile: true, hasTouch: true })
      const page = await context.newPage(); await fixtures(page); await page.goto(origin); await ready(page)
      await page.waitForFunction(() => {
        const images = [...document.querySelectorAll('.home-view .waterfall-image > div')]
        return images.length === 9 && images.every(image => getComputedStyle(image).backgroundImage !== 'none')
      })
      const geometry = await cards(page).evaluateAll(nodes => nodes.map(node => {
        const rect = node.getBoundingClientRect(), cover = node.querySelector('.waterfall-cover')?.getBoundingClientRect()
        return { id: node.dataset.postId, x: rect.x, width: rect.width, radius: parseFloat(getComputedStyle(node).borderRadius), ratio: cover ? cover.width / cover.height : null }
      }))
      const expectedWidth = (750 - 48) / 2 * width / 750
      geometry.forEach(card => {
        assert(Math.abs(card.width - expectedWidth) < 1)
        assert(Math.abs(card.radius - 18 * width / 750) < 1)
        if (card.ratio) {
          const image = posts.find(post => post.id === card.id).media[0]
          const expectedRatio = image.aspectRatio || image.coverAspectRatio || 1
          assert(Math.abs(card.ratio - expectedRatio) < .01, `cover ${card.id} keeps its original ratio`)
        }
      })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width)
      await scroll(page).evaluate(el => { el.scrollTop = 245 * innerWidth / 390 })
      await page.waitForTimeout(150)
      await page.screenshot({ path: path.join(output, `${width}-waterfall.png`) })
      await context.close()
    }
    assert.equal(JSON.stringify(posts.map(post => post.media)), originals, 'source media metadata unchanged')
    assert.deepEqual(errors, [])
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ passed: true, widths, persistence: true, defaults: true, pagination: true, detailReturn: true, interactions: true, errors }, null, 2))
    console.log('Waterfall passed: six widths, settings/default precedence, persistence, pagination/retry, interactions and detail return.')
  } finally { await browser.close() }
}
module.exports = { fixtures }
if (require.main === module) run().catch(error => { console.error(error); process.exitCode = 1 })

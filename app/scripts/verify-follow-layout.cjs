const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), ts = require('typescript')
const root = path.resolve(__dirname, '..')
const output = path.join(root, 'artifacts/follow-reference')
const base = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
const compiled = file => ts.transpileModule(fs.readFileSync(path.join(root, file), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const mediaModule = { exports: {} }
new Function('exports', 'require', compiled('utils/media-layout.uts'))(mediaModule.exports, require)
const followModule = { exports: {} }
new Function('exports', 'require', compiled('utils/follow-layout.uts'))(followModule.exports, () => mediaModule.exports)
const { getFollowMediaLayout, followPostHeight, followPublishContext, FOLLOW_CONTENT_WIDTH, FOLLOW_MEDIA_GAP } = followModule.exports
const now = Date.now()
const image = (id, ratio = 1, type = 'image') => ({ id, type, aspectRatio: ratio, layout: ratio > 1 ? 'wide' : 'portrait', url: 'http://follow-layout.test/' + id + '.svg', coverUrl: 'http://follow-layout.test/' + id + '.svg', duration: type === 'video' ? '00:45' : undefined })
const makePost = (id, media, title, content = '') => ({ id: String(id), authorId: '100', authorNickname: '喵喵五岁啦', authorLevel: 12, title, content, status: 1, date: '10-25', createTime: new Date(now - 3 * 3600000).toISOString(), channelName: '崩坏：星穹铁道', mode: media[0]?.type === 'video' ? 'video' : 'image', media, tags: ['Cosplay', 'cos正片', '令人心动的COS', '知更鸟·晴歌', '知更鸟'], likeCount: 196, commentCount: 17, following: true })
const posts = [
  makePost(501, [image(1), image(2, .75), image(3, 2)], '天凉了哦，会想念和我一起的看花的夏天吗？'),
  makePost(502, [image(4, 16 / 9, 'video')], '不死鸟与六翼天使 菲欧妮设定全解析！《绝区零》'),
  makePost(503, [image(5, .89, 'video')], '薇斯纳助力达妮卡成为全冬最强女仆！', '听说这是她们还在队长麾下训练时的故事。'),
  makePost(504, [image(6), image(7, .5)], '两张图片等宽并排'),
  makePost(505, [image(8), image(9), image(10), image(11), image(12)], '超过三张，最后一格显示剩余数量'),
  makePost(506, [image(13, .75)], '单张竖图保留原比例'),
  makePost(507, [], '没有媒体的帖子', '长正文需要在两行后截断。'.repeat(30)),
  makePost(508, [], '   ', '  '),
  makePost(509, [image(14, 9 / 16, 'video')], '竖向视频等比缩小、靠左显示')
]
const snapshot = JSON.stringify(posts)
for (const post of posts) {
  post.media.forEach(Object.freeze); Object.freeze(post.media)
  const layout = getFollowMediaLayout(post.media)
  assert(Number.isFinite(followPostHeight({ ...post, author: {} })))
  assert.equal(layout.cells.length, Math.min(post.media.length, 3))
  layout.cells.forEach(cell => { assert(cell.left + cell.width <= FOLLOW_CONTENT_WIDTH + .001); assert(cell.top + cell.height <= layout.height + .001) })
  if (post.media.length > 2) {
    assert.equal(layout.cells[1].top, 0)
    assert.equal(layout.cells[2].top, layout.cells[1].height + FOLLOW_MEDIA_GAP)
    assert.equal(layout.remaining, post.media.length - 3)
  }
  if (post.media[0]?.type === 'video' && post.media[0].aspectRatio < 1) {
    assert.equal(layout.height, 560, 'Portrait video must respect the agreed height cap')
    assert(Math.abs(layout.width / layout.height - post.media[0].aspectRatio) < .0001, 'Portrait video must retain its original ratio')
  }
}
assert.equal(JSON.stringify(posts), snapshot, 'Layout must not mutate original media')
assert.equal(followPublishContext({ author: {}, createTime: '2026-10-25 10:00:00', channelName: '原神' }, Date.parse('2026-10-25T13:00:00+08:00')), '3小时前·在原神发布了帖子')
assert.equal(followPublishContext({ author: {}, createTime: 'invalid', date: '10-25' }), '10-25·发布了帖子')
const near = (actual, expected, name) => assert(Math.abs(actual - expected) < 1, `${name}: ${actual} != ${expected}`)
async function cardAt(page, id) {
  await page.locator('.follow-view .virtual-post-column').evaluate((el, id) => {
    let vm = el.__vueParentComponent
    while (vm && !vm.setupState.offsets) vm = vm.parent
    const scroll = [...el.closest('.screen-scroll').querySelectorAll('.uni-scroll-view')].find(n => /auto|scroll/.test(getComputedStyle(n).overflowY))
    const origin = el.getBoundingClientRect().top - scroll.getBoundingClientRect().top + scroll.scrollTop
    scroll.scrollTop = origin + vm.setupState.offsets[id - 501]
  }, id)
  const card = page.locator(`.follow-view [data-virtual-id="${id}"] .follow-post-card`)
  await card.waitFor()
  return card
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const errors = [], failures = [], results = []
  try {
    for (const width of [320, 390, 430]) {
      const context = await browser.newContext({ viewport: { width, height: Math.round(width * 2.2) }, isMobile: true, hasTouch: true })
      const page = await context.newPage()
      page.on('pageerror', e => errors.push(e.message))
      page.on('response', r => { if (r.url().includes('/static/') && r.status() >= 400) failures.push(r.url()) })
      let liked = false, likes = 0
      await page.route('**/app/**', async route => {
        const url = new URL(route.request().url())
        let data = []
        if (url.pathname === '/app/home/config') data = { channels: [{ id: 1, name: '推荐' }], entries: [], news: { enabled: false, items: [] }, defaultFeedLayout: 'single' }
        if (['/app/post/list', '/app/post/following'].includes(url.pathname)) data = { records: posts, pages: 1, total: posts.length, size: 10, current: 1 }
        if (/^\/app\/post\/\d+$/.test(url.pathname)) data = { ...posts.find(p => p.id === url.pathname.split('/').pop()), liked, likeCount: liked ? 197 : 196 }
        if (url.pathname === '/app/comment/config') data = { imageMax: 3, contentMax: 1000, emojis: [], emojiGroups: [] }
        if (url.pathname.endsWith('/comments')) data = { records: [], pages: 0, total: 0 }
        if (url.pathname === '/app/interaction/toggleLike') { likes++; liked = !liked; data = { active: liked, count: liked ? 197 : 196 } }
        await route.fulfill({ json: { code: '0000', data }, headers: { 'Access-Control-Allow-Origin': '*' } })
      })
      await page.route('http://follow-layout.test/**', route => {
        const id = Number(new URL(route.request().url()).pathname.match(/\d+/)[0])
        const media = posts.flatMap(p => p.media).find(m => m.id === id)
        const file = path.join(output, 'fixtures', id + '.jpg')
        if (fs.existsSync(file)) return route.fulfill({ contentType: 'image/jpeg', body: fs.readFileSync(file), headers: { 'Access-Control-Allow-Origin': '*' } })
        const height = 600 / media.aspectRatio
        return route.fulfill({ contentType: 'image/svg+xml', headers: { 'Access-Control-Allow-Origin': '*' }, body: `<svg xmlns="http://www.w3.org/2000/svg" width="600" height="${height}"><rect width="600" height="${height}" fill="#c1d8e8"/><circle cx="300" cy="${height / 2}" r="120" fill="#8db6cf"/></svg>` })
      })
      await page.goto(base)
      await page.evaluate(() => { uni.setStorageSync('app_token', 'follow-layout-fixture'); uni.setStorageSync('app_user_info', { userId: '10', nickname: '验收', tags: [] }) })
      await page.reload()
      await page.locator('.tab-item').nth(1).click()
      await page.locator('.follow-post-card').first().waitFor()
      const scale = width / 750
      const measurements = []
      for (const post of posts) {
        const card = await cardAt(page, Number(post.id))
        await page.waitForTimeout(100)
        const actual = await card.evaluate(el => {
          const box = e => { if (!e) return null; const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height } }
          const style = getComputedStyle(el)
          return { card: box(el), padding: parseFloat(style.paddingLeft), border: parseFloat(style.borderBottomWidth), author: box(el.querySelector('.follow-author-row')), gallery: box(el.querySelector('.follow-gallery')), cells: [...el.querySelectorAll('.follow-media-cell')].map(box), actions: box(el.querySelector('.follow-actions')), tags: box(el.querySelector('.follow-tags')), nameColor: getComputedStyle(el.querySelector('.follow-author-name')).color }
        })
        near(actual.card.width, width, 'card width')
        near(actual.padding, 31.944 * scale, 'gutter')
        near(actual.border, 1.389 * scale, 'divider')
        near(actual.author.height, 92 * scale, 'author row')
        near(actual.actions.height, 64 * scale, 'action row')
        near(actual.tags.height, 35 * scale, 'single-line tags')
        assert.equal(await card.locator('.follow-title').count(), post.title.trim() ? 1 : 0)
        assert.equal(await card.locator('.follow-body').count(), post.content.trim() ? 1 : 0)
        const layout = getFollowMediaLayout(post.media)
        assert.equal(actual.cells.length, layout.cells.length)
        assert.equal(await card.locator('.follow-more-count').count(), layout.remaining ? 1 : 0)
        if (layout.remaining) assert.equal(await card.locator('.follow-more-count').innerText(), '+' + layout.remaining)
        if (actual.gallery) {
          near(actual.gallery.width, layout.width * scale, 'media width')
          near(actual.gallery.height, layout.height * scale, 'media height')
          near(actual.gallery.x, actual.card.x + 31.944 * scale, 'media left alignment')
          if (post.media[0]?.type === 'video' && post.media[0].aspectRatio < 1) {
            near(actual.gallery.height, 560 * scale, 'portrait video maximum height')
            near(actual.gallery.width, 560 * post.media[0].aspectRatio * scale, 'portrait video proportional width')
          }
          actual.cells.forEach((cell, i) => { const expected = layout.cells[i]; near(cell.x - actual.gallery.x, expected.left * scale, 'cell x'); near(cell.y - actual.gallery.y, expected.top * scale, 'cell y'); near(cell.width, expected.width * scale, 'cell width'); near(cell.height, expected.height * scale, 'cell height') })
        }
        if (post.media[0]?.type === 'video') { assert.equal(await card.locator('.follow-play').count(), 1); assert.equal(await card.locator('.follow-video-duration').innerText(), '00:45'); assert.equal(await card.locator('video').count(), 0) }
        assert(await card.locator('.follow-publish-context').innerText().then(s => /小时前·在崩坏：星穹铁道发布了帖子/.test(s)))
        measurements.push({ id: post.id, ...actual })
        if (width === 390 && post.id === '509') {
          await card.scrollIntoViewIfNeeded()
          await card.screenshot({ path: path.join(output, 'portrait-video-390.png') })
        }
      }
      const first = await cardAt(page, 501)
      await first.scrollIntoViewIfNeeded()
      await first.locator('[aria-label="点赞"]').click()
      await first.locator('[aria-label="取消点赞"]').waitFor()
      assert.equal(likes, 1)
      assert(!page.url().includes('pages/post'), 'Like must not navigate')
      await first.locator('[aria-label="取消点赞"]').click()
      await first.locator('[aria-label="点赞"]').waitFor()
      assert.equal(likes, 2)
      await first.locator('.follow-media-cell').first().click()
      await page.locator('.post-detail').waitFor()
      assert(new URL(page.url()).hash.includes('id=501'), 'Image opens correct post')
      await page.locator('.post-detail [aria-label="返回"]').first().click()
      await page.locator('.follow-post-card').first().waitFor()
      await (await cardAt(page, 501)).locator('[aria-label="查看评论"]').click()
      await page.locator('.post-detail').waitFor()
      assert(page.url().includes('anchor=comments'))
      await page.locator('.post-detail [aria-label="返回"]').first().click()
      await page.locator('.follow-view > .screen-scroll').evaluate(host => {
        const scroll = [...host.querySelectorAll('.uni-scroll-view')].find(n => /auto|scroll/.test(getComputedStyle(n).overflowY))
        scroll.scrollTop = 0
      })
      await page.waitForTimeout(180)
      await page.screenshot({ path: path.join(output, `follow-${width}.png`) })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width)
      results.push({ width, measurements, navigation: 'passed', likes: 'passed' })
      await context.close()
    }
    assert.deepEqual(errors, [])
    assert.deepEqual(failures, [])
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ status: 'passed', scope: 'H5 fixtures; native resource compilation checked separately', mediaUnchanged: true, results, errors, failures }, null, 2))
    console.log('Follow layout passed: 3 widths, 9 post types, 560rpx portrait video cap with preserved ratio and left alignment, original media preserved, mosaic/counts, video ratios, tags, likes and post/comment navigation.')
  } finally { await browser.close() }
})().catch(e => { console.error(e); process.exitCode = 1 })

const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const origin = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
const output = path.resolve(__dirname, '../artifacts/pagination-loading')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const post = id => ({ id: String(id), authorId: '20', authorNickname: '分页测试', authorLevel: 2,
  title: '分页帖子 ' + id, content: '保留已有内容，等待下一页。'.repeat(6), mode: 'post', media: [], tags: [], commentCount: 40, likeCount: 0 })
const comment = (id, parentId = null) => ({ id: String(id), postId: '101', parentId, authorId: '20', authorNickname: '评论作者',
  authorLevel: 2, content: '分页评论 ' + id, contentType: 'text', createTime: '2026-10-26 08:00:00', region: '上海',
  likeCount: 0, dislikeCount: 0, pinned: false, deleted: false, canDelete: false, replyCount: parentId ? 0 : 40, media: [], replies: [] })
const rows = (make, start, count) => Array.from({ length: count }, (_, i) => make(start + i))
const pageData = (records, current, pages = 2) => ({ records, current, pages, size: records.length, total: 40 })

async function fixture(browser, layout = 'single') {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  const page = await context.newPage(), calls = [], errors = [], pending = []
  let hold = '', fail = ''
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/app/**', async route => {
    const url = new URL(route.request().url()), endpoint = url.pathname
    const current = Number(url.searchParams.get('pageNum') || 1)
    const kind = endpoint.endsWith('/replies') ? 'replies' : endpoint.endsWith('/comments') ? 'comments' : endpoint === '/app/post/list' ? 'home' : ''
    if (kind) {
      calls.push({ kind, current, sort: url.searchParams.get('sort'), channel: url.searchParams.get('channelId') })
      if (hold === kind) await new Promise(resolve => pending.push(resolve))
      if (fail === kind) return route.fulfill({ json: { code: '1000', msg: '分页暂不可用' } })
    }
    let data = {}
    if (endpoint === '/app/home/config') data = { channels: [{ id: 1, name: '推荐' }, { id: 2, name: '频道二' }], entries: [], defaultFeedLayout: layout, news: { enabled: false, items: [] } }
    else if (kind === 'home') data = pageData(rows(post, current === 1 ? 101 : 111, 10), current)
    else if (endpoint === '/app/post/101') data = post(101)
    else if (kind === 'comments') data = pageData(rows(comment, current === 1 ? 1 : 21, 20), current)
    else if (kind === 'replies') data = pageData(rows(id => comment(id, '1'), current === 1 ? 1001 : 1021, 20), current)
    else if (endpoint === '/app/media/config') data = { cacheMaxBytes: 16777216 }
    else if (endpoint === '/app/comment/config') data = { imageMax: 3, contentMax: 2000, emojiGroups: [], emojis: [] }
    else if (/categories|conversations/.test(endpoint)) data = []
    else if (/following|favorites|mine/.test(endpoint)) data = pageData([], 1, 0)
    await route.fulfill({ json: { code: '0000', msg: 'ok', data } })
  })
  return { page, context, calls, errors, hold: value => { hold = value }, fail: value => { fail = value },
    release: () => { hold = ''; pending.splice(0).forEach(resolve => resolve()) },
    count: kind => calls.filter(call => call.kind === kind).length }
}
async function bottom(page, selector) {
  await page.locator(selector + ' > .uni-scroll-view > .uni-scroll-view').evaluate(el => { el.scrollTop = el.scrollHeight })
}
async function exposed(page, selector, code) {
  return page.locator(selector).evaluate((el, code) => {
    let vm = el.__vueParentComponent
    while (vm && !(vm.setupState.loadMoreOnReachBottom)) vm = vm.parent
    return Function('s', code)(vm.setupState)
  }, code)
}
async function spinner(page, selector, name) {
  const ring = page.locator(selector + ' .pagination-loading-ring')
  await ring.waitFor()
  const style = await ring.evaluate(el => {
    const s = getComputedStyle(el)
    return { color: s.borderTopColor, duration: s.animationDuration, width: parseFloat(s.width), name: s.animationName, transform: s.transform }
  })
  assert.equal(style.color, 'rgb(0, 195, 255)')
  assert.equal(style.duration, '0.8s')
  assert(Math.abs(style.width - 36 * 390 / 750) < 1)
  await pause(120)
  assert.notEqual(await ring.evaluate(el => getComputedStyle(el).transform), style.transform, 'Ring rotates')
  const position = await ring.evaluate(el => {
    const r = el.getBoundingClientRect(), scroll = el.closest('uni-scroll-view').getBoundingClientRect()
    return { top: r.top, bottom: r.bottom, viewportTop: scroll.top, viewportBottom: scroll.bottom }
  })
  assert(position.top >= position.viewportTop && position.bottom <= position.viewportBottom, 'Spinner must be visible inside the scroll viewport: ' + JSON.stringify(position))
  await page.screenshot({ path: path.join(output, name + '.png') })
}
async function home(browser, layout) {
  const f = await fixture(browser, layout), { page } = f
  try {
    await page.goto(origin)
    await page.waitForFunction(() => {
      let vm = document.querySelector('.home-view')?.__vueParentComponent
      while (vm && !vm.setupState.feedReveal) vm = vm.parent
      return vm?.setupState.feedReveal.phase.value === 'idle'
    })
    assert.equal(await page.locator('.home-view .pagination-loading').count(), 0)
    f.hold('home')
    await bottom(page, '.home-view > .screen-scroll')
    await spinner(page, '.home-view .pagination-tail', 'home-' + layout)
    const before = f.count('home')
    await exposed(page, '.home-view', 's.loadMoreOnReachBottom(); s.loadMoreOnReachBottom()')
    assert.equal(f.count('home'), before, 'Concurrent bottom events share the pending page')
    assert(await page.locator(layout === 'single' ? '.home-view .post-card' : '.home-view .waterfall-post').count() > 0)
    await page.emulateMedia({ reducedMotion: 'reduce' })
    assert.equal(await page.locator('.pagination-loading-ring').evaluate(el => getComputedStyle(el).animationName), 'none')
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    f.fail('home'); f.release()
    await page.locator('.home-view .load-tip', { hasText: '分页暂不可用' }).waitFor()
    assert.equal(await page.locator('.home-view .pagination-loading').count(), 0)
    await exposed(page, '.home-view', 's.loadMoreOnReachBottom()')
    assert.equal(f.count('home'), before, 'Failure waits for manual retry')
    f.fail(''); f.hold('home')
    await page.locator('.home-view .load-tip [aria-label="重试"]').click()
    await spinner(page, '.home-view .pagination-tail', 'home-' + layout + '-retry')
    f.release()
    await page.locator('.home-view .load-tip', { hasText: '已经到底了' }).waitFor()
    const done = f.count('home')
    await exposed(page, '.home-view', 's.loadMoreOnReachBottom()')
    assert.equal(f.count('home'), done)
    assert.deepEqual(f.errors, [])
    return { layout, calls: f.calls, status: 'passed' }
  } finally { f.release(); await f.context.close() }
}
async function comments(browser, panel) {
  const f = await fixture(browser), { page } = f
  const area = panel ? '.comments-scroll' : '.detail-scroll'
  try {
    await page.goto(origin + '/#/pages/post/index?id=101')
    await page.locator('.detail-scroll .comment-item').first().waitFor()
    if (panel) {
      await page.evaluate(() => {
        let vm = document.querySelector('.post-detail').__vueParentComponent
        while (vm && !('commentsVisible' in vm.setupState)) vm = vm.parent
        vm.setupState.commentsVisible = true
      })
      await page.locator('.comments-scroll .comment-item').first().waitFor()
    }
    assert.equal(await page.locator(area + ' .comment-end').count(), 0, 'No premature end marker')
    f.hold('comments')
    await bottom(page, area)
    await spinner(page, area + ' .comment-pagination', panel ? 'comments-panel' : 'comments-detail')
    const before = f.count('comments')
    await exposed(page, area + ' .post-comments', 's.loadMoreOnReachBottom(); s.loadMoreOnReachBottom()')
    assert.equal(f.count('comments'), before)
    assert.equal(await page.locator(area + ' .comment-item').count(), 20)
    f.fail('comments'); f.release()
    await page.locator(area + ' [aria-label="重试加载评论"]').waitFor()
    await exposed(page, area + ' .post-comments', 's.loadMoreOnReachBottom()')
    assert.equal(f.count('comments'), before)
    f.fail(''); f.hold('comments')
    await page.locator(area + ' [aria-label="重试加载评论"]').click()
    await page.locator(area + ' .pagination-loading-ring').waitFor()
    f.release()
    await page.locator(area + ' .comment-end').waitFor()
    assert.equal(await page.locator(area + ' .comment-item').count(), 40)
    const done = f.count('comments')
    await exposed(page, area + ' .post-comments', 's.loadMoreOnReachBottom()')
    assert.equal(f.count('comments'), done)
    const first = page.locator(area + ' .comment-item').first()
    f.hold('replies')
    await first.locator('.reply-preview').click()
    await spinner(page, area + ' .reply-loading', 'replies-' + panel)
    assert.equal(await first.locator('.pagination-loading-ring').count(), 1)
    assert.equal(await first.locator('.more-replies').count(), 0)
    f.release()
    await first.locator('.nested-reply').first().waitFor()
    f.hold('replies')
    await first.locator('.more-replies').click()
    await first.locator('.pagination-loading-ring').waitFor()
    f.fail('replies'); f.release()
    await first.locator('[aria-label="重试加载回复"]').waitFor()
    assert.equal(await first.locator('.nested-reply').count(), 20)
    f.fail(''); f.hold('replies')
    await first.locator('[aria-label="重试加载回复"]').click()
    await first.locator('.pagination-loading-ring').waitFor()
    // Reset while an old reply request is pending: the old thread cannot repopulate the new sort.
    await exposed(page, area + ' .post-comments', "s.sort = 'latest'; s.reset()")
    await page.locator(area + ' .comment-item').first().waitFor()
    f.release()
    await pause(150)
    assert.equal(await page.locator(area + ' .nested-reply').count(), 0)
    assert.equal(await page.locator(area + ' .pagination-loading-ring').count(), 0)
    if (panel) {
      f.hold('comments')
      await bottom(page, area)
      await page.locator(area + ' .pagination-loading-ring').waitFor()
      await page.locator('.comments-panel-heading [aria-label="关闭"]').click()
      assert.equal(await page.locator('.comments-scroll').count(), 0)
      f.release()
    }
    assert.deepEqual(f.errors, [])
    return { panel, calls: f.calls, status: 'passed' }
  } finally { f.release(); await f.context.close() }
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const results = []
  try {
    for (const layout of ['single', 'waterfall']) results.push(await home(browser, layout))
    for (const panel of [false, true]) results.push(await comments(browser, panel))
    fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify({ status: 'passed', results }, null, 2))
    console.log(JSON.stringify({ status: 'passed', scenarios: results.length }))
  } catch (error) {
    fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify({ status: 'failed', results, error: String(error) }, null, 2))
    throw error
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

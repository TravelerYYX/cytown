const { chromium } = require('playwright-core')
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const { fixture } = require('../../scripts/user-profile-fixture.cjs')
const origin = process.env.UI_TEST_URL || 'http://127.0.0.1:15274'
const output = path.resolve(__dirname, '../artifacts/feed-autoplay')
const mediaOrigin = 'http://feed-autoplay.test'
const videoFile = path.resolve(__dirname, '../../artifacts/chat-admin-20261026/fixture-video.mp4')
const state = fixture(), errors = [], results = []
const posts = Array.from({ length: 8 }, (_, index) => ({ ...state.post(index), id: String(7000 + index), title: '自动播放验收 ' + index,
  mode: 'video', media: [{ assetId: 7000 + index, type: 'video', layout: 'wide', aspectRatio: 16 / 9, status: 'ready', resourceVersion: 'autoplay-v1',
    url: `${mediaOrigin}/${index}.mp4`, coverUrl: `${mediaOrigin}/cover.svg`, durationMs: 2000, duration: '00:02' }] }))
const videos = page => page.locator('.feed-video-layer video')
async function waitPlaying(page, selector) {
  await page.waitForFunction(selector => {
    const video = document.querySelector(selector + ' .feed-video-layer video')
    return video && !video.paused && video.currentTime > 0 && video.muted && video.loop
  }, selector)
  assert.equal(await videos(page).count(), 1, 'Only one mounted list decoder')
}
async function center(page, selector, index = 0) {
  const card = page.locator(selector + ' .feed-video-layer').nth(index)
  await card.evaluate(el => { let pane = el.parentElement; while (pane && !(['auto','scroll'].includes(getComputedStyle(pane).overflowY) && pane.scrollHeight > pane.clientHeight)) pane = pane.parentElement; if (!pane) throw Error('Missing vertical scroller'); const rect = el.getBoundingClientRect(), bounds = pane.getBoundingClientRect(); pane.scrollTop += rect.top + rect.height / 2 - bounds.top - bounds.height / 2 })
  await page.waitForTimeout(400)
}
async function setting(page, label, value) {
  await page.evaluate(() => uni.navigateTo({ url: '/pages/settings/index' }))
  await page.locator('.settings-page').waitFor()
  await page.locator('.settings-row', { hasText: label }).click()
  await page.locator('.sheet-layer.open').waitFor()
  await page.locator('.sheet-option', { hasText: value }).click()
  await page.locator('.sheet-layer').waitFor({ state: 'detached' })
  assert.equal(await videos(page).count(), 0, 'Hidden list releases decoder')
  await page.locator('.settings-header [aria-label="返回"]').click()
  await page.locator('.app-shell').waitFor()
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  let page
  try {
    page = await browser.newPage({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => {
      const connection = new EventTarget(); connection.type = 'wifi'; connection.effectiveType = ''
      Object.defineProperty(navigator, 'connection', { value: connection, configurable: true })
      window.setTestNetwork = type => { connection.type = type; connection.effectiveType = type === 'cellular' ? '4g' : ''; connection.dispatchEvent(new Event('change')) }
      window.peakListDecoders = 0
      new MutationObserver(() => { window.peakListDecoders = Math.max(window.peakListDecoders, document.querySelectorAll('.feed-video-layer video').length) }).observe(document, { subtree: true, childList: true })
    })
    let mediaRequests = 0, failVideo = false
    await page.route(mediaOrigin + '/**', async route => {
      if (route.request().url().endsWith('.svg')) return route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360"><rect width="640" height="360" fill="#a4cadb"/></svg>' })
      mediaRequests++
      if (failVideo) return route.fulfill({ status: 503, body: '' })
      return route.fulfill({ path: videoFile, contentType: 'video/mp4', headers: { 'Access-Control-Allow-Origin': '*' } })
    })
    await page.route('**/app/**', async route => {
      const url = new URL(route.request().url()), endpoint = url.pathname
      let data
      if (endpoint === '/app/home/config') data = { channels: [{ id: 1, name: '发现' }, { id: 2, name: '同人' }], entries: [], news: { enabled: false, items: [] }, defaultFeedLayout: 'single' }
      else if (['/app/post/list', '/app/post/following', '/app/post/mine', '/app/post/favorites'].includes(endpoint) || /\/user\/\d+\/posts$/.test(endpoint)) data = { records: endpoint === '/app/post/mine' ? posts.map(post => ({...post,authorId:state.owner})) : posts, current: 1, pages: 1, total: posts.length, size: 10 }
      else if (/^\/app\/post\/\d+$/.test(endpoint)) data = posts.find(post => post.id === endpoint.split('/').pop())
      else if (endpoint.endsWith('/comments')) data = { records: [], current: 1, pages: 0, total: 0 }
      else if (endpoint === '/app/comment/config') data = { emojis: [], emojiGroups: [], imageMax: 3, contentMax: 1000 }
      else return route.fulfill(await state.handle(route.request().url(), route.request().method()))
      return route.fulfill({ json: { code: '0000', data } })
    })
    await page.goto(origin)
    await page.locator('.home-view .post-card').first().waitFor()
    await page.evaluate(user => { uni.setStorageSync('app_token', 'autoplay-fixture'); uni.setStorageSync('app_user_info', user) }, state.user(state.owner))
    await page.reload()
    await page.locator('.home-view .post-card').first().waitFor()
    await center(page, '.home-view')
    await waitPlaying(page, '.home-view')
    const firstSource = await videos(page).getAttribute('src')
    await videos(page).evaluate(video => { window.firstListVideo = video; window.firstListSource = video.src; video.currentTime = Math.max(0, video.duration - .15) })
    await page.waitForTimeout(650)
    assert(await videos(page).evaluate(video => !video.paused && video.currentTime < video.duration - .1), 'Video loops at the end')
    await center(page, '.home-view', 2)
    await waitPlaying(page, '.home-view')
    assert.notEqual(await videos(page).getAttribute('src'), firstSource)
    assert(await page.evaluate(() => !window.firstListVideo.isConnected && window.firstListVideo.paused && !window.firstListVideo.hasAttribute('src')), 'Old decoder released')
    await center(page, '.home-view', 0)
    await waitPlaying(page, '.home-view')
    assert(await videos(page).evaluate(video => video.currentTime < 1.5), 'Reentry starts at zero')
    await page.screenshot({ path: path.join(output, 'home-playing.png') })
    results.push('home: muted, one decoder, looping, switching, release, restart')

    await page.evaluate(() => window.setTestNetwork('cellular'))
    await page.waitForTimeout(300); assert.equal(await videos(page).count(), 0)
    const requestsOff = mediaRequests
    await center(page, '.home-view', 2); assert.equal(mediaRequests, requestsOff)
    await setting(page, '列表视频自动播放', '移动网络和Wi-Fi')
    await waitPlaying(page, '.home-view')
    await page.evaluate(() => window.setTestNetwork('none'))
    await page.waitForTimeout(300); assert.equal(await videos(page).count(), 0)
    await page.evaluate(() => window.setTestNetwork('unknown'))
    await page.waitForTimeout(300); assert.equal(await videos(page).count(), 0)
    await page.evaluate(() => window.setTestNetwork('wifi'))
    await waitPlaying(page, '.home-view')
    await setting(page, '列表视频自动播放', '关闭')
    await page.waitForTimeout(300); assert.equal(await videos(page).count(), 0)
    await setting(page, '列表视频自动播放', '仅Wi-Fi')
    await waitPlaying(page, '.home-view')
    results.push('network and setting changes: wifi/mobile/offline/unknown/off, hidden page release')

    await page.locator('.tab-bar .tab-item').nth(1).click()
    await page.locator('.follow-post-card').first().waitFor()
    await center(page, '.follow-view'); await waitPlaying(page, '.follow-view')
    await page.screenshot({ path: path.join(output, 'follow-playing.png') })
    await page.locator('.tab-bar .tab-item').nth(4).click()
    await page.waitForTimeout(1000)
    await page.locator('.mine-view .post-card').first().waitFor()
    await center(page, '.mine-view'); await waitPlaying(page, '.mine-view')
    await page.evaluate(id => uni.navigateTo({ url: '/pages/user-profile/index?userId=' + id }), state.target)
    await page.locator('.up-page .post-card').first().waitFor()
    await center(page, '.up-page'); await waitPlaying(page, '.up-page')
    await page.screenshot({ path: path.join(output, 'profile-playing.png') })
    await page.evaluate(() => uni.navigateBack())
    await waitPlaying(page, '.mine-view')
    results.push('follow, mine and user profile: playback and navigation suspension/resume')

    await page.locator('.tab-bar .tab-item').first().click()
    await center(page, '.home-view'); await waitPlaying(page, '.home-view')
    await page.locator('.home-view .feed-video-touch').first().click()
    await page.locator('.post-detail').waitFor(); assert.equal(await videos(page).count(), 0)
    await page.locator('.post-detail [aria-label="返回"]').first().click()
    await waitPlaying(page, '.home-view')
    await setting(page, '首页帖子布局', '双列瀑布布局')
    await page.locator('.waterfall-post').first().waitFor()
    assert.equal(await videos(page).count(), 0)
    const beforeWaterfall = mediaRequests
    await page.waitForTimeout(500); assert.equal(mediaRequests, beforeWaterfall)
    await setting(page, '首页帖子布局', '单列布局')
    await center(page, '.home-view'); await waitPlaying(page, '.home-view')
    results.push('click opens detail; return resumes; waterfall never loads a list stream')

    failVideo = true
    await center(page, '.home-view', 3)
    await page.waitForTimeout(600); assert.equal(await videos(page).count(), 0)
    const failedRequests = mediaRequests
    await page.waitForTimeout(700); assert.equal(mediaRequests, failedRequests, 'No automatic retry loop')
    failVideo = false
    await center(page, '.home-view', 0); await waitPlaying(page, '.home-view')
    await center(page, '.home-view', 3); await waitPlaying(page, '.home-view')
    results.push('failed stream: cover fallback, no retry storm, reentry retries')
    await page.evaluate(() => {
      window.savedPlay = HTMLMediaElement.prototype.play
      HTMLMediaElement.prototype.play = () => Promise.reject(new DOMException('Test autoplay policy', 'NotAllowedError'))
    })
    await center(page, '.home-view', 5)
    await page.waitForTimeout(350); assert.equal(await videos(page).count(), 0, 'Autoplay rejection falls back to cover')
    await page.evaluate(() => { HTMLMediaElement.prototype.play = window.savedPlay })
    await center(page, '.home-view', 0); await waitPlaying(page, '.home-view')
    await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, value: true }); document.dispatchEvent(new Event('visibilitychange')) })
    await page.waitForTimeout(100); assert.equal(await videos(page).count(), 0, 'Background document releases decoder')
    await page.evaluate(() => { delete document.hidden; document.dispatchEvent(new Event('visibilitychange')) })
    await waitPlaying(page, '.home-view')
    for (const width of [320, 390, 430]) {
      await page.setViewportSize({width,height:858})
      await center(page, '.home-view', 0); await waitPlaying(page, '.home-view')
      const dimensions = await page.locator('.home-view .media-skeleton').first().evaluate(el => {
        const rect = el.getBoundingClientRect(), layer = el.querySelector('.feed-video-layer').getBoundingClientRect()
        return {width:rect.width,height:rect.height,layerWidth:layer.width,layerHeight:layer.height}
      })
      assert(Math.abs(dimensions.width - dimensions.layerWidth) < 1)
      assert(Math.abs(dimensions.height - dimensions.layerHeight) < 1)
    }
    results.push('autoplay rejection and browser background: cover/release/recovery; 320/390/430px media bounds preserved')
    assert.equal(await page.evaluate(() => window.peakListDecoders), 1, 'Decoder count never exceeds one')
    assert.deepEqual(errors, [])
    fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify({ status: 'passed', results, mediaRequests, errors }, null, 2))
    console.log(JSON.stringify({ status: 'passed', results }))
  } catch (error) {
    if (page) await page.screenshot({path:path.join(output,'failure.png')})
    fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify({ status: 'failed', results, errors, error: String(error) }, null, 2)); throw error
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

const { chromium } = require('playwright-core')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const ts = require('typescript')
const origin = (process.env.UI_TEST_URL || 'http://127.0.0.1:15273').replace(/\/$/, '')
const output = path.resolve(__dirname, '../artifacts/feed-layout')
const fixtureOrigin = 'http://feed-layout.test'
const image = (name, ratio, type = 'image') => ({
  id: name, type, layout: ratio > 1 ? 'wide' : ratio < 1 ? 'portrait' : 'square', aspectRatio: ratio,
  url: type === 'image' ? `${fixtureOrigin}/${name}.svg` : '',
  previewUrl: type === 'image' ? `${fixtureOrigin}/${name}.svg` : undefined,
  coverUrl: `${fixtureOrigin}/${name}.svg`, status: 'ready', resourceVersion: 'feed-layout-v1',
  duration: type === 'video' ? '00:33' : undefined
})
const post = (id, title, media, content = '') => ({
  id: String(id), authorId: '100', authorNickname: '布局验收', authorLevel: 16,
  title, content, mode: media[0]?.type === 'video' ? 'video' : 'image', status: 1, media,
  authorAvatar: origin + '/static/images/assate/moren.png', tags: [], date: '10-10',
  likeCount: 929, commentCount: 30, allowDownload: true
})
const posts = [
  post(101, '撞大运喽～', [image('wide', 2.16)], '有大佬算算这概率是多少吗？'),
  post(102, '横向视频参考尺寸', [image('video', 16 / 9, 'video')]),
  post(103, '竖图参考尺寸', [image('portrait', 3 / 4)]),
  post(104, '超长竖图', [image('long', 1 / 4)]),
  post(105, '方图', [image('square', 1)]),
  post(106, '同方向两张', [image('two-a', 16 / 9), image('two-b', 16 / 9)]),
  post(107, '重开开始第1天', [image('three-a', 3 / 4), image('three-b', 2 / 3), image('three-c', 1)], '目前0抽'),
  post(108, '超过三张', [image('many-a', 3 / 4), image('many-b', 2), image('many-c', 1), image('many-d', 1 / 4)]),
  post(109, '', [image('no-copy', 3 / 4)]),
  post(110, '', [image('body-only', 1)], '只有正文'),
  post(111, '无媒体帖子', []),
  post(112, '长正文', [image('long-copy', 2)], '这是长正文预览，需要保持单行并且省略超出部分。'.repeat(15)),
  post(113, '竖向视频', [image('vertical-video', 9 / 16, 'video')]),
  post(114, '混合横竖图', [image('mixed-a', 4 / 3), image('mixed-b', 1 / 4)]),
  post(115, '   ', [], '   ')
]
const sourceMedia = posts.flatMap(item => item.media)
const snapshot = JSON.stringify(sourceMedia)
// Exercise the real helper with frozen API objects, independently of DOM rendering.
const source = fs.readFileSync(path.resolve(__dirname, '../utils/media-layout.uts'), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const helper = { exports: {} }
new Function('exports', 'require', 'module', compiled)(helper.exports, require, helper)
for (const item of posts) {
  item.media.forEach(Object.freeze)
  Object.freeze(item.media)
  const feed = helper.exports.getFeedMedia(item.media)
  assert.equal(feed.items.length, Math.min(item.media.length, 3))
  assert.equal(feed.total, item.media.length)
  feed.items.forEach((media, index) => {
    const first = item.media[0]
    assert.notEqual(media, item.media[index], 'feed must use copies')
    const squareLandscape = item.media.length > 1 && first.type === 'image' && first.aspectRatio > 1
    assert.equal(media.aspectRatio, squareLandscape ? 1 : first.aspectRatio < 1 ? 3 / 4 : first.aspectRatio)
    assert.equal(media.url, item.media[index].url)
  })
}
assert.equal(JSON.stringify(sourceMedia), snapshot, 'source media changed')

const close = (actual, expected, label) => assert(Math.abs(actual - expected) <= 1, `${label}: ${actual} != ${expected}`)
async function installFixtures(page) {
  await page.route('**/app/**', async route => {
    const pathname = new URL(route.request().url()).pathname
    let data = { records: [], total: 0, pages: 0, current: 1, size: 10 }
    if (pathname === '/app/home/config') data = { channels: [{ id: 1, name: '酒馆' }], entries: [], news: { enabled: false, interval: 3, items: [] } }
    if (pathname === '/app/post/list') data = { records: posts, total: posts.length, pages: 1, current: 1, size: 20 }
    if (/^\/app\/post\/\d+$/.test(pathname)) data = posts.find(item => item.id === pathname.split('/').pop())
    if (pathname === '/app/media/config') data = { cacheMaxBytes: 3 * 1024 ** 3 }
    if (pathname.includes('categories')) data = []
    if (pathname === '/app/comment/config') data = { imageMax: 3, contentMax: 1000, emojis: [], emojiGroups: [] }
    await route.fulfill({ json: { code: '0000', msg: 'ok', data }, headers: { 'Access-Control-Allow-Origin': '*' } })
  })
  await page.route(fixtureOrigin + '/**', route => {
    const name = new URL(route.request().url()).pathname.slice(1, -4)
    const media = sourceMedia.find(item => item.id === name)
    const width = 600, height = Math.round(width / media.aspectRatio)
    const label = media.type === 'video' ? 'VIDEO' : media.layout.toUpperCase()
    return route.fulfill({ contentType: 'image/svg+xml', headers: { 'Access-Control-Allow-Origin': '*' },
      body: `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><rect width="100%" height="100%" fill="#dceaf3"/><rect x="20" y="20" width="560" height="${height - 40}" fill="#c1d8e8" stroke="#6f9ebc" stroke-width="8"/><circle cx="300" cy="${height / 2}" r="100" fill="#8db6cf"/><text x="300" y="${height / 2}" text-anchor="middle" fill="#254b68" font-size="36">${label}</text><text x="300" y="${height / 2 + 50}" text-anchor="middle" fill="#254b68" font-size="26">${name}</text></svg>` })
  })
}
async function readyImages(page, selector) {
  await page.waitForFunction(selector => [...document.querySelectorAll(selector)].every(img => img.complete && img.naturalWidth > 0), selector)
}
async function geometry(card) {
  return card.evaluate(el => {
    const box = element => {
      if (!element) return null
      const rect = element.getBoundingClientRect(), style = getComputedStyle(element)
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height, bottom: rect.bottom, right: rect.right,
        radius: parseFloat(style.borderRadius), font: parseFloat(style.fontSize), line: parseFloat(style.lineHeight),
        margin: parseFloat(style.marginTop), fit: style.backgroundSize }
    }
    const style = getComputedStyle(el)
    return { card: box(el), padding: [style.paddingTop, style.paddingRight, style.paddingBottom, style.paddingLeft].map(parseFloat),
      author: box(el.querySelector('.author-row')), avatar: box(el.querySelector('.user-avatar-photo')),
      copy: box(el.querySelector('.post-copy')), title: box(el.querySelector('.post-title')), body: box(el.querySelector('.post-content')),
      list: box(el.querySelector('.media-list')), slots: [...el.querySelectorAll('.media-skeleton')].map(box),
      fill: box(el.querySelector('.media-fill > div')), actions: box(el.querySelector('.actions')),
      icons: [...el.querySelectorAll('.actions .app-icon')].map(box),
      actionGap: parseFloat(getComputedStyle(el.querySelector('.actions')).gap) }
  })
}
async function setVariant(card, variant) {
  await card.evaluate((el, value) => {
    let instance = el.__vueParentComponent
    while (instance && !('variant' in instance.props)) instance = instance.parent
    if (!instance) throw new Error('PostCard instance not found')
    instance.props.variant = value
  }, variant)
}
async function cardAt(page, index) {
  await page.locator('.home-view .virtual-post-column').evaluate((root, index) => {
    let instance = root.__vueParentComponent
    while (instance && !instance.setupState.offsets) instance = instance.parent
    const host = root.closest('.screen-scroll')
    const scroll = [...host.querySelectorAll('.uni-scroll-view')].find(el => /auto|scroll/.test(getComputedStyle(el).overflowY))
    const origin = root.getBoundingClientRect().top - scroll.getBoundingClientRect().top + scroll.scrollTop
    scroll.scrollTop = origin + instance.setupState.offsets[index]
  }, index)
  const card = page.locator('.home-view [data-virtual-id="' + posts[index].id + '"] .post-card')
  await card.waitFor()
  return card
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const results = [], errors = []
  try {
    for (const width of [320, 375, 390, 393, 414, 430]) {
      const context = await browser.newContext({ viewport: { width, height: Math.round(width * 2.2) }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 })
      const page = await context.newPage()
      page.on('pageerror', error => errors.push(error.message))
      await installFixtures(page)
      await page.goto(origin)
      const cards = page.locator('.home-view .post-card')
      await cards.first().waitFor()
      await page.waitForFunction(count => Number(document.querySelector('.home-view [data-loaded-count]').dataset.loadedCount) === count, posts.length)
      await readyImages(page, '.home-view .media-fill img')
      await page.waitForFunction(() => !document.querySelector('.home-view .feed-reveal-covered, .home-view .feed-reveal-running'))
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width, 'horizontal page overflow')
      const scale = width / 750, column = (750 - 68 - 20) / 3
      const measurements = []
      for (let index = 0; index < posts.length; index++) {
        const fixture = posts[index], card = await cardAt(page, index)
        await page.waitForFunction(() => !document.querySelector('.home-view .feed-reveal-covered, .home-view .feed-reveal-running'))
        const geo = await geometry(card)
        const hasTitle = !!fixture.title.trim(), hasBody = !!fixture.content.trim()
        close(geo.card.width, width, 'card width')
        ;[20, 34, 10, 34].forEach((value, i) => close(geo.padding[i], value * scale, 'card padding'))
        close(geo.avatar.width, 56 * scale, 'avatar')
        assert.equal(!!geo.copy, hasTitle || hasBody, 'empty text section')
        assert.equal(!!geo.title, hasTitle, 'empty title')
        assert.equal(!!geo.body, hasBody, 'empty body')
        if (geo.copy) close(geo.copy.y - geo.author.bottom, 8 * scale, 'author/copy gap')
        if (geo.title) { close(geo.title.font, 26 * scale, 'title font'); close(geo.title.line, 32 * scale, 'title line height') }
        if (geo.body) {
          close(geo.body.font, 25 * scale, 'body font')
          close(geo.body.height, 32 * scale, 'single-line body')
          close(geo.body.margin, (hasTitle ? 6 : 0) * scale, 'body margin')
        }
        assert.equal(geo.slots.length, Math.min(fixture.media.length, 3))
        if (geo.list) {
          close(geo.list.y - (geo.copy || geo.author).bottom, 16 * scale, 'media gap')
          const first = fixture.media[0]
          const squareLandscape = fixture.media.length > 1 && first.type === 'image' && first.aspectRatio > 1
          const ratio = squareLandscape ? 1 : first.aspectRatio < 1 ? 3 / 4 : first.aspectRatio
          const mediaWidth = fixture.media.length === 1 && ratio > 1 ? (first.type === 'video' ? 450 : 410) : column
          geo.slots.forEach((slot, i) => {
            close(slot.width, mediaWidth * scale, fixture.title + ': media width')
            close(slot.height, mediaWidth / ratio * scale, fixture.title + ': media height')
            close(slot.radius, 16 * scale, 'media radius')
            close(slot.y, geo.slots[0].y, 'same row')
            if (i) close(slot.x - geo.slots[i - 1].right, 10 * scale, 'column gap')
            assert(slot.right <= geo.card.right + 1, 'media overflow')
          })
          assert.equal(geo.fill.fit, 'cover', 'feed crop mode')
        }
        close(geo.actions.margin, 14 * scale, 'actions margin')
        close(geo.actions.height, 50 * scale, 'actions height')
        close(geo.actionGap, 44 * scale, 'actions gap')
        geo.icons.forEach(icon => close(icon.width, 32 * scale, 'action icon'))
        assert.equal(await card.locator('.media-count').count(), fixture.media.length > 3 ? 1 : 0)
        if (fixture.media.length > 3) {
          assert.equal(await card.locator('.media-slot').nth(2).locator('.media-count').innerText(), String(fixture.media.length))
        }
        measurements.push({ id: fixture.id, media: geo.slots.map(({ width, height }) => ({ width, height })) })
      }
      const scroll = page.locator('.home-view > .screen-scroll > .uni-scroll-view > .uni-scroll-view')
      // Feed images and videos open the full post; images can then be previewed from its detail.
      for (const [cardIndex, mediaIndices] of [[1, [0]], [3, [0]], [6, [0, 1, 2]], [7, [2]]]) {
        for (const mediaIndex of mediaIndices) {
          await (await cardAt(page, cardIndex)).scrollIntoViewIfNeeded()
          await (await cardAt(page, cardIndex)).locator('.media-slot').nth(mediaIndex).scrollIntoViewIfNeeded()
          await page.waitForTimeout(120)
          const before = await scroll.evaluate(el => el.scrollTop)
          await (await cardAt(page, cardIndex)).locator('.media-slot').nth(mediaIndex).click()
          await page.waitForSelector('.post-detail .detail-scroll')
          assert.equal(new URLSearchParams(page.url().split('?')[1]).get('id'), posts[cardIndex].id)
          assert(!page.url().includes('view=media'), 'feed must open the post detail')
          assert(!page.url().includes('mediaIndex='), 'feed must not select a preview')
          assert.equal(await page.locator('.image-viewer').count(), 0)
          assert.equal(await page.locator('.detail-title').innerText(), posts[cardIndex].title)
          if (posts[cardIndex].media[mediaIndex].type === 'image') {
            await page.locator('.detail-image').nth(mediaIndex).click()
            await page.waitForSelector('.image-counter')
            assert.equal(await page.locator('.image-counter').innerText(), `${mediaIndex + 1}/${posts[cardIndex].media.length}`)
            await readyImages(page, '.full-image img')
            const originalRatio = await page.locator('.full-image img').evaluate(img => img.naturalWidth / img.naturalHeight)
            assert(Math.abs(originalRatio - posts[cardIndex].media[mediaIndex].aspectRatio) < .005, 'detail lost original ratio')
            assert.equal(await page.locator('.full-image > div').evaluate(el => getComputedStyle(el).backgroundSize), 'contain', 'detail should show full original')
            if (width === 390 && cardIndex === 3) await page.screenshot({ path: path.join(output, '390-long-image-detail.png') })
            await page.locator('.viewer-top [aria-label="返回"]').click()
            await page.waitForSelector('.image-viewer', { state: 'detached' })
          }
          await page.locator('.post-detail [aria-label="返回"]').first().click()
          await page.waitForSelector('.app-shell')
          await page.waitForTimeout(120)
          close(await scroll.evaluate(el => el.scrollTop), before, 'feed scroll restored')
        }
      }
      // Follow now owns its reference layout; verify it via verify-follow-layout.cjs.
      if (width === 390) {
        for (const variant of ['mine']) {
          await setVariant(await cardAt(page, 0), variant)
          await page.waitForFunction(variant => document.querySelector('.home-view .post-card').classList.contains(variant), variant)
          const geo = await geometry(await cardAt(page, 0))
          close(geo.slots[0].width, (750 - 68) * scale, 'other variant full-width landscape')
          close(geo.padding[0], 30 * scale, 'other variant top padding')
          close(geo.padding[2], 20 * scale, 'other variant bottom padding')
          close(geo.icons[0].width, 36 * scale, 'other variant action icon')
        }
        await setVariant(await cardAt(page, 0), 'home')
        await page.waitForFunction(() => document.querySelector('.home-view .post-card').classList.contains('home'))
      }
      await scroll.evaluate(el => { el.scrollTop = 250 })
      await page.waitForTimeout(150)
      await page.screenshot({ path: path.join(output, `${width}-feed.png`) })
      if (width === 390) {
        for (const [index, name] of [[0, 'wide-image'], [1, 'wide-video'], [2, 'portrait'], [6, 'three-columns']]) {
          await (await cardAt(page, index)).scrollIntoViewIfNeeded()
          await (await cardAt(page, index)).screenshot({ path: path.join(output, `390-${name}.png`) })
        }
      }
      results.push({ width, cards: posts.length, geometry: measurements, navigation: 'passed' })
      await context.close()
    }
    assert.deepEqual(errors, [], 'runtime errors')
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ status: 'passed', sourceMediaUnchanged: true, otherVariants: 'passed', results, errors }, null, 2))
    console.log(`Feed layout passed: ${results.length} widths, ${posts.length} fixtures each; media opens post detail, image preview and scroll return preserved. Screenshots: ${output}`)
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

const { chromium } = require('playwright-core')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')

const output = path.resolve(__dirname, '../artifacts/publish-ui')
const url = process.env.UI_TEST_URL || 'http://127.0.0.1:15273/'
const browserPath = process.env.UI_BROWSER_PATH
fs.mkdirSync(output, { recursive: true })

const topics = Array.from({ length: 7 }, (_, index) => ({ id: index + 1, name: `话题${index + 1}`, description: '验收话题', coverUrl: '', hot: index === 0, recommended: true, viewCount: 0 }))
const config = {
  rules: [
    { mode: 'image', titleMin: 0, titleMax: 30, contentMin: 0, contentMax: 2000, imageMax: 9, videoMax: 0, totalMediaMax: 9, maxVideoMb: 0 },
    { mode: 'video', titleMin: 0, titleMax: 30, contentMin: 0, contentMax: 2000, imageMax: 0, videoMax: 1, totalMediaMax: 1, maxVideoMb: 300 },
    { mode: 'post', titleMin: 1, titleMax: 30, contentMin: 1, contentMax: 30000, imageMax: 9, videoMax: 3, totalMediaMax: 12, maxVideoMb: 500 }
  ],
  channels: [{ id: 9, name: '默认频道', isDefault: true }, { id: 10, name: '交流区', isDefault: false }], defaultChannelId: 9,
  creatorPolicies: { allowDownload: { defaultValue: true, userEditable: true }, downloadWatermark: { defaultValue: false, userEditable: true }, original: { defaultValue: false, userEditable: true } },
  reviewRequired: true, featureFlags: { emoji: true, mention: true, color: true, divider: true, link: true, poll: true }, pollRule: { minOptions: 2, maxOptions: 10 },
  topics, emojiGroups: [{ id: 1, name: '默认' }], emojis: [{ id: 1, groupId: 1, name: '开心', imageUrl: '/uploads/emoji.png' }], colors: [{ id: 1, name: '红', colorValue: '#ff0000' }], dividers: [{ id: 1, name: '实线', previewText: '────────' }]
}
const draft = { id: 42, revision: 3, mode: 'post', title: '已恢复草稿', summary: '草稿正文', coverUrl: '', updateTime: '2026-09-08 12:00:00', payload: { mode: 'post', title: '已恢复草稿', postText: '草稿正文', content: '', assets: [], postBlocks: [], inlineRuns: [], selectedTopics: topics.slice(0, 2), channelId: 9, creator: { allowDownload: true, downloadWatermark: false, original: true }, poll: { question: '', options: ['', ''] } } }
const envelope = data => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: '0000', msg: 'success', data }) })

async function mockApi(page, state = { config, draft }) {
  const fixtureUser={userId:'10',nickname:'发布测试',avatar:'',level:1,coinBalance:0,tags:[]}
  await page.addInitScript(user=>{localStorage.setItem('app_token',JSON.stringify({type:'string',data:'publish-test'}));localStorage.setItem('app_user_info',JSON.stringify({type:'object',data:user}))},fixtureUser)
  await page.route('**/app/**',async route=>{
    const p=new URL(route.request().url()).pathname
    let data=[]
    if(p==='/app/user/me')data=fixtureUser
    else if(p==='/app/home/config')data={channels:[{id:9,name:'推荐'}],entries:[],news:{enabled:false,items:[],interval:3,emptyText:''}}
    else if(p.includes('/post/'))data={records:[],total:0,pages:0,current:1}
    else if(p==='/app/notice/summary')data={unreadCount:0,categories:[]}
    else if(p==='/app/media/config')data={cacheMaxBytes:10000000}
    return route.fulfill(envelope(data))
  })

  await page.route('**/app/publish/**', async route => {
    const requestUrl = new URL(route.request().url())
    if (requestUrl.pathname === '/app/publish/config') { state.configRequests = (state.configRequests || 0) + 1; if (state.configDelay) await new Promise(resolve => setTimeout(resolve, state.configDelay)); if (state.failConfig) return route.fulfill({ status: 500, body: 'test config failure' }); return route.fulfill(envelope(state.config)) }
    if (requestUrl.pathname === '/app/publish/topics') return route.fulfill(envelope(topics))
    if (requestUrl.pathname === '/app/publish/drafts') return route.fulfill(envelope([state.draft]))
    if (requestUrl.pathname === '/app/publish/drafts/42') return route.fulfill(envelope(state.draft))
    if (requestUrl.pathname === '/app/publish/drafts/save') { state.savedDraft = route.request().postDataJSON(); return route.fulfill(envelope({ ...state.draft, revision: state.draft.revision + 1 })) }
    return route.fulfill(envelope(null))
  })
}
async function openPublish(page, state) {
  await mockApi(page, state)
  await page.goto(new URL('#/pages/publish/index', url).href)
  await page.waitForSelector('.publish-page')
  await page.waitForSelector('.options')
}
async function verifyChannelAccess(browser, errors) {
  const publicConfig = { ...config, channels: [{ id: 10, name: '交流区', isDefault: false, adminOnly: false }], defaultChannelId: 10 }
  const officialConfig = { ...config, channels: [{ id: 9, name: '官方专属', isDefault: true, adminOnly: true }, ...publicConfig.channels], defaultChannelId: 9 }
  const oldDraft = { ...draft, payload: { ...draft.payload, channelId: 9, assets: [{ id: 88, mediaType: 'image', status: 'ready', url: '/uploads/test.png' }], postBlocks: [{ type: 'image', assetId: 88 }] } }
  const context = await browser.newContext({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
  try {
    const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message))
    const state = { config: publicConfig, draft: oldDraft, configDelay: 250 }
    await openPublish(page, state)
    await page.locator('.options .option').first().click()
    await page.getByText('交流区', { exact: true }).last().waitFor()
    assert.equal(await page.getByText('官方专属', { exact: true }).count(), 0)
    await page.getByText('取消', { exact: true }).last().click()
    await page.locator('.draft-button').click(); await page.waitForSelector('.draft'); await page.locator('.draft').first().click()
    await page.waitForFunction(() => document.querySelector('.title input')?.value === '已恢复草稿')
    assert((await page.locator('.options .option').first().textContent()).includes('交流区'))
    assert.equal(await page.locator('.post-text textarea').inputValue(), '草稿正文')
    assert.equal(await page.locator('.block uni-image').count(), 1, 'draft media retained')
    assert((await page.locator('.publish-button').getAttribute('class')).includes('ready'))
    await page.screenshot({ path: path.join(output, 'restricted-draft-fallback.png') })

    state.config = officialConfig
    await page.locator('.options .option').nth(1).click(); await page.waitForSelector('.topic-page'); await page.locator('.topic-header .confirm').click(); await page.waitForSelector('.options')
    await page.waitForSelector('.loading', { state: 'detached' })
    await page.locator('.options .option').first().click(); await page.locator('.channel-card').filter({ hasText: '官方专属' }).click()
    assert((await page.locator('.options .option').first().textContent()).includes('官方专属'))
    state.config = publicConfig
    await page.locator('.options .option').nth(1).click(); await page.waitForSelector('.topic-page'); await page.locator('.topic-header .confirm').click(); await page.waitForSelector('.options')
    await page.waitForFunction(() => document.querySelector('.options .option')?.textContent.includes('交流区'))
    assert.equal(await page.locator('.title input').inputValue(), '已恢复草稿')
    assert.equal(await page.locator('.post-text textarea').inputValue(), '草稿正文')
    assert(state.configRequests >= 4, 'config refreshed on return')

    state.failConfig = true
    await page.locator('.options .option').nth(1).click(); await page.waitForSelector('.topic-page'); await page.locator('.topic-header .confirm').click(); await page.waitForSelector('.options')
    await page.waitForSelector('.loading', { state: 'detached' })
    assert(!(await page.locator('.publish-button').getAttribute('class')).includes('ready'), 'failed refresh cannot publish from stale config')
    assert.equal(await page.locator('.post-text textarea').inputValue(), '草稿正文')
    state.failConfig = false; state.config = { ...publicConfig, channels: [], defaultChannelId: null }
    await page.locator('.options .option').nth(1).click(); await page.waitForSelector('.topic-page'); await page.locator('.topic-header .confirm').click(); await page.waitForSelector('.options')
    await page.waitForFunction(() => document.querySelector('.options .option')?.textContent.includes('暂无可发布版区'))
    assert(!(await page.locator('.publish-button').getAttribute('class')).includes('ready'))
    const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/app/publish/drafts/save')
    await page.locator('.draft-button').click(); await saved; await page.waitForSelector('.draft')
    assert.equal(state.savedDraft.payload.channelId, null)
    assert.equal(state.savedDraft.payload.postText, '草稿正文')
    assert.equal(state.savedDraft.payload.assets[0].id, 88)
    await page.locator('.page .header uni-button').first().click(); await page.waitForSelector('.options')
    await page.screenshot({ path: path.join(output, 'no-publishable-channel.png') })

    state.config = publicConfig
    await page.locator('.options .option').nth(1).click(); await page.waitForSelector('.topic-page'); await page.locator('.topic-header .confirm').click(); await page.waitForSelector('.options')
    await page.waitForFunction(() => document.querySelector('.options .option')?.textContent.includes('交流区'))
    assert((await page.locator('.publish-button').getAttribute('class')).includes('ready'), 'opening a channel restores publishing')

    // A draft requested on first show must wait for the slow initial config.
    const first = await context.newPage(); first.on('pageerror', e => errors.push(e.message))
    await first.addInitScript(() => localStorage.setItem('publish-open-draft', JSON.stringify({ type: 'number', data: 42 })))
    await openPublish(first, { config: publicConfig, draft: oldDraft, configDelay: 400 })
    await first.waitForFunction(() => document.querySelector('.title input')?.value === '已恢复草稿')
    assert((await first.locator('.options .option').first().textContent()).includes('交流区'))
  } finally { await context.close() }
}
async function geometry(page, width) {
  const result = await page.locator('.publish-page').evaluate(el => ({ width: el.getBoundingClientRect().width, scrollWidth: document.documentElement.scrollWidth, labels: [...el.querySelectorAll('.mode-button')].map(node => node.textContent.trim()) }))
  assert(Math.abs(result.width - width) <= 1, `publish page width at ${width}`)
  assert.equal(result.scrollWidth, width, `horizontal overflow at ${width}`)
  assert.deepEqual(result.labels, ['图文', '视频', '帖子'])
  assert(!result.labels.includes('长文'))
  const layout = await page.evaluate(() => {
    const rect = selector => { const r = document.querySelector(selector).getBoundingClientRect(); return { x:r.x, y:r.y, width:r.width, height:r.height, bottom:r.bottom } }
    const options = [...document.querySelectorAll('.option')].map(node => { const r=node.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,padding:getComputedStyle(node).paddingLeft} })
    return { media:rect('.add-media'), title:rect('.title-row'), editor:rect('.editor'), options:rect('.options'), rows:options, footer:rect('.footer'), button:rect('.publish-button'), surface:rect('.surface'), dock:rect('.mode-dock'), tileColor:getComputedStyle(document.querySelector('.add-media')).backgroundColor, buttonColor:getComputedStyle(document.querySelector('.publish-button')).backgroundColor, buttonTextColor:getComputedStyle(document.querySelector('.publish-label')).color, hints:document.querySelector('.options').textContent }
  })
  const scale=width/750, near=(actual,expected,label)=>assert(Math.abs(actual-expected)<1.5, `${label}: ${actual} != ${expected}`)
  near(layout.media.x,25*scale,'reference media left inset')
  near(layout.media.width,208*scale,'reference media square')
  near(layout.media.height,208*scale,'reference media square height')
  near(layout.title.x,34*scale,'reference text inset')
  near(layout.options.y+276*scale+126*scale,layout.footer.y,'reference option to footer spacing')
  near(layout.button.x,154*scale,'reference publish button left edge')
  near(layout.button.height,92*scale,'reference publish button height')
  near(layout.surface.bottom,layout.dock.y,'surface meets unchanged navigation')
  for (const row of layout.rows) { near(row.height,92*scale,'reference option row height'); near(parseFloat(row.padding),30*scale,'consistent option inset') }
  assert.equal(layout.tileColor,'rgb(246, 247, 249)')
  assert.equal(layout.buttonColor,'rgb(205, 238, 253)')
  assert.equal(layout.buttonTextColor,'rgb(255, 255, 255)')
  assert(!layout.hints.includes('最多5个'),'remove only the requested topic hint')
  assert((await page.locator('.media-count').textContent()).includes('0/9'),'keep image count')
  assert((await page.locator('.title-row').textContent()).includes('0/30'),'keep title count')
}

async function verifyChannelPicker(page, width) {
  await page.locator('.options .option').first().click()
  const cards = await page.locator('.channel-card').evaluateAll(nodes => nodes.map(node => {
    const rect = node.getBoundingClientRect()
    return { x: rect.x, y: rect.y, width: rect.width, height: rect.height }
  }))
  assert.equal(cards.length, 5)
  for (let index = 0; index < cards.length; index++) {
    assert(cards[index].width > width * .4 && cards[index].height > 0, 'channel remains visible with a half-width tap target')
    if (index % 2) {
      assert(Math.abs(cards[index].y - cards[index - 1].y) < 1, 'two channels share each row')
      assert(cards[index].x >= cards[index - 1].x + cards[index - 1].width - 1, 'columns do not overlap')
    } else if (index > 0) assert(cards[index].y >= cards[index - 2].y + cards[index - 2].height, 'rows do not overlap')
  }
  assert.equal(cards[4].x, cards[0].x, 'the odd final channel stays in the left column')
  const grid = await page.locator('.channel-grid').boundingBox()
  assert(cards[4].y + cards[4].height <= grid.y + grid.height + 1, 'all five channels fit without clipping')
  await page.screenshot({ path: path.join(output, `channels-${width}.png`) })
  await page.locator('.channel-card').nth(3).click()
  await page.waitForSelector('.channel-sheet', { state: 'detached' })
  assert((await page.locator('.options .option').first().textContent()).includes('同人图'))
  await page.locator('.options .option').first().click()
  assert.equal(await page.locator('.channel-name-active').textContent(), '同人图')
  await page.locator('.channel-cancel').click()
  await page.waitForSelector('.channel-sheet', { state: 'detached' })
}

;(async () => {
  const browser = await chromium.launch({ headless: true, ...(browserPath ? { executablePath: browserPath } : {}) })
  const errors = []
  try {
    for (const width of [320, 375, 390, 393, 414, 430]) {
      const context = await browser.newContext({ viewport: { width, height: Math.round(width * 2.2) }, isMobile: true, hasTouch: true })
      const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message))
      const channels = ['酒馆', '官方', '二创', '同人图', 'COS'].map((name, index) => ({ id: index + 9, name, isDefault: index === 0 }))
      await openPublish(page, { config: { ...config, channels }, draft }); await geometry(page, width); await verifyChannelPicker(page, width); await context.close()
    }

    const context = await browser.newContext({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
    const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message)); await openPublish(page)
    assert((await page.locator('.add-media').textContent()).includes('0/9'))
    assert((await page.locator('.options .option').first().textContent()).includes('默认频道'))
    await page.screenshot({ path: path.join(output, 'image-390.png') })

    await page.locator('.mode-button').nth(1).click(); assert(await page.locator('.video-media').isVisible()); assert((await page.locator('.video-media').textContent()).includes('添加视频'))
    await page.screenshot({ path: path.join(output, 'video-390.png') })

    await page.locator('.mode-button').nth(2).click(); await page.locator('.title input').fill('帖子标题'); await page.locator('.post-text textarea').fill('帖子正文')
    assert.equal(await page.locator('.toolbar .app-icon').count(), 6, 'all enabled editor tools are visible')
    assert((await page.locator('.publish-button').getAttribute('class')).includes('ready'))
    await page.screenshot({ path: path.join(output, 'post-390.png') })

    await page.locator('.options .option').nth(1).click(); await page.waitForSelector('.topic-page'); assert.equal(await page.locator('.topic-row').count(), 7)
    for (let index = 0; index < 6; index++) await page.locator('.topic-row').nth(index).click()
    assert((await page.locator('.topic-header').textContent()).includes('5/5'), 'topic selection is capped at five')
    await page.screenshot({ path: path.join(output, 'topics-390.png') })
    await page.locator('.topic-header .confirm').click(); await page.waitForSelector('.options'); await page.waitForSelector('.publish-page')
    assert((await page.locator('.mode-button').nth(2).getAttribute('class')).includes('active'), 'publish mode was preserved')

    await page.locator('.close-button').click(); await page.waitForSelector('.app-shell'); await page.locator('.tab-bar .tab-item').nth(2).click(); await page.waitForSelector('.publish-page'); assert.equal(await page.locator('.title input').inputValue(), '帖子标题', 'direct return retains the editor without an exit dialog')
    await page.locator('.draft-button').click(); await page.waitForSelector('.draft'); await page.locator('.draft').first().click(); await page.waitForSelector('.publish-page'); await page.waitForFunction(() => document.querySelector('.title input')?.value === '已恢复草稿')
    assert.equal(await page.locator('.title input').inputValue(), '已恢复草稿')
    await page.screenshot({ path: path.join(output, 'draft-restored-390.png') })

    await page.evaluate(() => localStorage.setItem('publish-cover-context', JSON.stringify({ type: 'object', data: { id: 8, localPath: '', coverUrl: '/uploads/cover.jpg', durationMs: 5000, width: 1080, height: 1920, cropJson: '' } })))
    await page.goto(new URL('#/pages/publish/cover', url).href); await page.waitForSelector('.crop-frame'); assert((await page.locator('.crop-frame').textContent()).includes('竖屏')); assert.equal(await page.locator('uni-slider').count(), 1)
    const stageRatio = await page.locator('.stage').evaluate(node => { const rect = node.getBoundingClientRect(); return rect.width / rect.height })
    assert(Math.abs(stageRatio - 9 / 16) < .01, 'portrait cover editor retains video proportions')
    await page.screenshot({ path: path.join(output, 'cover-390.png') })
    await context.close()

    await verifyChannelAccess(browser, errors)
    assert.equal(errors.length, 0, errors.join('\n'))
    const result = { widths: [320, 375, 390, 393, 414, 430], modes: ['图文', '视频', '帖子'], topicsMax: 5, draftRecovery: 'passed', coverCrop: 'passed', editorTools: 'passed', channelAccess: 'passed', restrictedDraftFallback: 'passed', emptyChannelDraftSave: 'passed', configRefreshFailure: 'passed' }
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

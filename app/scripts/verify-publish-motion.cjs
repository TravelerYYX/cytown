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
async function openReady(browser) {
  const context = await browser.newContext({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
  const page = await context.newPage()
  await openPublish(page)
  await page.locator('.mode-button').nth(2).click()
  await page.locator('.title input').fill('标题')
  await page.locator('.post-text textarea').fill('正文')
  await page.locator('.publish-button.ready').waitFor()
  return { context, page }
}
async function verifySubmitMotion(browser) {
  const { context, page } = await openReady(browser)
  let requests=0
  await page.route('**/app/publish/submit', async route => { requests++; await new Promise(resolve => setTimeout(resolve, 1550)); await route.fulfill(envelope({ id:'1', status:1, message:'发布成功' })) })
  const button=page.locator('.publish-button')
  const original=await button.boundingBox()
  const started=Date.now()
  await button.click()
  await page.waitForTimeout(60)
  assert.equal(await page.locator('.publish-spinner').count(),0,'spinner waits for label to fade')
  await page.waitForTimeout(450)
  const circle=await button.evaluate(node => {const r=node.getBoundingClientRect(); return {width:r.width,height:r.height,radius:parseFloat(getComputedStyle(node).borderTopLeftRadius)} })
  assert(Math.abs(circle.width-circle.height)<1,'loading button is square')
  assert(Math.abs(circle.radius-circle.height/2)<1,'loading button is circular')
  assert.equal(await page.locator('.publish-spinner').count(),1,'spinner visible')
  assert.equal(await page.locator('.publish-check').count(),0,'no success before server response')
  await page.locator('.publish-check').waitFor()
  assert(Date.now()-started>=1400,'success waits for delayed server response')
  assert.equal(requests,1,'only one submit sent')
  await page.waitForTimeout(700)
  assert.equal(await page.locator('.publish-check').count(),1,'check remains visible')
  assert.equal(new URL(page.url()).hash,'#/pages/publish/index','page remains open during success')
  await page.waitForFunction(() => !document.querySelector('.publish-check') && !!document.querySelector('.publish-button'))
  assert.equal(new URL(page.url()).hash,'#/pages/publish/index','page remains open while button expands')
  await page.waitForFunction(() => !document.querySelector('.publish-page'))
  assert(Date.now()-started>=2700,'page closes after restoration')
  await context.close()

  const fast=await openReady(browser)
  await fast.page.route('**/app/publish/submit',route=>route.fulfill(envelope({id:'2',status:1,message:'发布成功'})))
  const fastStarted=Date.now()
  await fast.page.locator('.publish-button').click()
  await fast.page.locator('.publish-check').waitFor()
  assert(Date.now()-fastStarted>=1100,'fast success displays the spinner for at least 1.2 seconds')
  await fast.page.waitForFunction(() => !document.querySelector('.publish-page'))
  await fast.context.close()

  const failure=await openReady(browser)
  await failure.page.route('**/app/publish/submit',route=>route.fulfill({status:500,body:'test failure'}))
  const failureStarted=Date.now()
  await failure.page.locator('.publish-button').click()
  await failure.page.waitForTimeout(220)
  assert.equal(await failure.page.locator('.publish-spinner').count(),1,'fast failure still shows loading')
  assert.equal(await failure.page.locator('.publish-interaction-blocker').count(),1,'failure animation finishes before retry is enabled')
  await failure.page.waitForFunction(() => !document.querySelector('.publish-interaction-blocker'))
  assert(Date.now()-failureStarted>=650,'failure completes contraction, fade and restoration')
  assert.equal(await failure.page.locator('.publish-check').count(),0,'failure never shows check')
  const restored=await failure.page.locator('.publish-button').boundingBox()
  assert(Math.abs(restored.width-original.width)<1,'failure restores button')
  assert.equal(new URL(failure.page.url()).hash,'#/pages/publish/index','failure keeps editor open')
  await failure.context.close()
  console.log('Submit motion: passed (fast and slow success, restoration, and failure)')
}
;(async()=>{const browser=await chromium.launch({headless:true, ...(browserPath ? { executablePath:browserPath } : {})}); try {await verifySubmitMotion(browser)} finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1})

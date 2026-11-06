const { chromium } = require('playwright-core')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const origin = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
const output = path.resolve(__dirname, '../artifacts/channel-loading')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const record = (channel, index) => ({ id: channel+'-'+index, authorId: 'author', authorNickname: '频道测试',
  title: channel+'频道卡片 '+index, content: index === 0 ? '保留布局，分块出现' : '', tags: [], likeCount: 0, commentCount: 0,
  media: [{ type: 'image', layout: 'wide', aspectRatio: 16 / 9, url: 'http://channel-loading.test/'+channel+'-'+index+'.svg' }] })
async function state(page, expression) {
  return page.locator('.home-view').evaluate((el, code) => {
    let vm = el.__vueParentComponent
    while (vm && !vm.setupState.activeChannel) vm = vm.parent
    return Function('s', 'return (' + code + ')')(vm.setupState)
  }, expression)
}
async function select(page, channel) { await state(page, 's.activeChannel = '+channel) }
async function settled(page) {
  await page.waitForFunction(() => {
    let vm = document.querySelector('.home-view')?.__vueParentComponent
    while (vm && !vm.setupState.activeChannel) vm = vm.parent
    return vm && !vm.setupState.loading && vm.setupState.feedReveal.phase.value === 'idle'
  })
}
async function assertInstantRestore(page, channel) {
  const samples = await page.locator('.home-view').evaluate(async (el, channel) => {
    let vm = el.__vueParentComponent
    while (vm && !vm.setupState.activeChannel) vm = vm.parent
    const s = vm.setupState, samples = []
    s.activeChannel = channel
    for (let i = 0; i < 20; i++) {
      await new Promise(resolve => requestAnimationFrame(resolve))
      samples.push({ phase:s.feedReveal.phase.value, covered:el.querySelectorAll('.feed-reveal-covered,.feed-reveal-running,.feed-loading-placeholder,.feed-loading-light').length })
    }
    return samples
  }, channel)
  assert(samples.every(sample => sample.phase === 'idle' && sample.covered === 0), 'Cached channel never replays skeletons or reveal transitions: '+JSON.stringify(samples))
}
async function assertSingleCardSweep(page, name) {
  await page.locator('.feed-loading-light').waitFor()
  const sample = () => page.locator('.feed-loading-sheen').evaluateAll(nodes => nodes.map(node => {
    const card = node.closest('.feed-placeholder-card,.post-card,.waterfall-post')
    const a = node.getBoundingClientRect(), b = card?.getBoundingClientRect()
    return { card: card?.id, within:!!b && a.left >= b.left-1 && a.right <= b.right+1 && a.top >= b.top-1 && a.bottom <= b.bottom+1 }
  }))
  const first = await sample()
  assert.equal(first.length,1,'Only one card may sweep at a time')
  assert(first[0].within,'Sweep stays inside its card')
  await page.waitForTimeout(650)
  await page.screenshot({path:path.join(output,name+'-single-card-sweep.png')})
  await page.waitForTimeout(900)
  const next = await sample()
  assert.equal(next.length,1)
  assert(next[0].within)
  assert.notEqual(next[0].card,first[0].card,'The next card gets its own sweep')
}
async function run(browser, layout, width) {
  const context = await browser.newContext({ viewport: { width, height: 844 }, isMobile: true, hasTouch: true })
  const page = await context.newPage(), errors = [], calls = []
  let fail = false, releaseImage, imageHeld = false
  let releaseFirstFeed
  const firstFeed = new Promise(resolve => { releaseFirstFeed = resolve })
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/app/**', async route => {
    const url = new URL(route.request().url())
    let data = { records: [], current: 1, pages: 0 }
    if (url.pathname === '/app/home/config') data = { channels: [1,2,3,4,5].map(id => ({id, name: '频道'+id})), entries: [], news: {enabled:false,items:[]}, defaultFeedLayout: layout }
    else if (url.pathname === '/app/post/list') {
      const channel = Number(url.searchParams.get('channelId') || 1), pageNum = Number(url.searchParams.get('pageNum') || 1)
      calls.push({channel,pageNum})
      if (calls.length === 1) await firstFeed
      await pause(channel === 2 ? 700 : 220)
      if (channel === 5 && fail) return route.fulfill({json:{code:'1000',msg:'测试加载失败'}})
      data = { records: channel === 4 ? [] : Array.from({length:6}, (_,i) => record(channel,i)), current:pageNum, pages:1 }
    }
    await route.fulfill({json:{code:'0000',data}})
  })
  await page.route('http://channel-loading.test/**', async route => {
    if (route.request().url().endsWith('/3-0.svg') && !imageHeld) { imageHeld=true; await new Promise(resolve => { releaseImage=resolve }) }
    await route.fulfill({contentType:'image/svg+xml', body:'<svg xmlns="http://www.w3.org/2000/svg" width="160" height="90"><rect width="160" height="90" fill="#729dab"/></svg>'})
  })
  await page.goto(origin)
  await page.locator('.feed-loading-placeholder').waitFor()
  await assertSingleCardSweep(page,layout+'-'+width)
  assert.equal(await page.locator('.feed-loading-light').evaluate(el => getComputedStyle(el).animationDuration),'1.5s')
  releaseFirstFeed()
  await settled(page)
  await page.waitForFunction(() => !document.querySelector('.home-view .feed-reveal-covered'))
  await select(page,2); await pause(40)
  await assertInstantRestore(page,1)
  await page.waitForTimeout(750)
  assert.equal(await state(page,'s.posts[0].id'),'1-0','Returning to cache rejects the unfinished channel response')
  const scroll = page.locator('.home-view > .screen-scroll > .uni-scroll-view > .uni-scroll-view')
  await scroll.evaluate(el => { el.scrollTop=220 })
  await page.waitForTimeout(200)
  const before = await scroll.evaluate(el => el.scrollTop)
  await page.evaluate(() => { window.feedColumn = document.querySelector('.home-view .virtual-post-column') })
  await select(page,2)
  await page.locator('.feed-reveal-covered').first().waitFor()
  assert.equal(await page.locator('.feed-loading-light').count(),1)
  assert(await page.evaluate(() => window.feedColumn === document.querySelector('.home-view .virtual-post-column')), 'Switching keeps the list root')
  assert(Math.abs(await scroll.evaluate(el => el.scrollTop)-before)<1,'Loading preserves scroll offset')
  const positions = await page.locator('.home-view .feed-reveal-block').evaluateAll(nodes => nodes.slice(0,4).map(el => {const r=el.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}}))
  await page.screenshot({path:path.join(output,layout+'-'+width+'-loading.png')})
  await page.waitForFunction(() => document.querySelector('.home-view .feed-reveal-running'))
  await page.waitForFunction(() => document.querySelector('.home-view .feed-reveal-running > .feed-reveal-media'))
  const delays = await page.locator('.home-view .feed-reveal-block > .feed-reveal-content').evaluateAll(nodes => nodes.slice(0,4).map(el => parseFloat(getComputedStyle(el).transitionDelay)*1000))
  assert.deepEqual(delays.slice(0,3),[0,60,120],'Blocks stagger by 60ms in visual order')
  await settled(page)
  const afterPositions = await page.locator('.home-view .feed-reveal-block').evaluateAll(nodes => nodes.slice(0,4).map(el => {const r=el.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}}))
  positions.forEach((rect,i) => Object.keys(rect).forEach(key => assert(Math.abs(rect[key]-afterPositions[i][key])<1,layout+' skeleton geometry '+i+'.'+key)))
  assert(Math.abs(await scroll.evaluate(el => el.scrollTop)-before)<1,'Reveal preserves scroll offset')
  const count = calls.length
  await assertInstantRestore(page,1); await assertInstantRestore(page,2)
  assert.equal(calls.length,count,'Fresh channel snapshots avoid repeated requests')
  await state(page,"s.channelCache.get('1:false').at = Date.now()-61000")
  await assertInstantRestore(page,1)
  await page.waitForTimeout(300)
  assert.equal(calls.length,count+1,'Expired snapshots validate once in the background')
  await assertInstantRestore(page,2)
  await select(page,3)
  await settled(page)
  for(let i=0; !releaseImage && i<100;i++) await pause(20)
  assert(releaseImage,'Slow image request started')
  assert(await page.locator('.feed-reveal-covered .feed-reveal-mask-media').count()>0,'Slow image stays covered')
  releaseImage(); await page.waitForFunction(() => !document.querySelector('.home-view .feed-reveal-covered'))
  await select(page,4); await settled(page)
  assert.equal(await page.locator('.home-view .app-empty-state').count(),1)
  assert.equal(await page.locator('.home-view .feed-reveal-mask').count(),0)
  const emptyCalls = calls.length
  await assertInstantRestore(page,1); await assertInstantRestore(page,4)
  assert.equal(calls.length,emptyCalls,'Empty channels are cached too')
  fail=true; await select(page,5); await settled(page)
  assert((await page.locator('.home-view .load-tip').innerText()).includes('测试加载失败'))
  fail=false; await page.locator('.home-view [aria-label="重试"]').click(); await settled(page)
  assert.equal(await state(page,'s.posts[0].id'),'5-0')
  await state(page,'s.clearChannelCache()'); await select(page,2); await pause(50); await select(page,3)
  await settled(page); await pause(750)
  assert.equal(await state(page,'s.posts[0].id'),'3-0','Late response cannot overwrite the latest channel')
  await page.emulateMedia({reducedMotion:'reduce'})
  await select(page,1); await page.locator('.feed-reveal-covered').first().waitFor()
  assert.equal(await page.locator('.feed-loading-light').evaluate(el => getComputedStyle(el).animationName),'none')
  await settled(page)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth),width)
  assert.deepEqual(errors,[])
  await page.screenshot({path:path.join(output,layout+'-'+width+'-ready.png')})
  await context.close()
  return {layout,width,requests:calls.length,singleCardSweep:true,instantCacheRestore:true,staleCacheBackgroundRefresh:true,geometryStable:true,scrollStable:true,errors}
}
;(async()=>{
  fs.mkdirSync(output,{recursive:true})
  const browser=await chromium.launch({headless:true,executablePath:process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'})
  try {
    const results=[]
    for(const layout of ['single','waterfall']) for(const width of [320,390,430]) results.push(await run(browser,layout,width))
    fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({status:'passed',results},null,2))
    console.log('PASS: single/waterfall, 3 widths, one card per 1.5s sweep, 60ms block delays, exact mask geometry, scroll stability, instant cached-channel restore, stale background refresh, slow images, empty/error/retry, stale responses, reduced motion.')
  } finally {await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})

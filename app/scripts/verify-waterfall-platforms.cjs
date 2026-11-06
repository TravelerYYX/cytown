// Compare real feed geometry with the connected HBuilder Android WebView.
const { chromium } = require('playwright-core')
const { connect } = require('./android-cdp.cjs')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const output = path.resolve(__dirname, '../artifacts/waterfall')
const snapshot = `(()=>({width:innerWidth,columns:[...document.querySelectorAll('.home-view .waterfall-column')].map(n=>[...n.querySelectorAll('.waterfall-post')].map(c=>c.dataset.postId)),cards:[...document.querySelectorAll('.home-view .waterfall-post')].map(n=>{const f=n.querySelector('.waterfall-cover'),r=f?.getBoundingClientRect(),image=f?.querySelector('.waterfall-image img');return{id:n.dataset.postId,ratio:r?r.width/r.height:null,natural:image?image.naturalWidth/image.naturalHeight:null}})}))()`
;(async () => {
  const android = await connect()
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const app = await android.evaluate(snapshot)
    assert(app.cards.length > 0, 'Android home waterfall must be open')
    const page = await browser.newPage({ viewport: { width: app.width, height: 900 }, isMobile: true, hasTouch: true })
    await page.goto(process.env.UI_TEST_URL || 'http://127.0.0.1:15273')
    await page.locator('.home-view .post-card, .home-view .waterfall-post').first().waitFor()
    await page.evaluate(() => uni.setStorageSync('settings_feed_layout', 'waterfall'))
    await page.reload()
    await page.waitForFunction(count => document.querySelectorAll('.home-view .waterfall-post').length === count, app.cards.length)
    await page.waitForFunction(() => {
      const images = [...document.querySelectorAll('.home-view .waterfall-image > div')]
      return images.length > 0 && images.every(image => getComputedStyle(image).backgroundImage !== 'none')
    })
    const h5 = await page.evaluate(snapshot)
    assert.deepEqual(app.columns, h5.columns, 'APP/H5 column membership must match')
    for (const card of app.cards) {
      const other = h5.cards.find(item => item.id === card.id)
      if (card.ratio) {
        assert(Math.abs(card.ratio - other.ratio) < .01, 'APP/H5 frame ratio must match: ' + card.id)
        assert(Math.abs(card.ratio - card.natural) < .01, 'native cover must retain full frame: ' + card.id)
      }
    }
    const screenshot = await android.call('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(output, 'android-full-video-cover.png'), Buffer.from(screenshot.data, 'base64'))
    await page.screenshot({ path: path.join(output, 'h5-full-video-cover.png') })
    await page.locator('.waterfall-post').filter({ has: page.locator('.waterfall-duration', { hasText: '00:48' }) }).screenshot({ path: path.join(output, 'portrait-cover-restored.png') })
    fs.writeFileSync(path.join(output, 'app-h5-cover-comparison.json'), JSON.stringify({ passed: true, android: app, h5 }, null, 2))
    console.log('APP/H5 real-data column membership, frame proportions and full portrait cover comparison passed.')
  } finally { android.close(); await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

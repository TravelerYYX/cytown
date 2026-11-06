// Optional visual preview using existing local artwork, without changing posts or media files.
const { chromium } = require('playwright-core')
const { fixtures } = require('./verify-waterfall.cjs')
const fs = require('node:fs')
const path = require('node:path')
const origin = process.env.UI_TEST_URL || 'http://127.0.0.1:15273'
const artworkRoot = path.resolve(__dirname, '../../ciyuantoen-back/ciyuantown-back/uploads/posts/133')
const artwork = fs.existsSync(artworkRoot) ? fs.readdirSync(artworkRoot).map(dir => path.join(artworkRoot, dir, 'preview.png')).filter(file => fs.existsSync(file)) : []
;(async () => {
  if (!artwork.length) throw new Error('No local artwork found for the optional preview; use check:waterfall for portable fixture screenshots.')
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true })
    await fixtures(page)
    let index = 0
    await page.route('http://waterfall.test/**', route => route.fulfill({ contentType: 'image/png', body: fs.readFileSync(artwork[index++ % artwork.length]), headers: { 'Access-Control-Allow-Origin': '*' } }))
    await page.goto(origin)
    await page.evaluate(() => uni.setStorageSync('settings_feed_layout', 'waterfall'))
    await page.reload()
    await page.waitForFunction(() => document.querySelectorAll('.waterfall-post').length === 10)
    await page.waitForFunction(() => [...document.querySelectorAll('.waterfall-image img')].every(image => image.complete && image.naturalWidth > 0))
    await page.locator('.home-view > .screen-scroll > .uni-scroll-view > .uni-scroll-view').evaluate(el => { el.scrollTop = 190 })
    await page.waitForTimeout(200)
    const target = path.resolve(__dirname, '../artifacts/waterfall/390-waterfall-preview.png')
    await page.screenshot({ path: target })
    console.log(target)
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

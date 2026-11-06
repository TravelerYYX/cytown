const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const { chromium } = require('playwright-core')
const { fixtures, records } = require('./verify-performance.cjs')
const output = path.resolve(__dirname, '../../artifacts/feed-image-loading-20261026')
fs.mkdirSync(output, { recursive: true })
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const results = []
  try {
    for (const layout of ['waterfall', 'single']) for (const fault of (process.env.FEED_IMAGE_FAULT ? [process.env.FEED_IMAGE_FAULT] : ['download-stall', 'storage-stall', 'missing-cover', 'refresh-same-media', 'normal'])) {
      const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      await fixtures(page, [], layout)
      if (fault === 'missing-cover') await page.route('**/app/post/list?**', route => {
        const data = records.slice(0, 10).map(record => ({ ...record, media: record.media.map(media => media.type === 'video' ? { ...media, url: 'http://performance.test/video.mp4', coverUrl: '', previewUrl: '' } : media) }))
        return route.fulfill({ json: { code: '0000', data: { records: data, current: 1, pages: 1, total: 10 } } })
      })
      await page.addInitScript(fault => {
        window.imageProbe = { stalled: 0, aborted: 0 }
        if (fault === 'storage-stall') indexedDB.open = () => ({})
        if (fault === 'download-stall') {
          const original = window.fetch
          window.fetch = function (url, options) {
            if (String(url).includes('performance.test') && window.imageProbe.stalled < 3) {
              window.imageProbe.stalled++
              return new Promise((resolve, reject) => options?.signal?.addEventListener('abort', () => {
                window.imageProbe.aborted++; reject(new DOMException('Aborted', 'AbortError'))
              }))
            }
            return original.call(this, url, options)
          }
        }
      }, fault)
      const started = Date.now()
      await page.goto((process.env.UI_TEST_URL || 'http://127.0.0.1:15273') + '/#/pages/index/index', { waitUntil: 'domcontentloaded' })
      await page.locator('.home-view .cached-image').first().waitFor()
      try {
        await page.waitForFunction(() => {
          const images = [...document.querySelectorAll('.home-view .cached-image img')]
          const wrappers = document.querySelectorAll('.home-view .cached-image')
          return images.length >= 4 && images.length === wrappers.length && images.every(image => image.complete && image.naturalWidth > 0) && !document.querySelector('.home-view .feed-reveal-covered')
        }, null, { timeout: 17000 })
      } catch (error) {
        await page.screenshot({ path: path.join(output, layout + '-' + fault + '-failed.png') })
        console.error(await page.locator('.home-view .cached-image').evaluateAll(images => images.map(image => ({ src: image.getAttribute('src'), loaded: !!image.querySelector('img')?.naturalWidth }))))
        throw error
      }
      if (fault === 'refresh-same-media') {
        await page.locator('.home-view').evaluate(async element => {
          let vm = element.__vueParentComponent
          while (vm && !vm.setupState.refreshAll) vm = vm.parent
          await vm.setupState.refreshAll()
        })
        try {
          await page.waitForFunction(() => !document.querySelector('.home-view .feed-reveal-covered'), null, { timeout: 5000 })
        } catch (error) {
          await page.screenshot({ path: path.join(output, layout + '-' + fault + '-failed.png') })
          throw error
        }
      }
      const probe = await page.evaluate(() => window.imageProbe)
      if (fault === 'download-stall') { assert.equal(probe.stalled, 3); assert.equal(probe.aborted, 3, 'Stalled fetches must release their network resources') }
      assert.deepEqual(errors, [])
      const result = { layout, fault, elapsedMs: Date.now() - started, ...probe, errors }
      results.push(result); console.log(JSON.stringify(result))
      await page.screenshot({ path: path.join(output, layout + '-' + fault + '.png') })
      await page.close()
    }
    fs.writeFileSync(path.join(output, 'h5-regression.json'), JSON.stringify({ passed: true, results }, null, 2))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

/* 针对 IP 属地显示的浏览器验证：打开 H5，切到“我的”页，检查 IP 文案是否从占位符更新为接口结果。 */
const { chromium } = require('playwright-core')
const fs = require('node:fs')
const path = require('node:path')

const url = process.env.UI_TEST_URL || 'http://127.0.0.1:15273/'
const browserPath = process.env.UI_BROWSER_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const output = path.resolve(__dirname, '../artifacts/ip-locate-check')

;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: browserPath })
  const page = await browser.newPage({ viewport: { width: 390, height: 858 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 })
  const consoleLogs = []
  page.on('console', msg => consoleLogs.push(msg.type() + ': ' + msg.text()))
  page.on('pageerror', err => consoleLogs.push('pageerror: ' + err.message))

  console.log('open:', url)
  await page.goto(url, { waitUntil: 'load', timeout: 60000 })
  await page.waitForSelector('.app-shell', { timeout: 60000 })
  console.log('app-shell mounted')

  // 切换到“我的”tab（home/follow/publish/message/mine → index 4）
  await page.locator('.tab-bar .tab-item').nth(4).click()
  await page.waitForSelector('.mine-view', { timeout: 20000 })
  console.log('mine view shown')

  // 等待 IP 请求完成（异步），轮询 identity-meta 文案
  let ipText = ''
  for (let i = 0; i < 20; i++) {
    const texts = await page.locator('.mine-view .identity-meta').allTextContents().catch(() => [])
    const ipLine = texts.find(t => t.includes('IP')) || ''
    if (ipLine && !ipLine.includes('IP: —')) { ipText = ipLine.trim(); break }
    await new Promise(r => setTimeout(r, 500))
  }
  const allMeta = await page.locator('.mine-view .identity-meta').allTextContents().catch(() => [])
  console.log('identity-meta lines:', JSON.stringify(allMeta))
  console.log('IP line:', JSON.stringify(ipText))

  await page.screenshot({ path: path.join(output, 'mine-ip.png') })
  await browser.close()

  const requestTrace = consoleLogs.filter(l => /api\/locate|401|未知/.test(l))
  console.log('console trace:', JSON.stringify(requestTrace.slice(0, 10)))
  if (!ipText.includes('IP:')) {
    console.error('FAIL: IP 文案未渲染')
    process.exit(1)
  }
  console.log('PASS: IP 文案 =', ipText)
})().catch(err => { console.error('ERROR:', err.message); process.exit(1) })

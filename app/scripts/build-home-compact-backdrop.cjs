// Split one rendering of the existing banner into an opaque header and
// transparent corner cover. Both pieces retain the same wallpaper pixels;
// stretching the header for status-bar insets does not change their shared edge.
const fs = require('node:fs')
const path = require('node:path')
const { chromium } = require('playwright-core')
const output = path.resolve(__dirname, '../static/images')
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage()
    const source = fs.readFileSync(path.join(output, 'home-banner.jpg')).toString('base64')
    const parts = await page.evaluate(async source => {
      const image = new Image()
      image.src = 'data:image/jpeg;base64,' + source
      await image.decode()
      const scale = 3, width = 750 * scale, header = 148 * scale, radius = 32 * scale
      const canvas = document.createElement('canvas')
      canvas.width = width; canvas.height = header + radius
      const context = canvas.getContext('2d')
      context.fillStyle = '#103970'; context.fillRect(0, 0, width, canvas.height)
      context.globalAlpha = .08
      // Match HomeBanner's aspectFill at the reference 40rpx status inset.
      const fit = Math.max(width / image.width, header / image.height)
      context.drawImage(image, (width - image.width * fit) / 2, (header - image.height * fit) / 2, image.width * fit, image.height * fit)
      const top = document.createElement('canvas')
      top.width = width; top.height = header
      top.getContext('2d').drawImage(canvas, 0, 0)
      const corners = document.createElement('canvas')
      corners.width = width; corners.height = radius
      const cornerContext = corners.getContext('2d')
      cornerContext.drawImage(canvas, 0, -header)
      // Subtract the white surface's rounded top, keeping its interior clear.
      cornerContext.globalCompositeOperation = 'destination-out'
      cornerContext.beginPath()
      cornerContext.moveTo(radius, 0); cornerContext.lineTo(width - radius, 0)
      cornerContext.arcTo(width, 0, width, radius, radius)
      cornerContext.lineTo(width, radius * 2); cornerContext.lineTo(0, radius * 2)
      cornerContext.lineTo(0, radius); cornerContext.arcTo(0, 0, radius, 0, radius)
      cornerContext.closePath(); cornerContext.fill()
      return { header: top.toDataURL('image/png'), corners: corners.toDataURL('image/png') }
    }, source)
    for (const [part, data] of Object.entries(parts)) {
      const file = path.join(output, 'home-compact-' + part + '.png')
      fs.writeFileSync(file, Buffer.from(data.split(',')[1], 'base64'))
      console.log(path.basename(file) + ': ' + fs.statSync(file).size + ' bytes')
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

// Editable vector originals; raster PNGs keep the same alpha on all uni-app targets.
const fs = require('node:fs')
const path = require('node:path')
const { chromium } = require('playwright-core')
const output = path.resolve(__dirname, '../static/images/avatar-frames')
const palettes = [
  { id: 'star-sea', dark: '#427ca6', blue: '#7ccfee', pale: '#e8faff', middle: '#aedfea', gold: '#f8cd71' },
  { id: 'sakura-mist', dark: '#9d7bab', blue: '#d8a7e1', pale: '#fff0fa', middle: '#edc8e6', gold: '#ffcf9d' }
]
const star = (x, y, size, fill, stroke) => `<path d="M${x} ${y-size} Q${x+size*.22} ${y-size*.22} ${x+size} ${y} Q${x+size*.22} ${y+size*.22} ${x} ${y+size} Q${x-size*.22} ${y+size*.22} ${x-size} ${y} Q${x-size*.22} ${y-size*.22} ${x} ${y-size}Z" fill="${fill}" stroke="${stroke}" stroke-width="1.4"/>`
function svg(p) { return `<svg xmlns="http://www.w3.org/2000/svg" width="152" height="152" viewBox="0 0 152 152">
<defs><linearGradient id="ring" x1="0" y1="0" x2="1" y2="1"><stop stop-color="${p.pale}"/><stop offset=".45" stop-color="${p.blue}"/><stop offset="1" stop-color="${p.dark}"/></linearGradient></defs>
<circle cx="76" cy="76" r="56" fill="none" stroke="${p.dark}" stroke-width="5"/>
<circle cx="76" cy="76" r="56" fill="none" stroke="url(#ring)" stroke-width="3.5"/>
<path d="M24 64 A54 54 0 0 1 95 25 M121 105 A54 54 0 0 1 58 128" fill="none" stroke="${p.pale}" stroke-width="1.5" stroke-linecap="round"/>
<path d="M22 89 C8 84 9 75 20 75 C7 68 13 57 24 63" fill="${p.middle}" stroke="${p.dark}" stroke-width="1.7"/>
<path d="M129 89 C142 82 140 75 130 76 C144 68 139 58 129 63" fill="${p.middle}" stroke="${p.dark}" stroke-width="1.7"/>
<path d="M19 36 C12 35 14 26 22 26 C20 18 29 14 35 20 C39 11 52 14 53 24 C66 23 67 36 55 37Z" fill="${p.pale}" stroke="${p.dark}" stroke-width="1.8"/>
<path d="M22 33 H53" stroke="${p.blue}" stroke-width="2" stroke-linecap="round"/>
${star(41,17,8,p.gold,'#c1a568')}${star(117,30,12,p.gold,p.dark)}${star(135,48,4,p.pale,p.dark)}
<path d="M112 121 L130 116 L125 128 L134 135 L116 137 L106 130Z" fill="${p.blue}" stroke="${p.dark}" stroke-width="1.8"/>
<path d="M24 120 L39 125 L45 135 L27 138 L29 129 L17 129Z" fill="${p.middle}" stroke="${p.dark}" stroke-width="1.8"/>
<path d="M40 126 Q76 143 113 126 L109 136 Q76 149 44 136Z" fill="${p.pale}" stroke="${p.dark}" stroke-width="1.8"/>
<path d="M50 133 Q76 143 103 133" fill="none" stroke="${p.blue}" stroke-width="1.5"/>
${star(76,136,8,p.gold,p.dark)}${star(19,103,5,p.gold,p.dark)}
<circle cx="128" cy="103" r="3.5" fill="${p.pale}" stroke="${p.dark}" stroke-width="1.4"/>
</svg>` }
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ executablePath: process.env.UI_BROWSER_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  try {
    const page = await browser.newPage({ viewport: { width: 456, height: 456 }, deviceScaleFactor: 1 })
    for (const palette of palettes) {
      const source = svg(palette)
      fs.writeFileSync(path.join(output, palette.id + '.svg'), source)
      await page.setContent('<style>html,body{margin:0;background:transparent}svg{width:456px;height:456px;display:block}</style>' + source)
      await page.screenshot({ path: path.join(output, palette.id + '.png'), omitBackground: true })
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })

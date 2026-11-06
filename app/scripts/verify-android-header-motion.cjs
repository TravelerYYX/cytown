// Real native UI/ADB acceptance. Does not write account or backend data.
const { execFileSync, spawnSync } = require('node:child_process')
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const adb = process.env.ADB_PATH || 'D:/Editor/HBuilderX/plugins/launcher-tools/tools/adbs/adb.exe'
const serial = process.env.CT_ANDROID_DEVICE_SERIAL || '127.0.0.1:16384'
const output = path.resolve(__dirname, '../artifacts/header-motion')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const run = args => execFileSync(adb, ['-s', serial, ...args], { windowsHide: true, timeout: 15000 })
const tap = (x, y) => run(['shell', 'input', 'tap', String(x), String(y)])
const swipe = (x, y, endY) => run(['shell', 'input', 'swipe', String(x), String(y), String(x), String(endY), '1200'])
function capture(name) { fs.writeFileSync(path.join(output, 'android-' + name + '.png'), run(['exec-out', 'screencap', '-p'])) }
function inspect(name) {
  // MuMu sometimes exits 139 after successfully writing the hierarchy.
  spawnSync(adb, ['-s', serial, 'shell', 'uiautomator', 'dump', '/sdcard/ct-header-motion.xml'], { windowsHide: true, timeout: 15000 })
  const xml = run(['shell', 'cat', '/sdcard/ct-header-motion.xml']).toString('utf8')
  fs.writeFileSync(path.join(output, 'android-' + name + '.xml'), xml)
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(match => {
    const attrs = Object.fromEntries([...match[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(attribute => [attribute[1], attribute[2]]))
    const bounds = (attrs.bounds || '').match(/-?\d+/g)?.map(Number) || []
    return { label: attrs['content-desc'], text: attrs.text, enabled: attrs.enabled, focusable: attrs.focusable, bounds }
  })
}
function pinned(nodes, channels, statusHeight) {
  const avatar = nodes.find(node => node.label === '用户头像')
  const search = nodes.find(node => node.label?.endsWith(' 搜索'))
  assert(avatar && search, 'Pinned avatar and search must be accessible')
  assert.equal(search.enabled, 'true'); assert.equal(search.focusable, 'true')
  const tabs = channels.map(label => {
    const matches = nodes.filter(node => node.label === label)
    assert.equal(matches.length, 1, 'Exactly one accessible channel: ' + label)
    assert.equal(matches[0].enabled, 'true')
    return matches[0]
  })
  assert(tabs.every(node => Math.abs(node.bounds[1] - (statusHeight + 108 * deviceWidth / 750)) < 4), 'Channels must meet the bottom of the compact header')
  return { avatar, search, tabs }
}
let deviceWidth = 0
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const dimensions = run(['shell', 'wm', 'size']).toString().match(/(\d+)x(\d+)/)
  deviceWidth = +dimensions[1]; const height = +dimensions[2]
  const homeX = Math.round(deviceWidth * .1), navY = Math.round(height * .96)
  tap(homeX, navY); await pause(350)
  const expanded = inspect('expanded'); capture('expanded')
  assert(!expanded.some(node => node.label === '用户头像'), 'Hidden search controls must be absent from native accessibility')
  const row = expanded.filter(node => node.label && node.bounds[1] > deviceWidth * .4 && node.bounds[1] < deviceWidth * .8 && node.bounds[3] - node.bounds[1] < deviceWidth * .15)
  const tabTop = row.find(node => node.label === '酒馆')?.bounds[1]
  assert(tabTop != null, 'This acceptance fixture requires the existing 酒馆 home channel')
  const channels = expanded.filter(node => node.bounds[1] === tabTop && node.label).map(node => node.label)
  const statusHeight = tabTop - deviceWidth * 420 / 750
  const start = Math.round(height * .7), x = Math.round(deviceWidth * .78)
  swipe(x, start, Math.round(start - deviceWidth * .36)); await pause(160); capture('middle')
  swipe(x, start, Math.round(start - deviceWidth * .25)); await pause(200)
  capture('pinned'); const initial = pinned(inspect('pinned'), channels, statusHeight)
  const search = initial.search.bounds, searchX = Math.round((search[0] + search[2]) / 2), searchY = Math.round((search[1] + search[3]) / 2)
  run(['shell', 'input', 'motionevent', 'DOWN', String(searchX), String(searchY)]); await pause(150); capture('pressed')
  run(['shell', 'input', 'motionevent', 'UP', String(searchX), String(searchY)]); await pause(180); capture('released')
  tap(Math.round(deviceWidth * .3), navY); await pause(250)
  tap(homeX, navY); await pause(400)
  pinned(inspect('tab-return'), channels, statusHeight); capture('tab-return')
  const avatar = initial.avatar.bounds
  tap(Math.round((avatar[0] + avatar[2]) / 2), Math.round((avatar[1] + avatar[3]) / 2)); await pause(350)
  capture('avatar-route'); run(['shell', 'input', 'keyevent', '4']); await pause(400)
  pinned(inspect('route-return'), channels, statusHeight); capture('route-return')
  // Reverse into the transition range, then restore through the existing home button.
  swipe(x, Math.round(height * .4), Math.round(height * .4 + deviceWidth * .22)); await pause(180); capture('reverse')
  tap(homeX, navY); await pause(350); capture('returned-top')
  assert(!inspect('returned-top').some(node => node.label === '用户头像'), 'Return to top hides search accessibility again')
  fs.writeFileSync(path.join(output, 'android-report.json'), JSON.stringify({ status: 'passed', serial, resolution: [deviceWidth, height], channels, statusHeight, pinned: initial, scenarios: ['expanded hidden controls', 'slow upward transition', 'pinned channel seam', 'press/release screenshots', 'tab restoration', 'avatar route return', 'reverse scroll', 'return to top'], scope: 'Actual Android native simulator UI and accessibility hierarchy; physical device feel and frame-rate performance not asserted.' }, null, 2))
  console.log('Android native header passed: channel seam, single accessible channel set, visible search controls, warm tab/route restoration and return to top; screenshots saved.')
})().catch(error => { fs.writeFileSync(path.join(output, 'android-report.json'), JSON.stringify({ status: 'failed', error: String(error) }, null, 2)); console.error(error); process.exitCode = 1 })

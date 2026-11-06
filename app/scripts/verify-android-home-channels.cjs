// Actual Android UI regression: channel content must survive native slot updates.
// Uses existing backend records and navigation only; does not modify business data.
const { execFileSync } = require('node:child_process')
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const adb = process.env.ADB_PATH || 'D:/Editor/HBuilderX/plugins/launcher-tools/tools/adbs/adb.exe'
const output = process.env.ANDROID_TEST_OUTPUT || path.resolve(__dirname, '../../artifacts/home-channel-motion/android-review')
const backend = process.env.ANDROID_TEST_API_URL || 'http://127.0.0.1:8080'
const devices = execFileSync(adb, ['devices'], { encoding: 'utf8', windowsHide: true }).split(/\r?\n/).map(line => line.match(/^(\S+)\s+device$/)?.[1]).filter(Boolean)
const serial = process.env.CT_ANDROID_DEVICE_SERIAL || (devices.length === 1 ? devices[0] : '')
assert(serial, 'Specify CT_ANDROID_DEVICE_SERIAL when more than one device is connected')
const run = args => execFileSync(adb, ['-s', serial, ...args], { windowsHide: true, timeout: 15000, maxBuffer: 24 * 1024 * 1024 })
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
function capture(name) { fs.writeFileSync(path.join(output, name + '.png'), run(['exec-out', 'screencap', '-p'])) }
function inspect(name) {
  run(['shell', 'uiautomator', 'dump', '/sdcard/ct-home-channels.xml'])
  const xml = run(['shell', 'cat', '/sdcard/ct-home-channels.xml']).toString('utf8')
  fs.writeFileSync(path.join(output, name + '.xml'), xml)
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(match => {
    const attrs = Object.fromEntries([...match[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], m[2]]))
    return { label: attrs['content-desc'] || attrs.text || '', bounds: (attrs.bounds || '').match(/-?\d+/g)?.map(Number) || [], className: attrs.class }
  })
}
function tap(node) { const [x1,y1,x2,y2] = node.bounds; run(['shell', 'input', 'tap', String(Math.round((x1+x2)/2)), String(Math.round((y1+y2)/2))]) }
async function data(url) { const response = await fetch(backend + url); assert(response.ok); const json = await response.json(); assert.equal(json.code, '0000'); return json.data }
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const pid = run(['shell', 'pidof', 'io.dcloud.uniappx']).toString('utf8').trim()
  assert(pid, 'The native app must be running')
  const config = await data('/app/home/config'), channels = config.channels, results = []
  let nodes = inspect('regression-start')
  assert(channels.every(channel => nodes.some(node => node.label === channel.name)), 'Start on the expanded home channel bar')
  const tabs = channels.map(channel => ({ ...channel, node: nodes.find(node => node.label === channel.name) }))
  const contentTop = Math.max(...tabs.map(tab => tab.node.bounds[3]))
  const navTop = nodes.find(node => node.label === '首页' && node.bounds[1] > contentTop)?.bounds[1]
  assert(navTop, 'Home navigation is visible')
  let populated, empty
  for (const tab of tabs) {
    const page = await data('/app/post/list?pageNum=1&pageSize=10&channelId=' + tab.id)
    tab.records = page.records
    if (page.records.length && !populated) populated = tab
    if (!page.records.length && !empty) empty = tab
    tap(tab.node); await pause(650)
    nodes = inspect('channel-' + tab.id); capture('channel-' + tab.id)
    const content = nodes.filter(node => node.bounds[1] >= contentTop && node.bounds[3] <= navTop && node.bounds[3] > node.bounds[1])
    if (page.records.length) {
      assert(content.some(node => /点赞/.test(node.label)), tab.name + ': backend returned posts, but native content is missing')
      assert(content.some(node => page.records.some(record => node.label === record.authorNickname || node.label === record.title)), tab.name + ': actual returned post is visible')
    } else {
      // A real empty channel has a 240rpx image; a detached slot has no image at all.
      assert(content.some(node => /ImageView/.test(node.className) && node.bounds[2]-node.bounds[0] > 200), tab.name + ': empty-state illustration is missing')
    }
    results.push({ channel: tab.name, records: page.records.length, visibleContent: true })
  }
  assert(populated && empty, 'Regression requires both populated and empty channels')
  for (let index = 0; index < 3; index++) {
    tap(empty.node); await pause(250); tap(populated.node); await pause(600)
    nodes = inspect('return-to-posts-' + index)
    assert(nodes.some(node => /点赞/.test(node.label) && node.bounds[1] >= contentTop), 'Posts must reappear after empty -> populated switch ' + index)
  }
  for (let index = 0; index < 8; index++) tap((index % 2 ? populated : empty).node)
  await pause(800)
  nodes = inspect('rapid-final'); capture('rapid-final')
  assert(nodes.some(node => /点赞/.test(node.label) && node.bounds[1] >= contentTop), 'Rapid switching must retain the final channel content')
  assert.equal(run(['shell', 'pidof', 'io.dcloud.uniappx']).toString('utf8').trim(), pid, 'The app must survive all channel switches without restarting')
  const log = run(['logcat', '-d', '--pid=' + pid, '-t', '1000', '-s', 'console:I', 'AndroidRuntime:E']).toString('utf8')
  fs.writeFileSync(path.join(output, 'regression-runtime.log'), log)
  assert(!/FATAL EXCEPTION|TypeError:|ReferenceError:|Uncaught/.test(log), 'Native runtime error; inspect regression-runtime.log')
  fs.writeFileSync(path.join(output, 'native-verification.json'), JSON.stringify({ status: 'passed', serial, scenarios: ['all channels', 'populated content', 'empty illustration', 'repeated empty-to-populated switching', 'rapid reversal'], results, scope: 'Connected physical Android device, native bytecode runtime, existing local backend; no business writes' }, null, 2))
  console.log('PASS: actual Android native UI, ' + tabs.length + ' channels, visible real posts and empty states, repeated switching, rapid reversal, no native runtime errors.')
})().catch(error => { console.error(error); process.exitCode = 1 })

// Exercises the actual Vapor initial-layout/text-registry crash on Android.
// Start on the expanded home screen with an existing signed-in test session.
// Only opens/closes the check-in page; never claims or makes up a reward.
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const { execFileSync, spawn } = require('node:child_process')
const { uniqueDevices } = require('./android-dev-tunnel.cjs')
const { sourceFingerprint } = require('./source-fingerprint.cjs')
const root = path.resolve(__dirname, '..')
const output = process.env.ANDROID_TEST_OUTPUT || path.join(root, 'artifacts/checkin-navigation')
const adb = process.env.ADB_PATH || 'D:/Editor/HBuilderX/plugins/launcher-tools/tools/adbs/adb.exe'
const execute = args => execFileSync(adb, args, { encoding: 'utf8', windowsHide: true, timeout: 15000, maxBuffer: 16 * 1024 * 1024 })
const devices = execute(['devices']).split(/\r?\n/).map(line => line.match(/^(\S+)\s+device$/)?.[1]).filter(Boolean)
const candidates = uniqueDevices(execute, devices)
const serial = process.env.CT_ANDROID_DEVICE_SERIAL || (candidates.length === 1 ? candidates[0] : '')
assert(serial && devices.includes(serial), 'Connect one Android device or set CT_ANDROID_DEVICE_SERIAL')
const run = args => execute(['-s', serial, ...args])
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const iterations = Number(process.env.ANDROID_TEST_ITERATIONS || 30)
assert(Number.isInteger(iterations) && iterations > 0)
function inspect(name) {
  run(['shell', 'uiautomator', 'dump', '/sdcard/ct-checkin-navigation.xml'])
  const xml = run(['shell', 'cat', '/sdcard/ct-checkin-navigation.xml'])
  fs.writeFileSync(path.join(output, name + '.xml'), xml)
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(match => {
    const attrs = Object.fromEntries([...match[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], m[2]]))
    return { label: attrs['content-desc'] || attrs.text || '', bounds: (attrs.bounds || '').match(/\d+/g)?.map(Number) || [] }
  })
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const pid = run(['shell', 'pidof', 'io.dcloud.uniappx']).trim()
  assert(pid, 'Run the project in the Android debug base first')
  const entry = inspect('home').find(node => node.label === '每日签到')
  assert(entry, 'Start on the expanded home screen with the daily check-in entry visible')
  const [x1, y1, x2, y2] = entry.bounds
  const stream = spawn(adb, ['-s', serial, 'logcat', '--pid=' + pid, '-T', '1', '-v', 'threadtime'], { windowsHide: true })
  let log = ''
  stream.stdout.on('data', chunk => { log += chunk.toString('utf8') })
  const completed = []
  const before = sourceFingerprint(root)
  try {
    await pause(100)
    for (let index = 0; index < iterations; index++) {
      const start = log.length
      run(['shell', 'input', 'tap', String(Math.round((x1 + x2) / 2)), String(Math.round((y1 + y2) / 2))])
      const deadline = Date.now() + 6000
      while (!/onReady.*pageUrl:\/pages\/checkin\/index/.test(log.slice(start)) && !/FATAL EXCEPTION/.test(log.slice(start)) && Date.now() < deadline) await pause(50)
      assert(!/FATAL EXCEPTION|ConcurrentModificationException/.test(log.slice(start)), 'Native crash on entry ' + (index + 1))
      assert(/onReady.*pageUrl:\/pages\/checkin\/index/.test(log.slice(start)), 'Check-in did not finish initial layout on entry ' + (index + 1))
      await pause(450)
      assert.equal(run(['shell', 'pidof', 'io.dcloud.uniappx']).trim(), pid, 'Process restarted on entry ' + (index + 1))
      if (index === 0 || index === iterations - 1) {
        const nodes = inspect('checkin-' + (index + 1))
        assert(nodes.some(node => node.label === '签到规则'), 'Check-in controls must be visible')
        assert(nodes.some(node => /^第\d+天/.test(node.label)), 'Real check-in rewards must load after initial layout')
      }
      run(['shell', 'input', 'keyevent', '4'])
      await pause(350)
      completed.push(index + 1)
      if ((index + 1) % 5 === 0) console.log('Completed ' + (index + 1) + '/' + iterations + ' check-in entries; PID ' + pid)
    }
    assert(!/FATAL EXCEPTION|ConcurrentModificationException/.test(log), 'Native runtime crash')
    assert.equal(sourceFingerprint(root), before, 'Source changed during device verification')
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ status: 'passed', serial, pid, iterations, sourceSHA256: before, scenarios: ['initial layout finishes on every entry', 'real rewards load', 'repeated return to home', 'same process survives'], scope: 'Physical Android debug base; navigation only, no reward claims or makeup actions' }, null, 2))
    console.log('PASS: ' + iterations + ' Android check-in entries, loaded rewards, no native crash or process restart.')
  } finally {
    stream.kill()
    fs.writeFileSync(path.join(output, 'runtime.log'), log)
    if (completed.length !== iterations) fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ status: 'failed', serial, pid, completed: completed.length, iterations }, null, 2))
  }
})().catch(error => { console.error(error); process.exitCode = 1 })

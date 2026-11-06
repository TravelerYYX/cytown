// Read-only Android navigation; count requests inside the actual native V8 runtime.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const { execFileSync } = require('node:child_process')
const { sourceFingerprint } = require('./source-fingerprint.cjs')
const root = path.resolve(__dirname, '..'), output = path.join(root, 'artifacts/channel-cache/android')
const adb = process.env.ADB_PATH || 'D:/Editor/HBuilderX/plugins/launcher-tools/tools/adbs/adb.exe'
const devices = execFileSync(adb, ['devices'], { encoding: 'utf8', windowsHide: true }).split(/\r?\n/).map(line => line.match(/^(\S+)\s+device$/)?.[1]).filter(Boolean)
const serial = process.env.CT_ANDROID_DEVICE_SERIAL || (devices.length === 1 ? devices[0] : '')
assert(serial, 'Connect one Android device or set CT_ANDROID_DEVICE_SERIAL')
const run = args => execFileSync(adb, ['-s', serial, ...args], { windowsHide: true, timeout: 15000, maxBuffer: 24 * 1024 * 1024 })
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
function inspect(name) {
  try { run(['shell', 'uiautomator', 'dump', '/sdcard/ct-channel-cache.xml']) }
  catch (error) { if (!error.stdout?.toString().includes('UI hierchary dumped')) throw error }
  const xml = run(['shell', 'cat', '/sdcard/ct-channel-cache.xml']).toString('utf8')
  fs.writeFileSync(path.join(output, name + '.xml'), xml)
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(match => {
    const attrs = Object.fromEntries([...match[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(item => [item[1], item[2]]))
    return { label: attrs['content-desc'] || attrs.text || '', bounds: (attrs.bounds || '').match(/\d+/g)?.map(Number) || [] }
  })
}
function capture(name) { fs.writeFileSync(path.join(output, name + '.png'), run(['exec-out', 'screencap', '-p'])) }
function tap(tab) { const [x1, y1, x2, y2] = tab.bounds; run(['shell', 'input', 'tap', String(Math.round((x1 + x2) / 2)), String(Math.round((y1 + y2) / 2))]) }
async function inspector(port) {
  const targets = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json()
  const url = new URL(targets.find(target => target.type === 'node').webSocketDebuggerUrl); url.port = port
  const socket = new WebSocket(url), pending = new Map(); let id = 0
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }) })
  socket.addEventListener('message', event => {
    const result = JSON.parse(event.data), task = pending.get(result.id)
    if (!task) return
    pending.delete(result.id); clearTimeout(task.timer)
    if (result.error || result.result?.exceptionDetails) task.reject(Error(JSON.stringify(result.error || result.result.exceptionDetails)))
    else task.resolve(result.result.result.value)
  })
  return {
    evaluate(expression) { return new Promise((resolve, reject) => {
      const current = ++id, timer = setTimeout(() => { pending.delete(current); reject(Error('Native inspector timed out')) }, 15000)
      pending.set(current, { resolve, reject, timer })
      socket.send(JSON.stringify({ id: current, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } }))
    }) },
    close() { socket.close() }
  }
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const source = sourceFingerprint(root)
  const pid = run(['shell', 'pidof', 'io.dcloud.uniappx']).toString().trim()
  const backend = process.env.ANDROID_TEST_API_URL || 'http://127.0.0.1:8080'
  const envelope = await (await fetch(backend + '/app/home/config')).json()
  assert.equal(envelope.code, '0000')
  const channels = envelope.data.channels, nodes = inspect('before')
  const tabs = channels.map(channel => ({ ...channel, ...nodes.find(node => node.label === channel.name) }))
  assert(tabs.every(tab => tab.bounds?.length === 4), 'Start on the expanded home channel bar')
  const port = run(['forward', 'tcp:0', 'tcp:9229']).toString().trim()
  let client
  try {
    client = await inspector(port)
    assert.equal(await client.evaluate(`(()=>{
      if (globalThis.__ctChannelAudit) throw Error('Another native request audit is active')
      const audit = { original: uni.request, calls: [], total: 0 }
      globalThis.__ctChannelAudit = audit
      uni.request = function(options) {
        audit.total++
        if (/\\/app\\/post\\/list(?:\\?|$)/.test(options.url)) audit.calls.push({ channel: options.data?.channelId, page: options.data?.pageNum })
        return audit.original.call(this, options)
      }
      return true
    })()`), true)
    // Validate the instrumentation through a real, public, read-only API call.
    const probe = await client.evaluate(`new Promise(resolve => uni.request({ url: 'http://127.0.0.1:18080/app/home/config', success: result => resolve(result.statusCode), fail: () => resolve(0) }))`)
    assert.equal(probe, 200)
    assert((await client.evaluate('globalThis.__ctChannelAudit.total')) > 0, 'The native request counter must observe its control request')
    const started = Date.now()
    for (const tab of tabs) { tap(tab); await pause(600) }
    // Wait until any native HTTP requests have completed before the measured returns.
    await pause(1000)
    const warmed = await client.evaluate('globalThis.__ctChannelAudit.calls.length')
    for (const tab of [...tabs, tabs[0]]) { tap(tab); await pause(300) }
    const requests = await client.evaluate('globalThis.__ctChannelAudit.calls'), repeated = requests.slice(warmed)
    assert(Date.now() - started < 55_000, 'Complete the measurement before normal background revalidation')
    assert.deepEqual(repeated, [], 'Loaded channels must not request posts again on return')
    const final = inspect('after'); capture('after')
    assert(final.some(node => /点赞/.test(node.label)), 'Discovery cards must remain visible after all channel returns')
    assert.equal(run(['shell', 'pidof', 'io.dcloud.uniappx']).toString().trim(), pid, 'Native process must survive switching')
    const log = run(['logcat', '-d', '--pid=' + pid, '-t', '500', '-s', 'console:I', 'AndroidRuntime:E']).toString('utf8')
    assert(!/FATAL EXCEPTION|TypeError:|ReferenceError:|Uncaught/.test(log), 'Native runtime errors')
    assert.equal(sourceFingerprint(root), source, 'Source changed during native verification')
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ status: 'passed', serial, pid, channels: channels.map(channel => channel.name), counterControlRequest: probe, warmupRequests: requests.slice(0, warmed), returnRequests: repeated, sourceSHA256: source, scope: 'Connected Android native bytecode runtime; validated in-process request counter, existing backend, navigation and GET requests only' }, null, 2))
    console.log('PASS: Android native runtime, all ' + channels.length + ' configured channels, zero repeated feed requests on return, visible cards, same process.')
  } finally {
    if (client) {
      try { await client.evaluate('(()=>{const audit=globalThis.__ctChannelAudit;if(audit){uni.request=audit.original;delete globalThis.__ctChannelAudit}return true})()') }
      finally { client.close() }
    }
    run(['forward', '--remove', 'tcp:' + port])
  }
})().catch(error => { console.error(error); process.exitCode = 1 })

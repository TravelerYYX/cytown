const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict'), ts = require('typescript')
let now = 0, sequence = 0
const timers = new Map()
function advance(ms) {
  now += ms
  for (const [id, task] of [...timers]) if (task.at <= now) { timers.delete(id); task.fn() }
}
function load(name, dependencies = {}, globals = {}) {
  const exports = {}
  const source = fs.readFileSync(path.join(__dirname, '..', name), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText
  vm.runInNewContext(code, { exports, require: name => { assert(name in dependencies, name); return dependencies[name] },
    setTimeout: (fn, ms) => { const id = ++sequence; timers.set(id, { fn, at: now + ms }); return id }, clearTimeout: id => timers.delete(id), ...globals })
  return exports
}
const policy = load('utils/feed-autoplay-policy.uts')
for (const network of ['wifi', '2g', '3g', '4g', '5g', 'ethernet', 'none', 'unknown', '']) {
  for (const mode of ['off', 'wifi-only', 'all-networks']) {
    assert.equal(policy.allowsFeedAutoplay(mode, network), mode === 'all-networks' ? !['none', 'unknown', ''].includes(network) : mode === 'wifi-only' && network === 'wifi')
  }
}
const viewport = { top: 100, left: 0, width: 400, height: 400 }
const candidate = (key, top, height = 100, left = 0, width = 100, order = 0) => ({ key, rect: { top, left, width, height }, order })
const choose = (items, current = null) => policy.chooseFeedVideo(items, viewport, current)
assert.equal(choose([candidate('outside', 49)]), null)
assert.equal(choose([candidate('half', 50)]), 'half')
assert.equal(choose([candidate('horizontal', 200, 100, 351)]), null)
assert.equal(choose([candidate('first', 150), candidate('center', 240)]), 'center')
assert.equal(choose([candidate('upper', 200), candidate('lower', 300)], 'lower'), 'lower')
assert.equal(choose([candidate('lower', 300), candidate('upper', 200)]), 'upper')
assert.equal(policy.chooseFeedVideo([candidate('large', 0, 1000)], viewport, null), null)
const changes = [], selection = new policy.FeedAutoplaySelection(key => changes.push(key))
selection.update([candidate('a', 200)], viewport)
advance(179); assert.equal(selection.current, null)
selection.update([candidate('a', 210)], viewport)
advance(1); assert.equal(selection.current, 'a')
selection.update([candidate('a', 60), candidate('b', 250)], viewport)
advance(179); assert.equal(selection.current, 'a')
advance(1); assert.equal(selection.current, 'b')
selection.update([candidate('b', 49)], viewport); assert.equal(changes.at(-1), null)
selection.update([candidate('recycled', 250)], viewport); advance(100); selection.reset(); advance(200)
assert.equal(selection.current, null); assert(!changes.includes('recycled'))
selection.update([candidate('fast-a', 200)], viewport); advance(100)
selection.update([candidate('fast-b', 200)], viewport); advance(100); assert.equal(selection.current, null)
advance(80); assert.equal(selection.current, 'fast-b')
selection.reset(); assert.equal(timers.size, 0)

const queries = [], callbacks = new Set(), mode = { value: 'wifi-only' }
const network = load('utils/feed-network.uts', { vue: { ref: value => ({ value }) }, './feed-autoplay-policy': policy, './app-settings': { videoAutoplayMode: mode } }, {
  uni: { getNetworkType: query => queries.push(query), onNetworkStatusChange: fn => callbacks.add(fn), offNetworkStatusChange: fn => callbacks.delete(fn) }
})
const release1 = network.retainFeedNetwork(), release2 = network.retainFeedNetwork()
assert.equal(callbacks.size, 1); assert.equal(queries.length, 1)
assert.equal(network.feedNetworkAllowsAutoplay(), false)
queries[0].success({ networkType: 'wifi' }); assert.equal(network.feedNetworkAllowsAutoplay(), true)
network.refreshFeedNetwork(); assert.equal(network.feedNetworkAllowsAutoplay(), false)
callbacks.forEach(fn => fn({ isConnected: true, networkType: '4g' }))
queries[1].success({ networkType: 'wifi' }); assert.equal(network.feedNetwork.value, '4g')
assert.equal(network.feedNetworkAllowsAutoplay(), false)
mode.value = 'all-networks'; assert.equal(network.feedNetworkAllowsAutoplay(), true)
callbacks.forEach(fn => fn({ isConnected: false, networkType: 'wifi' })); assert.equal(network.feedNetworkAllowsAutoplay(), false)
network.refreshFeedNetwork(); queries.at(-1).fail(); assert.equal(network.feedNetwork.value, 'unknown')
release1(); assert.equal(callbacks.size, 1)
network.refreshFeedNetwork(); release2(); queries.at(-1).success({ networkType: 'wifi' })
assert.equal(callbacks.size, 0); assert.equal(network.feedNetwork.value, 'unknown')
console.log('Feed autoplay policy, geometry, stable selection, recycling cancellation, network changes, stale queries and subscription cleanup passed.')

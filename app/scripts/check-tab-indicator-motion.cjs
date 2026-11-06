const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict'), ts = require('typescript')
const { platformSource } = require('./platform-source.cjs')
const root = path.resolve(__dirname, '..')
function execute(source, context) {
  const exports = {}
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, { exports, ...context })
  return exports
}
const math = execute(fs.readFileSync(path.join(root, 'utils/tab-indicator-motion.uts'), 'utf8'), {})
const props = { searchable: true, active: 'a', tabs: [{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }] }
const calls = [], animations = [], mounted = [], disposed = [], resized = []
let now = 1000, reduced = false
const element = {
  getBoundingClientRect() { throw Error('A tap must not synchronously measure layout') },
  style: { setProperty(name, value) { calls.push({ type: 'style', name, value }) } },
  animate(frames, options) {
    const animation = { frames, options, onfinish: null, cancel() { calls.push({ type: 'cancel' }) } }
    calls.push({ type: 'animate' }); animations.push(animation); return animation
  }
}
const source = platformSource(fs.readFileSync(path.join(root, 'components/ChannelTabs.uvue'), 'utf8'), 'app-android').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
const api = execute(source + '\nexports.selectTab = selectTab;', {
  require: name => name === 'vue' ? { computed: fn => ({ get value() { return fn() } }), ref: value => ({ value }), getCurrentInstance: () => ({ uid: 1 }), onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => disposed.push(fn), watch() {} }
    : name.includes('tab-indicator-motion') ? math
    : name.includes('window-events') ? { observeWindowResize: fn => { resized.push(fn); return () => {} } }
    : { uxMedia: () => reduced },
  defineProps: () => props, withDefaults: value => value,
  defineEmits: () => () => calls.push({ type: 'emit' }),
  uni: { rpx2px: value => value * .52, getElementById: () => element },
  Date: { now: () => now }
})
mounted.forEach(fn => fn()); calls.length = 0
api.selectTab(props.tabs[3])
assert(calls.findIndex(call => call.type === 'animate') < calls.findIndex(call => call.type === 'emit'), 'visual feedback starts before content changes')
assert.equal(animations.length, 1, 'one animation owns both translation and contraction')
const pose = transform => { const m = transform.match(/translateX\(([^p]+)px\) scaleX\(([^)]+)\)/); return { x: Number(m[1]), scale: Number(m[2]) } }
const first = animations[0], midpoint = pose(first.frames[8].transform)
assert.equal(first.options.duration, 240)
assert(Math.abs(midpoint.x - 3 * 102.778 * .52 * .875) < 1e-6)
assert(Math.abs(midpoint.scale - .42) < 1e-6)
now += 120
api.selectTab(props.tabs[1])
const interrupted = pose(animations[1].frames[0].transform)
assert(Math.abs(interrupted.x - midpoint.x) < 1e-6, 'reversal continues at the current position')
assert(Math.abs(interrupted.scale - midpoint.scale) < 1e-6, 'reversal preserves current contraction')
const count = animations.length
api.selectTab(props.tabs[1]); assert.equal(animations.length, count, 'same target must not restart an in-flight animation')
reduced = true; api.selectTab(props.tabs[2]); assert.equal(animations.length, count, 'reduced motion commits without animation')
resized.forEach(fn => fn()); disposed.forEach(fn => fn())
console.log('PASS: native tap starts immediately, performs no layout read, uses one animation, preserves position/scale on reversal, deduplicates targets and handles reduced motion/resize/disposal (mock regression).')

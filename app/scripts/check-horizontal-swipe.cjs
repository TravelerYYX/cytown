const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const assert = require('node:assert/strict'), ts = require('typescript')
function load(file, modules = {}) {
  const exports = {}
  const source = ts.transpileModule(fs.readFileSync(path.resolve(__dirname, '..', file), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  vm.runInNewContext(source, { exports, require: name => { assert(name in modules, name); return modules[name] } })
  return exports
}
const swipeModule = load('utils/horizontal-swipe.uts')
const watchers = [], disposed = []
const { useHorizontalSwipe } = load('composables/useHorizontalSwipe.uts', {
  vue: { watch: (source, callback) => watchers.push({ source, callback }), onBeforeUnmount: callback => disposed.push(callback) },
  '@/utils/horizontal-swipe': swipeModule
})
let enabled = true, key = 'one', changes = [], prevented = 0
const swipe = useHorizontalSwipe(() => enabled, () => key, direction => changes.push(direction))
const event = (x, y, ended = false, count = 1) => ({ touches: ended ? [] : Array.from({ length: count }, () => ({ clientX: x, clientY: y })), changedTouches: [{ clientX: x, clientY: y }], preventDefault() { prevented++ } })
function trace(points, cancel = false) {
  changes = []; prevented = 0
  swipe.start(event(...points[0]))
  points.slice(1).forEach(point => swipe.move(event(...point)))
  if (cancel) swipe.cancel()
  swipe.end(event(...points.at(-1), true))
  return changes
}
assert.deepEqual(trace([[200, 200], [180, 202], [90, 205]]), [1], 'Left swipe selects the next tab once')
assert.deepEqual(trace([[80, 200], [100, 200], [200, 205]]), [-1], 'Right swipe selects the previous tab')
assert.deepEqual(trace([[200, 200], [198, 205]]), [], 'A tap must not switch tabs')
assert.equal(prevented, 0, 'Taps retain their default action')
assert.deepEqual(trace([[200, 200], [185, 201]]), [], 'Short drags must not switch tabs')
assert.deepEqual(trace([[200, 200], [201, 230], [80, 260]]), [], 'A vertical scroll cannot become a horizontal switch')
assert.equal(prevented, 0, 'Vertical scrolling and pull refresh remain available')
assert.deepEqual(trace([[200, 200], [180, 220], [80, 260]]), [], 'Diagonal scrolling must not switch tabs')
assert.deepEqual(trace([[200, 200], [80, 200]], true), [], 'Canceled gestures must not switch tabs')
changes = []; swipe.start(event(200, 200)); swipe.move(event(80, 200, false, 2)); swipe.end(event(80, 200, true))
assert.deepEqual(changes, [], 'Multitouch cancels switching')
changes = []; swipe.start(event(200, 200)); swipe.move(event(80, 200)); key = 'two'; swipe.end(event(80, 200, true))
assert.deepEqual(changes, [], 'A click or config update during a gesture cancels the old gesture')
enabled = false
assert.deepEqual(trace([[200, 200], [80, 200]]), [], 'Hidden or blocked panes must not switch tabs')
enabled = true; changes = []; swipe.start(event(200, 200)); swipe.move(event(80, 200)); disposed.forEach(callback => callback()); swipe.end(event(80, 200, true))
assert.deepEqual(changes, [], 'Unmount cancels an unfinished swipe')
console.log('Horizontal swipe: direction lock, tap/short drag, cancellation, multitouch, stale key, visibility and disposal passed')

const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict'), ts = require('typescript')
const { platformSource } = require('./platform-source.cjs')
const source = platformSource(fs.readFileSync(path.resolve(__dirname, '../composables/useChannelTransition.uts'), 'utf8'), 'app-android')
const exportsObject = {}, watchers = [], disposed = [], animations = []
let visible = true, reduced = false
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, {
  exports: exportsObject,
  require: name => name === 'vue' ? { nextTick: () => Promise.resolve(), watch: (get, callback) => watchers.push(callback), onBeforeUnmount: callback => disposed.push(callback) } : { uxMedia: () => reduced },
  uni: { rpx2px: value => value / 2, getElementById: id => ({ animate(frames, options) {
    const animation = { id, frames, options, canceled: false, onfinish: null, cancel() { this.canceled = true } }
    animations.push(animation); return animation
  } }) }
})
;(async () => {
  const transition = exportsObject.useChannelTransition(() => 'content', () => visible, () => ['row-a', 'row-b'])
  await transition.play('from-right')
  assert.equal(animations.length, 3)
  const completed = animations[1]
  completed.onfinish()
  await transition.play('from-left')
  assert.equal(completed.canceled, false, 'Never cancel a completed animation after its native list cell has been recycled')
  assert.equal(animations[0].canceled, true, 'An active animation is canceled when the direction changes')
  assert.equal(animations[2].canceled, true)
  assert(animations[3].frames[0].transform.includes('-14px'), 'Reverse switching enters from the left')
  visible = false; watchers.forEach(callback => callback(false))
  assert(animations.slice(3).every(animation => animation.canceled), 'Hiding the pane cancels in-flight animations')
  const previous = animations.length
  await transition.play('from-right')
  assert.equal(animations.length, previous, 'Hidden panes must not animate')
  visible = true; reduced = true
  await transition.play('from-right')
  assert.equal(animations.length, previous, 'Reduced motion must not animate')
  reduced = false
  const pending = transition.play('from-right')
  disposed.forEach(callback => callback())
  await pending
  assert.equal(animations.length, previous, 'Unmounting invalidates pending next-tick animations')
  console.log('PASS: native channel transitions release finished list-cell animations, cancel only active effects, and handle reversal, hidden panes, reduced motion and disposal.')
})().catch(error => { console.error(error); process.exitCode = 1 })

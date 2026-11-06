const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const { test } = require('node:test')
const ts = require('typescript')
const { platformSource } = require('./platform-source.cjs')
const source = fs.readFileSync(path.resolve(__dirname, '../utils/image-info.uts'), 'utf8')

function fixture(target) {
  const native = [], builtin = [], success = [], fail = [], exports = {}
  const code = platformSource(source, target)
  vm.runInNewContext(ts.transpileModule(code, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, {
    exports, require: () => ({ readImageInfo: src => new Promise(resolve => native.push({ src, callback: (width, height) => resolve(JSON.stringify({ width, height })) })) }),
    JSON: { parse: value => { const info = JSON.parse(value); return { getNumber: key => info[key] } } },
    uni: { getImageInfo: options => {
      assert.notEqual(target, 'app-android', 'Android must never enter the crashing DCloud callback')
      builtin.push(options)
    } }
  })
  return { native, builtin, success, fail, probe: src => exports.probeImageInfo(src, (width, height) => success.push({ width, height }), error => fail.push(error)) }
}

test('Android probes local files and network avatars without the built-in MIME callback', async () => {
  const f = fixture('app-android')
  for (const src of ['/static/avatar.png', 'unifile://usr/cached.gif', 'content://media/1', 'https://images.test/avatar', 'HTTP://images.test/avatar']) {
    f.probe(src)
    assert.equal(f.native.at(-1).src, src)
    // Width/height are sufficient even when native MIME metadata is absent.
    f.native.at(-1).callback(200, 300)
    await Promise.resolve()
    assert.equal(f.success.at(-1).width, 200)
    assert.equal(f.success.at(-1).height, 300)
  }
  assert.equal(f.success.length, 5)
  assert.equal(f.builtin.length, 0)
  f.probe('')
  assert.equal(f.native.length, 5, 'An empty binding must not start decoding')
})

test('invalid image bounds fail safely instead of reporting a loaded image', async () => {
  const f = fixture('app-android')
  for (const [width, height] of [[0, 0], [-1, -1], [200, 0]]) {
    f.probe('unifile://usr/invalid')
    f.native.at(-1).callback(width, height)
    await Promise.resolve()
  }
  assert.equal(f.success.length, 0)
  assert.equal(f.fail.length, 3)
})

test('iOS retains the platform image API and forwards success and failure', () => {
  const f = fixture('app-ios')
  f.probe('/static/avatar.png')
  f.builtin[0].success({ width: 50, height: 60 })
  assert.equal(f.success[0].width, 50)
  f.builtin[0].fail({ errMsg: 'invalid image' })
  assert.equal(f.fail[0].errMsg, 'invalid image')
  assert.equal(f.native.length, 0)
})

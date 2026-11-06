const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const { test } = require('node:test')
const ts = require('typescript'), vue = require('vue')
const { platformSource } = require('./platform-source.cjs')
const original = fs.readFileSync(path.resolve(__dirname, '../components/ChatBubble.uvue'), 'utf8')
const asset = { id: '10', version: 'v1', coverId: '', width: 640, height: 480 }
const message = { id: '1', senderId: '1', kind: 'image', revoked: false, asset }
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
async function flush() { for (let i = 0; i < 8; i++) await vue.nextTick() }
function fixture({ target = 'app-android', cachedImage = async a => 'unifile://cache/' + a.id + '-' + a.version + '.png', cachedSource = () => '', coverSource = () => '', cover = async () => 'unifile://cache/cover.png', value = message } = {}) {
  const props = vue.reactive({ message: structuredClone(value), maxWidth: 250, viewportWidth: 390 })
  const scope = vue.effectScope(), probes = [], requests = [], timers = new Map()
  let sequence = 0, unmount
  const script = platformSource(original, target).match(/<script setup lang="uts">([\s\S]*?)<\/script>/)[1]
  const exports = {}
  scope.run(() => vm.runInNewContext(ts.transpileModule(script + '\nexports.probe={source,imageReady,showLoading,loadError,loadedWidth,loadedHeight,loadSource,imageLoaded,imageFailed};', {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText, {
    exports, defineProps: () => props, defineEmits: () => () => {},
    setTimeout: fn => { const id = ++sequence; timers.set(id, fn); return id }, clearTimeout: id => timers.delete(id),
    uni: { getImageInfo: options => probes.push(options) },
    require: name => {
      if (name === 'vue') return { ...vue, onBeforeUnmount: fn => { unmount = fn } }
      if (name === '@/utils/image-info') return { probeImageInfo: (src, success, fail) => probes.push({ src, success: info => success(info.width, info.height), fail }) }
      if (name === '@/utils/chat-local') return { chatOwner: () => '1' }
      if (name === '@/utils/chat-cache') return { chatCachedImageSource: cachedSource, chatCachedImage: a => { requests.push({ ...a }); return cachedImage(a) } }
      if (name === '@/utils/chat-state') return { chatCover: cover, chatCoverSource: coverSource }
      return {}
    }
  }))
  return { ...exports.probe, props, probes, requests, timers,
    advanceLoading() { for (const [id, fn] of [...timers]) { timers.delete(id); fn() } },
    destroy() { unmount(); scope.stop() }
  }
}
test('cached native images finish without a load event and unchanged history keeps them mounted', async () => {
  const f = fixture()
  try {
    assert.equal(f.showLoading.value, false)
    await flush()
    assert.equal(f.source.value, 'unifile://cache/10-v1.png')
    f.probes.at(-1).success({ width: 640, height: 480 })
    assert.equal(f.imageReady.value, true)
    assert.equal(f.timers.size, 0)
    f.props.message = structuredClone(message)
    await flush()
    assert.equal(f.requests.length, 1, 'Replacing an identical history row must not reread its image')
    assert.equal(f.imageReady.value, true)
    assert.equal(f.loadedWidth.value, 640)
    assert.equal(f.showLoading.value, false)
  } finally { f.destroy() }
})
test('slow uncached images show loading until decoding completes', async () => {
  const download = deferred(), f = fixture({ cachedImage: () => download.promise })
  try {
    await flush()
    assert.equal(f.source.value, '')
    f.advanceLoading()
    assert.equal(f.showLoading.value, true)
    download.resolve('unifile://cache/downloaded.png')
    await flush()
    assert.equal(f.showLoading.value, true)
    f.probes.at(-1).success({ width: 640, height: 480 })
    assert.equal(f.imageReady.value, true)
    assert.equal(f.showLoading.value, false)
  } finally { f.destroy() }
})
test('retry remounts the same cached URL and a late native error does not undo a load event', async () => {
  const f = fixture()
  try {
    await flush()
    f.probes.at(-1).fail()
    assert.equal(f.loadError.value, true)
    const retry = f.loadSource(true)
    assert.equal(f.source.value, '', 'Retry must unmount even when its URL is unchanged')
    await retry; await flush()
    assert.equal(f.source.value, 'unifile://cache/10-v1.png')
    f.imageLoaded({ detail: { width: 640, height: 480 } })
    f.probes.at(-1).fail()
    assert.equal(f.imageReady.value, true)
    assert.equal(f.loadError.value, false)
  } finally { f.destroy() }
})
test('resource changes and revocation ignore stale decodes and downloads', async () => {
  const download = deferred(), f = fixture({ cachedImage: a => a.version === 'v1' ? Promise.resolve('unifile://cache/v1.png') : download.promise })
  try {
    await flush()
    const oldProbe = f.probes.at(-1)
    f.props.message.asset.version = 'v2'
    await flush()
    oldProbe.success({ width: 999, height: 999 })
    assert.equal(f.imageReady.value, false)
    assert.equal(f.loadedWidth.value, 0)
    f.props.message.revoked = true
    await flush()
    download.resolve('unifile://cache/v2.png')
    await flush()
    assert.equal(f.source.value, '')
    assert.equal(f.imageReady.value, false)
    assert.equal(f.timers.size, 0)
  } finally { f.destroy() }
})
test('unmount cancels the indicator and ignores late native callbacks', async () => {
  const f = fixture()
  await flush()
  const probe = f.probes.at(-1)
  f.destroy()
  probe.success({ width: 640, height: 480 })
  assert.equal(f.imageReady.value, false)
  assert.equal(f.timers.size, 0)
})
test('failed downloads stop loading and remain retryable', async () => {
  const f = fixture({ cachedImage: async () => { throw Error('offline') } })
  try {
    await flush()
    assert.equal(f.loadError.value, true)
    assert.equal(f.showLoading.value, false)
    assert.equal(f.timers.size, 0)
  } finally { f.destroy() }
})
test('video previews resolve only the cover and emoji images use the image cache', async () => {
  let covers = 0
  const f = fixture({ value: { ...message, kind: 'video', localPath: 'file:///video.mp4' }, cover: async () => { covers++; return 'unifile://cache/cover.png' } })
  try {
    await flush()
    assert.equal(covers, 1)
    assert.equal(f.requests.length, 0)
    assert.equal(f.source.value, 'unifile://cache/cover.png')
    f.props.message = { ...structuredClone(message), kind: 'emoji' }
    await flush()
    assert.equal(f.requests.length, 1)
    assert.equal(f.source.value, 'unifile://cache/10-v1.png')
  } finally { f.destroy() }
})
test('web images finish through their load event without native decoding APIs', async () => {
  const f = fixture({ target: 'h5', cachedImage: async () => 'blob:chat-image' })
  try {
    await flush()
    assert.equal(f.probes.length, 0)
    f.imageLoaded({ detail: { width: 640, height: 480 } })
    assert.equal(f.imageReady.value, true)
    assert.equal(f.timers.size, 0)
  } finally { f.destroy() }
})
test('re-entered pages bind persisted images synchronously while file validation is still pending', async () => {
  for (let visit = 0; visit < 3; visit++) {
    const validation = deferred(), source = 'unifile://usr/ct-private-chat-1/10-v1.png'
    const f = fixture({ cachedSource: () => source, cachedImage: () => validation.promise })
    try {
      assert.equal(f.source.value, source, 'The very first render must already contain the local image URL')
      await flush()
      f.probes.at(-1).success({ width: 640, height: 480 })
      assert.equal(f.imageReady.value, true, 'Rendering must not wait for file stat or history requests')
      validation.resolve(source)
      await flush()
      assert.equal(f.source.value, source)
      assert.equal(f.imageReady.value, true, 'Background validation must not clear an already displayed image')
    } finally { f.destroy() }
  }
})
test('persisted video covers are also bound on the first render without using the video body', async () => {
  const validation = deferred(), source = 'unifile://usr/ct-private-chat-1/cover-v1.jpg'
  const f = fixture({ value: { ...message, kind: 'video', localPath: 'file:///video.mp4' }, coverSource: () => source, cover: () => validation.promise })
  try {
    assert.equal(f.source.value, source)
    assert.equal(f.requests.length, 0)
    validation.resolve(source)
    await flush()
  } finally { f.destroy() }
})
test('a missing persisted file can be replaced by a fresh background download', async () => {
  const download = deferred(), f = fixture({ cachedSource: () => 'unifile://usr/missing.png', cachedImage: () => download.promise })
  try {
    await flush()
    f.probes.at(-1).fail()
    assert.equal(f.loadError.value, true)
    download.resolve('unifile://usr/repaired.png')
    await flush()
    assert.equal(f.source.value, 'unifile://usr/repaired.png')
    assert.equal(f.loadError.value, false)
    f.probes.at(-1).success({ width: 640, height: 480 })
    assert.equal(f.imageReady.value, true)
  } finally { f.destroy() }
})
test('the real persistent source lookup respects account, version, deletion and clearing without async file APIs', () => {
  let owner = '1'
  const storage = new Map(), removed = new Set(), cache = {}
  const source = platformSource(fs.readFileSync(path.resolve(__dirname, '../utils/chat-cache.uts'), 'utf8'), 'app-android')
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, {
    exports: cache, require: name => name === './chat-local' ? {
      chatOwner: () => owner, chatAssetRemoved: id => removed.has(owner + ':' + id),
      chatStored: (suffix, fallback) => storage.get(owner + ':' + suffix) || fallback
    } : {}
  })
  const a = { ...asset, sizeBytes: 100 }, key = '1:media.10.v1', url = 'unifile://usr/ct-private-chat-1/10-v1.png'
  storage.set(key, { path: url, size: 100 })
  assert.equal(cache.chatCachedImageSource(a), url)
  owner = '2'; assert.equal(cache.chatCachedImageSource(a), '')
  owner = '1'; assert.equal(cache.chatCachedImageSource({ ...a, version: 'v2' }), '')
  assert.equal(cache.chatCachedImageSource({ ...a, sizeBytes: 200 }), '')
  removed.add('1:10'); assert.equal(cache.chatCachedImageSource(a), '')
  removed.clear(); storage.delete(key); assert.equal(cache.chatCachedImageSource(a), '')
})

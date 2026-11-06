const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const tick = () => new Promise(resolve => setImmediate(resolve))
function load(file, imports, extra = {}) {
  const exports = {}
  const stack = [true]
  const input = fs.readFileSync(path.join(__dirname, '..', file), 'utf8').split(/\r?\n/).filter(line => {
    const directive = line.match(/^\s*\/\/\s*#(ifdef|ifndef|endif)\s*([\w-]*)/)
    if (!directive) return stack[stack.length - 1]
    if (directive[1] === 'endif') { assert(stack.length > 1); stack.pop() }
    else {
      assert(['WEB', 'APP', 'MP-WEIXIN'].includes(directive[2]), 'explicitly model any added platform condition')
      const enabled = directive[2] === 'APP'
      stack.push(stack[stack.length - 1] && (directive[1] === 'ifdef' ? enabled : !enabled))
    }
    return false
  }).join('\n')
  assert.equal(stack.length, 1, 'balanced native platform directives')
  const source = ts.transpileModule(input, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText
  vm.runInNewContext(source, { exports, require: name => { assert(name in imports, name); return imports[name] }, console, setTimeout, clearTimeout, ...extra })
  return exports
}
function nativeCache(initial = []) {
  let reads = 0, syncWrites = 0, writes = 0, max = 0, active = 0, saved = initial
  const started = [], waiting = []
  const files = new Map(initial.map(row => [row.path, row.size])), deleted = []
  const fileSystem = {
    stat: options => files.has(options.path) ? options.success({ stats: { size: files.get(options.path) } }) : options.fail(new Error('missing file')),
    saveFile: options => { const savedFilePath = options.filePath || '_doc/' + options.tempFilePath.split('/').pop(); files.set(savedFilePath, files.get(options.tempFilePath)); options.success({ savedFilePath }) },
    unlink: options => { deleted.push(options.filePath); files.delete(options.filePath); options.success() }
  }
  const uni = {
    env: { USER_DATA_PATH: '_doc' },
    getStorageSync: key => { if (key === 'ct_image_file_cache_v1') return []; reads++; return saved }, removeStorageSync() {}, setStorageSync: () => { syncWrites++ },
    setStorage: options => { writes++; saved = options.data; options.success() },
    downloadFile: options => { active++; max = Math.max(max, active); started.push(options.url); waiting.push(() => { active--; files.set(options.filePath || options.url, 1024); options.success({ statusCode: 200, tempFilePath: options.filePath || options.url }) }) },
    getFileSystemManager: () => fileSystem
  }
  const imageLoading = load('utils/image-loading.uts', {})
  const fileStorage = load('utils/file-storage.uts', { './image-loading': imageLoading }, { uni })
  const api = load('utils/media-cache.uts', { '@/config/env': { API_BASE_URL: 'http://cache.test' }, './file-storage': fileStorage, './image-loading': imageLoading }, { uni })
  return { api, started, waiting, stats: () => ({ reads, syncWrites, writes, max, saved, files, deleted }) }
}
const deadline = setTimeout(() => { console.error('Performance core failed: an awaited operation did not settle'); process.exitCode = 1 }, 10000)
;(async () => {
  const mediaLayout = load('utils/media-layout.uts', {})
  const unknown = { type: 'image', aspectRatio: 0, resourceVersion: 'v1', previewUrl: '/preview.png' }
  assert.equal(mediaLayout.mediaAspectRatio(unknown), 1)
  mediaLayout.rememberMediaRatio(unknown, .75); assert.equal(mediaLayout.mediaAspectRatio(unknown), .75)
  unknown.resourceVersion = 'v2'; assert.equal(mediaLayout.mediaAspectRatio(unknown), 1, 'decoded size must not survive a resource version change')
  unknown.aspectRatio = 2; assert.equal(mediaLayout.mediaAspectRatio(unknown), 2, 'server dimensions take precedence')
  const test = nativeCache()
  let cancelled = false
  const tasks = [0, 1, 2].map(id => test.api.cachedImage('/' + id + '.png'))
  await tick(); assert.equal(test.started.length, 3)
  tasks.push(test.api.cachedImage('/background.png', 'v1', 'cover', { priority: () => 1 }))
  tasks.push(test.api.cachedImage('/visible.png', 'v1', 'cover', { priority: () => 10 }))
  tasks.push(test.api.cachedImage('/cancelled.png', 'v1', 'cover', { cancelled: () => cancelled }))
  tasks.push(test.api.cachedImage('/visible.png', 'v1', 'cover'))
  await tick(); cancelled = true
  test.waiting.shift()(); await tick(); await tick()
  assert(test.started[3].endsWith('/visible.png'), 'visible image must overtake queued prefetches')
  for (let i = 0; i < 10; i++) { while (test.waiting.length) test.waiting.shift()(); await tick() }
  const images = await Promise.all(tasks)
  images.forEach(image => image.release())
  assert.equal(test.api.cachedImageSource('/visible.png', 'v1', 'cover'), images[4].url, 'native remount must receive the validated file synchronously')
  assert.equal(test.api.cachedImageSource('/visible.png', 'v2', 'cover'), '', 'a new resource version must not reuse the old image')
  assert.equal(test.api.cachedImageSource('/visible.png', 'v1', 'other'), '', 'a different variant must not reuse the wrong file')
  assert.equal(test.stats().max, 3)
  assert.equal(test.started.filter(url => url.endsWith('/visible.png')).length, 1, 'shared URL/version/variant must download once')
  assert(!test.started.some(url => url.endsWith('/cancelled.png')), 'unmounted prefetch must not download')
  for (let i = 0; i < 10; i++) await test.api.cachedImage('/visible.png', 'v1', 'cover')
  await test.api.flushMediaCacheMetadata()
  assert.equal(test.stats().reads, 1, 'native index must be read only once')
  assert.equal(test.stats().syncWrites, 0, 'image hits must not synchronously rewrite the index')
  assert.equal(test.stats().writes, 1, 'metadata writes must batch')
  const version = test.api.cachedImage('/visible.png', 'v2', 'cover')
  await tick(); test.waiting.shift()(); await version
  assert.equal(test.started.filter(url => url.endsWith('/visible.png')).length, 2, 'resource version must invalidate cache')
  const stale = test.api.cachedImage('/late.png')
  await tick(); await test.api.clearImageCache(); test.waiting.shift()(); await stale; await tick()
  assert.equal(await test.api.imageCacheSize(), 0, 'late downloads must not repopulate a cleared cache')
  assert.equal(test.api.cachedImageSource('/visible.png', 'v1', 'cover'), '', 'manual clearing must remove synchronous hits too')
  assert.equal(test.stats().saved.length, 0)

  const collision = nativeCache()
  const sameNames = [
    collision.api.cachedImage('/first/preview.jpg', 'v1', 'cover'),
    collision.api.cachedImage('/second/preview.jpg', 'v1', 'cover'),
    collision.api.cachedImage('/first/preview.jpg', 'v2', 'cover'),
    collision.api.cachedImage('/first/preview.jpg', 'v1', 'preview')
  ]
  await tick()
  for (let i = 0; i < 5; i++) { while (collision.waiting.length) collision.waiting.shift()(); await tick() }
  const distinct = await Promise.all(sameNames)
  assert.equal(new Set(distinct.map(image => image.url)).size, 4, 'same filenames, versions and variants must own separate files')
  assert.equal((await collision.api.cachedImage('/first/preview.jpg', 'v1', 'cover')).url, distinct[0].url)
  assert.equal(collision.started.length, 4, 'valid cache hit must keep its original file')
  await collision.api.clearImageCache()

  const brokenPath = '_doc/preview.jpg'
  const repaired = nativeCache(['/first/preview.jpg', '/second/preview.jpg'].map(source => ({
    key: JSON.stringify(['http://cache.test' + source, 'v1', 'cover']), path: brokenPath, size: 1024, used: 1
  })))
  const recovered = ['/first/preview.jpg', '/second/preview.jpg'].map(source => repaired.api.cachedImage(source, 'v1', 'cover'))
  await tick()
  assert.equal(repaired.started.length, 2, 'every old entry sharing a local path must be downloaded again')
  while (repaired.waiting.length) repaired.waiting.shift()()
  const restored = await Promise.all(recovered)
  assert.notEqual(restored[0].url, restored[1].url)
  assert(repaired.stats().deleted.includes(brokenPath), 'discard the overwritten legacy file')
  await repaired.api.clearImageCache()

  const vue = require('vue'), cleanup = []
  let now = 1_000_000, timerId = 0, taskCalls = 0, finish
  const timers = new Map(), visible = vue.ref(true)
  const freshModule = load('composables/useFreshTask.uts', { vue: { ...vue, onBeforeUnmount: fn => cleanup.push(fn) } }, {
    Date: { now: () => now },
    setTimeout: (fn, delay) => { const id = ++timerId; timers.set(id, { fn, at: now + delay }); return id },
    clearTimeout: id => timers.delete(id)
  })
  async function advance(ms) {
    now += ms
    for (const [id, timer] of [...timers]) if (timer.at <= now) { timers.delete(id); timer.fn() }
    await tick()
  }
  const fresh = freshModule.useFreshTask(() => visible.value, () => { taskCalls++; return new Promise(resolve => { finish = resolve }) }, 1000)
  await advance(300); assert.equal(taskCalls, 1)
  assert.equal(fresh.run(), fresh.run(), 'refresh must coalesce in-flight work')
  fresh.invalidate(); finish(); await tick(); await advance(300)
  assert.equal(taskCalls, 2, 'invalidation during a request must not be lost')
  finish(); await tick(); await advance(500); assert.equal(taskCalls, 2, 'fresh data must not reload')
  fresh.busy(); fresh.invalidate(); await advance(10_000); assert.equal(taskCalls, 2, 'validation must pause during scrolling')
  fresh.idle(); await advance(300); assert.equal(taskCalls, 3)
  finish(); await tick(); visible.value = false; await vue.nextTick(); await advance(10_000)
  assert.equal(taskCalls, 3, 'hidden pane must not refresh')
  cleanup.forEach(fn => fn())
  let identity = 'one'
  const authRevision = vue.ref(0)
  const interactions = load('utils/interactions.uts', {
    vue, './auth': { authRevision, isLoggedIn: () => true, getUserInfo: () => ({ userId: identity, nickname: identity }) },
    './auth-api': {}, './request': {}
  })
  const post = (id, author = 'author') => ({ id, title: id, author: { id: author, nickname: author }, media: [], likeCount: 0, commentCount: 0, detail: {} })
  const first = interactions.registerPost(post('1')); interactions.retainPost(first)
  const same = interactions.registerPost(post('1')); assert.equal(first, same)
  const second = interactions.registerPost(post('2')); interactions.retainPost(second)
  interactions.setRelationship('author', true, false)
  assert(first.detail.following && second.detail.following, 'author index must update all owned posts')
  interactions.releasePost(first)
  const replacement = interactions.registerPost(post('1')); assert.notEqual(replacement, first, 'released objects must leave global registry')
  interactions.retainPost(replacement)
  identity = 'two'; authRevision.value++; await vue.nextTick()
  assert.equal(second.detail.following, false, 'account switch must clear previous state')
  interactions.releasePost(second); interactions.releasePost(replacement)
  await new Promise(resolve => setTimeout(resolve, 5))
  console.log('Performance core passed: cache batching/concurrency/priority/cancellation/version/clear races; TTL, single flight, in-flight invalidation, scroll idle and hidden suspension; post ownership and author synchronization.')
})().catch(error => { console.error(error); process.exitCode = 1 }).finally(() => clearTimeout(deadline))

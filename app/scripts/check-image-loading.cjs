const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const ts = require('typescript')
const { platformSource } = require('./platform-source.cjs')
const root = path.resolve(__dirname, '..')
const tick = () => new Promise(resolve => setImmediate(resolve))
let now = 0, id = 0
const timers = new Map(), started = [], aborted = [], completed = []
const setTimer = (fn, delay) => { const key = ++id; timers.set(key, { fn, at: now + delay }); return key }
async function advance(ms) {
  const end = now + ms
  while (true) {
    const next = [...timers].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
    if (!next) break
    now = next[1].at; timers.delete(next[0]); next[1].fn(); await tick()
  }
  now = end; await tick()
}
function load(file, imports = {}, globals = {}) {
  const original = process.env.IMAGE_LOADING_BASELINE && path.join(process.env.IMAGE_LOADING_BASELINE, file)
  const source = fs.readFileSync(original && fs.existsSync(original) ? original : path.join(root, file), 'utf8')
  const exports = {}
  vm.runInNewContext(ts.transpileModule(platformSource(source, 'app-android'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, {
    exports, require: name => { assert(name in imports, name); return imports[name] },
    setTimeout: setTimer, clearTimeout: key => timers.delete(key), ...globals
  })
  return exports
}
const uni = {
  env: { USER_DATA_PATH: 'unifile://cache' }, getStorageSync: () => [], removeStorageSync() {}, setStorage: options => options.success(),
  getFileSystemManager: () => ({ stat: options => options.success({ stats: { size: 1024 } }), unlink: options => options.success() }),
  downloadFile(options) {
    started.push(options.url)
    if (options.url.includes('stalled')) return { abort: () => aborted.push(options.url) }
    setTimer(() => { completed.push(options.url); options.success({ statusCode: 200, tempFilePath: options.filePath }) }, 1)
    return { abort: () => aborted.push(options.url) }
  }
}
;(async () => {
  const deadline = load('utils/image-loading.uts')
  const storage = load('utils/file-storage.uts', { './image-loading': deadline }, { uni })
  const cache = load('utils/media-cache.uts', { './image-loading': deadline, './file-storage': storage, '@/config/env': { API_BASE_URL: 'https://images.test' } }, { uni })
  const urls = ['/stalled-1.png', '/stalled-2.png', '/stalled-3.png', '/good-1.png', '/good-2.png']
  const values = []
  const requests = urls.map(url => cache.cachedImage(url).then(image => { values.push(image.url); return image }))
  await tick()
  assert.equal(started.length, 3, 'The first three downloads occupy the queue')
  await advance(11000)
  assert.equal(aborted.length, 3, 'Stalled native downloads must be aborted')
  assert.equal(started.length, 5, 'Healthy images must run after stalled slots are released')
  assert.equal(values.length, 5, 'Every caller must settle; no permanent empty image source')
  const images = await Promise.all(requests)
  for (let i = 0; i < 3; i++) assert.equal(images[i].url, 'https://images.test' + urls[i], 'A cache timeout must yield the original URL')
  assert(images.slice(3).every(image => image.url.startsWith('unifile://cache/')))
  assert.equal(completed.length, 2)
  await cache.flushMediaCacheMetadata()
  console.log('PASS: native callback stalls are aborted; the three-slot queue recovers; later images finish and timed-out callers receive their original URLs.')
})().catch(error => { console.error(error); process.exitCode = 1 })

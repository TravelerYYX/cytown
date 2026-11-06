// Exercise the real setup scripts with native platform branches and Vue reactivity.
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const ts = require('typescript'), vue = require('vue')
const tick = async () => { await vue.nextTick(); await new Promise(resolve => setImmediate(resolve)); await vue.nextTick() }
function native(source) {
  const stack = [true]
  return source.split(/\r?\n/).filter(line => {
    const directive = line.match(/^\s*\/\/\s*#(ifdef|ifndef|endif)\s*(\S*)/)
    if (!directive) return stack.at(-1)
    if (directive[1] === 'endif') stack.pop()
    else {
      const enabled = directive[2].split('||').some(value => ['APP', 'APP-ANDROID'].includes(value.trim()))
      stack.push(stack.at(-1) && (directive[1] === 'ifdef' ? enabled : !enabled))
    }
    return false
  }).join('\n')
}
function setup(file, names, imports, props = {}) {
  const cleanup = [], mounted = [], reused = [], imageInfo = [], events = [], scope = vue.effectScope(), exports = {}
  const source = fs.readFileSync(path.join(__dirname, '..', file), 'utf8').match(/<script setup lang="uts">([\s\S]*?)<\/script>/)[1]
  const compiled = ts.transpileModule(native(source) + '\nObject.assign(exports, {' + names + '})', {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  scope.run(() => vm.runInNewContext(compiled, {
    exports, console, setTimeout, clearTimeout,
    defineProps: () => props, withDefaults: (input, defaults) => { for (const key in defaults) if (!(key in input)) input[key] = defaults[key]; return input },
    defineEmits: () => (...args) => events.push(args), defineExpose: () => {},
    uni: { getWindowInfo: () => ({ windowHeight: 844 }), getImageInfo: options => imageInfo.push(options) },
    require: name => {
      if (name === 'vue') return { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => cleanup.push(fn), onReuse: fn => reused.push(fn) }
      if (name === '@/utils/image-info') return { probeImageInfo: (src, success, fail) => imageInfo.push({ src, success: info => success(info.width, info.height), fail }) }
      if (name in imports) return imports[name]
      if (name.endsWith('.uvue')) return {}
      throw Error('Unmodeled import: ' + name)
    }
  }))
  return { ...exports, imageInfo, events, mount: async () => { for (const fn of mounted) await fn(); await tick() }, reuse: async () => { reused.forEach(fn => fn()); await tick() }, dispose: () => { cleanup.forEach(fn => fn()); scope.stop() } }
}
function home() {
  const calls = [], pending = [], retained = new Map(), tasks = []
  const api = setup('components/views/HomeView.uvue', 'activeChannel,posts,pageNum,hasMore,channelCache,loadMore,refreshAll,loadConfig,feedReveal,viewer', {
    '@/utils/native-styles': {},
    '@/composables/useHorizontalSwipe': { useHorizontalSwipe: () => ({ start() {}, move() {}, end() {}, cancel() {} }) },
    '@/composables/usePaneVisibility': { usePaneVisibility: () => vue.ref(true) },
    '@/composables/usePaneScroll': { usePaneScroll: () => ({ offset: vue.ref(0), requestedTop: vue.ref(0), scrollToTop: () => {} }) },
    '@/composables/usePostOwnership': { usePostOwnership: () => {} },
    '@/utils/auth': { authRevision: vue.ref(0), isLoggedIn: () => false },
    '@/utils/app-settings': { feedLayout: vue.ref('single') },
    '@/mock/home': {}, '@/utils/navigation': { entryChannelRequest: vue.ref(null) },
    '@/utils/post-api': {
      fetchPosts: (pageNum, pageSize, channel) => { calls.push({ channel, pageNum }); return new Promise((resolve, reject) => pending.push({ channel, pageNum, resolve, reject })) },
      toPost: record => record
    },
    '@/utils/home-config-api': { fetchHomeConfig: async () => ({ channels: Array.from({ length: 7 }, (_, i) => ({ id: i + 1, showAllPosts: i === 0 })), entries: [], news: {} }) },
    '@/composables/useFreshTask': { useFreshTask: (visible, action) => { tasks.push(action); return { touch() {}, busy() {}, idle() {} } } },
    '@/composables/useHomeHeaderMotion': { useHomeHeaderMotion: () => ({ pinned: vue.ref(false), scheduleMeasure() {} }) },
    '@/composables/useFeedReveal': { useFeedReveal: () => {
      const phase = vue.ref('loading'), restoring = vue.ref(false)
      return { phase, restoring, pending: vue.computed(() => phase.value === 'loading'), begin() { restoring.value = false; phase.value = 'loading' }, reveal() { phase.value = 'idle' }, finish() { phase.value = 'idle' }, restore() { restoring.value = true; phase.value = 'idle' } }
    } },
    '@/utils/interactions': { retainPost: post => retained.set(post, (retained.get(post) || 0) + 1), releasePost: post => retained.set(post, (retained.get(post) || 0) - 1) }
  }, { active: true })
  async function respond(channel, count = 10, pages = 8) {
    const request = pending.find(item => item.channel === channel)
    assert(request, 'Expected pending channel ' + channel)
    pending.splice(pending.indexOf(request), 1)
    request.resolve({ records: Array.from({ length: count }, (_, i) => ({ id: channel + '-' + ((request.pageNum - 1) * 10 + i) })), current: request.pageNum, pages })
    await tick()
  }
  return { ...api, calls, pending, respond, tasks, retained }
}
const cases = {
  async 'native cached images have a source on their first render'() {
    const props = vue.reactive({ src: '/one.png', version: 'v1' }), waiting = []
    const s = setup('components/CachedImage.uvue', 'source', {
      '@/utils/ui-assets': { uiAsset: value => value }, '@/utils/native-styles': {},
      '@/utils/media-cache': {
        cachedImageSource: (url, version) => url === '/one.png' && version === 'v1' ? 'unifile://cached-one.png' : '',
        cachedImage: () => new Promise(resolve => waiting.push(resolve))
      }
    }, props)
    try {
      assert.equal(s.source.value, 'unifile://cached-one.png', 'A cached remount must not start with an empty image source')
      assert.equal(waiting.length, 0, 'Validated native hits must bypass the async lookup')
      props.version = 'v2'; await vue.nextTick()
      assert.equal(s.source.value, '', 'Changed versions must not show old bytes')
      props.src = '/two.png'; await vue.nextTick()
      waiting[1]({ url: 'unifile://two.png', release() {} }); await tick()
      waiting[0]({ url: 'unifile://old-one.png', release() {} }); await tick()
      assert.equal(s.source.value, 'unifile://two.png', 'Late image lookups must not replace the current image')
    } finally { s.dispose() }
  },
  async 'native memory hits notify readiness without a renderer load event'() {
    const props = vue.reactive({ src: '/one.png', version: 'v1' })
    const s = setup('components/CachedImage.uvue', 'source,loaded,imageKey', {
      '@/utils/ui-assets': { uiAsset: value => value }, '@/utils/native-styles': {},
      '@/utils/media-cache': { cachedImageSource: url => 'unifile://' + url, mediaUrl: url => 'http://cache.test' + url }
    }, props)
    try {
      await s.mount()
      assert(s.imageInfo.length > 0, 'A cache hit needs a decoding confirmation even when @load is lost')
      const info = s.imageInfo.at(-1)
      info.success({ width: 200, height: 300 })
      assert.equal(s.events.length, 1)
      assert.equal(s.events[0][0], 'load')
      assert.equal(s.events[0][1].detail.width, 200)
      s.loaded({ detail: { width: 200, height: 300 } })
      assert.equal(s.events.length, 1, 'Decoding confirmation and @load must notify only once')
      props.src = '/two.png'; await tick()
      info.success({ width: 1, height: 1 })
      assert.equal(s.events.length, 1, 'A late callback must not reveal another image')
      s.imageInfo.at(-1).success({ width: 400, height: 500 })
      assert.equal(s.events.length, 2)
      const key = s.imageKey.value
      await s.reuse()
      assert(s.imageKey.value > key, 'A reused native node must bind its image again')
      s.imageInfo.at(-1).success({ width: 400, height: 500 })
      assert.equal(s.events.length, 3, 'Reused parents must receive readiness even for the same cached source')
      const late = s.imageInfo.at(-1)
      s.dispose()
      late.success({ width: 400, height: 500 })
      assert.equal(s.events.length, 3, 'Unmount must invalidate decoding callbacks')
    } finally { s.dispose() }
  },
  async 'discovery scope switches request each channel once'() {
    const s = home()
    try {
      await s.mount(); assert.equal(s.calls.length, 1); await s.respond(1)
      s.activeChannel.value = 2; await tick()
      assert.equal(s.calls.length, 2, 'showAllPosts scope must not schedule duplicate loads')
      await s.respond(2); s.activeChannel.value = 1; await tick()
      assert.equal(s.calls.length, 2); assert.equal(s.posts.value[0].id, '1-0')
      assert.equal(s.feedReveal.phase.value, 'idle')
    } finally { s.dispose() }
  },
  async 'refreshing one channel preserves other channel snapshots'() {
    const s = home()
    try {
      await s.mount(); await s.respond(1)
      s.activeChannel.value = 2; await tick(); await s.respond(2)
      const refresh = s.refreshAll(); await tick(); await s.respond(2); await refresh
      const count = s.calls.length
      s.activeChannel.value = 1; await tick()
      assert.equal(s.calls.length, count, 'Refreshing channel 2 must not discard channel 1')
      assert.equal(s.posts.value[0].id, '1-0')
    } finally { s.dispose() }
  },
  async 'all configured channels survive a complete round trip'() {
    const s = home()
    try {
      await s.mount(); await s.respond(1)
      for (let channel = 2; channel <= 7; channel++) { s.activeChannel.value = channel; await tick(); await s.respond(channel) }
      const count = s.calls.length
      s.activeChannel.value = 1; await tick()
      assert.equal(s.calls.length, count, 'Seven configured channels must not evict the discovery snapshot')
      assert.equal(s.posts.value[0].id, '1-0')
    } finally { s.dispose() }
  },
  async 'returning after pagination keeps all loaded rows and next page'() {
    const s = home()
    try {
      await s.mount(); await s.respond(1)
      for (let page = 2; page <= 5; page++) { const task = s.loadMore(); await s.respond(1); await task }
      const ids = s.posts.value.map(post => post.id)
      s.activeChannel.value = 2; await tick(); await s.respond(2)
      s.activeChannel.value = 1; await tick()
      assert.deepEqual(Array.from(s.posts.value, post => post.id), Array.from(ids), 'Loaded rows must not shrink back to page one')
      const task = s.loadMore(); await tick()
      assert.equal(s.calls.at(-1).pageNum, 6, 'Continue from the next unloaded page')
      await s.respond(1); await task
    } finally { s.dispose() }
  },
  async 'rapid return shares the pending first-page request'() {
    const s = home()
    try {
      await s.mount()
      s.activeChannel.value = 2; await tick()
      s.activeChannel.value = 1; await tick()
      assert.equal(s.calls.filter(call => call.channel === 1).length, 1, 'Returning before completion must reuse the in-flight request')
      await s.respond(1); assert.equal(s.posts.value[0].id, '1-0')
      await s.respond(2); assert.equal(s.posts.value[0].id, '1-0', 'Inactive response must not replace the active channel')
    } finally { s.dispose() }
  }
}
;(async () => {
  let failed = 0
  for (const [name, test] of Object.entries(cases)) {
    try { await test(); console.log('PASS: ' + name) }
    catch (error) { failed++; console.error('FAIL: ' + name + '\n' + error.message) }
  }
  process.exitCode = failed ? 1 : 0
})()

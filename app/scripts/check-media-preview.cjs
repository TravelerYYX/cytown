const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict')
const ts = require('typescript'), { parse } = require('@vue/compiler-sfc'), { baseParse } = require('@vue/compiler-dom')
const { platformSource } = require('./platform-source.cjs')
const root = path.resolve(__dirname, '..')
const read = file => fs.readFileSync(path.join(root, file), 'utf8')
const template = (file, target = 'app-android') => baseParse(parse(platformSource(read(file), target)).descriptor.template.content)
function visit(node, fn) { fn(node); for (const child of node.children || []) visit(child, fn) }
for (const target of ['app-android', 'app-ios']) {
  visit(template('components/ImagePostViewer.uvue', target), node => {
    assert(!['movable-area', 'movable-view'].includes(node.tag), target + ': unsupported native zoom component')
  })
}
for (const target of ['h5', 'mp-weixin']) {
  let movable = false
  visit(template('components/ImagePostViewer.uvue', target), node => { if (node.tag === 'movable-view') movable = true })
  assert(movable, target + ': preserve the supported zoom component')
}
// Exercise the real template expressions with loaded, empty and optional values.
// Android's dynamic renderer requires boolean branch data, including false cases.
for (const file of ['pages/post/index.uvue', 'components/PostVideoPlayer.uvue', 'components/NativeVideoSurface.uvue']) {
  visit(template(file), node => {
    for (const prop of node.props || []) {
      if (prop.type !== 7 || !['if', 'else-if'].includes(prop.name)) continue
      const expression = ts.transpileModule('(' + prop.exp.content + ')', { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText
      for (const populated of [false, true]) for (const video of [false, true]) {
        const media = { type: video ? 'video' : 'image', previewUrl: populated ? '/preview.jpg' : '', url: populated ? '/media' : '', status: populated ? 'failed' : 'ready' }
        const scope = {
          post: { content: populated ? 'x'.repeat(100) : '', tags: populated ? ['tag'] : undefined },
          media, firstMedia: media, loading: false, isImageDetail: !video, imageOverlay: false,
          isVideo: video, isRichPost: false, isImagePost: !video, commentsVisible: false,
          galleryImages: populated ? [media, media] : [], image: media,
          outsideOverlay: true, canRetry: populated ? true : undefined, error: populated ? 'failed' : '',
          menu: populated ? 'quality' : '', controls: true, fullscreen: false, waiting: false,
          started: false, options: { poster: populated ? '/poster.jpg' : undefined }
        }
        assert.equal(typeof vm.runInNewContext(expression, scope), 'boolean', file + ': ' + prop.exp.content)
      }
    }
  })
}
// Exercise gallery loading with real Vue reactivity, including late load events.
const vue = require('vue')
const galleryProps = vue.reactive({ post: { id: 1, media: [
  { id: 11, type: 'image', previewUrl: '/1-preview.jpg', originalUrl: '/1.jpg', resourceVersion: 'v1' },
  { id: 12, type: 'image', previewUrl: '/2-preview.jpg', originalUrl: '/2.jpg', resourceVersion: 'v1' }
] }, initialIndex: 0 })
const galleryExports = {}
const gallerySource = parse(read('components/ImagePostViewer.uvue')).descriptor.scriptSetup.content
vm.runInNewContext(ts.transpileModule(gallerySource + '\nexports.probe={displayImages,imageLoading,loadedImages,failedImages,change,loadOriginal};', {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText, {
  exports: galleryExports,
  require: name => name === 'vue' ? vue : name.includes('useNativeLayout') ? { useNativeLayout: () => ({}) } : {},
  defineProps: () => galleryProps, withDefaults: props => props, defineEmits: () => () => {}
})
const gallery = galleryExports.probe
const go = current => gallery.change({ detail: { current } })
const firstPreview = gallery.displayImages.value[0]
assert.equal(gallery.imageLoading.value, true, 'First visit must show loading')
gallery.loadedImages.add(firstPreview.key)
assert.equal(gallery.imageLoading.value, false)
go(1)
assert.equal(gallery.imageLoading.value, true, 'Unseen image must show loading')
gallery.loadedImages.add(firstPreview.key)
assert.equal(gallery.imageLoading.value, true, 'Late event from another image must not clear loading')
gallery.loadedImages.add(gallery.displayImages.value[1].key)
for (const index of [0, 1, 0, 1]) {
  go(index)
  assert.equal(gallery.imageLoading.value, false, 'Loaded images must stay ready when switching back')
}
go(0); gallery.loadOriginal()
assert.equal(gallery.imageLoading.value, true, 'Original image must load independently of preview')
gallery.loadedImages.add(firstPreview.key)
assert.equal(gallery.imageLoading.value, true, 'Late preview event must not mark original as loaded')
gallery.loadedImages.add(gallery.displayImages.value[0].key)
go(1); go(0)
assert.equal(gallery.imageLoading.value, false, 'Loaded original must stay ready on return')
galleryProps.post.media[0].resourceVersion = 'v2'
assert.equal(gallery.imageLoading.value, true, 'New resource version must load again')
gallery.failedImages.add(gallery.displayImages.value[0].key)
assert.equal(gallery.imageLoading.value, false, 'Failed image must not leave a permanent spinner')
go(1); go(0)
assert.equal(gallery.imageLoading.value, true, 'Revisiting a failed image must allow retry')
gallery.loadedImages.add(gallery.displayImages.value[0].key)
galleryProps.post.media[0].originalUrl = '/1-replaced.jpg'
assert.equal(gallery.imageLoading.value, true, 'Changed URL must load independently')

const source = parse(read('components/NativeImageZoom.uvue')).descriptor.scriptSetup.content
const exportsObject = {}, zoomEvents = []
vm.runInNewContext(ts.transpileModule(source + '\nexports.probe={start,move,end,scale,x,y};', {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
}).outputText, {
  exports: exportsObject, require: () => ({ ref: value => ({ value }) }),
  defineEmits: () => (name, value) => zoomEvents.push([name, value]),
  uni: { getWindowInfo: () => ({ windowWidth: 400, windowHeight: 800 }) }
})
const zoom = exportsObject.probe
const touch = (...points) => ({ touches: points.map(([clientX, clientY]) => ({ clientX, clientY })), stopPropagation() { this.stopped = true } })
const single = touch([50, 50]); zoom.start(single); zoom.move(touch([60, 60]))
assert(!single.stopped, 'Normal swipes must remain available to the gallery')
zoom.start(touch([0, 0], [100, 0])); zoom.move(touch([0, 0], [200, 0]))
assert.equal(zoom.scale.value, 2)
zoom.end(touch([100, 100])); zoom.move(touch([2000, 2000]))
assert.equal(zoom.x.value, 200); assert.equal(zoom.y.value, 400)
zoom.start(touch([0, 0], [100, 0])); zoom.move(touch([0, 0], [1000, 0]))
assert.equal(zoom.scale.value, 5, 'Clamp zoom to the existing 5x maximum')
zoom.start(touch([0, 0], [100, 0])); zoom.move(touch([0, 0], [1, 0])); zoom.end(touch())
assert.equal(zoom.scale.value, 1); assert.equal(zoom.x.value, 0); assert.equal(zoom.y.value, 0)
assert.equal(zoomEvents.at(-1)[1], false, 'Restore gallery swiping after zooming out')
zoom.start(touch([0, 0], [0, 0])); zoom.move(touch([0, 0], [10, 0]))
assert(Number.isFinite(zoom.scale.value), 'Coincident fingers must not produce invalid transforms')
console.log('Media preview checks passed: gallery loading state, resource changes, late load events, failed-image retry, native component compatibility, boolean branch values, pinch bounds, pan bounds and gallery gesture restoration. Mock gestures only; device rendering is verified separately.')

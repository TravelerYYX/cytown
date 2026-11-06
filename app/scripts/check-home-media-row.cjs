const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const assert = require('node:assert/strict'), ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')
const { parse: parseTemplate } = require('@vue/compiler-dom')
const root = path.resolve(__dirname, '..')
const source = fs.readFileSync(path.join(root, 'components/PostCard.uvue'), 'utf8')
const { descriptor, errors } = parse(source)
assert.deepEqual(errors, [])
const functions = new Set(['layoutPixelLength', 'homeCardInsets', 'homeMediaStyle'])
const syntax = ts.createSourceFile('PostCard.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true)
const selected = syntax.statements.filter(node => ts.isFunctionDeclaration(node) && functions.has(node.name.text))
assert.equal(selected.length, functions.size)
const runtime = ts.transpileModule(selected.map(node => node.getText(syntax)).join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020 }
}).outputText
const state = { uxNative: true, layoutWindow: { value: {} }, props: { post: { media: [] } } }
vm.createContext(state)
vm.runInContext(runtime, state)
const square = { type: 'image', layout: 'square' }, portrait = { type: 'image', layout: 'portrait' }
const wide = { type: 'image', layout: 'wide' }, video = { type: 'video', layout: 'wide' }
const near = (a, b) => assert(Math.abs(a - b) < 1e-6, `${a} != ${b}`)
let samples = 0
// Include physical screen widths that create fractional logical viewports.
for (const physicalWidth of [720, 750, 780, 786, 828, 861, 900, 1080, 1170, 1200, 1290, 1440]) {
  for (const pixelRatio of [2, 2.625, 2.75, 3, 3.5]) {
    state.layoutWindow.value = { windowWidth: physicalWidth / pixelRatio, pixelRatio }
    const insets = state.homeCardInsets(), content = physicalWidth - 2 * parseFloat(insets.paddingLeft) * pixelRatio
    for (const media of [[square], [portrait], [wide], [video], [square, square], [square, portrait, wide], [portrait, wide, square, square]]) {
      state.props.post.media = media
      const slots = media.slice(0, 3).map((item, index) => state.homeMediaStyle(item, index))
      let total = 0
      slots.forEach((slot, index) => {
        const width = parseFloat(slot.width) * pixelRatio, left = parseFloat(slot.marginLeft) * pixelRatio
        near(width, Math.round(width))
        near(left, Math.round(left))
        assert(width > 0)
        assert.equal(slot.marginTop, '0px')
        if (!index) assert.equal(left, 0, 'recycled first slot must clear its gap')
        else assert.equal(slot.width, slots[0].width)
        total += width + left
      })
      assert(total <= content + 1e-6, `media overflows: ${physicalWidth}px @${pixelRatio}, ${media.length} items`)
      if (media.length >= 3) assert(content - total < 3 + 1e-6, 'three columns must fill the row within rounding')
      if (media.length === 1 && media[0].layout === 'wide') {
        near(parseFloat(slots[0].width), state.layoutPixelLength(media[0].type === 'video' ? 450 : 410))
      }
      samples++
    }
  }
}
// Browser keeps its existing CSS sizes/gaps; no native pixel styles leak into H5.
state.uxNative = false
assert.equal(Object.keys(state.homeCardInsets()).length, 0)
assert.equal(Object.keys(state.homeMediaStyle(square, 1)).length, 0)
const tree = parseTemplate(descriptor.template.content)
let wired = false
function walk(node) {
  if (node.type === 1 && node.props.some(p => p.type === 6 && p.name === 'class' && p.value?.content === 'media-slot')) {
    wired = node.props.some(p => p.type === 7 && p.arg?.content === 'style' && p.exp.content.includes('homeMediaStyle(media,Number(index))'))
  }
  for (const child of node.children || []) walk(child)
}
walk(tree)
assert(wired, 'native slot must receive its size on first render')
assert(source.includes("variant==='home'?'home-media-list':''"))
assert(/\.home-media-list\s*\{\s*flex-wrap:\s*nowrap;/.test(source))
console.log(`Home media row passed: ${samples} physical-pixel layouts, first render, resize/reuse, 1–4 media, single landscape/video sizing and H5 isolation.`)

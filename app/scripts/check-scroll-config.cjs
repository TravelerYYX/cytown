const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
// Resolve compiler dependencies from their owners, also supporting pnpm's layout.
const compilerRequire = createRequire(require.resolve('@dcloudio/vite-plugin-uni/package.json'))
const sharedRequire = createRequire(compilerRequire.resolve('@dcloudio/uni-cli-shared/package.json'))
const { initPreContext, preJson } = sharedRequire('./dist/preprocess')
const { parse, printParseErrorCode } = sharedRequire('jsonc-parser')

const root = path.resolve(__dirname, '..')
const filename = path.join(root, 'pages.json')
const source = fs.readFileSync(filename, 'utf8')
const platforms = ['app', 'app-plus', 'h5', 'mp-weixin']
let routes

for (const platform of platforms) {
  // Use the compiler's actual conditional preprocessing, including APP-PLUS aliases.
  initPreContext(platform)
  const errors = []
  const config = parse(preJson(source, filename), errors)
  assert.deepEqual(errors.map(error => printParseErrorCode(error.error)), [], `${platform}: invalid pages.json`)
  assert(config.pages?.length > 0, `${platform}: no pages checked`)
  const currentRoutes = config.pages.map(page => page.path)
  if (routes) assert.deepEqual(currentRoutes, routes, `${platform}: route list changed`)
  else routes = currentRoutes

  const app = platform === 'app' || platform === 'app-plus'
  for (const page of config.pages) {
    const style = { ...config.globalStyle, ...page.style }
    const effectiveStyle = { ...style, ...style[app ? 'app-plus' : platform] }
    if (app) {
      assert(!effectiveStyle.disableScroll, `${page.path}: App disableScroll installs a passive document.touchmove blocker`)
      assert.equal(effectiveStyle.bounce, 'none', `${page.path}: App bounce must stay disabled`)
    } else {
      assert.equal(effectiveStyle.disableScroll, true, `${page.path}: ${platform} scroll configuration changed`)
    }
  }
  console.log(`${platform}: ${config.pages.length} page scroll configurations passed`)
}

// Optional verification of the actual CLI or HBuilderX App build.
const outputIndex = process.argv.indexOf('--app-output')
if (outputIndex !== -1) {
  assert(process.argv[outputIndex + 1], '--app-output requires an App output directory')
  const output = path.resolve(root, process.argv[outputIndex + 1])
  const serviceConfig = fs.readFileSync(path.join(output, 'app-config-service.js'), 'utf8')
  const match = serviceConfig.match(/const __uniRoutes = (\[.*?\])\.map/)
  assert(match, 'App build route configuration not found')
  const builtRoutes = JSON.parse(match[1])
  assert.deepEqual(builtRoutes.map(route => route.path), routes, 'App build routes differ from pages.json')
  for (const route of builtRoutes) {
    assert(!route.meta.disableScroll, `${route.path}: App build still enables disableScroll`)
    assert.equal(route.meta.bounce, 'none', `${route.path}: App build bounce changed`)
  }
  console.log(`App build: ${builtRoutes.length} generated route configurations passed`)
}

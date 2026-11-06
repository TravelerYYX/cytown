const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict'), ts = require('typescript')
const { platformSource } = require('./platform-source.cjs')
const { parse } = require('@vue/compiler-sfc')
const { parse:parseTemplate } = require('@vue/compiler-dom')
const root = path.resolve(__dirname, '..'), delivery = path.resolve(root, '../deliverables/uniapp-x-migration')
const routes = JSON.parse(fs.readFileSync(path.join(root, 'pages.json'), 'utf8')).pages
const original = JSON.parse(fs.readFileSync(path.join(delivery, 'baseline-inventory.json'), 'utf8')).files
const failures = [], graphs = {}, oldSFC = original.filter(entry => /\.vue$/.test(entry.path))
const crypto = require('node:crypto')
function requireCheck(value, message) { if (!value) failures.push(message) }
for (const entry of oldSFC) requireCheck(fs.existsSync(path.join(root, entry.path.replace(/\.vue$/, '.uvue'))), 'Missing migrated SFC: ' + entry.path)
for (const entry of original.filter(item => item.path.startsWith('static/'))) {
  const file = path.join(root,entry.path)
  requireCheck(fs.existsSync(file), 'Missing original asset: ' + entry.path)
  if (fs.existsSync(file)) requireCheck(crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex') === entry.sha256, 'Original asset changed: ' + entry.path)
}
for (const file of ['App.vue', 'main.ts', 'main.js', '.lang']) requireCheck(!fs.existsSync(path.join(root, file)), 'Legacy/experimental entry exists: ' + file)
requireCheck(fs.existsSync(path.join(root, 'main.uts')), 'Missing main.uts')
requireCheck(JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8'))['uni-app-x']?.vapor === true, 'Vapor must be enabled')
for (const target of ['app-android', 'app-ios', 'h5', 'mp-weixin']) {
  const seen = new Set(), native = target.startsWith('app-'), external = new Set()
  function visit(file) {
    file = path.resolve(file)
    if (seen.has(file)) return
    seen.add(file)
    const relative = path.relative(root, file).replaceAll('\\', '/')
    requireCheck(!relative.startsWith('../'), target + ': dependency escapes client: ' + relative)
    if (!fs.existsSync(file)) { failures.push(target + ': missing source: ' + relative); return }
    const source = platformSource(fs.readFileSync(file, 'utf8'), target)
    if (file.endsWith('.uvue')) {
      const parsed = parse(source, {filename: relative})
      for (const error of parsed.errors) failures.push(target + ': invalid SFC ' + relative + ': ' + String(error.message || error))
      const scripts = [...source.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
      for (const script of scripts) requireCheck(/lang="uts"/.test(script[1]) && !/renderjs/.test(script[1]), target + ': non-UTS script: ' + relative)
      if (native) {
        if(parsed.descriptor.template){
          const tree=parseTemplate(parsed.descriptor.template.content)
          function inspect(node){
            if(node.type===1&&node.tag==='button')requireCheck(!node.children.some(child=>child.type===1),target+': native button contains nested components: '+relative)
            if(node.type===1&&node.tag==='scroll-view')requireCheck(!node.props.some(prop=>prop.type===6&&['scroll-x','scroll-y'].includes(prop.name)),target+': native scrolling must use direction: '+relative)
            if(node.type===1&&node.tag==='image')requireCheck(!node.props.some(prop=>prop.type===7&&prop.name==='bind'&&!prop.arg),target+': native image must bind its id explicitly and apply semantics after decoding: '+relative)
            for(const child of node.children||[])inspect(child)
          }
          inspect(tree)
        }
        requireCheck(!/<web-view\b/.test(source) || relative === 'pages/webview/index.uvue', target + ': business WebView: ' + relative)
        for (const rich of source.matchAll(/<rich-text\b[^>]*>/g)) requireCheck(/\bmode="native"/.test(rich[0]), target + ': rich-text without native mode: ' + relative)
      }
      if(target==='mp-weixin'&&parsed.descriptor.template){
        function inspectWeixin(node){
          if(node.type===1)requireCheck(!node.props.some(prop=>prop.type===7&&prop.name==='bind'&&!prop.arg&&prop.exp?.content!=='$attrs'),target+': unsupported object v-bind: '+relative)
          for(const child of node.children||[])inspectWeixin(child)
        }
        inspectWeixin(parseTemplate(parsed.descriptor.template.content))
      }
    }
    const code = file.endsWith('.uvue') ? [...source.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(match => match[1]).join('\n') : source
    const runtime = ts.transpileModule(code, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText
    if (native && !relative.includes('/utssdk/')) {
      const forbidden = /\b(?:document|window|navigator|indexedDB|localStorage|HTMLElement|IntersectionObserver)\s*[.(\[]|\b(?:new\s+Blob|renderjs)\b|\.\s*plus\b|\bplus\s*[.(\[]/g
      for (const match of runtime.matchAll(forbidden)) failures.push(target + ': forbidden browser/legacy dependency ' + match[0] + ' in ' + relative)
    }
    // SFC component imports used only by the template disappear when script
    // alone is transpiled. Inspect source imports before TypeScript elision.
    const syntax = ts.createSourceFile(relative, code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
    const imports = new Set()
    function collect(node) {
      if (ts.isImportDeclaration(node) && !node.importClause?.isTypeOnly && ts.isStringLiteral(node.moduleSpecifier)) imports.add(node.moduleSpecifier.text)
      if (ts.isExportDeclaration(node) && !node.isTypeOnly && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) imports.add(node.moduleSpecifier.text)
      if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
        requireCheck(node.arguments.length === 1 && ts.isStringLiteral(node.arguments[0]), target + ': unresolved dynamic import in ' + relative)
        if (node.arguments.length === 1 && ts.isStringLiteral(node.arguments[0])) imports.add(node.arguments[0].text)
      }
      ts.forEachChild(node,collect)
    }
    collect(syntax)
    for (const spec of imports) {
      if (!spec.startsWith('.') && !spec.startsWith('@/')) { external.add(spec); continue }
      const base = spec.startsWith('@/') ? path.join(root, spec.slice(2)) : path.resolve(path.dirname(file), spec)
      if (base.includes(path.join('uni_modules', 'ct-platform')) && native) {
        visit(path.join(root, 'uni_modules/ct-platform/utssdk', target, 'index.uts')); continue
      }
      const found = [base, base + '.uts', base + '.uvue', base + '.js', path.join(base, 'index.uts')].find(candidate => fs.existsSync(candidate) && fs.statSync(candidate).isFile())
      requireCheck(!!found, target + ': unresolved import ' + spec + ' from ' + relative)
      if (found) { requireCheck(!/\.vue$/.test(found), target + ': old Vue dependency ' + spec); visit(found) }
    }
  }
  visit(path.join(root, 'main.uts'))
  for (const route of routes) visit(path.join(root, route.path + '.uvue'))
  graphs[target] = { sources: [...seen].map(file => path.relative(root, file).replaceAll('\\', '/')).sort(), external: [...external].sort() }
}
const report = { status: failures.length ? 'failed' : 'passed', scope: 'Static entry, route and platform dependency graph only; excludes behavior, native packaging, visual and performance acceptance', originalSFC: oldSFC.length, routes: routes.length, graphs, failures }
fs.writeFileSync(path.join(delivery, 'architecture-report.json'), JSON.stringify(report, null, 2))
console.log(JSON.stringify({ status: report.status, routes: routes.length, originalSFC: oldSFC.length, failures }, null, 2))
process.exitCode = failures.length ? 1 : 0

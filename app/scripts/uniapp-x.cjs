const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { sourceFingerprint } = require('./source-fingerprint.cjs')
const root = path.resolve(__dirname, '..')
const hx = process.env.HBUILDERX_HOME || 'D:/Editor/HBuilderX'
const about = JSON.parse(fs.readFileSync(path.join(hx,'plugins/about/package.json'),'utf8'))
if (about.version !== '5.26.2026091802') throw Error('This project is pinned to HBuilderX 5.26.2026091802; set HBUILDERX_HOME to its installation.')
const cliRoot = path.join(hx,'plugins/uniapp-cli-vite')
const [action = 'build', target = 'h5', ...args] = process.argv.slice(2)
if (!['build','dev'].includes(action) || !['h5','mp-weixin','app-android','app-ios'].includes(target)) throw Error('Usage: uniapp-x.cjs build|dev h5|mp-weixin|app-android|app-ios [args]')
const app = target.startsWith('app-')
const env = {...process.env, HX_APP_ROOT:hx, UNI_INPUT_DIR:root, UNI_CLI_CONTEXT:cliRoot,
  UNI_PLATFORM:app?'app':target, UNI_UTS_PLATFORM:app?target:target, UNI_APP_X:'true',
  UNI_OUTPUT_DIR:path.join(root,'unpackage/dist',action==='dev'?'dev':'build',target),
  NODE_PATH:path.join(cliRoot,'node_modules')}
if (app) Object.assign(env,{UNI_APP_PLATFORM:target.slice(4),UNI_APP_X_DOM2:'true',UNI_APP_X_VAPOR_RENDER_TARGET:'bytecode'})
// Native compiler bundles contain V8 bytecode tied to HBuilderX's Node version.
const compilerNode = path.join(hx, 'plugins/node', process.platform === 'win32' ? 'node.exe' : 'node')
const startedAt = new Date().toISOString()
const before = action === 'build' ? sourceFingerprint(root) : ''
const result = spawnSync(compilerNode,[path.join(cliRoot,'node_modules/@dcloudio/vite-plugin-uni/bin/uni.js'),...(action==='build'?['build']:[]),'-p',app?'app':target,...args],{cwd:cliRoot,env,stdio:action==='build'?'pipe':'inherit',encoding:'utf8',maxBuffer:64*1024*1024,windowsHide:true})
if (result.error) throw result.error
let diagnostics = []
if (action === 'build') {
  const output = (result.stdout || '') + (result.stderr || '')
  process.stdout.write(output)
  diagnostics = output.split(/\r?\n/).filter(line => /\b(?:error|warning|warn)\s*:/i.test(line))
  if (result.status !== 0) diagnostics.push('ERROR: compiler exited with status ' + result.status)
  const reportDir = path.resolve(root, '../deliverables/uniapp-x-migration/builds')
  fs.mkdirSync(reportDir,{recursive:true})
  const after = sourceFingerprint(root)
  if(before !== after) diagnostics.push('ERROR: source changed during compilation; rerun against an unchanged source tree')
  const report = {target,toolchain:about.version,mode:args.includes('development')?'development':'production',startedAt,finishedAt:new Date().toISOString(),sourceSHA256:before,sourceUnchanged:before===after,compilerExitCode:result.status,resourceCompilation:result.status===0?'completed':'failed',diagnosticGate:diagnostics.length?'failed':'passed',nativePackage:'not-built',deviceAcceptance:'not-run',diagnostics}
  fs.writeFileSync(path.join(reportDir,target+'.log'),output)
  fs.writeFileSync(path.join(reportDir,target+'.json'),JSON.stringify(report,null,2))
  if (diagnostics.length) console.error(`Build diagnostics gate failed: ${diagnostics.length} compiler diagnostics. See ${path.join(reportDir,target+'.json')}`)
}
process.exitCode = result.status || (diagnostics.length ? 1 : 0)

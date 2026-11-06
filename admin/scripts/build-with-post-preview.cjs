const {spawnSync}=require('node:child_process'),path=require('node:path')
if(!process.env.CT_BUILD_MODE)process.env.CT_BUILD_MODE=process.argv[2]==='test'?'development':'production'
const app=path.resolve(__dirname,'../../../次元町')
const preview=spawnSync(process.execPath,[path.join(app,'scripts/build-post-preview.cjs')],{cwd:app,env:process.env,stdio:'inherit'})
if(preview.status!==0)process.exit(preview.status||1)
const vite=spawnSync(process.execPath,[path.join(path.dirname(require.resolve('vite/package.json')),'bin/vite.js'),'build','--mode',process.argv[2]||'prod'],{cwd:path.resolve(__dirname,'..'),env:process.env,stdio:'inherit'})
process.exit(vite.status||0)

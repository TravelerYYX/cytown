const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),ts=require('typescript'),vue=require('vue');
const {platformSource}=require('./platform-source.cjs');
const root=path.resolve(__dirname,'..');
function harness(cache){
 let callbacks=[],toasts=[],requests=[],opens=0,installs=0,starts=0,snapshot={},foreground=false,storage=cache||{},parents=[];
 const makePage=()=>({route:'pages/index/index',dialogs:[],getDialogPages(){return this.dialogs}});
 parents=[makePage()];
 const native={getInstalledUpdateVersion:()=>100,getUpdateSnapshot:()=>JSON.stringify(snapshot),startUpdateDownload:spec=>{starts++;const s=JSON.parse(spec);snapshot={...s,phase:'queued',percent:'0.0',progress:0};return ''},installDownloadedUpdate:()=>{installs++;snapshot.attempted=true;return ''},setUpdateForeground:v=>{foreground=v},takeUpdateNotification:()=>false};
 const uni={getWindowInfo:()=>({windowHeight:800}),showToast:e=>toasts.push(e.title),getStorageSync:k=>storage[k],setStorageSync:(k,v)=>storage[k]=v,removeStorageSync:k=>delete storage[k],request:r=>requests.push(r),openDialogPage:o=>{opens++;o.parentPage.dialogs.push({route:'pages/app-update/index'});o.success?.({});o.complete?.({})},closeDialogPage:o=>{for(const p of parents)p.dialogs=p.dialogs.filter(d=>d!==o.dialogPage)}};
 function load(source,require){const code=ts.transpileModule(platformSource(source,'app-android'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;const exports={};vm.runInNewContext(code,{exports,require,uni,getCurrentPages:()=>parents,setInterval:fn=>{callbacks.push(fn);return callbacks.length},clearInterval:()=>{callbacks=[]},Date,Promise,Error,console});return exports;}
 const mod=load(fs.readFileSync(path.join(root,'utils/app-update.uts'),'utf8'),name=>name==='vue'?vue:name==='@/config/env'?{API_BASE_URL:'https://api.example.test'}:native);
 const page=fs.readFileSync(path.join(root,'pages/app-update/index.uvue'),'utf8').match(/<script setup lang="uts">([\s\S]*?)<\/script>/)[1];let back;
 const pageCode=ts.transpileModule(platformSource(page,'app-android'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
 vm.runInNewContext(pageCode,{exports:{},require:name=>name==='vue'?vue:name.includes('app-update')?mod:{},onBackPress:fn=>back=fn,uni});
 return {mod,uni,native,storage,toasts,requests,back:()=>back(),tick:()=>callbacks.forEach(fn=>fn()),setSnapshot:s=>{snapshot=s},get snapshot(){return snapshot},get starts(){return starts},get installs(){return installs},get opens(){return opens},get foreground(){return foreground},get dialogs(){return parents.reduce((n,p)=>n+p.dialogs.length,0)},move:()=>parents.push(makePage()),answer:async(data)=>{requests.shift().success({statusCode:200,data:{code:'0000',data}});await new Promise(r=>setImmediate(r))},fail:async()=>{requests.shift().fail();await new Promise(r=>setImmediate(r))}};
}
const release={id:1,versionCode:101,versionName:'1.0.1',notes:'更新',forceUpdate:false,packageUrl:'/uploads/test.apk',packageName:'com.example.ct',sizeBytes:10000,sha256:'a'.repeat(64)};
(async()=>{
 let h=harness();h.mod.launchAppUpdate();h.mod.launchAppUpdate();assert.equal(h.requests.length,1);h.mod.showAppUpdate();await h.answer({hasUpdate:true,release});assert.equal(h.dialogs,1);assert(h.mod.hasAppUpdate.value);
 h.mod.closeUpdateDialog();assert.equal(h.dialogs,0);h.mod.hideAppUpdate();h.mod.showAppUpdate();h.tick();assert.equal(h.dialogs,0,'optional dismissal lasts this process');
 let manual=h.mod.checkAppUpdate(true);await h.answer({hasUpdate:true,release});await manual;assert.equal(h.dialogs,1,'manual reopens dismissed release');
 await h.mod.beginAppUpdate();await h.mod.beginAppUpdate();assert.equal(h.starts,1,'duplicate taps do not duplicate tasks');
 h.mod.closeUpdateDialog();assert.equal(h.starts,1);assert.equal(h.snapshot.phase,'queued','background action does not cancel download');
 h.mod.hideAppUpdate();h.setSnapshot({versionCode:101,phase:'ready',percent:'100.0',progress:100,attempted:false});h.tick();assert.equal(h.installs,0);
 h.mod.showAppUpdate();assert.equal(h.installs,1,'return to foreground installs verified package');h.tick();assert.equal(h.installs,1,'cancelled installer is not reopened in a loop');await h.mod.beginAppUpdate();assert.equal(h.installs,2,'explicit install can retry');
 h=harness();h.mod.showAppUpdate();manual=h.mod.checkAppUpdate();await h.answer({hasUpdate:true,release:{...release,forceUpdate:true}});await manual;
 assert(h.back());assert.equal(h.dialogs,1,'mandatory back does not close modal');h.mod.closeUpdateDialog();assert.equal(h.dialogs,1);
 h.move();await new Promise(r=>setTimeout(r,710));h.tick();assert.equal(h.dialogs,1,'mandatory modal follows replacement parent');
 manual=h.mod.checkAppUpdate(true);await h.answer({hasUpdate:false,release:null});await manual;assert.equal(h.dialogs,0);assert(!h.mod.hasAppUpdate.value);
 h=harness();h.mod.launchAppUpdate();await h.fail();assert.equal(h.toasts.length,0,'automatic network failure stays silent');manual=h.mod.checkAppUpdate(true);await h.fail();await manual;assert.equal(h.toasts.length,1,'manual failure is visible');
 h=harness();h.mod.launchAppUpdate();manual=h.mod.checkAppUpdate(true);assert.equal(h.requests.length,1,'concurrent checks share one request');await h.fail();await manual;assert.equal(h.toasts.length,1,'joined manual checks handle automatic request failure');
 h=harness({'ct.android.update.release.v1':JSON.stringify({...release,forceUpdate:true})});h.mod.launchAppUpdate();h.mod.showAppUpdate();assert.equal(h.dialogs,1,'known mandatory update survives restart');await h.fail();assert.equal(h.dialogs,1);
 const next=harness();next.mod.launchAppUpdate();next.mod.showAppUpdate();await next.answer({hasUpdate:true,release});assert.equal(next.dialogs,1,'optional update prompts again on next cold launch');
 console.log('App update checks passed: detection, dismissal, forced navigation/back, download deduplication, installer retry and recovery.');
})().catch(e=>{console.error(e);process.exitCode=1});

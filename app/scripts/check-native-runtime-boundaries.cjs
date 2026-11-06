const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),ts=require('typescript')
const {platformSource}=require('./platform-source.cjs'),root=path.resolve(__dirname,'..')
const {ensureAndroidDevTunnel}=require('./android-dev-tunnel.cjs')
const calls=[],env={VITE_ANDROID_DEV_API_BASE_URL:'http://127.0.0.1:18080',VITE_API_BASE_URL:'http://127.0.0.1:8080'}
const run=args=>{calls.push(args);return args[0]==='devices'?'List of devices attached\r\nemulator-test\tdevice\r\n':args.at(-1)==='--list'?'host tcp:18080 tcp:8080':''}
assert.equal(ensureAndroidDevTunnel({hxHome:'unused',environment:env,exists:()=>true,run}).status,'connected')
assert(calls.some(args=>JSON.stringify(args)==='["-s","emulator-test","reverse","tcp:18080","tcp:8080"]'))
assert.equal(ensureAndroidDevTunnel({hxHome:'unused',environment:env,exists:()=>true,run:()=> 'List of devices attached\n'}).status,'no-authorized-device')
assert.equal(ensureAndroidDevTunnel({hxHome:'unused',environment:env,exists:()=>true,run:()=> 'List of devices attached\na device\nb device\n'}).status,'device-selection-required')
// 同一台设备被两条传输通道列出（HBuilderX 的 127.0.0.1:16384 与 SDK 的 emulator-5554）时必须去重后建隧道，
// 否则会误报“多台设备”，客户端所有请求都连不上，只显示“网络异常”。
const dupCalls=[]
const runDuplicate=args=>{dupCalls.push(args);if(args[0]==='devices')return 'List of devices attached\r\n127.0.0.1:16384\tdevice\r\nemulator-5554\tdevice\r\n';if(args.at(-1)==='--list')return 'host tcp:18080 tcp:8080';if(args.includes('android_id'))return '194714c507bdc471\n';return ''}
assert.equal(ensureAndroidDevTunnel({hxHome:'unused',environment:env,exists:()=>true,run:runDuplicate}).status,'connected')
assert(dupCalls.some(args=>JSON.stringify(args)==='["-s","127.0.0.1:16384","reverse","tcp:18080","tcp:8080"]'))
assert.throws(()=>ensureAndroidDevTunnel({hxHome:'unused',environment:{...env,VITE_API_BASE_URL:'https://example.test'},exists:()=>true,run}))
function script(file,target){return platformSource(fs.readFileSync(path.join(root,file),'utf8'),target).match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]}
function execute(source,context){const exports={};vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{exports,...context});return exports}
const props={disabled:true},emits=[],event={stopPropagation(){this.stopped=true},stopped:false}
const button=execute(script('components/ActionButton.uvue','app-android')+'\nexports.tap=tap;',{
 require:()=>({inject:()=>null,watch:()=>{},useNativeSemantics:()=>({prefix:'button',attrs:()=>({}),refresh:()=>{}})}),defineProps:()=>props,withDefaults:(value,defaults)=>{for(const [key,fallback] of Object.entries(defaults))if(value[key]===undefined)value[key]=fallback;return value},defineEmits:()=>((...args)=>emits.push(args))
})
button.tap(event);assert.equal(emits.length,0);assert(event.stopped,'A disabled nested action must not bubble into a post/navigation action')
props.disabled=false;button.tap(event);assert.equal(emits.length,1);assert.equal(emits[0][1],event,'Preserve the original pointer event and modifier behavior')
const attempts=[],releases=[],mount=[],update=[],dispose=[],elements=new Set(['dialog'])
const vue={getCurrentInstance:()=>({uid:1}),nextTick:()=>Promise.resolve(),onMounted:fn=>mount.push(fn),onUpdated:fn=>update.push(fn),onBeforeUnmount:fn=>dispose.push(fn)}
const source=platformSource(fs.readFileSync(path.join(root,'composables/useNativeSemantics.uts'),'utf8'),'app-android')
const semanticsModule=execute(source,{require:name=>name==='vue'?vue:name==='@/utils/window-events'?{observeWindowResize:()=>()=>{}}:{applyNativeSemantics:async(id,spec)=>{attempts.push({id,spec});return true},releaseNativeSemantics:id=>releases.push(id)},uni:{getElementById:id=>elements.has(id)?{}:null},setTimeout,clearTimeout,console})
;(async()=>{
 const statResults=[{stats:[{path:'',stats:{size:512}}]},{stats:{size:256}},{stats:[]}]
 const storage=execute(fs.readFileSync(path.join(root,'utils/file-storage.uts'),'utf8'),{Error,uni:{getFileSystemManager:()=>({stat(options){assert.equal(options.recursive,false,'Native stat requires an explicit recursive flag');options.success(statResults.shift())}})}})
 assert.equal(await storage.fileSize('/native-download'),512)
 assert.equal(await storage.fileSize('/weixin-download'),256)
 await assert.rejects(storage.fileSize('/missing-stats'))
 const downloads=[],imageFiles=new Map(),removedFiles=[]
 const isolatedStorage=execute(fs.readFileSync(path.join(root,'utils/file-storage.uts'),'utf8'),{Error,uni:{env:{USER_DATA_PATH:'unifile://usr'},downloadFile:options=>downloads.push(options),getFileSystemManager:()=>({stat:options=>options.success({stats:[{stats:{size:imageFiles.get(options.path).length}}]}),unlink:options=>{removedFiles.push(options.filePath);imageFiles.delete(options.filePath);options.success({})}})}})
 const firstImage=isolatedStorage.downloadImageFile('https://images.test/first/preview.jpg')
 const secondImage=isolatedStorage.downloadImageFile('https://images.test/second/preview.jpg?version=2')
 assert.notEqual(downloads[0].filePath,downloads[1].filePath,'Same-basename downloads must be isolated before either response writes bytes')
 assert(downloads.every(job=>job.filePath.startsWith('unifile://usr/')&&job.filePath.endsWith('.jpg')))
 // Complete in the opposite order, as concurrent CDN downloads commonly do.
 for(const index of [1,0]){const job=downloads[index];imageFiles.set(job.filePath,index===0?'first image':'different second image');job.success({statusCode:200,tempFilePath:job.filePath})}
 const [firstResult,secondResult]=await Promise.all([firstImage,secondImage])
 assert.equal(imageFiles.get(firstResult.path),'first image');assert.equal(imageFiles.get(secondResult.path),'different second image')
 assert.equal(firstResult.size,11);assert.equal(secondResult.size,22)
 const failedImage=isolatedStorage.downloadImageFile('https://images.test/failed/preview.jpg')
 const failure=downloads.at(-1);imageFiles.set(failure.filePath,'HTTP error document');failure.success({statusCode:503,tempFilePath:failure.filePath})
 await assert.rejects(failedImage,/HTTP 503/);assert(removedFiles.includes(failure.filePath),'Failed downloads remove their partial file')
 const removedStorage=[],deletedLegacy=[]
 const cacheModule=execute(platformSource(fs.readFileSync(path.join(root,'utils/media-cache.uts'),'utf8'),'app-android')+'\nexports.probe=nativeIndex;',{
  require:name=>name==='@/config/env'?{API_BASE_URL:'https://api.test'}:{deleteFile:path=>{deletedLegacy.push(path);return Promise.resolve()}},
  uni:{getStorageSync:key=>key==='ct_image_file_cache_v1'?[{key:'old',path:'unifile://usr/corrupt.jpg',size:10}]:[{key:'new',path:'unifile://usr/isolated.jpg',size:20}],removeStorageSync:key=>removedStorage.push(key)},setTimeout:()=>1,clearTimeout:()=>{}
 })
 assert.equal(cacheModule.probe().size,1);assert(cacheModule.probe().has('new'),'The new cache retains isolated downloads')
 assert.deepEqual(removedStorage,['ct_image_file_cache_v1']);assert.deepEqual(deletedLegacy,['unifile://usr/corrupt.jpg'],'Migration removes only legacy image files and metadata')
 const helper=semanticsModule.useNativeSemantics();helper.attrs('dialog',{'aria-label':'确认','aria-modal':'true','aria-hidden':false,role:'dialog'})
 const flush=async()=>{for(let i=0;i<6;i++)await Promise.resolve()}
 mount.forEach(fn=>fn());await flush();assert.equal(attempts.length,1);assert.equal(attempts[0].spec.modal,true);assert.equal(attempts[0].spec.label,'确认')
 update.forEach(fn=>fn());await flush();assert.equal(attempts.length,1,'Stable metadata does not call native APIs on every render')
 elements.delete('dialog');update.forEach(fn=>fn());await flush();assert(releases.includes('dialog'),'A conditional modal releases its restrictions while its Vue component remains mounted')
 elements.add('dialog');helper.refresh('dialog');await flush();assert.equal(attempts.length,2,'A recycled cell restores its semantics without requiring a Vue remount')
 dispose.forEach(fn=>fn())
 const web=execute(platformSource(fs.readFileSync(path.join(root,'composables/useNativeSemantics.uts'),'utf8'),'h5'),{require:name=>name==='vue'?vue:{observeWindowResize:()=>()=>{}},setTimeout,clearTimeout})
 const attributes={'aria-label':'原文','aria-hidden':true,tabindex:0,role:'button'}
 assert.equal(web.useNativeSemantics().attrs('web',attributes),attributes,'Web retains original ARIA and focus attributes')
 console.log('Native runtime boundary checks passed: tunnel selection/ports, native/WeChat file stat contracts, disabled pointer gating, semantics mapping/deduplication/detached modal release and unchanged web attributes. Mock logic only.')
})().catch(error=>{console.error(error);process.exitCode=1})

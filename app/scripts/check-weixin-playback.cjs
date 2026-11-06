const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),ts=require('typescript')
const {platformSource}=require('./platform-source.cjs')
function load(name,props,names){
 const file=path.join(__dirname,'../components',name+'.uvue'),source=platformSource(fs.readFileSync(file,'utf8'),'mp-weixin').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
 const timers=new Map(),events=[],calls=[],mounted=[],disposed=[],queries=[];let sequence=0
 const context=Object.fromEntries(['play','pause','stop','seek','playbackRate','requestFullScreen','exitFullScreen'].map(name=>[name,(...args)=>calls.push([name,...args])]))
 const vue={computed:get=>({get value(){return get()}}),ref:value=>({value}),getCurrentInstance:()=>({uid:9,proxy:{}}),nextTick:()=>Promise.resolve(),onMounted:fn=>mounted.push(fn),onBeforeUnmount:fn=>disposed.push(fn),watch:(_get,fn,settings)=>{if(settings?.immediate)fn()}}
 const query={in(){return query},select(){return query},boundingClientRect(fn){queries.push(fn);return query},exec(){}}
 const result={}
 const code=ts.transpileModule(source+'\nexports.probe={'+names+'}',{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText
 vm.runInNewContext(code,{exports:result,require:name=>{if(name==='vue')return vue;if(name==='@/utils/native-styles')return{};if(name==='@/utils/ui-assets')return{uiAsset:value=>value||''};throw Error(name)},defineProps:()=>props,defineEmits:()=>((...args)=>events.push(args)),uni:{createVideoContext:()=>context,getWindowInfo:()=>({windowWidth:390,windowHeight:844}),createSelectorQuery:()=>query,showToast:value=>calls.push(['toast',value])},Date,setTimeout:fn=>{timers.set(++sequence,fn);return sequence},clearTimeout:id=>timers.delete(id),setInterval:()=>++sequence,clearInterval:()=>{}},{filename:file})
 return{probe:result.probe,timers,events,calls,mounted,disposed,queries}
}
const options={source:'https://media.test/video.m3u8',poster:'',qualities:[],quality:'auto',rate:1,command:{id:0,type:''},active:true,suspendId:0,autoplay:false,fullscreen:false}
const surface=load('NativeVideoSurface',{options},'applyFullscreen,nativeFullscreen,commands,played,started')
surface.mounted.forEach(fn=>fn());options.fullscreen=true;surface.probe.applyFullscreen()
assert(surface.calls.some(call=>call[0]==='requestFullScreen'&&call[1].direction===90),'Fullscreen uses the native WeChat video context')
surface.probe.nativeFullscreen({detail:{fullScreen:true}})
assert(surface.events.some(event=>event[1].fullscreen===true),'Native fullscreen entry reaches the parent state')
options.active=false;surface.probe.nativeFullscreen({detail:{fullScreen:false}})
assert(surface.events.some(event=>event[1].fullscreen===false),'System exit synchronizes even when playback is inactive')
options.active=true;surface.probe.applyFullscreen();const timeout=[...surface.timers.values()][0];timeout()
assert(surface.calls.some(call=>call[0]==='toast'),'Unacknowledged fullscreen cannot silently remain enabled')
for(const rate of [.5,.75,1,1.25,1.5,2]){options.rate=rate;surface.probe.commands();assert(surface.calls.some(call=>call[0]==='playbackRate'&&call[1]===rate))}
assert.equal(surface.probe.started.value,false);surface.probe.played();assert.equal(surface.probe.started.value,true,'Poster is hidden by actual playback rather than metadata alone')
surface.probe.nativeFullscreen({detail:{fullScreen:true}});surface.disposed.forEach(fn=>fn())
assert(surface.calls.some(call=>call[0]==='exitFullScreen'),'Disposal releases native fullscreen')
const controls=load('MpFullscreenControls',{active:true,title:'',controls:true,playing:false,waiting:false,error:'',time:0,duration:100,quality:'auto',actualLevel:720,speed:1,menu:'speed',availableLevels:[720,360],processing:'',canRetry:false},'startSeek,moveSeek,endSeek,cancelSeek,pickLevel,speeds,menuHeight')
assert.equal(JSON.stringify(controls.probe.speeds),'[0.5,0.75,1,1.25,1.5,2]','All original speed entries remain available')
controls.probe.pickLevel(1080);assert(!controls.events.some(event=>event[0]==='quality'),'Unavailable quality remains disabled')
controls.probe.pickLevel(720);assert(controls.events.some(event=>event[0]==='quality'&&event[1]===720))
controls.probe.startSeek({touches:[{clientX:10}]});controls.probe.endSeek({touches:[],changedTouches:[{clientX:110}]})
assert(!controls.events.some(event=>event[0]==='seek'),'A quick tap waits for the real fullscreen track bounds')
controls.queries.shift()({left:10,width:200});assert(controls.events.some(event=>event[0]==='seek'&&event[1]===500),'Touch completion before async measurement still seeks to the intended point')
controls.probe.startSeek({touches:[{clientX:10}]});controls.queries.shift()({left:10,width:200});controls.probe.moveSeek({touches:[{clientX:999}]});controls.probe.endSeek({touches:[]})
assert(controls.events.some(event=>event[0]==='seek'&&event[1]===1000),'Seek gestures clamp to the duration')
const before=controls.events.filter(event=>event[0]==='seek').length
controls.probe.startSeek({touches:[{clientX:30}]});const late=controls.queries.shift();controls.probe.cancelSeek();late({left:10,width:200})
assert.equal(controls.events.filter(event=>event[0]==='seek').length,before,'Cancelled geometry cannot submit a seek')
controls.disposed.forEach(fn=>fn());assert.equal(controls.timers.size,0)
console.log('WeChat playback unit checks passed: native fullscreen acknowledgement/timeout/system exit/disposal, poster state, all rate requests and async cover-view seek/cancel. Mock contexts only; actual supported rates, rendering and touch remain unverified.')

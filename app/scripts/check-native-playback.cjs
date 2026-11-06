const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),ts=require('typescript')
const {platformSource}=require('./platform-source.cjs')
const filename=path.join(__dirname,'../components/NativeVideoSurface.uvue')
const source=platformSource(fs.readFileSync(filename,'utf8'),'app-android').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
const options={source:'https://media.test/master.m3u8',poster:'',qualities:[1080,720,480,360].map(height=>({height,url:'https://media.test/'+height+'.m3u8'})),quality:'auto',rate:1,command:{id:0,type:''},active:true,suspendId:0,autoplay:false}
const calls=[],events=[],mount=[],dispose=[],timers=new Map(),intervals=new Map()
let now=100000,id=0,contextReady=false
const context=Object.fromEntries(['play','pause','stop','seek','playbackRate'].map(name=>[name,(...args)=>calls.push([name,...args])]))
const vue={computed:get=>({get value(){return get()}}),ref:value=>({value}),getCurrentInstance:()=>({uid:1,proxy:{}}),nextTick:()=>Promise.resolve(),onMounted:fn=>mount.push(fn),onBeforeUnmount:fn=>dispose.push(fn),watch:(_get,callback,settings)=>{if(settings?.immediate)callback()}}
const exportsObject={}
const code=ts.transpileModule(source+'\nexports.probe={loaded,timeUpdate,progress,waiting,played,paused,failed,commands,applyQuality,switchSource,source,notify,ended,playRequested};',{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText
vm.runInNewContext(code,{exports:exportsObject,require:name=>{if(name==='vue')return vue;if(name==='@/utils/native-styles')return{};if(name==='@/utils/ui-assets')return{uiAsset:value=>value||''};throw Error('Unexpected dependency '+name)},defineProps:()=>({options}),defineEmits:()=>((_event,value)=>events.push(value)),uni:{createVideoContext:()=>contextReady?context:undefined},Date:{now:()=>now},setTimeout:fn=>{timers.set(++id,fn);return id},clearTimeout:key=>timers.delete(key),setInterval:fn=>{intervals.set(++id,fn);return id},clearInterval:key=>intervals.delete(key)},{filename})
;(async()=>{
 const probe=exportsObject.probe
 assert(probe.source.value.endsWith('/720.m3u8'),'Auto starts at the existing 720p ceiling')
 mount.forEach(fn=>fn());assert(!calls.some(call=>call[0]==='play'),'Disabled autoplay must stay paused')
 assert.equal(timers.size,1,'An unavailable native context must schedule a bounded readiness retry')
 options.command={id:10,type:'play'};probe.commands();assert(!calls.some(call=>call[0]==='play'))
 contextReady=true;const connect=[...timers.values()][0];timers.clear();connect();await Promise.resolve();await Promise.resolve()
 assert.equal(calls.filter(call=>call[0]==='play').length,1,'Replay the pending user play exactly once when the native context becomes ready')
 // A context can exist before the native media accepts play(). No play event yet.
 let playCount=calls.filter(call=>call[0]==='play').length
 probe.loaded({detail:{duration:120}})
 assert.equal(calls.filter(call=>call[0]==='play').length,playCount+1,'A manual play before metadata must resume once media is ready')
 assert(events.some(event=>event.waiting===true),'An early play must immediately report buffering')
 options.command={id:11,type:'pause'};probe.commands();probe.paused()
 playCount=calls.filter(call=>call[0]==='play').length
 probe.loaded({detail:{duration:120}})
 assert.equal(calls.filter(call=>call[0]==='play').length,playCount,'Cancelling an early play must prevent late metadata from starting playback')
 assert.equal(events[events.length-1].waiting,false,'Cancelling buffering clears the loading indicator')
 options.command={id:12,type:'play'};probe.commands()
 options.quality=480;probe.applyQuality()
 playCount=calls.filter(call=>call[0]==='play').length
 probe.loaded({detail:{duration:120}})
 assert.equal(calls.filter(call=>call[0]==='play').length,playCount+1,'Quality changes during initial buffering must preserve the pending play')
 options.quality='auto';probe.applyQuality();probe.waiting()
 const startupStall=[...timers.values()][0];timers.clear();startupStall()
 assert(probe.source.value.endsWith('/480.m3u8'),'Auto playback can lower quality while waiting for the first play event')
 probe.applyQuality();probe.loaded({detail:{duration:120}})
 probe.played();probe.timeUpdate({detail:{currentTime:42,duration:120}})
 options.quality=480;probe.applyQuality();probe.loaded({detail:{duration:120}})
 assert(probe.source.value.endsWith('/480.m3u8'));assert(calls.some(call=>call[0]==='seek'&&call[1]===42),'Quality changes preserve position')
 assert(calls.some(call=>call[0]==='play'),'Playing quality changes resume playback')
 options.quality='auto';probe.applyQuality();probe.waiting();assert.equal(timers.size,1)
 const stalled=[...timers.values()][0];timers.clear();stalled();assert(probe.source.value.endsWith('/480.m3u8'),'Stalling auto playback drops one level')
 probe.loaded({detail:{duration:120}});probe.timeUpdate({detail:{currentTime:43,duration:120}});probe.progress({detail:{buffered:80}})
 now+=31000;intervals.forEach(fn=>fn());assert(probe.source.value.endsWith('/720.m3u8'),'Buffered recovery restores one level after the hold interval')
 for(const rate of [.5,.75,1,1.25,1.5,2]){options.rate=rate;probe.commands();assert(calls.some(call=>call[0]==='playbackRate'&&call[1]===rate),'Preserve playback rate '+rate)}
 options.active=false;options.suspendId=7;probe.commands();await Promise.resolve()
 assert(events.some(event=>event.suspended===7),'Navigation suspension must acknowledge without waiting forever for a pause event')
 playCount=calls.filter(call=>call[0]==='play').length
 probe.loaded({detail:{duration:120}})
 assert.equal(calls.filter(call=>call[0]==='play').length,playCount,'Metadata arriving after navigation must never start playback')
 const before=events.length;probe.notify({time:90});assert.equal(events.length,before,'Inactive surfaces suppress late updates')
 options.active=true;options.command={id:1,type:'retry'};probe.commands();await Promise.resolve();probe.loaded({detail:{duration:120}})
 assert.equal(calls[calls.length-1][0],'play','Retry resumes playback after metadata')
 options.loop=true;probe.ended();assert.equal(probe.playRequested.value,true,'Looping retains play intent when the native ended event fires')
 options.loop=false;probe.ended();assert.equal(probe.playRequested.value,false,'Detail videos retain their default stop-at-end behavior')
 dispose.forEach(fn=>fn());assert.equal(intervals.size,0);assert.equal(timers.size,0)
 const count=events.length;probe.notify({playing:true});assert.equal(events.length,count,'Disposed engines suppress late callbacks')
 assert(calls.some(call=>call[0]==='stop'),'Unmount releases the native video context')
 console.log('Native playback controller unit checks passed: early play before context/metadata, immediate buffering/cancellation, quality changes and auto downgrade before first play, quality/seek, recovery, all rates, suspension, retry and disposal. Mock context only; no actual decoding, fullscreen or device acceptance.')
})().catch(error=>{console.error(error);process.exitCode=1})

const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),ts=require('typescript')
const {platformSource}=require('./platform-source.cjs')
const pullModule={}
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname,'../utils/inline-pull-refresh.uts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{exports:pullModule})
const {InlinePullRefresh}=pullModule
function load(name,props,probe,deps={}){
 const filename=path.join(__dirname,'../components',name+'.uvue')
 const source=platformSource(fs.readFileSync(filename,'utf8'),'app-android').match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
 const disposed=[],updates=[],resize=[],frames=new Map(),provided=new Map(),rects=new Map();let id=0
 const vue={ref:value=>({value}),computed:get=>({get value(){return get()}}),getCurrentInstance:()=>({uid:17,proxy:{}}),nextTick:()=>Promise.resolve(),onMounted:()=>{},onUpdated:fn=>updates.push(fn),onBeforeUnmount:fn=>disposed.push(fn),provide:(key,value)=>provided.set(key,value),watch:()=>{}}
 const modules={vue,'@/composables/useFeedAutoplay':{useFeedAutoplay:()=>({schedule(){},reset(){}})},'@/utils/native-styles':{},'@/utils/native-length':{nativeLength:(value,axis,context)=>value.endsWith('rpx')?parseFloat(value)*context.viewportWidth/750:value.endsWith('%')?parseFloat(value)*context[axis]/100:parseFloat(value)},'@/utils/element-geometry':{rectById:key=>Promise.resolve(rects.get(key)||null),setElementStyle:()=>{}},'@/utils/window-events':{observeWindowResize:fn=>{resize.push(fn);return()=>{}}},...deps}
 const result={}
 const code=ts.transpileModule(source+'\nexports.probe={'+probe+'}',{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText
 vm.runInNewContext(code,{exports:result,require:name=>{if(name==='@/utils/inline-pull-refresh')return pullModule;if(name in modules)return modules[name];if(name.endsWith('.uvue'))return{};throw Error(name)},defineProps:()=>props,withDefaults:(value,defaults)=>{for(const [key,fallback] of Object.entries(defaults))if(value[key]===undefined)value[key]=fallback;return value},defineEmits:()=>()=>{},defineOptions:()=>{},uni:{getWindowInfo:()=>({windowWidth:375,windowHeight:844,safeAreaInsets:{bottom:0}}),rpx2px:value=>value/2},requestAnimationFrame:fn=>{frames.set(++id,fn);return id},cancelAnimationFrame:key=>frames.delete(key),clearTimeout:()=>{},setTimeout:()=>1,console},{filename})
 return{probe:result.probe,rects,disposed,updates,resize,frames,provided}
}
;(async()=>{
 // Vapor's native fragment children can report parentElement=null. Grid tracks
 // must still use their measured content width, including after resize.
 const nativeLengthModule={}
 vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname,'../utils/native-length.uts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{exports:nativeLengthModule,Math,Number,Error})
 const layoutModule={},layoutMount=[],layoutResize=[],layoutDispose=[],layoutReuse=[],nativeElements=new Map()
 let trackWidth=374,viewportWidth=412
 const layoutVue={reactive:value=>value,getCurrentInstance:()=>({uid:42}),nextTick:()=>Promise.resolve(),onMounted:fn=>layoutMount.push(fn),onUpdated:()=>{},onBeforeUnmount:fn=>layoutDispose.push(fn),onReuse:fn=>layoutReuse.push(fn)}
 const layoutCode=platformSource(fs.readFileSync(path.join(__dirname,'../composables/useNativeLayout.uts'),'utf8'),'app-android')
 vm.runInNewContext(ts.transpileModule(layoutCode,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{exports:layoutModule,require:name=>name==='vue'?layoutVue:name==='@/utils/native-length'?nativeLengthModule:{observeWindowResize:fn=>{layoutResize.push(fn);return()=>{}}},uni:{getWindowInfo:()=>({windowWidth:viewportWidth,windowHeight:918}),getElementById:id=>{const element=nativeElements.get(id);if(element&&!element.style)element.style={getPropertyValue:()=>'',setProperty:()=>{}};return element}},requestAnimationFrame:fn=>{fn();return 1},cancelAnimationFrame:()=>{},console})
 nativeElements.set('media-track',{getBoundingClientRect:()=>({width:trackWidth,height:80})})
 for(let index=0;index<3;index++)nativeElements.set('image-'+index,{parentElement:null,getBoundingClientRect:()=>({width:134,height:80})})
 const layout=layoutModule.useNativeLayout()
 // Controls must have fixed gaps even before their native nodes exist.
 const firstGap=layout.style('first-frame-control',[{on:true,declarations:{__gap:'28rpx',__direction:'row',__index:'1'}}],{})[0]
 assert.equal(firstGap.marginLeft,28*viewportWidth/750+'px','First-frame controls must not wait for a measurement to receive spacing')
 const columnGap=layout.style('first-frame-column',[{on:true,declarations:{__gap:'12rpx',__direction:'column',__index:'2'}}],{})[0]
 assert.equal(columnGap.marginTop,12*viewportWidth/750+'px')
 assert.equal(layout.style('first-frame-wrap',[{on:true,declarations:{__gap:'28rpx',__wrap:'wrap',__index:'1'}}],{})[0].marginLeft,undefined,'Wrapped gaps still require actual bounds')
 // Portrait video cells contain only absolutely positioned cover content, so
 // their initial bounds can be 0x0 until the track assigns a column width.
 nativeElements.set('portrait-video',{parentElement:null,getBoundingClientRect:()=>({width:0,height:0})})
 const portraitRules=[{on:true,declarations:{__container:'media-track',__columns:'3',__gap:'10rpx',__direction:'row',__wrap:'wrap',__index:'0'}}]
 layout.style('portrait-video',portraitRules,{})
 const delayedCell=nativeElements.get('image-2');nativeElements.delete('image-2')
 const rules=index=>[{on:true,declarations:{__container:'media-track',__columns:'3',__gap:'10rpx',__direction:'row',__wrap:'wrap',__index:String(index)}}]
 for(let index=0;index<3;index++)layout.style('image-'+index,rules(index),{})
 layoutMount.forEach(fn=>fn());await Promise.resolve();await Promise.resolve()
 nativeElements.set('image-2',delayedCell);layoutResize.forEach(fn=>fn());await Promise.resolve();await Promise.resolve()
 const grid=index=>layout.style('image-'+index,rules(index),{})[1]
 const gridWidth=()=>parseFloat(grid(0).width)
 assert.equal(layout.style('portrait-video',portraitRules,{})[1].width,grid(0).width,'A zero-sized portrait video must receive its width from the measured track')
 assert(Math.abs(gridWidth()-(trackWidth-20*viewportWidth/750)/3)<1e-7,'Three images must fit the measured content track, not the viewport')
 assert.equal(grid(2).marginTop,undefined,'The third image stays on the first row')
 assert.equal(grid(2).width,grid(0).width,'A cell that appears after mount must retain and apply its layout rules')
 assert(Math.abs(3*gridWidth()+2*parseFloat(grid(2).marginLeft)-trackWidth)<1e-7,'Widths and gaps must exactly fill one row')
 trackWidth=320;viewportWidth=358;layoutResize.forEach(fn=>fn());await Promise.resolve();await Promise.resolve()
 assert.equal(layout.style('portrait-video',portraitRules,{})[1].width,grid(0).width,'A portrait video must remeasure its column width after resize')
 assert(Math.abs(gridWidth()-(trackWidth-20*viewportWidth/750)/3)<1e-7,'Rotation and window resize remeasure the real track')
 // Vapor recycles a list row by updating its render effect. This need not
 // invoke the owning component's onUpdated hook or resize the window.
 const replacementRules=[{on:true,declarations:{__container:'media-track',__columns:'2',__gap:'10rpx',__direction:'row',__wrap:'wrap',__index:'0'}}]
 layout.style('portrait-video',replacementRules,{})
 await Promise.resolve();await Promise.resolve()
 assert(Math.abs(parseFloat(layout.style('portrait-video',replacementRules,{})[1].width)-(trackWidth-10*viewportWidth/750)/2)<1e-7,'Changed rules in a recycled row must schedule measurement without a component update or resize')
 nativeElements.set('returned-video',{parentElement:null,getBoundingClientRect:()=>({width:0,height:0})})
 layout.style('returned-video',portraitRules,{})
 await Promise.resolve();await Promise.resolve()
 assert.equal(layout.style('returned-video',portraitRules,{})[1].width,grid(0).width,'A cover inserted after mount must receive its track width without a component update or resize')
 // Native row reuse can clear inline width while its cached calculated value
 // stays identical (for example, an image and a video in the same column).
 const restoredStyles={width:'auto'}
 nativeElements.get('returned-video').style={getPropertyValue:key=>restoredStyles[key]||'',setProperty:(key,value)=>{restoredStyles[key]=value}}
 const recycledRules=[...portraitRules,{on:true,declarations:{width:'calc((100% - 20rpx) / 3)'}}]
 layout.style('returned-video',recycledRules,{})
 await Promise.resolve();await Promise.resolve()
 assert.equal(restoredStyles.width,grid(0).width,'A recycled native cover must regain its width even when the calculated width equals its cached value')
 // A row can reattach with identical rules and identical geometry. Its native
 // style still needs restoring; neither a rule signature nor resize changes.
 restoredStyles.width='100%'
 layoutReuse.forEach(fn=>fn());await Promise.resolve();await Promise.resolve()
 assert.equal(restoredStyles.width,grid(0).width,'Reuse must restore native width even when rules and measured bounds are unchanged')
 // An offscreen cell may exhaust the short mount retry window before it is
 // attached by list-view. Reuse must give it a fresh measurement opportunity.
 layout.style('offscreen-image',rules(1),{})
 for(let frame=0;frame<8;frame++)await Promise.resolve()
 nativeElements.set('offscreen-image',{parentElement:null,getBoundingClientRect:()=>({width:trackWidth,height:200})})
 layoutReuse.forEach(fn=>fn());await Promise.resolve();await Promise.resolve()
 assert.equal(layout.style('offscreen-image',rules(1),{})[1].width,grid(0).width,'A cell attached after mount retries must receive its thumbnail width on reuse')
 layoutDispose.forEach(fn=>fn())
 const props={maxHeight:'400rpx',limitId:'stage',inset:'0px'}
 const intrinsic=load('IntrinsicScroller',props,'measure,height,contentId')
 intrinsic.rects.set('stage',{width:375,height:180});intrinsic.rects.set(intrinsic.probe.contentId,{height:600})
 await intrinsic.probe.measure();assert.equal(intrinsic.probe.height.value,200,'Long tools remain scrollable within the original 400rpx limit')
 props.maxHeight='90%';props.inset='32rpx';await intrinsic.probe.measure();assert.equal(intrinsic.probe.height.value,146,'Player menu subtracts its outer padding from the stage limit')
 intrinsic.rects.set('stage',{width:844,height:390});await intrinsic.probe.measure();assert.equal(intrinsic.probe.height.value,335,'Orientation/viewport changes remeasure the menu cap')
 intrinsic.rects.set(intrinsic.probe.contentId,{height:72});await intrinsic.probe.measure();assert.equal(intrinsic.probe.height.value,72,'Short menus retain their natural height')
 intrinsic.disposed.forEach(fn=>fn());intrinsic.rects.set(intrinsic.probe.contentId,{height:500});await intrinsic.probe.measure();assert.equal(intrinsic.probe.height.value,72,'Late geometry cannot mutate a disposed scroller')
 const visual=[]
 const listProps={postItems:[{id:'a'},{id:'b'}],minimumContentHeight:1400,visualScroll:top=>visual.push(top)}
 const list=load('PaneScroll',listProps,'measureMinimum,minimumFill,hostId,scroll,restore',{'@/utils/scroll-context':{paneScrollKey:'pane',postLayoutKey:'post'},'@/utils/scroll-anchors':{ScrollAnchors:class{register(){return()=>{}}dispose(){} }},'@/composables/useChannelTransition':{useChannelTransition:()=>({play:()=>{}})},'@/composables/useHorizontalSwipe':{useHorizontalSwipe:()=>({start(){},move(){},end(){},cancel(){}})}})
 list.rects.set(list.probe.hostId+'-before',{height:600});list.rects.set(list.probe.hostId+'-footer-content',{height:200})
 list.rects.set(list.probe.hostId+'-post-a',{height:100});list.rects.set(list.probe.hostId+'-post-b',{height:100})
 await list.probe.measureMinimum();assert.equal(list.probe.minimumFill.value,400,'Short mine lists retain the complete profile panel minimum height')
 list.rects.set(list.probe.hostId+'-post-b',{height:900});await list.probe.measureMinimum();assert.equal(list.probe.minimumFill.value,0,'Long content removes the filler rather than limiting actual post content')
 listProps.postItems=[{id:'unreached'}];await list.probe.measureMinimum();assert.equal(list.probe.minimumFill.value,0,'Unreached native cells never receive invented measured heights')
 listProps.postItems=[];await list.probe.measureMinimum();assert.equal(list.probe.minimumFill.value,600,'Empty channels retain the existing panel scroll range')
 list.probe.scroll({detail:{scrollTop:20}});list.probe.scroll({detail:{scrollTop:40}});list.probe.scroll({detail:{scrollTop:60}})
 assert.equal(list.frames.size,1,'Multiple scroll events coalesce into one header frame')
 const flushFrames=()=>{const pending=[...list.frames.values()];list.frames.clear();pending.forEach(fn=>fn())}
 flushFrames();assert.deepEqual(visual,[60],'Visual callback receives the latest offset')
 await list.probe.restore(0);flushFrames();assert.equal(visual.at(-1),0,'Programmatic return to top repaints the header')
 // A fling may cross the entire sticky boundary between animation frames.
 // Its header handover must use the new offset in the scroll event itself.
 listProps.collapseThreshold=100
 list.probe.scroll({detail:{scrollTop:80}})
 assert.equal(list.frames.size,1,'Within a zone, visual updates remain coalesced')
 list.probe.scroll({detail:{scrollTop:140}})
 assert.equal(visual.at(-1),140,'Fast upward crossing paints before the next animation frame')
 assert.equal(list.frames.size,0,'Boundary paint cancels the queued pre-boundary frame')
 list.probe.scroll({detail:{scrollTop:101}})
 assert.equal(list.frames.size,1)
 list.probe.scroll({detail:{scrollTop:99}})
 assert.equal(visual.at(-1),99,'Reverse crossing restores flowing tabs immediately')
 assert.equal(list.frames.size,0)
 list.probe.scroll({detail:{scrollTop:100}})
 assert.equal(visual.at(-1),100,'The exact sticky boundary paints immediately')
 await list.probe.restore(0)
 assert.equal(visual.at(-1),0,'Return to top immediately releases the sticky header')
 list.probe.scroll({detail:{scrollTop:80}});listProps.active=false;flushFrames();assert.equal(visual.at(-1),0,'Hidden panes cannot run pending visual callbacks')
 listProps.active=true;list.probe.scroll({detail:{scrollTop:90}})
 list.disposed.forEach(fn=>fn());assert.equal(list.frames.size,0,'Disposal cancels pending header frames')
 const pull=new InlinePullRefresh(105)
 pull.begin(100,200,true);assert.equal(pull.move(101,204),0,'Finger jitter does not expose a refresh area');pull.move(100,300);assert.equal(pull.release(),false,'A short released pull cancels without a request')
 pull.begin(100,200,true);pull.move(100,450);assert.equal(pull.release(),true,'Pulling beyond the original 210rpx threshold refreshes on release');assert.equal(pull.release(),false,'One gesture cannot refresh twice')
 pull.begin(100,200,false);pull.move(100,900);assert.equal(pull.release(),false,'A gesture begun below the top remains an ordinary scroll')
 pull.begin(100,200,true);pull.move(200,210);pull.move(200,900);assert.equal(pull.release(),false,'A horizontal gesture cannot turn into a refresh')
 pull.begin(100,200,true);pull.move(100,180);pull.move(100,900);assert.equal(pull.release(),false,'An upward scroll cannot turn into a refresh')
 pull.begin(100,200,true);pull.move(100,900);pull.move(100,220);assert.equal(pull.release(),false,'Returning below the threshold before release cancels')
 pull.begin(100,200,true);pull.move(100,900);pull.cancel();assert.equal(pull.release(),false,'Native touch cancellation never submits a refresh')
 console.log('Native scroll checks passed: geometry and inline refresh gesture traces (jitter, threshold, cancellation, scroll/horizontal arbitration and one request per gesture). Mock logic only; device touch/rendering is verified separately.')
})().catch(error=>{console.error(error);process.exitCode=1})

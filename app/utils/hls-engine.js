import Hls from 'hls.js'

/** Browser playback engine, included only by the WEB platform component. */
export function createEngine(host, emit) {
 const video=document.createElement('video');video.playsInline=true;video.setAttribute('webkit-playsinline','true');video.controls=false;video.preload='metadata';video.style.cssText='display:block;width:100%;height:100%;object-fit:contain;background:#000';host.appendChild(video)
 let hls,options={},source='',quality='auto',currentLevel=0,commandId=-1,pendingSeek=0,stallTimer,capUntil=0,nativeLevel=0,destroyed=false
 const notify=(extra={})=>{if(!destroyed)emit({time:video.currentTime||0,duration:video.duration||0,playing:!video.paused,buffered:buffered(),...extra})}
 const buffered=()=>{for(let i=0;i<video.buffered.length;i++)if(video.buffered.start(i)<=video.currentTime&&video.buffered.end(i)>=video.currentTime)return video.buffered.end(i)-video.currentTime;return 0}
 const play=()=>{if(destroyed||options.active===false)return Promise.resolve();return video.play().catch(()=>notify({playing:false,waiting:false,blocked:true}))}
 function levelFor(value){const levels=options.qualities||[];if(value!=='auto')return levels.findIndex(q=>q.height===Number(value));return levels.findIndex(q=>q.height<=720)}
 function hlsIndex(height){const item=options.qualities?.find(q=>q.height===Number(height));return hls.levels.findIndex(level=>item?.url&&level.url?.includes(item.url)||Math.min(level.width,level.height)===Number(height))}
 function label(index){const level=hls.levels[index];return options.qualities?.find(q=>level.url?.includes(q.url))?.height||Math.min(level.width,level.height)}
 function nativeSource(index){const entries=options.qualities||[];nativeLevel=Math.max(0,index);pendingSeek=video.currentTime||0;const playing=!video.paused||options.autoplay;video.src=entries[nativeLevel]?.url||source;video.load();video.addEventListener('loadedmetadata',()=>{if(pendingSeek>0)video.currentTime=Math.min(pendingSeek,video.duration||pendingSeek);if(playing)void play()},{once:true});notify({level:entries[nativeLevel]?.height||0})}
 function select(value){quality=value;const index=levelFor(value);if(hls){hls.loadLevel=value==='auto'?-1:index;}else if(options.qualities?.length){nativeSource(index<0?0:index)}}
 const onWaiting=()=>{notify({waiting:true});clearTimeout(stallTimer);stallTimer=setTimeout(()=>{if(quality!=='auto'||video.paused)return;const levels=options.qualities||[];if(hls){const current=hls.currentLevel>=0?hls.currentLevel:currentLevel;const next=Math.max(0,current-1);if(next<current){hls.loadLevel=-1;hls.autoLevelCapping=next;hls.nextAutoLevel=next;capUntil=Date.now()+30000;}}
   else if(nativeLevel<levels.length-1){nativeSource(nativeLevel+1);capUntil=Date.now()+30000}},2000)}
 const onPlaying=()=>{clearTimeout(stallTimer);notify({waiting:false})}
 const listeners={timeupdate:()=>notify(),play:()=>notify(),pause:()=>notify(),durationchange:()=>notify(),playing:onPlaying,waiting:onWaiting,ended:()=>notify({ended:true}),error:()=>notify({error:'视频加载失败，请检查网络后重试',waiting:false})}
 for(const [name,fn] of Object.entries(listeners))video.addEventListener(name,fn)
 const recovery=setInterval(()=>{if(quality==='auto'&&hls&&capUntil&&Date.now()>capUntil&&buffered()>=12){hls.autoLevelCapping=-1;hls.nextLevel=-1;capUntil=0}if(quality==='auto'&&!hls&&capUntil&&Date.now()>capUntil&&buffered()>=12&&nativeLevel>0){nativeSource(nativeLevel-1);capUntil=Date.now()+30000}},3000)
 function update(next){
  const previous=options;options=next||{};video.muted=options.muted===true;video.loop=options.loop===true;video.style.objectFit=options.objectFit||'contain';video.poster=options.poster||'';const target=options.source||''
  if(target!==source){source=target;clearTimeout(stallTimer);capUntil=0;if(hls){hls.destroy();hls=undefined}video.removeAttribute('src');video.load();quality=options.quality||'auto'
   if(source){if(source.split('?')[0].endsWith('.m3u8')&&Hls.isSupported()){
     // hls.js sorts variants by bandwidth, independent of their master-playlist order.
     hls=new Hls({autoStartLoad:false,maxBufferLength:24,maxMaxBufferLength:24,backBufferLength:12});hls.on(Hls.Events.MANIFEST_PARSED,()=>{const wanted=levelFor(quality);const height=options.qualities?.[wanted]?.height;const index=hlsIndex(height);const fallback=hls.levels.map((level,index)=>({level,index})).filter(item=>Math.min(item.level.width,item.level.height)<=720).at(-1)?.index||0;hls.startLevel=index<0?fallback:index;if(quality!=='auto')hls.loadLevel=hls.startLevel;hls.startLoad();if(options.autoplay)void play()})
     hls.on(Hls.Events.LEVEL_SWITCHED,(_,data)=>{currentLevel=data.level;const level=hls.levels[data.level];notify({level:label(data.level),waiting:false})})
     hls.on(Hls.Events.ERROR,(_,data)=>{if(!data.fatal)return;if(data.type===Hls.ErrorTypes.NETWORK_ERROR){notify({error:'网络中断，请重试',waiting:false})}else if(data.type===Hls.ErrorTypes.MEDIA_ERROR)hls.recoverMediaError();else notify({error:'此设备无法播放该视频',waiting:false})})
     hls.attachMedia(video);hls.loadSource(source)
    }else if(!source.endsWith('.m3u8')||video.canPlayType('application/vnd.apple.mpegurl'))nativeSource(Math.max(0,levelFor(quality)))
    else notify({error:'当前系统 WebView 不支持 HLS，请更新 WebView',waiting:false})}
  }
  if(options.quality!==previous.quality&&target===source&&source){if(hls){quality=options.quality;hls.loadLevel=quality==='auto'?-1:hlsIndex(quality)}else select(options.quality)}
  if(options.autoplay&&!previous.autoplay&&source)void play()
  if(options.rate)video.playbackRate=options.rate
  if(options.command?.id!==commandId){const command=options.command||{};commandId=command.id;switch(command.type){case'play':void play();break;case'pause':video.pause();break;case'seek':video.currentTime=Math.max(0,Math.min(command.value,video.duration||command.value));break;case'retry':if(hls)hls.startLoad(video.currentTime);else video.load();void play();break}}
  if(options.active===false)video.pause()
  if(previous.active===false&&options.active!==false)notify()
 }
 return{update,destroy(){destroyed=true;clearTimeout(stallTimer);clearInterval(recovery);if(hls)hls.destroy();video.pause();video.removeAttribute('src');video.load();video.remove();for(const[name,fn]of Object.entries(listeners))video.removeEventListener(name,fn)}}
}

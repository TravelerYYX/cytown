'use strict'
// Chat video bytes have a single producer. Player ranges and background fill share chunks.
const CHUNK=1024*1024, DB='ct-private-chat-v1', sessions=new Map(), jobs=new Map(), chunks=new Map()
self.addEventListener('install',event=>event.waitUntil(self.skipWaiting()))
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()))
function database(){return new Promise((resolve,reject)=>{const q=indexedDB.open(DB,1);q.onupgradeneeded=()=>{for(const n of ['media','chunks'])if(!q.result.objectStoreNames.contains(n))q.result.createObjectStore(n,{keyPath:'key'})};q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error)})}
async function query(store,mode,action){const db=await database();return new Promise((resolve,reject)=>{const tx=db.transaction(store,mode),q=action(tx.objectStore(store));let result;q.onsuccess=()=>{result=q.result};tx.oncomplete=()=>{db.close();resolve(result)};tx.onerror=()=>{db.close();reject(tx.error)};tx.onabort=()=>{db.close();reject(tx.error)}})}
async function announce(data){for(const client of await self.clients.matchAll())client.postMessage(data)}
async function receipt(s,action){const response=await fetch(s.api+'/app/chat/messages/'+s.messageId+'/receipt',{method:'POST',headers:{Authorization:'Bearer '+s.token,'Content-Type':'application/json'},body:JSON.stringify({operationId:s.operation,kind:'received',action}),signal:s.abort.signal});const result=await response.json();if(!response.ok||result.code!=='0000')throw Error(result.msg||'视频接收确认失败');return result.data}
function active(s){if(s.abort.signal.aborted||sessions.get(s.key)!==s)throw Error('私聊缓存操作已结束')}
async function reserveVideo(s){
 if(!s.peer||s.reserved)return
 const existing=await query('media','readonly',q=>q.get(s.key));s.operation=existing?.operation||('v_'+crypto.randomUUID().replaceAll('-',''))
 const result=await receipt(s,'reserve');if(result.state==='failed')throw Error('接收操作已结束，请重新播放')
 active(s);s.reserved=true;await query('media','readwrite',q=>q.put({...existing,key:s.key,owner:s.owner,assetId:s.asset.id,size:s.asset.sizeBytes,operation:s.operation,reserved:true,complete:false}))
}
async function chunk(s,index){
 const key=s.key+':'+index;let flight=chunks.get(key);if(flight)return flight
 const download=async()=>{active(s);const expected=Math.min(CHUNK,s.asset.sizeBytes-index*CHUNK),cached=await query('chunks','readonly',q=>q.get(key));if(cached&&cached.bytes.byteLength===expected)return cached.bytes
  const keys=await query('chunks','readonly',q=>q.getAllKeys(IDBKeyRange.bound(s.key+':',s.key+':\uffff')));const lastMissing=keys.length===Math.ceil(s.asset.sizeBytes/CHUNK)-1
  if(lastMissing)await reserveVideo(s)
  const start=index*CHUNK,end=Math.min(s.asset.sizeBytes-1,start+CHUNK-1),response=await fetch(s.api+'/app/chat/assets/'+s.asset.id+'/content',{headers:{Authorization:'Bearer '+s.token,Range:'bytes='+start+'-'+end},signal:s.abort.signal})
  if(response.status!==206||response.headers.get('content-type')?.includes('json'))throw Error('视频分块下载失败')
  const bytes=await response.arrayBuffer();if(bytes.byteLength!==expected)throw Error('视频分块不完整');active(s);await query('chunks','readwrite',q=>q.put({key,mediaKey:s.key,bytes}));return bytes
 }
 flight=(s.sequence||Promise.resolve()).then(download,download);s.sequence=flight.then(()=>{},()=>{});chunks.set(key,flight)
 try{return await flight}finally{if(chunks.get(key)===flight)chunks.delete(key)}
}
async function fill(s){const meta=await query('media','readonly',q=>q.get(s.key));if(meta?.complete){if(meta.operation&&!meta.confirmed&&s.peer){s.operation=meta.operation;await receipt(s,'confirm');await query('media','readwrite',q=>q.put({...meta,confirmed:true}))}return}
 try{for(let i=0;i<Math.ceil(s.asset.sizeBytes/CHUNK);i++)await chunk(s,i);active(s);await reserveVideo(s);const row=await query('media','readonly',q=>q.get(s.key));await query('media','readwrite',q=>q.put({...row,key:s.key,owner:s.owner,assetId:s.asset.id,size:s.asset.sizeBytes,complete:true,operation:s.operation||'',confirmed:!s.peer}));if(s.peer){await receipt(s,'confirm');const final=await query('media','readonly',q=>q.get(s.key));await query('media','readwrite',q=>q.put({...final,confirmed:true}))}await announce({type:'chat-cached',key:s.key})
 }catch(error){if(s.abort.signal.aborted)throw error;const pending=await query('media','readonly',q=>q.get(s.key));if(s.reserved&&!pending?.complete){try{await receipt(s,'fail');s.reserved=false;s.operation='';await query('media','readwrite',q=>q.put({...pending,operation:'',reserved:false}))}catch{}}await announce({type:'chat-cache-error',key:s.key,error:error.message});throw error}}
function stop(s){s.abort.abort();sessions.delete(s.key)}
self.addEventListener('message',event=>{const data=event.data||{},reply=event.ports[0];
 if(data.type==='chat-video'){event.waitUntil((async()=>{try{const old=sessions.get(data.key);let s=old;if(!s||s.token!==data.token){if(old)stop(old);s={...data,abort:new AbortController()};sessions.set(data.key,s)}reply?.postMessage({ok:true});if(!jobs.has(data.key)){const job=fill(s).catch(()=>{}).finally(()=>{if(jobs.get(data.key)===job)jobs.delete(data.key)});jobs.set(data.key,job);await job}}catch(error){reply?.postMessage({error:error.message})}})());return}
 if(data.type==='chat-session-update'){for(const s of sessions.values())if(s.owner===data.owner){s.token=data.token;if(!jobs.has(s.key)){const job=fill(s).catch(()=>{}).finally(()=>{if(jobs.get(s.key)===job)jobs.delete(s.key)});jobs.set(s.key,job);event.waitUntil(job)}}return}
 if(['chat-clear','chat-remove','chat-session-end'].includes(data.type)){const stopped=[];for(const s of sessions.values())if(data.type==='chat-session-end'||data.key===s.key||data.owner===s.owner){stop(s);stopped.push(jobs.get(s.key),s.sequence)}event.waitUntil(Promise.allSettled(stopped.filter(Boolean)).then(()=>reply?.postMessage({ok:true})))}
})
self.addEventListener('fetch',event=>{const url=new URL(event.request.url);if(!url.pathname.startsWith('/__ct_chat_video__/'))return;event.respondWith((async()=>{const key=decodeURIComponent(url.pathname.slice('/__ct_chat_video__/'.length)),s=sessions.get(key);if(!s)return new Response('Chat session required',{status:403});const size=s.asset.sizeBytes;let start=0,end=size-1;const range=event.request.headers.get('Range');if(range){const match=/^bytes=(\d*)-(\d*)$/.exec(range);if(!match)return new Response(null,{status:416,headers:{'Content-Range':'bytes */'+size}});if(!match[1])start=Math.max(0,size-Number(match[2]));else{start=Number(match[1]);if(match[2])end=Math.min(end,Number(match[2]))}if(start>=size||end<start)return new Response(null,{status:416,headers:{'Content-Range':'bytes */'+size}})}
 let offset=start;const stream=new ReadableStream({async pull(controller){try{active(s);if(offset>end){controller.close();return}const index=Math.floor(offset/CHUNK),bytes=await chunk(s,index),base=index*CHUNK,from=offset-base,to=Math.min(bytes.byteLength,end-base+1);controller.enqueue(new Uint8Array(bytes,from,to-from));offset=base+to}catch(error){controller.error(error)}}});const headers={'Content-Type':s.asset.mime,'Content-Length':String(end-start+1),'Accept-Ranges':'bytes','Cache-Control':'no-store'};if(range)headers['Content-Range']='bytes '+start+'-'+end+'/'+size;return new Response(event.request.method==='HEAD'?null:stream,{status:range?206:200,headers})})())})

// Preview frames never receive an administrator token. All reads go through the owning window.
export const enabled=typeof window!=='undefined'&&window.parent!==window&&/[?&]preview=1(?:&|$)/.test(window.location.hash+window.location.search)
const pending=new Map(),listeners=new Set()
let sequence=0
const scrollSnapshots=new Map()
export function captureScroll(){
 const id=++sequence,root=document.querySelector('.detail-scroll')
 scrollSnapshots.set(id,root?[root,...root.querySelectorAll('*')].filter(element=>['auto','scroll'].includes(getComputedStyle(element).overflowY)&&element.scrollHeight>element.clientHeight).map(element=>({element,top:element.scrollTop})):[])
 return id
}
export function restoreScroll(id){
 const snapshot=scrollSnapshots.get(id)||[];scrollSnapshots.delete(id)
 for(const item of snapshot)if(item.element.isConnected)item.element.scrollTop=item.top
}
if(enabled)window.addEventListener('message',event=>{
 if(event.origin!==window.location.origin||event.source!==window.parent)return
 const data=event.data
 if(!data||typeof data!=='object')return
 if(data.type==='ct-post-preview-refresh'){for(const listener of listeners)listener();return}
 if(data.type!=='ct-post-preview-response')return
 const request=pending.get(data.requestId);if(!request)return
 pending.delete(data.requestId);clearTimeout(request.timer)
 if(data.error)request.reject(new Error(data.error));else request.resolve(data.data)
})
export function read(resource,postId,params={}){
 return new Promise((resolve,reject)=>{
  const requestId='preview-'+(++sequence)
  const timer=setTimeout(()=>{pending.delete(requestId);reject(new Error('预览加载超时，请重试'))},15000)
  pending.set(requestId,{resolve,reject,timer})
  window.parent.postMessage({type:'ct-post-preview-request',requestId,resource,postId,params},window.location.origin)
 })
}
export function subscribe(listener){listeners.add(listener);return()=>listeners.delete(listener)}

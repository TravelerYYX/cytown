// Native browser boundary: UTSJSONObject instances cannot cross structured-clone APIs.
export function postChatWorker(worker, payload, ports = []) {
  worker.postMessage(JSON.parse(JSON.stringify(payload)), ports)
}
export function refreshChatWorker(owner, token) {
  navigator.serviceWorker?.controller?.postMessage({type:'chat-session-update',owner,token})
}
export function createChatVideo(host, source, failed) {
  const video=document.createElement('video');video.src=source;video.controls=true;video.playsInline=true;video.autoplay=true;video.style.cssText='position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000';host.appendChild(video)
  const error=()=>failed();video.addEventListener('error',error);void video.play().catch(()=>{})
  return {pause(){video.pause()},play(){void video.play().catch(()=>{})},destroy(){video.removeEventListener('error',error);video.pause();video.removeAttribute('src');video.load();video.remove()}}
}

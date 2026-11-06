<script setup lang="ts">
import { computed,onMounted,onUnmounted,ref } from 'vue';
import { fetchPostComments,fetchPostDetail,fetchPostReplies } from '@/service/api/post';

const props=defineProps<{postId:string}>();
const frame=ref<HTMLIFrameElement>();
const error=ref(''),deleted=ref(false),loading=ref(true);
const src=computed(()=>'/post-preview/index.html#/pages/post/index?id='+encodeURIComponent(props.postId)+'&preview=1');
type Query={resource:'comments'|'replies';rootId?:string;pageNum:number;pageSize:number;scope:'all'|'author';sort:'hot'|'latest'};
const queries=new Map<string,Query>(),cache=new Map<string,unknown>();
let detail:Api.Post.PostDetail|undefined,detailFlight:Promise<void>|null=null,refreshing=false,disposed=false;
function messageOf(value:unknown){const e=value as {response?:{data?:{msg?:string}};message?:string};return e?.response?.data?.msg||e?.message||'预览同步失败，请重试';}
async function loadDetail(){
 if(detailFlight)return detailFlight;
 const flight=(async()=>{const result=await fetchPostDetail(props.postId);if(disposed)return;if(result.error||!result.data)throw new Error(messageOf(result.error));detail=result.data;})();
 detailFlight=flight;try{await flight}finally{if(detailFlight===flight)detailFlight=null;}
}
function send(data:Record<string,unknown>){if(!disposed)frame.value?.contentWindow?.postMessage(data,window.location.origin);}
function queryOf(data:Record<string,unknown>):Query{
 const params=(data.params||{}) as Record<string,unknown>;
 const pageNum=Number(params.pageNum||1),pageSize=Number(params.pageSize||20);
 if(!Number.isInteger(pageNum)||pageNum<1||pageNum>100000||!Number.isInteger(pageSize)||pageSize<1||pageSize>100)throw new Error('分页参数无效');
 const resource=data.resource;if(resource!=='comments'&&resource!=='replies')throw new Error('不支持的预览请求');
 const rootId=resource==='replies'?String(params.rootId||''):undefined;
 if(resource==='replies'&&!/^[1-9]\d*$/.test(rootId||''))throw new Error('回复参数无效');
 return {resource,rootId,pageNum,pageSize,scope:params.scope==='author'?'author':'all',sort:params.sort==='latest'?'latest':'hot'};
}
async function readQuery(query:Query){
 const {resource,rootId,...params}=query;
 const result=resource==='comments'?await fetchPostComments(props.postId,params):await fetchPostReplies(props.postId,rootId!,params);
 if(result.error||!result.data)throw new Error(messageOf(result.error));return result.data;
}
async function receive(event:MessageEvent){
 if(event.origin!==window.location.origin||event.source!==frame.value?.contentWindow)return;
 const data=event.data as Record<string,unknown>;
 if(!data||data.type!=='ct-post-preview-request'||data.postId!==props.postId||typeof data.requestId!=='string')return;
 try{
  let value:unknown;
  if(data.resource==='detail'){if(!detail)await loadDetail();value=detail;}
  else{const query=queryOf(data),key=JSON.stringify(query);queries.set(key,query);value=cache.get(key);if(value===undefined){value=await readQuery(query);if(!disposed)cache.set(key,value);}}
  send({type:'ct-post-preview-response',requestId:data.requestId,data:value});
 }catch(e){send({type:'ct-post-preview-response',requestId:data.requestId,error:messageOf(e)});}
}
async function refresh(){
 if(refreshing||disposed||document.hidden||deleted.value)return;
 refreshing=true;
 try{
  await loadDetail();
  const results=await Promise.all([...queries].map(async([key,query])=>[key,await readQuery(query)] as const));
  if(disposed)return;for(const [key,value] of results)cache.set(key,value);
  error.value='';send({type:'ct-post-preview-refresh'});
 }catch(e){if(!disposed){error.value=messageOf(e);deleted.value=/不存在|已删除/.test(error.value);}}
 finally{refreshing=false;loading.value=false;}
}
function resume(){if(!document.hidden)void refresh();}
let timer:ReturnType<typeof setInterval>|undefined;
onMounted(()=>{window.addEventListener('message',receive);document.addEventListener('visibilitychange',resume);window.addEventListener('online',resume);void refresh();timer=setInterval(()=>{if(!error.value)void refresh();},3000);});
onUnmounted(()=>{disposed=true;clearInterval(timer);window.removeEventListener('message',receive);document.removeEventListener('visibilitychange',resume);window.removeEventListener('online',resume);queries.clear();cache.clear();});
</script>

<template>
 <div class="post-phone-preview">
  <NAlert v-if="error" type="error" class="mb-12px">{{error}} <NButton v-if="!deleted" text @click="refresh">重试</NButton></NAlert>
  <NSpin :show="loading"><div class="post-phone-frame"><iframe v-if="!deleted" ref="frame" :src="src" title="帖子手机预览" allow="fullscreen" sandbox="allow-scripts allow-same-origin allow-presentation"/><NEmpty v-else description="帖子已删除"/></div></NSpin>
 </div>
</template>
<style scoped>
.post-phone-preview{display:flex;flex-direction:column;align-items:center;width:100%;}
.post-phone-frame{width:390px;max-width:calc(100vw - 56px);height:calc(100vh - 150px);min-height:360px;border:1px solid #ddd;border-radius:22px;overflow:hidden;background:#fff;box-shadow:0 8px 32px #0001;}
iframe{display:block;border:0;width:100%;height:100%;}
</style>

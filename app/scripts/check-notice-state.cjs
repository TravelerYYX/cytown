const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript')
const compiled=ts.transpileModule(fs.readFileSync(path.join(__dirname,'../utils/notice-state.uts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText
const flush=()=>new Promise(resolve=>setImmediate(resolve))
function setup(){
 let user='1',generation=1,pushListener=null,requests=[],summary={unreadCount:3,categories:[]},deferred=null,bindDeferred=null,boundOwner=''
 const watchers=[],storage=new Map(),navigation=[],timers=[],pages=[{route:'pages/index/index',options:{}}],exports={}
 const auth={authRevision:{value:0},getToken:()=>user?'token-'+user:null,getUserInfo:()=>user?{userId:user}:null,isLoggedIn:()=>!!user,getSessionGeneration:()=>generation}
 vm.runInNewContext(compiled,{exports,require:name=>{
  if(name==='vue')return{ref:value=>({value}),watch:(_source,fn)=>watchers.push(fn)}
  if(name==='@/utils/auth')return auth
  if(name==='@/config/env')return{API_BASE_URL:'https://test.invalid'}
  if(name==='@/utils/chat-state')return{receiveChatPush:()=>false}
  if(name==='@/utils/request')return{GET:async url=>{requests.push({url});if(url.endsWith('/summary')){if(deferred){const result=deferred;deferred=null;return result}return summary}if(url.endsWith('/push-config'))return{enabled:true};return{id:10,categoryId:2,publicationId:20,categoryName:'分类'}},POST:async(url,body)=>{const requester=user;requests.push({url,body});if(url.endsWith('/device/bind')){if(bindDeferred){const wait=bindDeferred;bindDeferred=null;await wait}boundOwner=requester;if(requester!==user)throw Error('登录状态已改变')}}}
  throw Error(name)
 },setTimeout:fn=>{timers.push(fn);return timers.length},setInterval:()=>1,clearInterval:()=>{},getCurrentPages:()=>pages,
 uni:{getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,value),removeStorageSync:key=>storage.delete(key),request:opts=>requests.push({url:opts.url,body:opts.data}),onPushMessage:fn=>{pushListener=fn},getPushChannelManager:()=>({setPushChannel(){}}),getPushClientId:opts=>opts.success({cid:'device1'}),navigateTo:opts=>{navigation.push(opts.url);pages.push({route:opts.url.split('?')[0].slice(1),options:{id:'10'}})},openAppAuthorizeSetting(){}},console})
 return{api:exports,requests,navigation,pages,timers,storage,changeUser:value=>{user=value;generation++;watchers.forEach(fn=>fn())},push:event=>pushListener(event),setSummary:value=>{summary=value},deferSummary:promise=>{deferred=promise},deferBind:promise=>{bindDeferred=promise},boundOwner:()=>boundOwner}
}
test('switching account clears badges and a stale summary cannot restore old user data',async()=>{
 const t=setup();t.api.launchNotices();await t.api.refreshNotices();assert.equal(t.api.noticeSummary.value.unreadCount,3)
 let resolve;t.deferSummary(new Promise(done=>{resolve=done}));const flight=t.api.refreshNotices();t.changeUser('2');assert.equal(t.api.noticeSummary.value.unreadCount,0)
 resolve({unreadCount:99,categories:[]});await flight;assert.equal(t.api.noticeSummary.value.unreadCount,0)
 t.setSummary({unreadCount:4,categories:[]});await t.api.refreshNotices();assert.equal(t.api.noticeSummary.value.unreadCount,4)
})
test('push clicks validate backend detail, defer login and avoid duplicate detail navigation',async()=>{
 const t=setup();t.api.launchNotices();t.api.showNotices();await flush();await flush()
 t.push({type:'click',data:{payload:{type:'notice',noticeId:10,categoryId:2}}});await flush();await flush()
 assert.equal(t.navigation[0],'/pages/notice/detail?id=10&fromPush=1')
 t.push({type:'click',data:{payload:JSON.stringify({type:'notice',noticeId:10,categoryId:2})}});await flush();assert.equal(t.navigation.length,1)
 t.changeUser('');t.pages.splice(0,t.pages.length,{route:'pages/index/index',options:{}})
 t.push({type:'click',data:{payload:{type:'notice',noticeId:10,categoryId:2}}});await flush();assert.equal(t.navigation.at(-1),'/pages/login/index')
 assert(t.storage.has('ct.notice.pending.v1'));t.api.hideNotices()
})
test('marking a category sends the list snapshot while detail sends its publication id',async()=>{
 const t=setup();await t.api.readNoticeCategory(2,[20,21]);await t.api.readNoticeOne({id:10,publicationId:20})
 assert.equal(JSON.stringify(t.requests.find(r=>r.url.endsWith('/read/category')).body),JSON.stringify({categoryId:2,publicationIds:[20,21]}))
 assert.equal(JSON.stringify(t.requests.find(r=>r.url.endsWith('/read/one')).body),JSON.stringify({id:10,publicationId:20}))
})

test('a late device binding response cannot overwrite the newly logged in owner',async()=>{
 const t=setup();let resolve;t.deferBind(new Promise(done=>{resolve=done}));t.api.launchNotices();t.api.showNotices();await flush();await flush();t.changeUser('2');await flush();resolve();await flush();await flush();assert.equal(t.boundOwner(),'2');t.api.hideNotices()
})

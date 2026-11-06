const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript')
const {reactive,ref,watch,nextTick}=require('vue')
const compiled=ts.transpileModule(fs.readFileSync(path.join(__dirname,'../utils/publish-tasks.uts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText
const flush=async()=>{for(let i=0;i<15;i++)await Promise.resolve();await nextTick()}
function setup(){
 let user='1',generation=1,now=10000,sequence=0,saveFailure=false,submitFailure=false,saveGate=null,deleteFailure=false,deleteGate=null
 const uploads=[],saved=[],submitted=[],deleted=[],timers=new Map(),intervals=new Map(),exports={}
 const auth={authRevision:ref(0),getUserInfo:()=>user?{userId:user}:null,isLoggedIn:()=>!!user,getSessionGeneration:()=>generation}
 class Clock extends Date {static now(){return now}}
 vm.runInNewContext(compiled,{exports,require:name=>{
  if(name==='vue')return{reactive,ref,watch}
  if(name==='./auth')return auth
  if(name==='./publish-api')return{
   deleteDraft:async id=>{deleted.push(id);if(deleteGate)await deleteGate;if(deleteFailure)throw Error('删除失败')},
   saveDraft:async data=>{saved.push(data);if(saveGate)await saveGate;if(saveFailure)throw Error('草稿保存失败');return{id:data.id||41,revision:(data.revision||0)+1}},
   submitPost:async data=>{submitted.push(data);if(submitFailure)throw Error('发布失败');return{id:String(500+submitted.length),status:0}}
  }
  if(name==='./request')return{upload:(url,file,percent,bytes,control)=>new Promise((resolve,reject)=>{
   const job={url,file,bytes,control,resolve,reject};uploads.push(job);control.abort=()=>{control.cancelled=true;reject(Error('cancelled'))}
  })}
  throw Error(name)
 },Date:Clock,setTimeout:fn=>{const id=++sequence;timers.set(id,fn);return id},clearTimeout:id=>timers.delete(id),setInterval:fn=>{const id=++sequence;intervals.set(id,fn);return id},clearInterval:id=>intervals.delete(id),console})
 const asset=(id,type='image')=>({id,mediaType:type,url:'/media/'+id,coverUrl:'',status:type==='video'?'processing':'ready',errorMessage:'',sizeBytes:1024,width:100,height:100,durationMs:0,coverTimeMs:0,cropJson:''})
 return{api:exports,uploads,saved,submitted,deleted,asset,advance(ms){now+=ms;for(const fn of intervals.values())fn()},runTimers(){const list=[...timers.values()];timers.clear();for(const fn of list)fn()},saveFailure(v){saveFailure=v},submitFailure(v){submitFailure=v},setSaveGate(v){saveGate=v},deleteFailure(v){deleteFailure=v},setDeleteGate(v){deleteGate=v},async changeUser(value){user=value;generation++;auth.authRevision.value++;await flush()}}
}
test('serial uploads survive leaving the editor, aggregate bytes and submit one snapshot with mapped rich references',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.mode='post';s.data.title='混合媒体';s.data.postText='正文';s.data.channelId=9
 t.api.addPublishMedia(s,[{path:'/first',size:1024}],'image',true)
 t.api.addPublishMedia(s,[{path:'/second',size:3072}],'video',true)
 assert.equal(t.uploads.length,1);assert.equal(s.data.assets.length,2)
 t.advance(100);t.uploads[0].bytes({sent:512,total:1024});t.advance(250)
 let stats=t.api.publishUploadStats(s);assert.equal(stats.progress,12.5);assert.equal(stats.speed,'0.5 KB/S')
 t.api.retainPublishEditor(s);assert.equal(t.api.getPublishEditor(),s,'reopening restores editor')
 assert.equal(t.api.enqueuePublish(s),true);assert.equal(t.api.enqueuePublish(s),false)
 const fresh=t.api.getPublishEditor();fresh.data.title='下一篇';assert.notEqual(fresh.key,s.key)
 t.uploads[0].resolve(t.asset(101));await flush();assert.equal(t.uploads.length,2);assert.equal(t.submitted.length,0)
 t.uploads[1].bytes({sent:3072,total:3072});t.advance(250);stats=t.api.publishUploadStats(s);assert.equal(stats.progress,100);assert.equal(stats.label,'处理中')
 t.advance(1100);assert.equal(t.api.publishUploadStats(s).speed,'0.0 KB/S')
 t.uploads[1].resolve(t.asset(102,'video'));await flush()
 assert.equal(t.submitted.length,1);assert.deepEqual(Array.from(t.submitted[0].assetIds),[101,102]);assert.equal(t.submitted[0].blocks[1].assetId,101);assert.equal(t.submitted[0].blocks[2].assetId,102)
 assert.equal(t.submitted[0].title,'混合媒体');assert.equal(s.phase,'complete');assert.equal(s.postStatus,0)
 assert.equal(t.api.minePublishTasks().length,1);t.api.acknowledgePublishPosts([s.postId]);assert.equal(t.api.minePublishTasks().length,0)
})
test('upload failure retains the card and retry reuses completed files',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.channelId=9
 t.api.addPublishMedia(s,[{path:'/a',size:1024},{path:'/b',size:1024}],'image',false);t.api.enqueuePublish(s)
 t.uploads[0].resolve(t.asset(1));await flush();t.uploads[1].reject(Error('断网'));await flush()
 assert.equal(s.phase,'failed');assert.equal(t.submitted.length,0)
 t.api.retryPublish(s);assert.equal(t.uploads.length,3);assert.equal(t.uploads[2].file,'/b')
 t.uploads[2].resolve(t.asset(2));await flush();assert.equal(t.submitted.length,1);assert.deepEqual(Array.from(t.submitted[0].assetIds),[1,2])
})
test('submission retry never reuploads media and concurrent retries do not duplicate requests',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.channelId=9;t.submitFailure(true)
 t.api.addPublishMedia(s,[{path:'/a',size:1024}],'image',false);t.api.enqueuePublish(s);t.uploads[0].resolve(t.asset(3));await flush()
 assert.equal(s.phase,'failed');t.submitFailure(false);t.api.retryPublish(s);t.api.retryPublish(s);await flush()
 assert.equal(t.uploads.length,1);assert.equal(t.submitted.length,2);assert.equal(s.phase,'complete')
})
test('removing active and queued media cancels upload and removes rich blocks',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.mode='post'
 t.api.addPublishMedia(s,[{path:'/a',size:10},{path:'/b',size:10},{path:'/c',size:10}],'image',true)
 t.api.removePublishMedia(s,s.data.assets[1].id);t.api.removePublishMedia(s,s.data.assets[0].id);await flush()
 assert(t.uploads[0].control.cancelled);assert.equal(t.uploads.length,2);assert.equal(t.uploads[1].file,'/c');assert.equal(s.data.postBlocks.length,1)
 t.uploads[1].resolve(t.asset(4));await flush();assert.equal(s.data.assets.length,1)
})
test('account switch aborts old jobs, suppresses stale callbacks and isolates tasks',async()=>{
 const t=setup(),s=t.api.getPublishEditor();t.api.addPublishMedia(s,[{path:'/old',size:100},{path:'/queued',size:100}],'image',false);t.api.enqueuePublish(s)
 await t.changeUser('2');assert(t.uploads[0].control.cancelled);t.uploads[0].resolve(t.asset(5));await flush()
 assert.equal(t.uploads.length,1);assert.equal(t.submitted.length,0);assert.equal(t.api.minePublishTasks().length,0);assert.equal(t.api.getPublishEditor().owner,'2')
})
test('draft save failure does not lose editor; pending save completes before submit',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.mode='post';s.data.title='草稿';s.data.postText='正文';t.saveFailure(true)
 t.api.retainPublishEditor(s);t.runTimers();await flush();assert.equal(t.api.getPublishEditor(),s)
 t.saveFailure(false);let release;t.setSaveGate(new Promise(resolve=>{release=resolve}));t.api.enqueuePublish(s);await flush();assert.equal(t.submitted.length,0)
 release();await flush();assert.equal(t.submitted.length,1);assert.equal(s.phase,'complete')
})
test('reopening a different draft isolates its media and restores the prior live session',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.title='first';s.draftId=11;t.api.addPublishMedia(s,[{path:'/a',size:100}],'image',false)
 const other=t.api.getPublishEditor(22);other.draftId=22;other.data.title='second';assert.notEqual(other.key,s.key)
 t.uploads[0].resolve(t.asset(6));await flush();assert.equal(other.data.assets.length,0)
 assert.equal(t.api.getPublishEditor(),other,'background completion cannot replace the active editor')
 assert.equal(t.api.getPublishEditor(11),s)
})
test('progress keeps decimal precision, switches speed units and resumes draft-local IDs without collisions',async()=>{
 const t=setup(),s=t.api.getPublishEditor()
 s.data.assets.push({...t.asset(-1),serverId:20},{...t.asset(-2),serverId:undefined,localPath:'/restored',status:'uploading'})
 t.api.restorePublishMedia(s);assert.equal(t.uploads.length,1);assert.equal(t.uploads[0].file,'/restored')
 t.api.addPublishMedia(s,[{path:'/new',size:1024}],'image',false);assert.equal(new Set(s.data.assets.map(a=>a.id)).size,3)
 t.uploads[0].bytes({sent:2097152,total:2097153});t.advance(250)
 assert.equal(t.api.publishUploadStats(s).speed,'2.0 MB/S');assert(t.api.publishUploadStats(s).progress<100)
 t.uploads[0].resolve(t.asset(21));await flush();t.uploads[1].resolve(t.asset(22));await flush()
 assert.equal(s.data.assets[0].serverId,20);assert.equal(s.data.assets[1].serverId,21);assert.equal(s.data.assets[2].serverId,22)
})
test('late completion of unused media cannot automatically retry a failed submission',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.title='失败任务';t.submitFailure(true)
 t.api.addPublishMedia(s,[{path:'/selected',size:10}],'image',false)
 t.api.addPublishMedia(s,[{path:'/unused',size:10}],'video',false)
 t.api.enqueuePublish(s);t.uploads[0].resolve(t.asset(1));await flush()
 assert.equal(t.submitted.length,1);assert.equal(s.phase,'failed')
 t.uploads[1].resolve(t.asset(2,'video'));await flush()
 assert.equal(t.submitted.length,1,'failure must wait for an explicit retry')
 t.submitFailure(false);t.api.retryPublish(s);await flush();assert.equal(s.phase,'complete')
 t.api.retryPublish(s);await flush();assert.equal(s.phase,'complete');assert.equal(t.submitted.length,2)
})
test('deleting a failed submission removes its server draft and prevents stale retries',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.title='失败任务';t.submitFailure(true)
 t.api.enqueuePublish(s);await flush();assert.equal(s.phase,'failed')
 assert.equal(await t.api.deletePublishTask(s),true);assert.deepEqual(t.deleted,[41]);assert.equal(t.api.minePublishTasks().length,0)
 t.api.retryPublish(s);await t.api.savePublishEditor(s);t.api.restorePublishMedia(s);await flush()
 assert.equal(t.submitted.length,1);assert.equal(t.saved.length,1);assert.notEqual(t.api.getPublishEditor(),s)
})
test('deleting an upload failure aborts other uploads, suppresses callbacks and advances the queue',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.title='上传任务'
 t.api.addPublishMedia(s,[{path:'/failed',size:10},{path:'/cancel',size:10}],'image',false);t.api.enqueuePublish(s)
 t.uploads[0].reject(Error('断网'));await flush();assert.equal(s.phase,'failed');assert.equal(t.uploads.length,2)
 const other=t.api.getPublishEditor();t.api.addPublishMedia(other,[{path:'/other',size:10}],'image',false)
 assert.equal(await t.api.deletePublishTask(s),true);await flush()
 assert(t.uploads[1].control.cancelled);assert.equal(t.uploads.length,3);assert.equal(t.uploads[2].file,'/other')
 t.uploads[1].resolve(t.asset(20));await flush();assert.equal(t.submitted.length,0);assert.equal(s.phase,'deleted')
})
test('deletion waits for an in-flight save and blocks concurrent deletion and retry',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.title='保存中'
 let releaseSave,releaseDelete;t.setSaveGate(new Promise(resolve=>{releaseSave=resolve}));t.setDeleteGate(new Promise(resolve=>{releaseDelete=resolve}))
 t.api.addPublishMedia(s,[{path:'/failed',size:10}],'image',false);t.runTimers();t.api.enqueuePublish(s)
 t.uploads[0].reject(Error('断网'));await flush();assert.equal(s.phase,'failed')
 const removing=t.api.deletePublishTask(s);await flush();assert.equal(t.deleted.length,0)
 assert.equal(await t.api.deletePublishTask(s),false);t.api.retryPublish(s);assert.equal(t.uploads.length,1)
 releaseSave();await flush();assert.deepEqual(t.deleted,[41]);assert.equal(t.api.minePublishTasks().length,1)
 releaseDelete();assert.equal(await removing,true);assert.equal(t.api.minePublishTasks().length,0);assert.equal(t.submitted.length,0)
})
test('failed server deletion keeps the card and draft available for another attempt',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.title='保留任务';t.submitFailure(true);t.api.enqueuePublish(s);await flush()
 t.deleteFailure(true);await assert.rejects(t.api.deletePublishTask(s),/删除失败/)
 assert.equal(t.api.minePublishTasks()[0],s);assert.equal(s.phase,'failed');assert.equal(s.deleting,false);assert.equal(s.draftId,41)
 t.deleteFailure(false);assert.equal(await t.api.deletePublishTask(s),true)
})
test('completed, active and foreign-account tasks cannot be deleted through the failed-task action',async()=>{
 const t=setup(),s=t.api.getPublishEditor();s.data.title='任务'
 assert.equal(await t.api.deletePublishTask(s),false);t.api.enqueuePublish(s)
 assert.equal(await t.api.deletePublishTask(s),false);await flush();assert.equal(s.phase,'complete')
 assert.equal(await t.api.deletePublishTask(s),false);await t.changeUser('2');assert.equal(await t.api.deletePublishTask(s),false);assert.equal(t.deleted.length,0)
})

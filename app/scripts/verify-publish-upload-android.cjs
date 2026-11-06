// Real native upload transport against a throttled local fixture server; no business data is written.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),http=require('node:http'),{execFileSync}=require('node:child_process')
const {evaluate}=require('../../artifacts/android-video-cover-return-20261024/inspect.cjs')
const adb='D:/Editor/HBuilderX/plugins/launcher-tools/tools/adbs/adb.exe',serial=process.env.CT_ANDROID_DEVICE_SERIAL||'127.0.0.1:16384'
const run=args=>execFileSync(adb,['-s',serial,...args],{windowsHide:true,maxBuffer:24*1024*1024}),pause=ms=>new Promise(r=>setTimeout(r,ms))
const output=path.resolve(__dirname,'../../artifacts/publish-upload-android-20261027'),port=18081,title='UPLOAD_NATIVE_VERIFICATION'
const jpeg=fs.readFileSync(path.resolve(__dirname,'../static/images/performance/profile-background.jpg')),fixture=Buffer.concat([jpeg,Buffer.alloc(12*1024*1024)])
let uploaded=0,uploadEnded=false,submitCount=0,saveCount=0,owner='',nickname='',fixtureUser=null,closed=false
const response=(res,data)=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({code:'0000',msg:'ok',data}))}
const server=http.createServer((req,res)=>{
 req.url=req.url.split('?')[0]
 if(req.url==='/fixture'){res.writeHead(200,{'Content-Type':'image/jpeg','Content-Length':fixture.length});res.end(fixture);return}
 if(req.url==='/upload'){
  req.on('data',chunk=>{uploaded+=chunk.length;req.pause();setTimeout(()=>req.resume(),80)})
  req.on('end',()=>{uploadEnded=true;response(res,{id:900001,mediaType:'image',url:'http://127.0.0.1:'+port+'/fixture',coverUrl:'',status:'ready',errorMessage:'',sizeBytes:fixture.length,width:600,height:600,durationMs:0,coverTimeMs:0,cropJson:''})});return
 }
 let body='';req.on('data',chunk=>{body+=chunk});req.on('end',()=>{
  if(req.url==='/save'){saveCount++;response(res,{id:900001,revision:saveCount})}
  else if(req.url==='/submit'){assert(uploadEnded);submitCount++;response(res,{id:'900001',status:0,message:'已提交审核'})}
  else if(req.url==='/mine')response(res,{records:submitCount?[{id:'900001',status:0,authorId:owner,authorNickname:nickname,authorAvatar:'',authorLevel:1,title,content:'Native upload verification',date:'10月27日',createTime:'2026-10-27 12:00:00',media:[],tags:[],mode:'image',likeCount:0,commentCount:0}]:[],total:submitCount?1:0,pages:submitCount?1:0,current:1})
  else if(req.url==='/app/publish/config')response(res,{rules:[{mode:'image',titleMin:0,titleMax:30,contentMin:0,contentMax:2000,imageMax:9,videoMax:0,totalMediaMax:9,maxVideoMb:0}],channels:[{id:9,name:'Upload verification',isDefault:true}],defaultChannelId:9,creatorPolicies:{allowDownload:{defaultValue:true,userEditable:true},downloadWatermark:{defaultValue:false,userEditable:true},original:{defaultValue:false,userEditable:true}},reviewRequired:true,featureFlags:{},pollRule:{minOptions:2,maxOptions:10},topics:[],emojiGroups:[],emojis:[],colors:[],dividers:[]})
  else if(req.url==='/app/user/me')response(res,fixtureUser)
  else if(req.url==='/app/home/config')response(res,{channels:[{id:9,name:'推荐'}],entries:[],news:{enabled:false,items:[],interval:3,emptyText:''}})
  else if(req.url==='/app/notice/summary')response(res,{unreadCount:0,categories:[]})
  else if(req.url==='/app/level/config')response(res,{levels:[]})
  else if(req.url==='/app/level/me')response(res,{level:fixtureUser.level,experience:0})
  else if(req.url==='/app/media/config')response(res,{cacheMaxBytes:10000000})
  else if(req.url.includes('/post/'))response(res,{records:[],total:0,pages:0,current:1})
  else response(res,[])
 })
})
async function inspect(body){for(let attempt=0;attempt<20;attempt++){try{return await evaluate(body)}catch(error){if(attempt===19||!String(error).includes('fetch failed'))throw error;await pause(500)}}}
async function state(){return inspect(`const nodes=[];function visit(e){const id=e.getAttribute('id')||'',r=e.getBoundingClientRect(),text=e.getAttribute('value')||'';if(id||e.tagName==='TEXT')nodes.push({id,tag:e.tagName,text,x:r.x,y:r.y,width:r.width,height:r.height});for(const c of e.children)visit(c)}visit(getCurrentPages().at(-1).$vm.$el);resolve({route:getCurrentPages().at(-1).route,width:uni.getWindowInfo().windowWidth,nodes})`)}
async function tap(cls){const s=await state(),n=s.nodes.find(n=>cls==='add-media'?/-g1001$/.test(n.id):cls==='publish-button'?/-submit$/.test(n.id):/^ct-semantics-/.test(n.id));assert(n,'Missing native '+cls);const scale=900/s.width;run(['shell','input','tap',String(Math.round((n.x+n.width/2)*scale)),String(Math.round((n.y+n.height/2)*scale))])}
async function capture(name){const s=await state();await pause(250);fs.writeFileSync(path.join(output,name+'.png'),run(['exec-out','screencap','-p']));fs.writeFileSync(path.join(output,name+'.json'),JSON.stringify(s,null,2));return s}
;(async()=>{
 fs.mkdirSync(output,{recursive:true});for(let attempt=0;attempt<30;attempt++){try{await evaluate('resolve(getCurrentPages().length>0)');break}catch(e){if(attempt===29)throw e;await pause(500)}}await new Promise(resolve=>server.listen(port,'127.0.0.1',resolve));run(['reverse','tcp:'+port,'tcp:'+port])
 try{
  const info=await evaluate(`resolve({owner:uni.getStorageSync('app_user_info')?.userId,nickname:uni.getStorageSync('app_user_info')?.nickname,user:uni.getStorageSync('app_user_info'),logged:!!uni.getStorageSync('app_token')})`);assert(info.logged);owner=info.owner;nickname=info.nickname;fixtureUser=info.user
  await evaluate(`globalThis.__ctUploadVerify={request:uni.request,upload:uni.uploadFile,choose:uni.chooseImage,path:uni.env.USER_DATA_PATH+'/ct-upload-verification.jpg'};const t=globalThis.__ctUploadVerify;uni.request=options=>{const url=options.url;let target='';if(url.includes('/app/publish/drafts/save'))target='save';else if(url.includes('/app/publish/submit'))target='submit';else if(url.includes('/app/post/mine'))target='mine';if(!target&&url.includes('/app/'))target=url.slice(url.indexOf('/app/')).split('?')[0].slice(1);return t.request(target?{...options,url:'http://127.0.0.1:${port}/'+target}:options)};uni.uploadFile=options=>t.upload(options.url.includes('/app/publish/assets/')?{...options,url:'http://127.0.0.1:${port}/upload'}:options);uni.chooseImage=options=>{options.success({tempFilePaths:[t.path],tempFiles:[{path:t.path,size:${fixture.length}}]})};uni.downloadFile({url:'http://127.0.0.1:${port}/fixture',filePath:t.path,success:r=>resolve({status:r.statusCode}),fail:e=>resolve({error:e.errMsg})})`)
  await evaluate(`uni.navigateTo({url:'/pages/publish/index',success:()=>resolve(true)})`);await pause(1200);await tap('add-media');await pause(900)
  const before=await capture('publish-progress');assert(before.nodes.some(n=>/^ct-upload-ring-/.test(n.id)),'native ring visible')
  const number=before.nodes.find(n=>/^ct-upload-ring-.*-number$/.test(n.id));assert(number&&/^\d+\.\d$/.test(number.text),'one decimal without percent')
  await tap('close-button');await pause(500);const afterBack=uploaded;await pause(800);assert(uploaded>afterBack,'real bytes continue after leaving publishing page')
  await evaluate(`uni.navigateTo({url:'/pages/publish/index',success:()=>resolve(true)})`);await pause(700);const restored=await state();assert(restored.nodes.some(n=>/^ct-upload-ring-/.test(n.id)))
  await tap('publish-button');await pause(1300);let mine=await capture('mine-progress');assert.equal(mine.route,'pages/index/index');assert(mine.nodes.some(n=>n.id.startsWith('ct-pending-post-')),'native pending card visible');assert.equal(submitCount,0)
  const deadline=Date.now()+45000;while(!submitCount&&Date.now()<deadline)await pause(500)
  assert.equal(submitCount,1);const syncedBy=Date.now()+10000;while(Date.now()<syncedBy){const current=await state();if(!current.nodes.some(n=>n.id.startsWith('ct-pending-post-')))break;await pause(300)}const done=await capture('mine-complete');assert(!done.nodes.some(n=>n.id.startsWith('ct-pending-post-')));assert(done.nodes.some(n=>n.text===title))
  fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({status:'passed',scope:'Android native Vapor runtime; real upload to throttled local server, fixture draft/submit/mine APIs, no business database writes',uploadedBytes:uploaded,fixtureBytes:fixture.length,submitCount,saveCount,progressText:number.text,backgroundBytesContinued:true,pendingCardReplaced:true},null,2));console.log('PASS native upload, back/restore, queued publish, pending card and completion; bytes='+uploaded)
 }finally{
  await evaluate(`const t=globalThis.__ctUploadVerify;if(t){uni.request=t.request;uni.uploadFile=t.upload;uni.chooseImage=t.choose;uni.getFileSystemManager().unlink({filePath:t.path});delete globalThis.__ctUploadVerify}uni.reLaunch({url:'/pages/index/index',success:()=>resolve(true)})`).catch(()=>{})
  run(['reverse','--remove','tcp:'+port]);closed=true;server.close();server.closeAllConnections()
 }
})().catch(e=>{console.error(e);process.exitCode=1;if(!closed){server.close();server.closeAllConnections()}})

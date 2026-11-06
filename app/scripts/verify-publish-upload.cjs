const {chromium}=require('playwright-core'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path')
const base=process.env.UI_TEST_URL||'http://localhost:15273/',output=path.resolve(__dirname,'../artifacts/publish-upload')
const user={userId:'10',nickname:'上传验收',avatar:'',level:1,coinBalance:0,tags:[]}
const config={rules:[{mode:'image',titleMin:0,titleMax:30,contentMin:0,contentMax:2000,imageMax:9,videoMax:0,totalMediaMax:9,maxVideoMb:0}],channels:[{id:9,name:'交流区',isDefault:true}],defaultChannelId:9,creatorPolicies:{allowDownload:{defaultValue:true,userEditable:true},downloadWatermark:{defaultValue:false,userEditable:true},original:{defaultValue:false,userEditable:true}},reviewRequired:true,featureFlags:{},pollRule:{minOptions:2,maxOptions:10},topics:[],emojiGroups:[],emojis:[],colors:[],dividers:[]}
async function scenario(browser,width){
 const context=await browser.newContext({viewport:{width,height:900},isMobile:true,hasTouch:true}),page=await context.newPage(),errors=[]
 page.on('pageerror',e=>errors.push(e.message));let releaseUpload,started=false,submitted=[],draft=0,mine=[],saveCount=0
 await page.addInitScript(u=>{localStorage.setItem('app_token',JSON.stringify({type:'string',data:'upload-test'}));localStorage.setItem('app_user_info',JSON.stringify({type:'object',data:u}))},user)
 await page.route('**/*',async route=>{
  const req=route.request(),p=new URL(req.url()).pathname
  if(!p.startsWith('/app/')||!['xhr','fetch'].includes(req.resourceType()))return route.continue()
  let data=[]
  if(p==='/app/publish/config')data=config
  else if(p==='/app/publish/assets/image'){
   started=true;await new Promise(resolve=>{releaseUpload=resolve});data={id:101,mediaType:'image',url:'/static/images/performance/profile-background.jpg',previewUrl:'/static/images/performance/profile-background.jpg',coverUrl:'',status:'ready',errorMessage:'',sizeBytes:1000,width:600,height:600,durationMs:0,coverTimeMs:0,cropJson:''}
  }else if(p==='/app/publish/drafts/save'){const body=req.postDataJSON();saveCount++;data={id:++draft,revision:saveCount,payload:body.payload}}
  else if(p==='/app/publish/submit'){
   submitted.push(req.postDataJSON());data={id:'501',status:0,message:'已提交审核'}
   mine=[{id:'501',status:0,authorId:user.userId,authorNickname:user.nickname,authorAvatar:'',authorLevel:1,title:submitted[0].title,content:'正文验收',date:'10月27日',createTime:'2026-10-27 12:00:00',media:[],tags:[],mode:'image',likeCount:0,commentCount:0}]
  }else if(p==='/app/user/me')data=user
  else if(p==='/app/home/config')data={channels:[{id:9,name:'推荐'}],entries:[],news:{enabled:false,items:[],interval:3,emptyText:''}}
  else if(p==='/app/post/mine')data={records:mine,total:mine.length,pages:mine.length?1:0,current:1}
  else if(p.includes('/post/'))data={records:[],total:0,pages:0,current:1}
  else if(p==='/app/notice/summary')data={unreadCount:0,categories:[]}
  else if(p==='/app/user/statistics')data={}
  else if(p==='/app/media/config')data={cacheMaxBytes:10000000}
  return route.fulfill({json:{code:'0000',msg:'ok',data}})
 })
 try{
  await page.goto(base+'#/pages/index/index?tab=mine');await page.locator('.mine-view').waitFor()
  await page.locator('.tab-bar .tab-item').nth(2).click();await page.locator('.publish-page .options').waitFor()
  await page.locator('.title input').fill('上传验收 '+width);await page.locator('.content textarea').fill('正文验收')
  const chooser=page.waitForEvent('filechooser');await page.locator('.add-media').click();await(await chooser).setFiles(path.resolve(__dirname,'../static/images/performance/profile-background.jpg'))
  await page.locator('.filled-media').waitFor();await page.locator('.header .upload-progress').waitFor()
  await page.waitForFunction(()=>/^\d+\.\d$/.test(document.querySelector('.upload-ring-number')?.textContent||''))
  assert(!await page.locator('.publish-button').getAttribute('disabled'),'button enabled during upload')
  assert.equal(await page.locator('.uploading').count(),0)
  const before=saveCount;await page.waitForTimeout(1250);assert(saveCount-before<=1,'byte progress must not keep saving drafts')
  const header=await page.locator('.header').boundingBox(),close=await page.locator('.close-button').boundingBox(),ring=await page.locator('.upload-ring').boundingBox()
  assert(ring.x>=close.x+close.width-1);assert(ring.y>=header.y&&ring.y+ring.height<=header.y+header.height+1)
  await page.screenshot({path:path.join(output,'publish-'+width+'.png')})
  await page.locator('.close-button').click();await page.locator('.mine-view').waitFor();assert.equal(await page.locator('.pending-post-card').count(),0,'unsubmitted edit stays private to editor')
  await page.locator('.tab-bar .tab-item').nth(2).click();await page.locator('.publish-page').waitFor();assert.equal(await page.locator('.title input').inputValue(),'上传验收 '+width);assert.equal(await page.locator('.filled-media').count(),1)
  await page.locator('.publish-button').click();await page.locator('.pending-post-card').waitFor();assert.equal(submitted.length,0,'submit waits for upload response')
  assert((await page.locator('.pending-post-card').innerText()).includes('上传验收 '+width));assert.equal(await page.locator('.pending-post-card .upload-ring').count(),1)
  await page.screenshot({path:path.join(output,'mine-'+width+'.png')})
  assert(started);releaseUpload();await page.waitForFunction(()=>document.querySelector('.mine-reference-title')?.textContent?.includes('上传验收'))
  assert.equal(submitted.length,1);assert.deepEqual(submitted[0].assetIds,[101]);assert.equal(await page.locator('.pending-post-card').count(),0)
  assert.deepEqual(errors,[]);return{width,restored:true,backgroundUpload:true,pendingCard:true,submittedOnce:true,errors}
 }finally{if(releaseUpload)releaseUpload();await context.close()}
}
async function failedScenario(browser,width){
 const context=await browser.newContext({viewport:{width,height:900},isMobile:true,hasTouch:true}),page=await context.newPage(),errors=[]
 let uploadCount=0,submitCount=0,deleteCount=0,deleteFails=true,nextDraft=40
 page.on('pageerror',e=>errors.push(e.message))
 await page.addInitScript(u=>{localStorage.setItem('app_token',JSON.stringify({type:'string',data:'failed-publish-test'}));localStorage.setItem('app_user_info',JSON.stringify({type:'object',data:u}))},user)
 await page.route('**/app/**',async route=>{
  const req=route.request(),p=new URL(req.url()).pathname
  if(!['xhr','fetch'].includes(req.resourceType()))return route.continue()
  let data=[]
  if(p==='/app/publish/config')data=config
  else if(p==='/app/publish/assets/image'){
   uploadCount++;data={id:100+uploadCount,mediaType:'image',url:'/static/images/performance/profile-background.jpg',coverUrl:'',status:'ready',errorMessage:'',sizeBytes:1000,width:600,height:600}
  }else if(p==='/app/publish/drafts/save'){
   const body=req.postDataJSON();data={id:body.id||++nextDraft,revision:(body.revision||0)+1,payload:body.payload}
  }else if(p==='/app/publish/submit'){
   submitCount++;return route.fulfill({json:{code:'1000',msg:'发布过于频繁，10分钟内最多发布10条，请稍后再试',data:null}})
  }else if(p==='/app/publish/drafts/delete'){
   deleteCount++;assert(req.postDataJSON().id>0)
   if(deleteFails)return route.fulfill({json:{code:'1000',msg:'删除失败，请重试',data:null}})
  }else if(p==='/app/user/me')data=user
  else if(p==='/app/home/config')data={channels:[{id:9,name:'推荐'}],entries:[],news:{enabled:false,items:[],interval:3,emptyText:''}}
  else if(p.includes('/post/'))data={records:[],total:0,pages:0,current:1}
  else if(p==='/app/notice/summary')data={unreadCount:0,categories:[]}
  else if(p==='/app/user/statistics')data={}
  else if(p==='/app/media/config')data={cacheMaxBytes:10000000}
  await route.fulfill({json:{code:'0000',msg:'ok',data}})
 })
 try{
  await page.goto(base+'#/pages/index/index?tab=mine');await page.locator('.mine-view').waitFor()
  await page.locator('.tab-bar .tab-item').nth(2).click();await page.locator('.publish-page .options').waitFor()
  await page.locator('.title input').fill('失败删除验收 '+width)
  const chooser=page.waitForEvent('filechooser');await page.locator('.add-media').click();await(await chooser).setFiles(path.resolve(__dirname,'../static/images/performance/profile-background.jpg'))
  await page.locator('.filled-media').waitFor();await page.locator('.publish-button').click()
  const card=page.locator('.pending-post-card');await card.locator('.pending-delete').waitFor()
  assert((await card.innerText()).includes('发布过于频繁'));assert.equal(submitCount,1)
  await card.locator('.upload-retry').click();await page.waitForFunction(()=>!!document.querySelector('.pending-delete'))
  assert.equal(submitCount,2);assert.equal(uploadCount,1,'retry reuses uploaded media')
  const ring=await card.locator('.upload-ring').boundingBox(),remove=await card.locator('.pending-delete').boundingBox()
  assert(remove.x>=ring.x+ring.width);assert(remove.x+remove.width<=width)
  await page.screenshot({path:path.join(output,'failed-'+width+'.png')})
  await card.locator('.pending-delete').click();await page.getByText('取消',{exact:true}).last().click()
  assert.equal(deleteCount,0);assert.equal(await card.count(),1)
  await card.locator('.pending-delete').click();await page.getByText('删除',{exact:true}).last().click()
  await page.getByText('删除失败，请重试',{exact:true}).waitFor();assert.equal(deleteCount,1);assert.equal(await card.count(),1)
  deleteFails=false;await card.locator('.pending-delete').click();await page.getByText('删除',{exact:true}).last().click()
  await card.waitFor({state:'detached'});assert.equal(deleteCount,2);assert.equal(submitCount,2)
  assert.deepEqual(errors,[]);return{width,failedRetryReusesMedia:true,cancelKeepsCard:true,deleteFailureKeepsCard:true,deleteRemovesCard:true,errors}
 }finally{await context.close()}
}
;(async()=>{fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({headless:true,executablePath:process.env.UI_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});try{const results=[];for(const width of [320,390,430]){results.push(await scenario(browser,width));results.push(await failedScenario(browser,width))}fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({status:'passed',scope:'H5 browser with intercepted upload response and API fixtures',results},null,2));console.log(JSON.stringify(results))}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1})

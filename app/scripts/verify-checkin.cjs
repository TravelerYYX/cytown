const {chromium}=require('playwright-core')
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const root=path.resolve(__dirname,'..'),output=path.join(root,'artifacts/checkin')
const base=process.env.UI_TEST_URL||'http://127.0.0.1:15273'
const adminBase=process.env.ADMIN_UI_TEST_URL||'http://127.0.0.1:9527'
const asset=name=>({url:'/uploads/checkin/'+name+'.webp',originalBytes:340000,processedBytes:13000})
const defaults=()=>({version:1,makeupPrice:10,coinIcon:asset('coin'),experienceIcon:asset('experience'),frames:[{id:'xingxi',name:'星汐 · 星愿',image:asset('frame')}],days:Array.from({length:31},(_,i)=>({day:i+1,coins:10,experience:10,frameId:i===26?'xingxi':'',frameDays:7,primary:i===26?'frame':i%2?'experience':'coins'}))})
const user={userId:'10',publicUid:'100001',qqNumber:'123456789',nickname:'星汐的町友',level:12,avatar:'',avatarFrame:'',signature:'',gender:2,cardBackground:'01.png',tags:[],account:'test',isOfficial:false,coinBalance:50}
async function appChecks(browser){
 const context=await browser.newContext({viewport:{width:390,height:858},isMobile:true,hasTouch:true,deviceScaleFactor:1})
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message))
 await page.addInitScript(u=>{localStorage.setItem('app_token','ui-test');localStorage.setItem('app_user_info',JSON.stringify(u))},user)
 let config=defaults(),month='2026-10',today=23,count=31,balance=50,signCalls=0,makeupCalls=0,allChannelRequested=false
 const signed=new Set(Array.from({length:15},(_,i)=>i+1));const makeups=new Set()
 const state=()=>({today:month+'-'+String(today).padStart(2,'0'),month,daysInMonth:count,signedDays:signed.size,missedDays:Array.from({length:today-1},(_,i)=>i+1).filter(d=>!signed.has(d)).length,balance,makeupPrice:10,signedToday:signed.has(today),config,days:config.days.slice(0,count).map(r=>({day:r.day,reward:r,signed:signed.has(r.day),makeup:makeups.has(r.day),today:r.day===today,canMakeup:r.day<today&&!signed.has(r.day)}))})
 await page.route('**/*',async route=>{
  const req=route.request(),url=new URL(req.url()),p=url.pathname
  if(p.startsWith('/uploads/checkin/'))return route.fulfill({contentType:'image/webp',body:fs.readFileSync(path.join(root,'static/images/checkin',path.basename(p)))})
  if(!['xhr','fetch'].includes(req.resourceType())||!p.startsWith('/app/'))return route.continue()
  let data=[]
  if(p==='/app/checkin/me')data=state()
  else if(p==='/app/user/me')data={...user,coinBalance:balance}
  else if(p==='/app/checkin/frames')data=config.frames
  else if(p==='/app/checkin/frames/owned')data=[]
  else if(p==='/app/user/followers')data={records:[{userId:'99',publicUid:'100099',nickname:'限时头像框测试',avatarFrame:'xingxi',avatarFrameExpiresAt:Date.now()+2500,level:1,following:false,followedBy:true}],total:1,current:1,pages:1}
  else if(p==='/app/checkin/recommendations')data=[{id:'101',title:'星汐的星愿手记：一起迎接全新旅程',excerpt:'好看的内容、有趣的人、温暖的讨论，都在这里等你。',coverUrl:'/uploads/checkin/mascot.webp'},{id:'102',title:'社区创作精选：把星光留在身边',excerpt:'记录今天的小小美好，分享属于你的次元故事。',coverUrl:'/uploads/checkin/experience.webp'},{id:'103',title:'次元町活动：与町友分享灵感',excerpt:'在这里，发现更多热爱的世界！',coverUrl:'/uploads/checkin/frame.webp'}]
  else if(p==='/app/checkin/sign'||p==='/app/checkin/makeup'){
   let day=today;if(p.endsWith('/makeup')){day=Number(req.postDataJSON().date.slice(-2));makeupCalls++;balance-=10;makeups.add(day)}else signCalls++
   signed.add(day);balance+=10;data={alreadySigned:false,gained:10,reward:config.days[day-1],state:state(),progress:{level:12}}
  }else if(p==='/app/checkin/history')data={records:[{signDate:'2026-10-23',makeup:false,rewardJson:JSON.stringify(config.days[22]),claimedAt:'2026-10-23 12:00:00'}],total:1,pages:1,current:1}
  else if(p==='/app/home/config')data={channels:[{id:7,name:'同人',showAllPosts:false},{id:9,name:'发现',showAllPosts:true}],entries:[],news:{enabled:false,items:[],interval:3},defaultFeedLayout:'single'}
  else if(p==='/app/post/list'){if(url.searchParams.get('channelId')==='9')allChannelRequested=true;data={records:[],total:0,pages:0,current:1}}
  else if(p==='/app/media/config')data={cacheMaxBytes:16777216}
  return route.fulfill({json:{code:'0000',msg:'ok',data},headers:{'Access-Control-Allow-Origin':'*'}})
 })
 await page.goto(base);await page.locator('.home-view').waitFor();
 await page.goto(base+'/#/pages/checkin/index');await page.locator('.reward-card').first().waitFor();await page.evaluate(()=>document.fonts.ready)
 assert.equal(await page.locator('.reward-card').count(),8)
 for(const width of [320,390,430]){await page.setViewportSize({width,height:Math.round(width*2.2)});await page.waitForTimeout(120);const grid=await page.locator('.reward-card').evaluateAll(nodes=>nodes.map(n=>{const r=n.getBoundingClientRect();return {x:r.x,y:r.y,right:r.right}}));assert.equal(new Set(grid.map(g=>Math.round(g.y))).size,2);assert.equal(grid.filter(g=>Math.round(g.y)===Math.round(grid[0].y)).length,4);assert(grid.every(g=>g.x>=0&&g.right<=width));await page.screenshot({path:path.join(output,`app-${width}.png`)})}
 await page.setViewportSize({width:390,height:858});await page.getByRole('button',{name:'查看全部奖励',exact:true}).click();assert.equal(await page.locator('.reward-card').count(),31)
 await page.getByRole('button',{name:'第27天奖励',exact:true}).click();assert.match(await page.locator('.detail-rewards').innerText(),/星汐.*7天/);await page.getByRole('button',{name:'关闭',exact:true}).click()
 await page.getByRole('button',{name:'第23天今日奖励',exact:true}).click();await page.getByRole('button',{name:'领取今日奖励',exact:true}).click();await page.waitForTimeout(150);assert.equal(signCalls,1)
 await page.getByRole('button',{name:'选择补签日期'}).click();await page.getByRole('button',{name:'补签20日',exact:true}).click();await page.getByRole('button',{name:'消耗 10 次元币补签',exact:true}).click();await page.getByText('确定',{exact:true}).last().click();await page.waitForTimeout(200);assert.equal(makeupCalls,1);assert(signed.has(20))
 await page.getByRole('button',{name:'签到记录',exact:true}).click();await page.locator('.history-row').waitFor();assert.match(await page.locator('.history-reward').innerText(),/次元币.*经验/);await page.getByRole('button',{name:'关闭',exact:true}).click()
 await page.getByRole('button',{name:'签到规则',exact:true}).click();assert.match(await page.locator('.rules-copy').innerText(),/不修复连续签到/);await page.getByRole('button',{name:'关闭',exact:true}).click()
 for(const [m,n] of [['2026-02',28],['2028-02',29],['2026-04',30]]){month=m;count=n;today=20;signed.clear();await page.reload();await page.locator('.reward-card').first().waitFor();await page.getByRole('button',{name:'查看全部奖励',exact:true}).click();assert.equal(await page.locator('.reward-card').count(),n)}
 await page.getByRole('button',{name:'查看更多，前往全部频道',exact:true}).click();await page.waitForTimeout(500);assert(allChannelRequested,'more must select configured all-posts channel')
 await page.evaluate(()=>uni.navigateTo({url:'/pages/relationships/index?type=followers'}));await page.locator('.relationship-row .user-avatar-frame').waitFor();await page.locator('.relationship-row .user-avatar-frame').waitFor({state:'detached',timeout:5000});
 await page.evaluate(()=>uni.navigateTo({url:'/pages/settings/avatar-frame'}));await page.getByRole('button',{name:'星汐 · 星愿',exact:true}).waitFor();assert(await page.getByRole('button',{name:'星汐 · 星愿',exact:true}).isDisabled());
 assert.deepEqual(errors,[]);await context.close();return {startupHome:true,expiredFrameHidden:true,unownedFrameLocked:true,fourColumnWidths:[320,390,430],months:[28,29,30,31],signCalls,makeupCalls,history:true,rules:true,allChannelRequested}
}
async function adminChecks(browser){
 const page=await browser.newPage({viewport:{width:1440,height:1050}}),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.addInitScript(()=>localStorage.setItem('SOY_token',JSON.stringify('ui-test-token')))
 let config=defaults(),saves=0,uploads=0
 await page.route('**/*',async route=>{const req=route.request(),p=new URL(req.url()).pathname;if(p.startsWith('/uploads/checkin/'))return route.fulfill({contentType:'image/webp',body:fs.readFileSync(path.join(root,'static/images/checkin',path.basename(p)))});if(!['xhr','fetch'].includes(req.resourceType()))return route.continue();let data=[];if(p.endsWith('/auth/getUserInfo'))data={userId:'1',userName:'测试管理员',nickName:'测试管理员',roles:['R_SUPER'],buttons:[],userType:0};else if(p.endsWith('/checkin/config')){if(req.method()==='POST'){config=req.postDataJSON();config.version++;saves++}data=config}else if(p.endsWith('/image/upload')){assert(req.postDataBuffer().toString().includes('webp'));uploads++;data={url:'/uploads/checkin/frame.webp',originalBytes:240000,processedBytes:12000}}else if(p.endsWith('/publish/health'))data=null;return route.fulfill({json:{code:'0000',msg:'ok',data}})})
 await page.goto(adminBase+'/checkin-settings');await page.getByText('每日签到配置',{exact:true}).waitFor();assert.equal(await page.locator('.day-button').count(),31)
 await page.locator('input[type=file]').first().setInputFiles({name:'coin.png',mimeType:'image/png',buffer:Buffer.from('fixture')});await page.getByText(/处理前 234.38 KB/).waitFor();assert.equal(uploads,1)
 await page.getByRole('button',{name:'保存配置',exact:true}).click();await page.waitForTimeout(200);assert.equal(saves,1);assert.equal(config.coinIcon.originalBytes,240000)
 await page.getByRole('button',{name:'重新加载',exact:true}).click();await page.getByText(/处理前 234.38 KB/).waitFor();await page.screenshot({path:path.join(output,'admin.png'),fullPage:true});assert.deepEqual(errors,[]);await page.close();return {days:31,saves,uploads,persistedSizes:true}
}
;(async()=>{fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({headless:true,...(process.env.UI_BROWSER_PATH?{executablePath:process.env.UI_BROWSER_PATH}:{})});try{const result={app:await appChecks(browser),admin:await adminChecks(browser)};fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2))}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1})

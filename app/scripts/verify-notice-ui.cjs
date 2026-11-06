const { chromium } = require('playwright-core')
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const output = path.resolve(__dirname, '../artifacts/notice-delivery')
const user = { userId:'10',nickname:'通知测试',avatar:'',level:1,account:'test',coinBalance:0 }
const category = { id:1,name:'系统通知',iconUrl:'',enabled:true,sortOrder:0,recipientPolicy:'choose',recipientUserIds:[] }
async function app(browser) {
  const context = await browser.newContext({ viewport:{width:390,height:844},isMobile:true,hasTouch:true })
  const page = await context.newPage(), errors=[], readCalls=[]
  page.on('pageerror',e=>errors.push(e.message))
  await page.addInitScript(u=>{localStorage.setItem('app_token','ui-token');localStorage.setItem('app_user_info',JSON.stringify(u))},user)
  let unread=24
  await page.route('**/*',async route=>{
    const req=route.request(),url=new URL(req.url()),p=url.pathname
    if(!['xhr','fetch'].includes(req.resourceType())||!p.startsWith('/app/'))return route.continue()
    let data=[]
    if(p==='/app/notice/summary')data={unreadCount:unread,categories:[{...category,unreadCount:unread,latestTitle:'通知功能联调',latestTime:'2026-10-25 14:00:00'}]}
    else if(p==='/app/notice/list')data={records:[{id:10,publicationId:100,categoryId:1,title:'通知功能联调',publishTime:'2026-10-25 14:00:00',unread:unread>0}],total:1,pages:1,readPublicationIds:[100]}
    else if(p==='/app/notice/read/category'){readCalls.push(req.postDataJSON());unread=0;data=null}
    else if(p==='/app/notice/10')data={id:10,publicationId:100,categoryId:1,categoryName:'系统通知',title:'通知功能联调',publishTime:'2026-10-25 14:00:00',blocks:[{type:'text',text:'这是一条通知正文。'}]}
    else if(p==='/app/notice/read/one'){readCalls.push(req.postDataJSON());data=null}
    else if(p==='/app/user/me')data=user
    else if(p==='/app/home/config')data={channels:[{id:1,name:'推荐'}],entries:[],news:{enabled:false,items:[],interval:3,emptyText:''}}
    else if(p==='/app/media/config')data={cacheMaxBytes:10000000}
    else if(p.includes('/post/'))data={records:[],total:0,pages:0}
    return route.fulfill({json:{code:'0000',msg:'ok',data}})
  })
  await page.goto(process.env.UI_TEST_URL||'http://127.0.0.1:15273')
  await page.locator('.tab-bar .badge').waitFor().catch(async error=>{console.log({errors,url:page.url(),body:(await page.locator('body').innerText()).slice(-700)});await page.screenshot({path:path.join(output,'failure.png')});throw error})
  assert.equal(await page.locator('.tab-bar .badge').innerText(),'24')
  await page.getByRole('button',{name:'消息',exact:true}).click()
  await page.locator('.conversation .unread').waitFor()
  assert.equal(await page.locator('.conversation .unread').innerText(),'24')
  await page.screenshot({path:path.join(output,'app-unread-390.png')})
  await page.locator('.conversation').first().click()
  await page.locator('.notice-row').waitFor()
  await page.waitForFunction(()=>document.querySelector('.tab-bar .badge')==null)
  assert.deepEqual(readCalls[0],{categoryId:1,publicationIds:[100]})
  await page.locator('.notice-row').first().click()
  await page.locator('.paragraph').waitFor()
  await page.waitForTimeout(100)
  assert.deepEqual(readCalls[1],{id:10,publicationId:100})
  assert.deepEqual(errors,[])
  await context.close()
  return {totalUnread:24,categoryUnread:24,snapshotRead:true,detailRead:true}
}
async function admin(browser) {
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],saved=[]
  page.on('pageerror',e=>errors.push(e.message))
  await page.addInitScript(()=>localStorage.setItem('SOY_token',JSON.stringify('ui-token')))
  await page.route('**/*',async route=>{
    const req=route.request(),p=new URL(req.url()).pathname
    if(!['xhr','fetch'].includes(req.resourceType()))return route.continue()
    let data=[]
    if(p.endsWith('/auth/getUserInfo'))data={userId:'1',userName:'admin',nickName:'管理员',roles:['R_SUPER'],buttons:[],userType:0}
    else if(p.endsWith('/notice/category/list'))data=[category]
    else if(p.endsWith('/app-user/list'))data={records:[{id:'10',nickname:'通知测试',publicUid:'100001'}],total:1}
    else if(p.endsWith('/notice/list'))data={records:[],total:0}
    else if(p.endsWith('/notice/push/config')){data={enabled:false,appId:'__UNI__B1A811F',endpoint:'',secret:'',secretConfigured:false};if(req.method()==='POST'){saved.push(req.postDataJSON());data={...data,...req.postDataJSON()}}}
    return route.fulfill({json:{code:'0000',msg:'ok',data}})
  })
  const base=process.env.ADMIN_UI_TEST_URL||'http://127.0.0.1:9527'
  await page.goto(base+'/notice-category');await page.getByRole('button',{name:'新增分类',exact:true}).click()
  await page.getByText('接收规则',{exact:true}).last().waitFor()
  await page.waitForTimeout(350);await page.screenshot({path:path.join(output,'admin-category.png'),fullPage:true})
  await page.goto(base+'/notice-manage');await page.getByRole('button',{name:'新建通知',exact:true}).click()
  await page.getByText('同步发送系统通知',{exact:true}).waitFor()
  await page.getByText('接收范围',{exact:true}).last().waitFor()
  await page.waitForTimeout(350);await page.screenshot({path:path.join(output,'admin-send.png'),fullPage:true})
  await page.goto(base+'/notice-push');await page.getByText('推送桥接接口（HTTPS）',{exact:true}).waitFor()
  await page.getByRole('button',{name:'保存配置',exact:true}).click();await page.waitForTimeout(200)
  assert.equal(saved.length,1);assert.equal(saved[0].enabled,false)
  await page.waitForTimeout(350);await page.screenshot({path:path.join(output,'admin-push.png'),fullPage:true})
  assert.deepEqual(errors,[]);await page.close();return {categoryRules:true,sendAudience:true,pushConfigSaved:true}
}
;(async()=>{fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({headless:true,executablePath:process.env.UI_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});try{const result={app:await app(browser),admin:await admin(browser)};fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result))}finally{await browser.close()}})().catch(error=>{console.error(error);process.exitCode=1})

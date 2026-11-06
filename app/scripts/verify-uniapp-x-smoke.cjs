const fs=require('fs'),path=require('path'),assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const output=process.env.UI_SMOKE_OUTPUT || path.resolve(__dirname,'../../deliverables/uniapp-x-migration/browser-smoke')
fs.mkdirSync(output,{recursive:true})
;(async()=>{
 const browser=await chromium.launch({executablePath:process.env.UI_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true})
 const errors=[],results=[]
 try {
  const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1})
  const page=await context.newPage();page.on('pageerror',e=>errors.push(String(e)))
  const posts=await fetch('http://127.0.0.1:8080/app/post/list?pageNum=1&pageSize=10').then(r=>r.json())
  assert.equal(posts.code,'0000');assert(posts.data.records.length>0,'Real backend must contain posts; empty lists cannot pass this smoke test')
  const base=process.env.UI_TEST_URL||'http://127.0.0.1:15273/'
  await page.goto(base+'#/pages/index/index',{waitUntil:'networkidle'})
  await page.locator('.post-card,.waterfall-post').first().waitFor({timeout:30000})
  assert(await page.locator('.post-card,.waterfall-post').count()>0)
  const tabs=await page.locator('.tab-bar .tab-item').evaluateAll(elements=>elements.map(element=>{const r=element.getBoundingClientRect();return{x:r.x,y:r.y,width:r.width,height:r.height}}))
  assert.equal(tabs.length,5)
  assert(tabs.every(tab=>Math.abs(tab.y-tabs[0].y)<2),'All bottom navigation items must share the same row')
  assert(tabs.every((tab,index)=>index===0||tab.x>tabs[index-1].x),'Bottom navigation items must run left to right')
  await page.screenshot({path:path.join(output,'home-390.png')})
  results.push({scenario:'home-real-backend',rendered:await page.locator('.post-card,.waterfall-post').count(),backendRecords:posts.data.records.length})
  for(const width of [320,390,430]){await page.setViewportSize({width,height:844});await page.screenshot({path:path.join(output,'home-'+width+'.png')});const shell=await page.locator('.app-shell').boundingBox();assert(shell&&Math.abs(shell.width-width)<2,'App shell width '+width);results.push({scenario:'viewport',width})}
  for(const [index,name] of [[1,'follow'],[3,'message'],[4,'mine']]){await page.locator('.tab-bar .tab-item').nth(index).click();await page.waitForTimeout(300);await page.screenshot({path:path.join(output,name+'.png')});results.push({scenario:'tab-'+name})}
  for(const [route,selector] of [['pages/settings/index','.settings-page'],['pages/login/index','.auth-page']]){await page.goto(base+'#/'+route,{waitUntil:'networkidle'});await page.locator(selector).waitFor();results.push({scenario:route});await page.screenshot({path:path.join(output,route.split('/')[1]+'.png')})}
  await page.goto(base+'#/pages/post/index?id='+encodeURIComponent(posts.data.records[0].id),{waitUntil:'networkidle'});await page.locator('.post-detail').waitFor();await page.screenshot({path:path.join(output,'post-detail.png')});results.push({scenario:'real-post-detail'})
  assert.deepEqual(errors,[],'Runtime errors')
  fs.writeFileSync(path.join(output,'report.json'),JSON.stringify({status:'passed',scope:'Read-only H5 smoke against the real local backend; not native or complete feature acceptance',results,errors},null,2))
  console.log(JSON.stringify({status:'passed',checks:results.length,scope:'H5 real-data smoke only'}))
 } catch(error){fs.writeFileSync(path.join(output,'report.json'),JSON.stringify({status:'failed',results,errors,error:String(error)},null,2));throw error}
 finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})

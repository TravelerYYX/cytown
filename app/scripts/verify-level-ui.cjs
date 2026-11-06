const {chromium} = require('playwright-core');
const fs=require('node:fs'), path=require('node:path'), assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..'), output=path.join(root,'artifacts/levels');
fs.mkdirSync(output,{recursive:true});
const seed=fs.readFileSync(path.join(root,'ciyuantoen-back/ciyuantown-back/doc/sql/p17-community-level-config.sql'),'utf8');
const defaults=JSON.parse(seed.match(/\(1,0,'(.*)'\);/)[1]);
const clone=value=>JSON.parse(JSON.stringify(value)), envelope=data=>({json:{code:'0000',msg:'ok',data}});
const image=fs.readFileSync(path.join(root,'次元町/static/images/levels/level-60.png'));
function badgeBytes(pathname) { const match=pathname.match(/level-(\d+)\.png$/);return match?fs.readFileSync(path.join(root,'次元町/static/images/levels/level-'+match[1]+'.png')):image; }
const results=[];
let activePage;
const authFile=fs.readdirSync(path.join(root,'次元町/unpackage/dist/build/h5/assets')).find(file=>/^auth\.[^.]+\.js$/.test(file));
const authModule=process.env.LEVEL_AUTH_MODULE || (authFile?'/assets/'+authFile:'/utils/auth');

async function appChecks(browser) {
  for(const width of [320,390,430]) {
    const context=await browser.newContext({viewport:{width,height:844},isMobile:true,hasTouch:true});
    const page=await context.newPage(), errors=[];activePage=page;page.on('pageerror',e=>errors.push(e.message));
    let signed=false, signCalls=0, historyCalls=0, staleHistory=null, failProgress=false, alternate=false, holdProgress=false, pendingProgress=null;
    const config=clone(defaults);config.levels[1].badgeUrl='/uploads/missing-badge.png';
    const user={userId:'level-ui-a',publicUid:'10001',nickname:'星旅测试',avatar:'',level:1,signature:'记录热爱',tags:[],gender:2};
    const progress=()=>({experience:alternate?0:signed?130:90,level:alternate?1:signed?2:1,title:'初旅',fullTitle:'初旅 · 星槎新客',color:'#8393a6',description:'社区新人，初入次元世界',badgeUrl:config.levels[signed?1:0].badgeUrl,
      levelStartExp:signed?100:0,nextLevelExp:signed?230:100,remainingExp:signed?100:10,maxLevel:false,signedToday:signed,streakDays:signed?7:6,rules:config.rules,today:signed?[{action:'sign_in',earned:10},{action:'streak_7',earned:30}]:[]});
    await page.route('**/*',async route=>{
      const request=route.request(), url=new URL(request.url()), pathname=url.pathname;
      if(pathname==='/uploads/missing-badge.png')return route.fulfill({status:404,body:''});
      if(pathname.startsWith('/uploads/levels/'))return route.fulfill({contentType:'image/png',body:badgeBytes(pathname)});
      if(!['xhr','fetch'].includes(request.resourceType()))return route.continue();
      if(pathname==='/app/level/config')return route.fulfill(envelope(config));
      if(pathname==='/app/level/me') {
        if(holdProgress){holdProgress=false;pendingProgress={route,data:progress()};return;}
        return route.fulfill(failProgress?{json:{code:'1000',msg:'测试加载失败',data:null}}:envelope(progress()));
      }
      if(pathname==='/app/level/sign-in'){signCalls++;await new Promise(resolve=>setTimeout(resolve,150));signed=true;return route.fulfill(envelope({gained:40,progress:progress()}));}
      if(pathname==='/app/level/history') {
        historyCalls++;
        if(historyCalls===1){staleHistory=route;return;}
        return route.fulfill(envelope({records:signed?[{id:'2',action:'sign_in',amount:10,createTime:'2026-10-20T12:00:00'},{id:'3',action:'streak_7',amount:30,createTime:'2026-10-20T12:00:00'},{id:'4',action:'admin_adjust',amount:15,createTime:'2026-10-20T12:00:00'},{id:'5',action:'admin_adjust',amount:-5,createTime:'2026-10-20T12:00:00'}]:[],total:signed?4:0,current:1,pages:1,size:20}));
      }
      if(pathname==='/app/user/me')return route.fulfill(envelope(user));
      if(pathname==='/app/user/statistics')return route.fulfill(envelope({followerCount:0,followingCount:0,receivedLikeCount:0,favoriteCount:0,postCount:0}));
      if(pathname==='/app/home/config')return route.fulfill(envelope({channels:[{id:1,name:'推荐'}],entries:[],news:{enabled:false,items:[],emptyText:'暂无资讯'},defaultFeedLayout:'single'}));
      if(pathname==='/app/media/config')return route.fulfill(envelope({cacheMaxBytes:16777216}));
      if(pathname.includes('/app/post/'))return route.fulfill(envelope({records:[],pages:0,total:0,current:1,size:10}));
      if(pathname==='/api/locate')return route.fulfill({json:{code:0,data:{country:'中国',province:'上海',city:'上海'}}});
      return route.fulfill(envelope([]));
    });
    await page.goto('http://127.0.0.1:15273/',{waitUntil:'domcontentloaded'});
    await page.evaluate(async ({user,authModule})=>{
      const auth=await import(authModule);
      const login=auth.saveLogin || Object.values(auth).find(fn=>typeof fn==='function'&&fn.toString().includes('.refreshToken')&&fn.toString().includes('.userInfo'));
      if(!login)throw new Error('Built auth module does not expose saveLogin');
      login({token:'level-ui-token',refreshToken:'level-ui-refresh',userInfo:user});location.hash='#/pages/level/index';
    },{user,authModule});
    await page.locator('.level-title').waitFor();await page.getByText('累计经验 90',{exact:true}).waitFor();
    await page.getByRole('button',{name:'每日签到',exact:true}).click();
    await page.getByText('累计经验 130',{exact:true}).waitFor();
    await page.getByRole('button',{name:'今日已签到',exact:true}).waitFor();assert.equal(signCalls,1);
    await page.waitForFunction(()=>document.querySelectorAll('.level-panel').length>=4);
    for(let i=0;i<50&&historyCalls<2;i++)await page.waitForTimeout(20);assert(historyCalls>=2,'Signing resets pending history and starts a fresh request');
    if(staleHistory)await staleHistory.fulfill(envelope({records:[{id:'stale',action:'post',amount:999,createTime:'2026-10-19T12:00:00'}],total:1,current:1,pages:1}));
    await page.waitForTimeout(150);assert(!(await page.locator('.level-page').innerText()).includes('+999'),'Stale history cannot replace the refreshed history');
    assert.equal(await page.locator('.gallery-item').count(),60);
    await page.waitForFunction(()=>document.querySelector('.level-hero img')?.src.includes('/static/images/levels/level-2.png'));
    assert.equal(await page.evaluate(async authModule=>{const auth=await import(authModule);const get=auth.getUserInfo || Object.values(auth).find(fn=>typeof fn==='function'&&fn.length===0&&fn.toString().includes('.userId')&&fn.toString().includes('object'));return get().level;},authModule),2);
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'No horizontal overflow');
    await page.waitForTimeout(1600);await page.screenshot({path:path.join(output,'app-level-'+width+'.png')});
    await page.locator('.level-scroll .uni-scroll-view').evaluateAll(elements=>elements.forEach(el=>{el.scrollTop=el.scrollHeight}));
    await page.getByText('连续7天签到',{exact:true}).last().waitFor();
    assert.equal(await page.getByText('管理员调整',{exact:true}).count(),2);
    assert.equal(await page.locator('.rule-exp').filter({hasText:/^\+15$/}).count(),1);
    assert.equal(await page.locator('.rule-exp').filter({hasText:/^-5$/}).count(),1);
    assert(!(await page.locator('.level-page').innerText()).includes('+-5'));
    await page.screenshot({path:path.join(output,'app-history-'+width+'.png')});
    failProgress=true;await page.reload();await page.getByText('等级加载失败，请稍后重试',{exact:true}).waitFor();
    failProgress=false;await page.getByRole('button',{name:'重试',exact:true}).click();await page.getByText('累计经验 130',{exact:true}).waitFor();
    await page.evaluate(()=>{location.hash='#/pages/index/index';});
    await page.locator('.tab-item[aria-label="我的"]').click();await page.locator('.mine-view .role-card').first().waitFor();
    await page.locator('.mine-view .role-card').first().getByText('次元町2级',{exact:true}).waitFor();
    assert((await page.locator('.mine-view .role-card').first().innerText()).includes('初旅'));
    if(width===390)await page.screenshot({path:path.join(output,'app-profile-390.png')});
    await page.evaluate(()=>{location.hash='#/pages/level/index';});await page.locator('.level-title').waitFor();
    if(width===390) {
      holdProgress=true;await page.reload({waitUntil:'domcontentloaded'});
      for(let i=0;i<100&&!pendingProgress;i++)await page.waitForTimeout(20);assert(pendingProgress);
      alternate=true;
      await page.evaluate(async authModule=>{
        const auth=await import(authModule);const login=auth.saveLogin || Object.values(auth).find(fn=>typeof fn==='function'&&fn.toString().includes('.refreshToken')&&fn.toString().includes('.userInfo'));
        login({token:'level-ui-token-b',refreshToken:'level-ui-refresh-b',userInfo:{userId:'level-ui-b',publicUid:'10002',nickname:'第二位町友',level:1,tags:[]}});location.hash='#/pages/index/index';
      },authModule);
      await page.locator('.tab-item[aria-label="我的"]').waitFor();await page.evaluate(()=>{location.hash='#/pages/level/index';});
      await page.getByText('累计经验 0',{exact:true}).waitFor();await pendingProgress.route.fulfill(envelope(pendingProgress.data));
      await page.waitForTimeout(200);assert.equal(await page.locator('.level-exp').innerText(),'累计经验 0');
    }
    assert.deepEqual(errors,[]);results.push({scenario:'app',width,signCalls,historyRaceProtected:true,accountSwitchProtected:width===390,badgeFallback:true,loadRetry:true,profileLevelDisplay:true,manualAdjustmentSigns:true,errors});
    await context.close();
  }
}
async function adminChecks(browser,readOnly=false) {
  const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[];activePage=page;page.on('pageerror',e=>errors.push(e.message));
  let config=clone(defaults),saveCalls=0,failSave=false;
  await page.addInitScript(()=>localStorage.setItem('SOY_token',JSON.stringify('level-admin-test-token')));
  await page.route('**/*',async route=>{
    const request=route.request(),pathname=new URL(request.url()).pathname;
    if(pathname.includes('/uploads/levels/'))return route.fulfill({contentType:'image/png',body:badgeBytes(pathname)});
    if(!['xhr','fetch'].includes(request.resourceType()))return route.continue();
    if(pathname.endsWith('/auth/getUserInfo'))return route.fulfill(envelope({userId:'1',userName:'等级测试',nickName:'测试管理员',avatar:'',roles:['R_SUPER'],buttons:[],userType:readOnly?1:0}));
    if(pathname.endsWith('/level/config')) {
      if(request.method()==='GET')return route.fulfill(envelope(config));
      saveCalls++;await new Promise(resolve=>setTimeout(resolve,150));
      if(failSave)return route.fulfill({json:{code:'1000',msg:'模拟保存失败',data:null}});
      config={...request.postDataJSON(),version:config.version+1};return route.fulfill(envelope(config));
    }
    if(pathname.endsWith('/level/badge'))return route.fulfill(envelope({url:'/uploads/levels/custom-ui.png'}));
    return route.fulfill(envelope([]));
  });
  await page.goto('http://127.0.0.1:9527/level-settings');await page.getByText('段位与头衔',{exact:true}).waitFor();
  const save=page.getByRole('button',{name:'保存全部配置',exact:true});
  async function commit() {
    const response=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith('/level/config')&&r.request().method()==='POST');
    await save.click();await response;
    await page.waitForFunction(()=>![...document.querySelectorAll('button')].some(button=>button.textContent.includes('保存全部配置')&&button.classList.contains('n-button--loading')));
  }
  if(readOnly){assert(await save.isDisabled());assert(await page.getByRole('button',{name:'上传 PNG 徽章',exact:true}).first().isDisabled());}
  else {
    const enabled=page.getByRole('switch').first();await enabled.click();await page.getByText('有未保存修改',{exact:true}).waitFor();
    await commit();await page.getByText('等级配置已保存，现有用户等级已按累计经验重新计算',{exact:true}).waitFor();assert.equal(config.rules[0].enabled,false);
    await page.reload();await page.getByText('段位与头衔',{exact:true}).waitFor();assert.equal(await page.getByRole('switch').first().getAttribute('aria-checked'),'false');
    const title=page.locator('.level-table').first().locator('tbody tr').first().locator('input').nth(2);await title.fill('初旅测试');
    failSave=true;await commit();await page.getByText('保存失败，修改已保留；如配置已被更新，请重新加载后编辑',{exact:true}).waitFor();assert.equal(await title.inputValue(),'初旅测试');
    failSave=false;await commit();await page.getByText('有未保存修改',{exact:true}).waitFor({state:'hidden'});
    const uploaded=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith('/level/badge'));
    await page.locator('input[type=file]').first().setInputFiles(path.join(root,'次元町/static/images/levels/level-1.png'));await uploaded;
    await page.waitForFunction(()=>[...document.querySelectorAll('input')].some(input=>input.value==='/uploads/levels/custom-ui.png'));
    await page.getByText('有未保存修改',{exact:true}).waitFor();
    await commit();assert.equal(config.levels[0].badgeUrl,'/uploads/levels/custom-ui.png');
  }
  config=clone(defaults);await page.reload();await page.getByText('段位与头衔',{exact:true}).waitFor();
  await page.screenshot({path:path.join(output,readOnly?'admin-readonly.png':'admin-level-settings.png'),fullPage:true});
  assert.deepEqual(errors,[]);results.push({scenario:'admin',readOnly,saveCalls,errors});await context.close();
}
;(async()=>{
  const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
  try {await appChecks(browser);await adminChecks(browser);await adminChecks(browser,true);fs.writeFileSync(path.join(output,'ui-report.json'),JSON.stringify({status:'passed',scope:'H5 and admin browser tests with mocked APIs; backend verified independently against MySQL',results},null,2));console.log(JSON.stringify({status:'passed',scenarios:results.length}));}
  catch(error){if(activePage){await activePage.screenshot({path:path.join(output,'ui-failure.png')});console.log((await activePage.locator('body').innerText()).slice(0,800));console.log(await activePage.locator('.level-sign').evaluateAll(items=>items.map(el=>el.outerHTML)));}fs.writeFileSync(path.join(output,'ui-report.json'),JSON.stringify({status:'failed',error:String(error),results},null,2));throw error;}
  finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});

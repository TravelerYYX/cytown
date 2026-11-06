const {chromium}=require('playwright-core'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const origin=process.env.ADMIN_UI_TEST_URL||'http://127.0.0.1:9527',output=path.resolve(__dirname,'../artifacts/media-ui')
;(async()=>{const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});try{
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],saved=[],tested=[],images=[]
 let settings={images:{coverMaxEdge:1280,coverQuality:78,previewMaxEdge:2560,previewQuality:85,pngCompression:6,cacheMaxBytes:3*1024**3},videos:{segmentSeconds:4,maxFps:30,audioKbps:128,crf:23,concurrency:1,bitrates:{1080:5000,720:2800,480:1400,360:800}}}
 let storage={id:'fixture',provider:'oss',localDir:'',endpoint:'https://oss-cn-hangzhou.aliyuncs.com',region:'cn-hangzhou',bucket:'fixture',accessKey:'********',secretKey:'',domain:'https://cloud.test'}
 await page.addInitScript(()=>localStorage.setItem('SOY_token',JSON.stringify('media-ui-fixture-token')));page.on('pageerror',e=>errors.push(e.message))
 await page.route('**/*',route=>{
  const request=route.request(),url=new URL(request.url()),name=url.pathname
  if(request.resourceType()==='image'){images.push(request.url());if(url.hostname==='cloud.test')return route.fulfill({body:fs.readFileSync(path.resolve(__dirname,'../static/images/assate/moren.png')),contentType:'image/png'})}
  if(!['xhr','fetch'].includes(request.resourceType()))return route.continue()
  let data={}
  if(name.endsWith('/auth/getUserInfo'))data={userId:'1',userName:'UI测试',nickName:'媒体测试管理员',avatar:'https://cloud.test/avatar.png',roles:['R_SUPER'],buttons:[],userType:0}
  else if(name.endsWith('/media-config/storage/test')){tested.push(request.postDataJSON());data=null}
  else if(name.endsWith('/media-config/storage')){if(request.method()==='POST'){saved.push(request.postDataJSON());storage={...request.postDataJSON(),accessKey:'********',secretKey:''}}data=storage}
  else if(name.endsWith('/media-config/processing')){if(request.method()==='POST'){saved.push(request.postDataJSON());settings=request.postDataJSON()}data=settings}
  return route.fulfill({json:{code:'0000',msg:'ok',data}})
 })
 await page.goto(origin+'/storage-config');const secret=page.getByPlaceholder('留空保留已保存密钥');await secret.waitFor();await secret.fill('ui-only-secret');await page.getByRole('button',{name:'连接检测',exact:true}).click();await page.getByText('连接检测通过',{exact:true}).waitFor();assert.equal(await secret.inputValue(),'ui-only-secret','connection test discarded the unsaved secret');await page.getByRole('button',{name:'保存存储配置',exact:true}).click();await page.getByText('存储配置已保存',{exact:true}).waitFor();assert.equal(saved[0].secretKey,'ui-only-secret');assert.equal(tested[0].secretKey,'ui-only-secret');await page.screenshot({path:path.join(output,'admin-storage.png')})
 await page.goto(origin+'/image-compression');await page.getByRole('button',{name:'保存图片压缩配置',exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'保存视频转码配置',exact:true}).count(),0);const edge=page.locator('.n-form-item').filter({hasText:'WebP 封面最长边'}).locator('input');await edge.fill('1024');await edge.blur();settings.videos.bitrates[720]=2222;await page.getByRole('button',{name:'保存图片压缩配置',exact:true}).click();await page.getByText('压缩配置已保存，新上传生效',{exact:true}).waitFor();assert.equal(settings.images.coverMaxEdge,1024);assert.equal(settings.videos.bitrates[720],2222,'image settings overwrote newer video settings');await page.screenshot({path:path.join(output,'admin-images.png')})
 await page.goto(origin+'/video-transcode');await page.getByRole('button',{name:'保存视频转码配置',exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'保存图片压缩配置',exact:true}).count(),0);await page.screenshot({path:path.join(output,'admin-video.png')})
 assert(images.includes('https://cloud.test/avatar.png'),'cloud avatar URL was not used directly');assert.deepEqual(errors,[]);fs.writeFileSync(path.join(output,'admin-result.json'),JSON.stringify({status:'passed',checks:['separate settings pages','connection test retains unsaved credentials','masked save','independent image/video settings','direct cloud image URLs'],errors},null,2));console.log('Admin media pages, credential handling, configuration isolation and cloud URLs passed.')
 }finally{await browser.close()}})().catch(error=>{console.error(error);process.exitCode=1})

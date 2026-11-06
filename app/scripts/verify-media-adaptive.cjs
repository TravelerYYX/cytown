const {chromium}=require('playwright-core')
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process')
const output=path.resolve(__dirname,'../artifacts/media-ui'),fixtures=path.join(output,'adaptive-fixtures')
const origin=process.env.UI_TEST_URL||'http://127.0.0.1:15273',ffmpeg=process.env.FFMPEG_PATH||'D:/ffmpeg-8.1.1-essentials_build/bin/ffmpeg.exe'
fs.mkdirSync(fixtures,{recursive:true})
const rates={720:2800,480:1400,360:800}
for(const height of [720,480,360]){
 const directory=path.join(fixtures,String(height));fs.mkdirSync(directory,{recursive:true})
 if(!fs.existsSync(path.join(directory,'index.m3u8'))){const rate=rates[height];const run=spawnSync(ffmpeg,['-y','-f','lavfi','-i',`color=c=blue:size=${Math.floor(height*16/9/2)*2}x${height}:rate=12`,'-t','90','-c:v','libx264','-preset','ultrafast','-b:v',rate+'k','-minrate',rate+'k','-maxrate',rate+'k','-bufsize',rate*2+'k','-x264-params','nal-hrd=cbr:force-cfr=1','-g','24','-pix_fmt','yuv420p','-f','hls','-hls_time','2','-hls_playlist_type','vod','-hls_segment_filename',path.join(directory,'segment-%03d.ts'),path.join(directory,'index.m3u8')],{windowsHide:true,encoding:'utf8'});assert.equal(run.status,0,run.stderr)}
}
fs.writeFileSync(path.join(fixtures,'master.m3u8'),'#EXTM3U\n'+[720,480,360].map(height=>`#EXT-X-STREAM-INF:BANDWIDTH=${rates[height]*1000},RESOLUTION=${Math.floor(height*16/9/2)*2}x${height}\n${height}/index.m3u8\n`).join(''))
let weak=true;const requests=[],errors=[],timers=new Set()
const server=http.createServer((request,response)=>{
 const relative=new URL(request.url,'http://localhost').pathname.slice(1),file=path.resolve(fixtures,relative)
 if(!file.startsWith(fixtures+path.sep)||!fs.existsSync(file)){response.writeHead(404);response.end();return}
 requests.push({file:relative,weak,time:Date.now()});const delay=weak&&relative.endsWith('.ts')&&!relative.includes('segment-000.ts')?(relative.startsWith('720/')?6500:relative.startsWith('480/')?3500:20):0
 const timer=setTimeout(()=>{timers.delete(timer);response.writeHead(200,{'Access-Control-Allow-Origin':'*','Content-Type':relative.endsWith('.m3u8')?'application/vnd.apple.mpegurl':'video/mp2t'});response.end(fs.readFileSync(file))},delay);timers.add(timer)
})
;(async()=>{await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const mediaOrigin='http://127.0.0.1:'+server.address().port
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'})
 try{const context=await browser.newContext({viewport:{width:858,height:390}});const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message))
 const media={id:1,assetId:2,type:'video',status:'ready',aspectRatio:16/9,hlsUrl:mediaOrigin+'/master.m3u8',durationMs:90000,qualities:[720,480,360].map(height=>({height,width:Math.floor(height*16/9/2)*2,actualHeight:height,bitrate:rates[height]*1000,url:mediaOrigin+`/${height}/index.m3u8`}))}
 await page.route('**/app/**',route=>route.fulfill({json:{code:'0000',data:route.request().url().includes('/app/post/2')?{id:2,authorId:100,authorNickname:'网络验收',title:'弱网自动切档',mode:'video',status:1,media:[media],allowDownload:true}:{records:[],total:0}},headers:{'Access-Control-Allow-Origin':'*'}}))
 await page.goto(origin+'/#/pages/post/index?id=2');await page.waitForSelector('.player-host video');await page.getByLabel('播放',{exact:true}).click();await page.getByLabel('全屏',{exact:true}).click()
 const levels=[];const start=Date.now();let last='';const sample=setInterval(async()=>{try{if(!await page.locator('.quality-button').count())await page.locator('.player-host').click();const value=await page.locator('.quality-button').innerText();if(value!==last){levels.push({value,seconds:Math.round((Date.now()-start)/1000)});last=value}}catch{}},500)
 try{
  await page.waitForFunction(()=>document.querySelector('.quality-button')?.textContent.includes('720P'),{},{timeout:20000})
  await page.waitForFunction(()=>/360P|480P/.test(document.querySelector('.quality-button')?.textContent||''),{},{timeout:45000})
  weak=false
  await page.waitForFunction(()=>document.querySelector('.quality-button')?.textContent.includes('720P'),{},{timeout:75000})
  await page.locator('.player-host').click();if(!await page.locator('.quality-button').isVisible())await page.locator('.player-host').click();await page.locator('.quality-button').click();await page.getByText('480P',{exact:true}).click();await page.waitForFunction(()=>document.querySelector('.quality-button')?.textContent.trim()==='480P')
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(output,'adaptive-result.json'),JSON.stringify({status:'passed',levels,requests,errors},null,2));console.log('Weak-network downgrade, stable-network upgrade and fixed manual quality passed.')
 }catch(error){fs.writeFileSync(path.join(output,'adaptive-debug.json'),JSON.stringify({levels,requests,errors,video:await page.locator('.player-host video').evaluate(v=>({paused:v.paused,time:v.currentTime,duration:v.duration,buffer:Array.from({length:v.buffered.length},(_,i)=>[v.buffered.start(i),v.buffered.end(i)])}))},null,2));await page.screenshot({path:path.join(output,'adaptive-failed.png')});throw error}finally{clearInterval(sample)}
 }finally{await browser.close();for(const timer of timers)clearTimeout(timer);await new Promise(resolve=>server.close(resolve))}
})().catch(error=>{console.error(error);for(const timer of timers)clearTimeout(timer);server.close();process.exitCode=1})

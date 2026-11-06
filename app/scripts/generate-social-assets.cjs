const fs=require('node:fs'),path=require('node:path')
const {chromium}=require('playwright-core')
const root=path.resolve(__dirname,'..')
;(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.UI_BROWSER_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'})
 try{
  const page=await browser.newPage({viewport:{width:192,height:192},deviceScaleFactor:2})
  const output=path.join(root,'static/native-raster/icons/social');fs.mkdirSync(output,{recursive:true})
  for(const file of fs.readdirSync(path.join(root,'static/icons/social')).filter(name=>name.endsWith('.svg'))){
   const svg=fs.readFileSync(path.join(root,'static/icons/social',file),'utf8').replace('<svg ','<svg width="192" height="192" ')
   await page.setContent('<body style="margin:0;background:transparent">'+svg+'</body>')
   await page.locator('svg').screenshot({path:path.join(output,file.replace('.svg','.png')),omitBackground:true})
  }
 }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})

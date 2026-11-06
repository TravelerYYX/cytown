import fs from 'node:fs';
import path from 'node:path';
import type { Plugin } from 'vite';

/** Serve/package the same built H5 app at a stable same-origin URL. */
export function postPreviewPlugin():Plugin {
 const source=path.resolve(process.cwd(),'../../次元町/unpackage/dist/build/h5');
 return {
  name:'ciyuantown-post-preview',
  configureServer(server){
   if(process.env.CT_POST_PREVIEW_DEV_ORIGIN)return;
   server.middlewares.use('/post-preview',(req,res,next)=>{
    let relative:string;try{relative=decodeURIComponent((req.url||'/').split('?')[0]);}catch{return next();}
    const file=path.resolve(source,'.'+(relative==='/'?'/index.html':relative));
    if(!file.startsWith(source+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile())return next();
    const types:Record<string,string>={'.html':'text/html; charset=utf-8','.js':'application/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.jpg':'image/jpeg','.gif':'image/gif','.json':'application/json','.woff2':'font/woff2'};
    res.setHeader('Content-Type',types[path.extname(file)]||'application/octet-stream');res.setHeader('Cache-Control','no-cache');fs.createReadStream(file).pipe(res);
   });
  },
  generateBundle(){
   if(!fs.existsSync(path.join(source,'index.html')))throw new Error('请先在次元町目录运行 npm run build:post-preview');
   const html=fs.readFileSync(path.join(source,'index.html'),'utf8');
   if(!html.includes('/post-preview/'))throw new Error('H5尚未以 /post-preview/ 为基础路径构建，请运行 npm run build:post-preview');
   const walk=(directory:string)=>{for(const item of fs.readdirSync(directory,{withFileTypes:true})){const file=path.join(directory,item.name);if(item.isDirectory())walk(file);else this.emitFile({type:'asset',fileName:'post-preview/'+path.relative(source,file).split(path.sep).join('/'),source:fs.readFileSync(file)});}};
   walk(source);
  }
 };
}

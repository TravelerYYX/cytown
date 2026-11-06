const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),ts=require('typescript');
const {platformSource}=require('./platform-source.cjs');
const root=path.resolve(__dirname,'..');
function load(target){
 const source=platformSource(fs.readFileSync(path.join(root,'utils/media-picker.uts'),'utf8'),target).replace(/import \{ openMediaAlbum \}[^\n]+/,'');
 const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
 const exports={},calls=[],toasts=[];let response={},image,video;
 const scope={exports,openMediaAlbum:spec=>{calls.push(JSON.parse(spec));return Promise.resolve(JSON.stringify(response))},uni:{showToast:value=>toasts.push(value),chooseImage:options=>{image=options},chooseVideo:options=>{video=options}}};
 vm.runInNewContext(code,scope);
 return {exports,calls,toasts,set:value=>{response=value},image:()=>image,video:()=>video};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
 const a=load('app-android');let received,fail;
 a.set({files:[{path:'/cache/a',size:5,width:20,height:40,duration:0},{path:'/cache/b',size:6,width:40,height:40,duration:0}],original:false});
 a.exports.chooseAlbumImages({count:2,sourceType:['album','camera'],success:value=>received=value});await settle();assert.equal(a.calls[0].count,2);assert.equal(received.tempFilePaths.join(','),'/cache/a,/cache/b');assert.equal(received.original,false);
 a.set({cancelled:true});received=null;a.exports.chooseAlbumImages({success:value=>received=value,fail:value=>fail=value});await settle();assert.equal(received,null);assert.match(fail.errMsg,/cancel/);
 a.set({error:'读取失败'});a.exports.chooseAlbumVideo({fail:value=>fail=value});await settle();assert.equal(fail.errMsg,'读取失败');assert.equal(a.toasts.length,1);
 a.set({action:'camera'});a.exports.chooseAlbumImages({count:1,crop:{width:600,height:600,quality:85},sourceType:['album','camera']});await settle();assert.equal(a.image().sourceType.join(','),'camera');assert.equal(a.image().crop.width,600);assert.equal(a.calls.at(-1).crop,600);
 a.set({files:[{path:'/cache/video',size:123,width:1080,height:1920,duration:9.5}],original:true});a.exports.chooseAlbumVideo({success:value=>received=value});await settle();assert.equal(received.duration,9.5);assert.equal(received.width,1080);assert.equal(received.tempFilePath,'/cache/video');assert.equal(a.calls.at(-1).count,1);
 for(const target of ['h5','app-ios','mp-weixin']){const p=load(target);p.exports.chooseAlbumImages({count:3,success:value=>received=value});p.image().success({tempFilePaths:['/fallback'],tempFiles:[{path:'/fallback',size:5}]});assert.equal(received.tempFilePaths[0],'/fallback');p.exports.chooseAlbumVideo({success:value=>received=value});p.video().success({tempFilePath:'/fallback-video',size:9,width:2,height:3,duration:4});assert.equal(received.tempFilePath,'/fallback-video');assert.equal(p.calls.length,0)}
 console.log('PASS: Android selection/cancel/error/camera/crop/video contracts and existing platform fallbacks');
})().catch(error=>{console.error(error);process.exitCode=1});

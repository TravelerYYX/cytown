const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto')
function sourceFingerprint(root){
 const hash=crypto.createHash('sha256')
 function walk(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){
  if(['node_modules','unpackage','dist','artifacts','.git'].includes(entry.name))continue
  const file=path.join(dir,entry.name)
  if(entry.isDirectory())walk(file)
  else if(entry.isFile()){hash.update(path.relative(root,file).replaceAll('\\','/'));hash.update('\0');hash.update(fs.readFileSync(file));hash.update('\0')}
 }}
 walk(root);return hash.digest('hex')
}
module.exports={sourceFingerprint}

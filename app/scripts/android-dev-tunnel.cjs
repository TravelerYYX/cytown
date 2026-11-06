const fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process')
const { networkInterfaces } = require('node:os')
// 同一台设备可能被 adb 用两条传输通道同时暴露：HBuilderX 连 127.0.0.1:16384，SDK adb 连 emulator-5554。
// 只按连接数判断会误认为“多台设备”而放弃建隧道，客户端随后所有请求都连不上，界面只剩“网络异常”。
// 因此按设备身份去重：ro.boot.serialno → ro.serialno → android_id（Android 10+ 通常只有 android_id 可用）。
const IDENTITY_PROBES=[['shell','getprop','ro.boot.serialno'],['shell','getprop','ro.serialno'],['shell','settings','get','secure','android_id']]
function deviceIdentity(execute,serial){
 for(const probe of IDENTITY_PROBES){
  let value=''
  try{value=String(execute(['-s',serial,...probe])||'').trim()}catch{continue}
  // 只接受单行标识，避免把 adb 的其它输出（如 devices 列表）当成身份。
  if(/^[0-9A-Za-z._:-]{4,}$/.test(value))return value
 }
 return ''
}
function uniqueDevices(execute,devices){
 const seen=new Set(),kept=[]
 for(const serial of devices){
  const identity=deviceIdentity(execute,serial)
  if(identity&&seen.has(identity))continue
  if(identity)seen.add(identity)
  kept.push(serial)
 }
 return kept
}
function ensureAndroidDevTunnel({hxHome,environment,deviceId,run,exists=fs.existsSync}){
 const address=environment.VITE_ANDROID_DEV_API_BASE_URL,backend=environment.VITE_API_BASE_URL
 if(!address||!backend)return{status:'not-configured'}
 const target=new URL(address),origin=new URL(backend)
 if(!['127.0.0.1','localhost','[::1]'].includes(target.hostname))return{status:'not-loopback'}
 // H5 和 iOS 可以使用电脑的局域网 IP。adb reverse 只取同一台电脑上的后端端口。
 const backendHost = origin.hostname.replace(/^\[|\]$/g, '')
 const isLocalBackend = ['127.0.0.1', 'localhost', '::1'].includes(backendHost) ||
  Object.values(networkInterfaces()).flat().some(entry => entry.address === backendHost)
 if(!isLocalBackend)throw Error('本地隧道的后端必须运行在当前电脑')
 const adb=process.env.ADB_PATH||path.join(hxHome,'plugins/launcher-tools/tools/adbs/adb.exe')
 if(!exists(adb))return{status:'adb-unavailable'}
 const execute=run||((args)=>execFileSync(adb,args,{encoding:'utf8',windowsHide:true,timeout:5000,stdio:['ignore','pipe','pipe']}).trim())
 const devices=execute(['devices']).split(/\r?\n/).slice(1).map(line=>line.trim().split(/\s+/)).filter(parts=>parts[1]==='device').map(parts=>parts[0])
 let selected=''
 if(deviceId)selected=devices.includes(deviceId)?deviceId:''
 else{
  const candidates=devices.length>1?uniqueDevices(execute,devices):devices
  selected=candidates.length===1?candidates[0]:''
 }
 if(!selected)return{status:devices.length?'device-selection-required':'no-authorized-device'}
 const sourcePort=target.port||(target.protocol==='https:'?'443':'80'),backendPort=origin.port||(origin.protocol==='https:'?'443':'80')
 execute(['-s',selected,'reverse','tcp:'+sourcePort,'tcp:'+backendPort])
 const rules=execute(['-s',selected,'reverse','--list'])
 if(!rules.includes('tcp:'+sourcePort+' tcp:'+backendPort))throw Error('Android 调试隧道未建立')
 return{status:'connected',device:selected,sourcePort,backendPort}
}
module.exports={ensureAndroidDevTunnel,deviceIdentity,uniqueDevices}

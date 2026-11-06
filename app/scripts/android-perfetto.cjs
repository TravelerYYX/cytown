// Native system evidence capture only. It never calculates or claims acceptance.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),{execFileSync}=require('node:child_process')
const args=process.argv.slice(2),action=args.shift()||'preflight',options={}
for(let i=0;i<args.length;i+=2){if(!args[i].startsWith('--')||!args[i+1])throw Error('Expected --option value');options[args[i].slice(2)]=args[i+1]}
if(action==='help'){console.log('android-perfetto.cjs preflight|start|stop --package com.example.app --output evidence-directory [--serial USB_SERIAL] [--variant baseline|migrated] [--case list|tabs20|details20|video]');process.exit(0)}
const adb=process.env.ADB_PATH||path.join(process.env.HBUILDERX_HOME||'D:/Editor/HBuilderX','plugins/launcher-tools/tools/adbs/adb.exe')
const output=path.resolve(options.output||path.join(__dirname,'../../deliverables/uniapp-x-migration/android-evidence'))
fs.mkdirSync(output,{recursive:true})
const stateFile=path.join(output,'capture-state.json')
let serial=options.serial||''
function run(argv,input){return execFileSync(adb,[...(serial?['-s',serial]:[]),...argv],{input,encoding:'utf8',maxBuffer:32*1024*1024,timeout:30000,windowsHide:true,stdio:['pipe','pipe','pipe']}).trim()}
function raw(name,argv){try{const value=run(argv);fs.writeFileSync(path.join(output,name+'.txt'),value);return{file:name+'.txt'}}catch(error){const message=String(error.stderr||error.message);fs.writeFileSync(path.join(output,name+'.error.txt'),message);return{error:message}}}
function preflight(packageName){
 const devices=run(['devices']).split(/\r?\n/).slice(1).map(line=>line.trim().split(/\s+/)).filter(row=>row[1]==='device').map(row=>row[0])
 if(!serial){if(devices.length!==1)throw Error(devices.length?'Specify --serial when multiple devices are connected':'No authorized Android USB device; capture was not run');serial=devices[0]}
 if(!devices.includes(serial))throw Error('The selected device is unavailable or unauthorized')
 if(!packageName||!/^([A-Za-z][A-Za-z0-9_]*\.)+[A-Za-z][A-Za-z0-9_]*$/.test(packageName))throw Error('Specify the actual installed --package; no package name is guessed')
 const details=run(['shell','dumpsys','package',packageName])
 if(!details.includes('versionCode='))throw Error('Target package is not installed')
 if(/(?:pkgFlags|flags|privateFlags|requested flags)\s*[=:]\s*\[[^\]]*\bDEBUGGABLE\b/.test(details))throw Error('A debuggable package cannot be used for release acceptance')
 const apk=run(['shell','pm','path',packageName]).split(/\r?\n/).find(line=>line.endsWith('/base.apk'))?.replace(/^package:/,'')
 if(!apk||!/^\/data\/app\/[A-Za-z0-9_~+=./-]+\/base\.apk$/.test(apk))throw Error('Cannot safely identify the installed base APK')
 const apkSHA256=run(['shell','sha256sum',apk]).split(/\s+/)[0]
 if(!/^[a-f0-9]{64}$/.test(apkSHA256))throw Error('Installed APK checksum unavailable')
 const metadata={serial,packageName,apkSHA256,model:run(['shell','getprop','ro.product.model']),android:run(['shell','getprop','ro.build.version.release']),sdk:Number(run(['shell','getprop','ro.build.version.sdk'])),version:details.match(/versionCode=[^\r\n]*/)?.[0]||'',display:run(['shell','wm','size']),density:run(['shell','wm','density'])}
 fs.writeFileSync(path.join(output,'device.json'),JSON.stringify(metadata,null,2));raw('display',['shell','dumpsys','display']);return metadata
}
try{
 if(action==='stop'){
  if(!fs.existsSync(stateFile))throw Error('No owned capture session in this output directory')
  const state=JSON.parse(fs.readFileSync(stateFile,'utf8'));serial=state.serial
  if(state.status!=='recording'||!/^ct_[0-9]+_[a-f0-9]{8}$/.test(state.session))throw Error('No valid recording session to stop')
  try{run(['shell','perfetto','--attach',state.session,'--stop'])}catch(error){state.stopError=String(error.stderr||error.message)}
  raw('meminfo-after',['shell','dumpsys','meminfo',state.packageName]);raw('gfxinfo-after',['shell','dumpsys','gfxinfo',state.packageName,'framestats'])
  raw('surfaceflinger-timestats',['shell','dumpsys','SurfaceFlinger','--timestats','-dump']);raw('surfaceflinger-layers',['shell','dumpsys','SurfaceFlinger','--list'])
  if(state.timestatsEnabled)raw('surfaceflinger-disable',['shell','dumpsys','SurfaceFlinger','--timestats','-disable'])
  run(['pull',state.remoteTrace,path.join(output,'system.pftrace')])
  state.status='captured-unassessed';state.stoppedAt=new Date().toISOString();state.acceptance='not-evaluated';state.executedRepetitions=null;state.frameSurfaceCoverage='must-be-verified-in-trace';state.traceSHA256=crypto.createHash('sha256').update(fs.readFileSync(path.join(output,'system.pftrace'))).digest('hex')
  fs.writeFileSync(stateFile,JSON.stringify(state,null,2));console.log(JSON.stringify({status:state.status,output,acceptance:state.acceptance}));process.exit(0)
 }
 const metadata=preflight(options.package)
 if(action==='preflight'){fs.writeFileSync(path.join(output,'preflight.json'),JSON.stringify({status:'ready-for-capture',acceptance:'not-run',metadata},null,2));console.log('Native capture preflight ready; no performance test has run.');process.exit(0)}
 if(action!=='start')throw Error('Use preflight, start, stop or help')
 if(!['baseline','migrated'].includes(options.variant)||!['list','tabs20','details20','video'].includes(options.case))throw Error('Specify --variant baseline|migrated and --case list|tabs20|details20|video')
 if(fs.existsSync(stateFile)&&JSON.parse(fs.readFileSync(stateFile,'utf8')).status==='recording')throw Error('An owned session is already recording; stop it before starting another')
 const session='ct_'+Date.now()+'_'+crypto.randomBytes(4).toString('hex'),remoteTrace='/data/misc/perfetto-traces/'+session+'.pftrace'
 const config=`buffers { size_kb: 32768 fill_policy: RING_BUFFER }\nduration_ms: 600000\nwrite_into_file: true\nfile_write_period_ms: 1000\nmax_file_size_bytes: 134217728\ndata_sources { config { name: "android.surfaceflinger.frametimeline" } }\ndata_sources { config { name: "linux.ftrace" ftrace_config { ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_wakeup" ftrace_events: "power/cpu_frequency" atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "input" atrace_apps: "${metadata.packageName}" } } }\n`
 fs.writeFileSync(path.join(output,'trace-config.pbtxt'),config)
 raw('meminfo-before',['shell','dumpsys','meminfo',metadata.packageName]);raw('gfxinfo-reset',['shell','dumpsys','gfxinfo',metadata.packageName,'reset'])
 const timestats=raw('surfaceflinger-enable',['shell','dumpsys','SurfaceFlinger','--timestats','-enable','-clear'])
 run(['shell','perfetto','--txt','-c','-','--detach',session,'-o',remoteTrace],config)
 const state={status:'recording',startedAt:new Date().toISOString(),session,remoteTrace,serial,packageName:metadata.packageName,variant:options.variant,plannedCase:options.case,executedRepetitions:null,acceptance:'not-run',timestatsEnabled:!timestats.error}
 fs.writeFileSync(stateFile,JSON.stringify(state,null,2));console.log('Native trace started. Perform the planned read-only workflow, record the operation counts/sequence, then run stop with the same --output. No metrics are accepted automatically.')
}catch(error){
 const report={status:'not-run-or-incomplete',action,acceptance:'not-run',reason:String(error.stderr||error.message),at:new Date().toISOString()}
 fs.writeFileSync(path.join(output,'capture-error.json'),JSON.stringify(report,null,2));console.error(JSON.stringify(report));process.exitCode=1
}

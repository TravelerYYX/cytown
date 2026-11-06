package io.ciyuantown.update

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// Separate provider class prevents manifest merging with the host's existing FileProvider.
class UpdateFileProvider: FileProvider()

/** The service, not the UVUE page, owns the transfer. JSON is the UTS boundary. */
object AppUpdater {
    private const val PREFS = "ct_android_update_v1"
    const val CHANNEL = "ct_app_updates"
    const val NOTIFICATION = 7841
    val running = AtomicBoolean(false)
    @Volatile var foreground = false
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Synchronized fun state(c: Context): JSONObject = try { JSONObject(prefs(c).getString("task", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    @Synchronized fun write(c: Context, s: JSONObject) { prefs(c).edit().putString("task", s.toString()).commit() }
    fun installedCode(c: Context): Long {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }
    fun snapshot(c: Context): String {
        val s = state(c)
        if (s.optLong("versionCode") in 1..installedCode(c)) {
            if (!running.get()) {
                apk(c).delete(); part(c).delete(); prefs(c).edit().remove("task").apply()
                c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                return "{}"
            }
        }
        if (!running.get() && s.optString("phase") in listOf("downloading", "verifying")) {
            s.put("phase", "paused").put("message", "下载已中断，点击继续下载"); write(c,s)
        }
        val total=s.optLong("sizeBytes"); val downloaded=s.optLong("downloaded")
        val tenths = if(total>0) ((downloaded.coerceIn(0,total)*1000L)/total).toInt() else 0
        s.put("percent", String.format(Locale.US,"%.1f",tenths/10.0))
        s.put("progress",tenths/10.0)
        s.put("notificationsEnabled",notificationsEnabled(c))
        return s.toString()
    }
    fun notificationsEnabled(c: Context): Boolean {
        val manager=c.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT<24 || manager.areNotificationsEnabled()) &&
            (Build.VERSION.SDK_INT<33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) &&
            (Build.VERSION.SDK_INT<26 || manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
    }
    fun start(activity: Activity, spec: String): String {
        try {
            val incoming=JSONObject(spec)
            val uri=Uri.parse(incoming.getString("url"))
            require(uri.scheme in listOf("https","http") && !uri.host.isNullOrBlank()) { "下载地址无效" }
            require(incoming.getLong("sizeBytes") in 1..(500L*1024*1024) && incoming.getString("sha256").matches(Regex("[a-fA-F0-9]{64}"))) { "安装包信息无效" }
            require(incoming.getString("packageName")==activity.packageName) { "安装包与当前应用的包名不一致" }
            require(incoming.getLong("versionCode")>installedCode(activity)) { "此版本已安装" }
            val previous=state(activity)
            val same=previous.optString("sha256")==incoming.getString("sha256") && previous.optLong("versionCode")==incoming.getLong("versionCode")
            if(running.get())return if(same) "" else "正在下载另一版本，请等待完成后重试"
            if(same && previous.optString("phase")=="ready")return install(activity)
            if(!same){part(activity).delete();apk(activity).delete()}
            incoming.put("phase","queued").put("downloaded",if(same) part(activity).length() else 0).put("message","").put("attempted",false)
            if(same) incoming.put("etag",previous.optString("etag"))
            write(activity,incoming)
            if(Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),7842)
            val intent=Intent(activity,AppUpdateService::class.java)
            if(Build.VERSION.SDK_INT>=26)activity.startForegroundService(intent) else activity.startService(intent)
            return ""
        } catch(e: Exception) {
            val s=state(activity);s.put("phase","failed").put("message",e.message?:"无法开始下载");write(activity,s)
            return e.message?:"无法开始下载"
        }
    }
    fun setForeground(activity: Activity?, visible: Boolean) {
        foreground=visible
        if(activity!=null && visible) {
            val s=state(activity)
            if(s.optBoolean("needsInstallPermission") && (Build.VERSION.SDK_INT<26 || activity.packageManager.canRequestPackageInstalls())) {
                s.put("attempted",false).put("needsInstallPermission",false);write(activity,s)
            }
        }
    }
    fun takeOpenRequest(activity: Activity): Boolean {
        val requested=prefs(activity).getBoolean("openRequested",false) || activity.intent?.getBooleanExtra("ct_update_open",false)==true
        prefs(activity).edit().remove("openRequested").apply()
        activity.intent?.removeExtra("ct_update_open")
        if(requested){val s=state(activity);if(s.optString("phase")=="ready"){s.put("attempted",false);write(activity,s)}}
        return requested
    }
    fun requestOpen(c: Context){prefs(c).edit().putBoolean("openRequested",true).commit()}
    fun install(activity: Activity): String {
        val s=state(activity)
        if(s.optString("phase")!="ready" || !apk(activity).isFile)return "安装包尚未下载或校验完成"
        try {
            s.put("attempted",true);write(activity,s)
            if(Build.VERSION.SDK_INT>=26 && !activity.packageManager.canRequestPackageInstalls()) {
                s.put("needsInstallPermission",true);write(activity,s)
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+activity.packageName)))
                return "请允许安装此来源的应用，返回后继续安装"
            }
            val uri=FileProvider.getUriForFile(activity,activity.packageName+".ct-update.files",apk(activity))
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return ""
        } catch(e: Exception) { return "无法打开安装程序："+(e.message?:"请稍后重试") }
    }
    fun directory(c: Context)=File(c.filesDir,"app-updates").also{it.mkdirs()}
    fun part(c: Context)=File(directory(c),"update.part")
    fun apk(c: Context)=File(directory(c),"update.apk")
    fun notification(c: Context, s: JSONObject): Notification {
        val nm=c.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel(CHANNEL,"应用更新",NotificationManager.IMPORTANCE_LOW))
        val intent=Intent(c,AppUpdateEntryActivity::class.java)
        val pending=PendingIntent.getActivity(c,NOTIFICATION,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val phase=s.optString("phase");val total=s.optLong("sizeBytes");val downloaded=s.optLong("downloaded")
        val tenths=if(total>0)((downloaded.coerceIn(0,total)*1000L)/total).toInt()else 0
        val text=when(phase){"ready"->"下载完成，点击安装";"verifying"->"100.0% · 正在校验安装包";"failed","paused"->s.optString("message","下载暂停，点击重试");else->String.format(Locale.US,"%.1f%% · 支持后台下载",tenths/10.0)}
        val b=if(Build.VERSION.SDK_INT>=26)Notification.Builder(c,CHANNEL)else @Suppress("DEPRECATION") Notification.Builder(c)
        b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("次元町 "+s.optString("versionName")+" 更新")
            .setContentText(text).setContentIntent(pending).setOnlyAlertOnce(true).setOngoing(phase in listOf("queued","downloading","verifying")).setAutoCancel(phase=="ready")
        if(phase in listOf("queued","downloading","verifying"))b.setProgress(1000,tenths,phase=="queued")
        if(phase=="ready")b.setSmallIcon(android.R.drawable.stat_sys_download_done)
        return b.build()
    }
}

/** Notification uses an activity PendingIntent, so installation is always user initiated. */
class AppUpdateEntryActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdater.requestOpen(this)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }
}

class AppUpdateService: Service() {
    @Volatile private var stopped=false
    private var worker: Thread?=null
    @Volatile private var connection: HttpURLConnection?=null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(!AppUpdater.running.compareAndSet(false,true))return START_NOT_STICKY
        stopped=false
        try {
            val notification=AppUpdater.notification(this,AppUpdater.state(this))
            if(Build.VERSION.SDK_INT>=29)startForeground(AppUpdater.NOTIFICATION,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(AppUpdater.NOTIFICATION,notification)
            worker=Thread({download()},"ct-update-download").also{it.start()}
        }catch(e: Exception){fail(e.message?:"后台下载启动失败");AppUpdater.running.set(false);stopSelf()}
        return START_NOT_STICKY
    }
    private fun update(s: JSONObject, notify: Boolean=true) {
        AppUpdater.write(this,s)
        if(notify)try{getSystemService(NotificationManager::class.java).notify(AppUpdater.NOTIFICATION,AppUpdater.notification(this,s))}catch(_:Exception){}
    }
    private fun fail(message: String) {val s=AppUpdater.state(this);s.put("phase","failed").put("message",message);update(s)}
    private fun download() {
        val s=AppUpdater.state(this)
        try {
            val target=AppUpdater.part(this);val expected=s.getLong("sizeBytes")
            require(directoryFree() >= expected-target.length()+16L*1024*1024){"存储空间不足，请清理后重试"}
            var attempts=0
            while(!stopped) {
                try {
                    transfer(s,target,expected)
                    break
                }catch(e: IOException) {
                    connection?.disconnect()
                    if(stopped)throw InterruptedIOException("下载已暂停")
                    attempts++
                    if(attempts>=4)throw IOException("网络连接失败，点击继续下载",e)
                    s.put("phase","downloading").put("message","网络中断，正在重试…").put("downloaded",target.length());update(s)
                    Thread.sleep((attempts*2000).toLong())
                }
            }
            if(stopped)throw InterruptedIOException("下载已暂停")
            require(target.length()==expected){"安装包大小不符，请重新下载"}
            s.put("phase","verifying").put("downloaded",expected).put("message","");update(s)
            val hash=MessageDigest.getInstance("SHA-256")
            target.inputStream().use{input->val buffer=ByteArray(65536);while(true){if(stopped)throw InterruptedIOException();val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
            require(hash.digest().joinToString(""){String.format("%02x",it)}.equals(s.getString("sha256"),true)){"安装包校验失败，请重新下载"}
            val archive=packageManager.getPackageArchiveInfo(target.path,0)?:throw IllegalArgumentException("APK 无效，请联系管理员")
            val code=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
            require(archive.packageName==packageName && code==s.getLong("versionCode") && archive.versionName==s.getString("versionName")){"安装包与当前应用或发布版本不一致"}
            require(code>AppUpdater.installedCode(this)){"此版本已经安装"}
            require(target.renameTo(AppUpdater.apk(this))){"安装包保存失败，请重试"}
            s.put("phase","ready").put("attempted",false).put("message","");update(s)
        }catch(e: Exception){
            if(e is IllegalArgumentException){AppUpdater.part(this).delete();AppUpdater.apk(this).delete();s.put("downloaded",0)}
            s.put("phase",if(stopped)"paused" else "failed").put("message",e.message?:"下载中断，点击重试");update(s)
        }finally{
            connection?.disconnect();connection=null
            stopForeground(STOP_FOREGROUND_DETACH);AppUpdater.running.set(false);stopSelf()
        }
    }
    private fun directoryFree()=AppUpdater.directory(this).usableSpace
    private fun transfer(s: JSONObject,target: File,expected: Long) {
        var offset=target.length()
        if(offset>expected){target.delete();offset=0}
        if(offset==expected)return
        val http=(URL(s.getString("url")).openConnection() as HttpURLConnection)
        connection=http;http.connectTimeout=15000;http.readTimeout=20000;http.setRequestProperty("Accept-Encoding","identity")
        if(offset>0){http.setRequestProperty("Range","bytes=$offset-");val etag=s.optString("etag");if(etag.isNotBlank())http.setRequestProperty("If-Range",etag)}
        val status=http.responseCode
        if(status==206){
            val range=http.getHeaderField("Content-Range")?:""
            val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range)
            require(match!=null && match.groupValues[1].toLong()==offset && match.groupValues[2].toLong()==expected-1 && match.groupValues[3].toLong()==expected){"服务器返回的续传范围不正确，请重新下载"}
        }else if(status==200){offset=0}else throw IOException("下载服务返回 HTTP $status")
        val length=http.getHeaderField("Content-Length")?.toLongOrNull()
        require(length==null || length==expected-offset){"服务器安装包大小已变化，请联系管理员"}
        if(stopped)throw InterruptedIOException()
        s.put("etag",http.getHeaderField("ETag")?:"").put("phase","downloading").put("message","").put("downloaded",offset);update(s)
        http.inputStream.use{input->FileOutputStream(target,offset>0).use{output->
            val buffer=ByteArray(65536);var downloaded=offset;var last=0L
            while(true){
                if(stopped || Thread.currentThread().isInterrupted)throw InterruptedIOException("下载已暂停")
                val n=input.read(buffer);if(n<0)break
                require(downloaded+n<=expected){"安装包超过声明大小"}
                output.write(buffer,0,n);downloaded+=n
                val now=SystemClock.elapsedRealtime()
                if(now-last>=400 || downloaded==expected){s.put("downloaded",downloaded);update(s);last=now}
            }
            output.fd.sync()
        }}
        if(target.length()!=expected)throw IOException("下载中断，请重试")
        http.disconnect();connection=null
    }
    // API 35 invokes this for the dataSync quota. Stop promptly and preserve partial bytes.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopped=true;connection?.disconnect();worker?.interrupt()
        val s=AppUpdater.state(this);s.put("phase","paused").put("message","后台下载达到系统时限，返回应用后可继续");update(s)
        stopForeground(STOP_FOREGROUND_DETACH);stopSelf()
    }
    override fun onDestroy(){stopped=true;connection?.disconnect();worker?.interrupt();super.onDestroy()}
}

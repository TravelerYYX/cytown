package io.ciyuantown.chat

import android.content.Context
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A loopback-only range proxy. Each missing chunk is downloaded once and kept in app files. */
object ChatMediaCache {
    private const val CHUNK = 1048576
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "ct-chat-media").apply { isDaemon = true } }
    private val jobs = ConcurrentHashMap<String, Job>()
    private var server: ServerSocket? = null
    private var context: Context? = null
    private class Job(val spec: JSONObject, val root: File) {
        val asset = spec.getJSONObject("asset")
        val owner = spec.getString("owner")
        val id = asset.getString("id")
        val version = asset.getString("version")
        val key = "$owner:$id:$version"
        val prefix = "$id-$version"
        val size = asset.getLong("sizeBytes")
        val count = ((size + CHUNK - 1) / CHUNK).toInt()
        val route = UUID.randomUUID().toString()
        @Volatile var token = spec.getString("token")
        @Volatile var stopped = false
        @Volatile var error = ""
        val metadata = File(root, "$prefix.json")
        var operation = ""
        var complete = false
        var confirmed = false
        init { root.mkdirs(); if (metadata.isFile) try { val m=JSONObject(metadata.readText());operation=m.optString("operation");complete=m.optBoolean("complete");confirmed=m.optBoolean("confirmed") } catch (_:Exception) {} }
        fun file(i:Int) = File(root, "$prefix-$i.part")
        fun expected(i:Int) = minOf(CHUNK.toLong(),size-i.toLong()*CHUNK).toInt()
        fun cached(i:Int) = file(i).length() == expected(i).toLong()
        fun persist() { val temp=File(root,"$prefix.json.tmp");temp.writeText(JSONObject().put("operation",operation).put("complete",complete).put("confirmed",confirmed).toString());if(!temp.renameTo(metadata)){temp.copyTo(metadata,true);temp.delete()} }
    }
    @JvmStatic @Synchronized fun start(app:Context, raw:String):String {
        context=app.applicationContext
        val spec=JSONObject(raw);val asset=spec.getJSONObject("asset");val owner=spec.getString("owner")
        require(owner.matches(Regex("[0-9]+")) && asset.getString("id").matches(Regex("[0-9]+")) && asset.getString("version").matches(Regex("[A-Za-z0-9-]+")))
        require(asset.getLong("sizeBytes")>0)
        if(server==null){ val socket=ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));server=socket;pool.submit { while(!socket.isClosed) try {val client=socket.accept();pool.submit { serve(client) }}catch(_:Exception){} } }
        val key=owner+":"+asset.getString("id")+":"+asset.getString("version")
        var job=jobs[key]
        if(job==null || job.stopped){job=Job(spec,File(app.filesDir,"ct-private-chat-$owner"));jobs[key]=job;val task=job;pool.submit {try{for(i in 0 until task.count)chunk(task,i);finish(task)}catch(e:Exception){task.error=e.message?:"视频缓存失败";failIncomplete(task)}}}
        else {job.token=spec.getString("token");job.error="";val task=job;pool.submit {try{for(i in 0 until task.count)chunk(task,i);finish(task)}catch(e:Exception){task.error=e.message?:"视频缓存失败";failIncomplete(task)}}}
        return "http://127.0.0.1:${server!!.localPort}/${job.route}"
    }
    private fun check(j:Job){if(j.stopped||j.token.isEmpty())throw IOException("会话已结束")}
    private fun connection(j:Job,url:String):HttpURLConnection { check(j);val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=30000;c.setRequestProperty("Authorization","Bearer ${j.token}");return c }
    private fun receipt(j:Job,action:String){val c=connection(j,j.spec.getString("api")+"/app/chat/messages/"+j.spec.getString("messageId")+"/receipt");try{c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(JSONObject().put("operationId",j.operation).put("kind","received").put("action",action).toString().toByteArray())};val result=JSONObject(c.inputStream.bufferedReader().use{it.readText()});if(result.optString("code")!="0000")throw IOException(result.optString("msg","视频接收确认失败"))}finally{c.disconnect()}}
    private fun failIncomplete(j:Job){synchronized(j){if(!j.complete&&j.operation.isNotEmpty())try{receipt(j,"fail");j.operation="";j.persist()}catch(_:Exception){}}}
    private fun reserve(j:Job){if(!j.spec.optBoolean("peer")||j.confirmed)return;if(j.operation.isEmpty()){j.operation="v_"+UUID.randomUUID().toString().replace("-","");j.persist()};receipt(j,"reserve")}
    private fun finish(j:Job){synchronized(j){check(j);if(!(0 until j.count).all{j.cached(it)})return;if(!j.complete){reserve(j);j.complete=true;j.persist()};if(j.spec.optBoolean("peer")&&!j.confirmed){receipt(j,"confirm");j.confirmed=true;j.persist()}}}
    private fun chunk(j:Job,index:Int):ByteArray=synchronized(j){
        check(j);if(index<0||index>=j.count)throw IOException("无效的分块")
        if(j.cached(index))return@synchronized j.file(index).readBytes()
        val lastMissing=(0 until j.count).all{it==index||j.cached(it)}
        if(lastMissing)reserve(j)
        val start=index.toLong()*CHUNK;val end=minOf(j.size-1,start+CHUNK-1)
        val c=connection(j,j.spec.getString("api")+"/app/chat/assets/${j.id}/content");c.setRequestProperty("Range","bytes=$start-$end")
        try{if(c.responseCode!=206)throw IOException("视频分块下载失败");val bytes=c.inputStream.use{it.readBytes()};if(bytes.size!=j.expected(index))throw IOException("视频分块不完整");check(j);val temp=File(j.root,"${j.prefix}-$index.tmp");temp.writeBytes(bytes);if(!temp.renameTo(j.file(index))){temp.copyTo(j.file(index),true);temp.delete()};if(lastMissing)finish(j);bytes}finally{c.disconnect()}
    }
    private fun serve(socket:Socket){socket.use { client -> try {
        client.soTimeout=30000;val reader=client.getInputStream().bufferedReader();val request=reader.readLine()?.split(' ')?:return
        if(request.size<2)return;val j=jobs.values.firstOrNull{request[1]=="/${it.route}"&&!it.stopped}?:run{client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray());return}
        var range:String?=null;while(true){val line=reader.readLine()?:break;if(line.isEmpty())break;if(line.startsWith("Range:",true))range=line.substringAfter(':').trim()}
        var start=0L;var end=j.size-1
        if(range!=null){val m=Regex("bytes=(\\d*)-(\\d*)").matchEntire(range!!)?:throw IOException("Invalid range");if(m.groupValues[1].isEmpty())start=maxOf(0,j.size-m.groupValues[2].toLong());else{start=m.groupValues[1].toLong();if(m.groupValues[2].isNotEmpty())end=minOf(end,m.groupValues[2].toLong())}}
        val output=client.getOutputStream();if(start<0||start>=j.size||end<start){output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nContent-Range: bytes */${j.size}\r\n\r\n".toByteArray());return}
        val headers=StringBuilder(if(range==null)"HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n").append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${end-start+1}\r\n")
        if(range!=null)headers.append("Content-Range: bytes $start-$end/${j.size}\r\n");headers.append("\r\n");output.write(headers.toString().toByteArray());output.flush();if(request[0]=="HEAD")return
        var offset=start;while(offset<=end){val index=(offset/CHUNK).toInt();val bytes=chunk(j,index);val base=index.toLong()*CHUNK;val from=(offset-base).toInt();val to=minOf(bytes.size.toLong(),end-base+1).toInt();output.write(bytes,from,to-from);output.flush();offset=base+to}
    }catch(_:Exception){} } }
    @JvmStatic fun status(key:String):String {val j=jobs.values.firstOrNull{it.key==key||"${it.id}:${it.version}"==key}?:return "{}";return JSONObject().put("complete",j.complete).put("confirmed",j.confirmed).put("error",j.error).put("bytes",(0 until j.count).sumOf{j.file(it).length()}).toString()}
    @JvmStatic fun session(token:String){for(j in jobs.values){j.token=token;if(token.isEmpty())j.stopped=true};if(token.isEmpty())jobs.clear() else for(j in jobs.values.filter{it.complete&&!it.confirmed})pool.submit{try{finish(j)}catch(_:Exception){}}}
    @JvmStatic fun release(app:Context,key:String){require(key.matches(Regex("[0-9]+:[A-Za-z0-9-]+")));for(j in jobs.values.filter{"${it.id}:${it.version}"==key}){synchronized(j){j.stopped=true;jobs.remove(j.key)}};val prefix=key.replace(':','-');app.filesDir.listFiles()?.filter{it.isDirectory&&it.name.matches(Regex("ct-private-chat-[0-9]+"))}?.forEach{dir->dir.listFiles()?.filter{it.name.startsWith(prefix+"-")||it.name==prefix+".json"||it.name==prefix+".json.tmp"}?.forEach{it.delete()}}}
    @JvmStatic fun clear(app:Context,owner:String){require(owner.matches(Regex("[0-9]+")));for(j in jobs.values.filter{it.owner==owner}){synchronized(j){j.stopped=true;jobs.remove(j.key)}};File(app.filesDir,"ct-private-chat-$owner").deleteRecursively()}
}

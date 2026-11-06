package com.yyx.ciyuantown.business.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.storage.StorageService;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Public storage holds authenticated ciphertext only; keys and manifests never leave the server. */
@Service @RequiredArgsConstructor
public class ChatVault {
 public static final int CHUNK=1024*1024;
 private final StorageService storage;
 private final ObjectMapper json;
 @Value("${CHAT_MEDIA_KEY:${MEDIA_CONFIG_KEY:${ciyuantown.jwt.secret}}}") private String master;
 public record Part(String url,String iv,int bytes) {}
 public record Manifest(String version,long size,List<Part> parts) {}
 private SecretKeySpec key(String version)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(master.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return new SecretKeySpec(mac.doFinal(("ct-private-chat-v1:"+version).getBytes(StandardCharsets.UTF_8)),"AES");}
 public Manifest store(Path source,String version)throws Exception{
  var config=storage.active();List<Part> parts=new ArrayList<>();long size=Files.size(source);String prefix="chat/"+UUID.randomUUID()+"/";
  try(var in=Files.newInputStream(source)){
   byte[] bytes;int index=0;
   while((bytes=in.readNBytes(CHUNK)).length>0){byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(version),new GCMParameterSpec(128,iv));cipher.updateAAD((version+":"+index).getBytes(StandardCharsets.UTF_8));Path temp=Files.createTempFile("ct-chat-encrypted-",".bin");try{Files.write(temp,cipher.doFinal(bytes));String url=storage.put(config,prefix+index+".bin",temp,"application/octet-stream").url();parts.add(new Part(url,Base64.getEncoder().encodeToString(iv),bytes.length));}finally{Files.deleteIfExists(temp);}index++;}
  }return new Manifest(version,size,parts);
 }
 public Manifest parse(String value){try{return json.readValue(value,Manifest.class);}catch(Exception e){throw new BizException("私聊媒体索引损坏");}}
 public byte[] part(Manifest m,int index)throws Exception{
  Path folder=Files.createTempDirectory("ct-chat-read-");try{var p=m.parts().get(index);Path input=storage.materialize(p.url(),folder);byte[] encrypted=Files.readAllBytes(input);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(m.version()),new GCMParameterSpec(128,Base64.getDecoder().decode(p.iv())));cipher.updateAAD((m.version()+":"+index).getBytes(StandardCharsets.UTF_8));byte[] plain=cipher.doFinal(encrypted);if(plain.length!=p.bytes())throw new IOException("invalid chunk");return plain;}finally{try(var walk=Files.walk(folder)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 }
 public void materialize(Manifest m,Path output)throws Exception{try(var out=Files.newOutputStream(output)){for(int i=0;i<m.parts().size();i++)out.write(part(m,i));}if(Files.size(output)!=m.size())throw new IOException("invalid media length");}
 public void range(Manifest m,long start,long end,OutputStream output,Runnable access)throws Exception{
  for(int index=(int)(start/CHUNK);index<=end/CHUNK;index++){access.run();byte[] bytes=part(m,index);long base=(long)index*CHUNK;int from=(int)Math.max(0,start-base),to=(int)Math.min(bytes.length,end-base+1);output.write(bytes,from,to-from);output.flush();}
 }
}

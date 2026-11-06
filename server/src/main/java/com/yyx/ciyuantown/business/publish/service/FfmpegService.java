package com.yyx.ciyuantown.business.publish.service;

import com.yyx.ciyuantown.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service @Slf4j
public class FfmpegService {
 @Value("${ciyuantown.ffmpeg.path:}") private String ffmpeg;
 @Value("${ciyuantown.ffmpeg.ffprobe-path:}") private String ffprobe;
 @Getter private volatile boolean available;
 @Getter private volatile String healthMessage="尚未检测";
 @org.springframework.beans.factory.annotation.Autowired private com.yyx.ciyuantown.common.storage.MediaSettingsService settings;
 public record VideoInfo(int width,int height,long durationMs) {}
 public record Crop(double x,double y,double width,double height) {}

 @PostConstruct public void check(){if(!executable(ffmpeg)||!executable(ffprobe)){available=false;healthMessage="请配置有效的 FFMPEG_PATH 与 FFPROBE_PATH";}else if(!run(List.of(ffmpeg,"-version"),10)||!run(List.of(ffprobe,"-version"),10)){available=false;healthMessage="FFmpeg/FFprobe 版本检测失败";}else if(!run(List.of(ffmpeg,"-hide_banner","-filters"),15)){available=false;healthMessage="FFmpeg 滤镜检测失败";}else{available=true;healthMessage="FFmpeg、FFprobe 与滤镜可用";}if(!available)log.warn(healthMessage);}
 public Map<String,Object> health(){return Map.of("available",available,"message",healthMessage,"ffmpegPath",orEmpty(ffmpeg),"ffprobePath",orEmpty(ffprobe));}
 public void requireAvailable(){if(!available)throw new BizException("视频处理服务不可用，请联系管理员配置 FFmpeg");}
 public void resizeGif(Path input,Path output,int max){requireAvailable();String scale="scale=w='min(iw,"+max+")':h='min(ih,"+max+")':force_original_aspect_ratio=decrease:flags=lanczos";exec(List.of(ffmpeg,"-y","-i",input.toString(),"-filter_complex",scale+",split[a][b];[a]palettegen=reserve_transparent=1[p];[b][p]paletteuse","-loop","0",output.toString()),180,"动态图片压缩失败");}
 public void command(List<String> arguments,int timeout,String message){requireAvailable();var command=new ArrayList<String>();command.add(ffmpeg);command.addAll(arguments);exec(command,timeout,message);}
 public void watermarkGif(Path source,Path target,String text,String fontPath){requireAvailable();if(fontPath==null||fontPath.isBlank()||!Files.isRegularFile(Path.of(fontPath)))throw new BizException("水印字体未配置");String escaped=text.replace("\\","\\\\").replace(":","\\:").replace("'","\\'");String filter="drawtext=fontfile='"+fontPath.replace("\\","/").replace(":","\\:")+"':text='"+escaped+"':fontcolor=white:fontsize=w/35:box=1:boxcolor=black@0.45:x=w-tw-12:y=h-th-12,split[a][b];[a]palettegen=reserve_transparent=1[p];[b][p]paletteuse";command(List.of("-y","-i",source.toString(),"-filter_complex",filter,"-loop","0",target.toString()),180,"动态图片水印生成失败");}
 public VideoInfo probe(Path input){requireAvailable();Path log=null;Process process=null;try{
  log=Files.createTempFile("ct-probe-",".json");process=new ProcessBuilder(ffprobe,"-v","error","-select_streams","v:0","-show_entries","stream=width,height:stream_side_data=rotation:stream_tags=rotate:format=duration","-of","json",input.toString()).redirectOutput(log.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("probe timeout");}if(process.exitValue()!=0)throw new IOException("probe failed");
  var result=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(log));var stream=result.path("streams").get(0);if(stream==null)throw new IOException("no video");int w=stream.path("width").asInt(),h=stream.path("height").asInt();double rotation=stream.path("tags").path("rotate").asDouble();for(var side:stream.path("side_data_list"))if(side.has("rotation"))rotation=side.path("rotation").asDouble();if(Math.round(Math.abs(rotation)/90)%2==1){int swap=w;w=h;h=swap;}double duration=result.path("format").path("duration").asDouble();if(w<=0||h<=0||!Double.isFinite(duration)||duration<=0)throw new IOException("invalid video metadata");return new VideoInfo(w,h,Math.max(1,Math.round(duration*1000)));
 }catch(Exception e){throw new BizException("视频无法解析或格式不受支持");}finally{if(process!=null&&process.isAlive())process.destroyForcibly();if(log!=null)delete(log);}}
 public long automaticCover(Path input,Path output,VideoInfo info){var c=settings.get().images();return automaticCover(input,output,info,c.coverMaxEdge(),c.coverQuality());}
 public long automaticCover(Path input,Path output,VideoInfo info,int coverEdge,int coverQuality){requireAvailable();long start=Math.min(1000,Math.max(0,info.durationMs()/4)),end=Math.min(2000,Math.max(start,info.durationMs()-50));List<Long> times=new ArrayList<>();for(int i=0;i<6;i++)times.add(start+Math.round((end-start)*(i/5d)));double best=Double.NEGATIVE_INFINITY;long bestTime=Math.min(info.durationMs()/2,1500);Path dir=output.getParent();for(long time:times){Path candidate=dir.resolve("candidate-"+UUID.randomUUID()+".jpg");try{frame(input,candidate,time,null,info,640,360);BufferedImage image=ImageIO.read(candidate.toFile());double score=score(image);if(score>best){best=score;bestTime=time;}}catch(Exception ignored){}finally{delete(candidate);}}frameWithOptions(input,output,bestTime,null,info,coverEdge,coverQuality);return bestTime;}
 public void cover(Path input,Path output,long timeMs,Crop crop,VideoInfo info){if(timeMs<0||timeMs>=info.durationMs())throw new BizException("封面时间超出视频范围");frame(input,output,timeMs,crop,info,1280,720);}
 public void watermarkVideo(Path input,Path output,String text,String fontPath){requireAvailable();if(fontPath==null||fontPath.isBlank()||!Files.isRegularFile(Path.of(fontPath)))throw new BizException("视频水印字体未配置");String escaped=text.replace("\\","\\\\").replace(":","\\:").replace("'","\\'");String filter="drawtext=fontfile='"+fontPath.replace("\\","/").replace(":","\\:")+"':text='"+escaped+"':fontcolor=white:fontsize=h/28:box=1:boxcolor=black@0.45:boxborderw=12:x=w-tw-24:y=h-th-24";exec(List.of(ffmpeg,"-y","-i",input.toString(),"-vf",filter,"-c:v","libx264","-preset","veryfast","-crf","21","-c:a","copy",output.toString()),300,"视频水印生成失败");}
 private void frame(Path input,Path output,long timeMs,Crop crop,VideoInfo info,int outWidth,int outHeight){
  boolean webp=output.toString().toLowerCase(Locale.ROOT).endsWith(".webp");var c=webp?settings.get().images():null;
  frameWithOptions(input,output,timeMs,crop,info,webp?c.coverMaxEdge():Math.max(outWidth,outHeight),webp?c.coverQuality():78);
 }
 private void frameWithOptions(Path input,Path output,long timeMs,Crop crop,VideoInfo info,int maxEdge,int quality){
  boolean webp=output.toString().toLowerCase(Locale.ROOT).endsWith(".webp");int outWidth,outHeight;
  int sourceWidth=crop==null?info.width():Math.max(2,(int)Math.round(crop.width()*info.width())),sourceHeight=crop==null?info.height():Math.max(2,(int)Math.round(crop.height()*info.height()));double scale=Math.min(1,maxEdge/(double)Math.max(sourceWidth,sourceHeight));outWidth=Math.max(2,(int)Math.round(sourceWidth*scale));outHeight=Math.max(2,(int)Math.round(sourceHeight*scale));
  String filter="";if(crop!=null){int x=Math.max(0,(int)Math.round(crop.x()*info.width())),y=Math.max(0,(int)Math.round(crop.y()*info.height()));filter="crop="+sourceWidth+":"+sourceHeight+":"+x+":"+y+":exact=1,";}filter+="scale="+outWidth+":"+outHeight+",setsar=1";
  var command=new ArrayList<>(List.of(ffmpeg,"-y","-ss",String.format(Locale.ROOT,"%.3f",timeMs/1000d),"-i",input.toString(),"-vf",filter,"-frames:v","1"));command.addAll(webp?List.of("-quality",String.valueOf(quality)):List.of("-q:v","2"));command.add(output.toString());exec(command,45,"视频封面生成失败");
 }
 private void exec(List<String> command,int timeout,String message){Process process=null;try{Files.createDirectories(Path.of(command.get(command.size()-1)).getParent());process=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!process.waitFor(timeout,TimeUnit.SECONDS))throw new IOException("timeout");if(process.exitValue()!=0)throw new IOException("exit="+process.exitValue());}catch(InterruptedException e){Thread.currentThread().interrupt();throw new BizException(message);}catch(Exception e){throw new BizException(message);}finally{if(process!=null&&process.isAlive())process.destroyForcibly();}}
 private boolean run(List<String> command,int timeout){try{Process p=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!p.waitFor(timeout,TimeUnit.SECONDS)){p.destroyForcibly();return false;}return p.exitValue()==0;}catch(Exception e){return false;}}
 private static boolean executable(String path){try{return path!=null&&!path.isBlank()&&Files.isRegularFile(Path.of(path));}catch(Exception e){return false;}}
 private static double score(BufferedImage image){if(image==null)return Double.NEGATIVE_INFINITY;long n=0,dark=0;double sum=0,sum2=0,edge=0;int step=Math.max(1,Math.min(image.getWidth(),image.getHeight())/180);for(int y=step;y<image.getHeight();y+=step)for(int x=step;x<image.getWidth();x+=step){int rgb=image.getRGB(x,y),prev=image.getRGB(x-step,y);double value=(.2126*((rgb>>16)&255)+.7152*((rgb>>8)&255)+.0722*(rgb&255));double pv=(.2126*((prev>>16)&255)+.7152*((prev>>8)&255)+.0722*(prev&255));n++;sum+=value;sum2+=value*value;edge+=Math.abs(value-pv);if(value<18)dark++;}if(n==0||dark/(double)n>.85)return Double.NEGATIVE_INFINITY;double variance=sum2/n-(sum/n)*(sum/n);return edge/n+Math.sqrt(Math.max(0,variance));}
 private static void delete(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){}}
 private static String orEmpty(String value){return value==null?"":value;}
}

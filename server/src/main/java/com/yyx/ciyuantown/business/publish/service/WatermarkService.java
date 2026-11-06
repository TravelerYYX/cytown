package com.yyx.ciyuantown.business.publish.service;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.imageio.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.UUID;
import java.time.Duration;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;

@Service @RequiredArgsConstructor
public class WatermarkService {
 private final FfmpegService ffmpeg;
 private final RedisHotspotService redis;
 @Value("${ciyuantown.ffmpeg.font-path:}") private String fontPath;
 public Path create(Path source,String type,String text,long postId,long mediaId){return create(source,type,text,postId,mediaId,source.getParent().resolve("downloads"));}
 public Path create(Path source,String type,String text,long postId,long mediaId,Path dir){String ext="video".equals(type)?"mp4":source.getFileName().toString().substring(source.getFileName().toString().lastIndexOf('.')+1);Path target=dir.resolve(postId+"-"+mediaId+"-"+Integer.toHexString(text.hashCode())+("."+ext));if(Files.isRegularFile(target))return target;String lockKey="ct:lock:watermark:"+postId+":"+mediaId+":"+Integer.toHexString(text.hashCode()),token=redis.tryLock(lockKey,Duration.ofMinutes(10)).orElseThrow(()->new BizException("水印文件正在生成，请稍后重试"));try{if(Files.isRegularFile(target))return target;Files.createDirectories(dir);Path temporary=dir.resolve(target.getFileName()+"."+UUID.randomUUID()+(".tmp."+ext));try{if("video".equals(type))ffmpeg.watermarkVideo(source,temporary,text,fontPath);else if("gif".equals(ext))ffmpeg.watermarkGif(source,temporary,text,fontPath);else if("webp".equals(ext)&&com.yyx.ciyuantown.common.image.AnimatedWebp.isAnimated(source))com.yyx.ciyuantown.common.image.AnimatedWebp.process(source,temporary,Integer.MAX_VALUE,92,text);else watermarkImage(source,temporary,text);move(temporary,target);return target;}finally{try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}}catch(BizException e){throw e;}catch(Exception e){throw new BizException("下载文件创建失败");}finally{redis.unlock(lockKey,token);}}
 private void watermarkImage(Path source,Path target,String text){try{BufferedImage input=ImageIO.read(source.toFile());if(input==null)throw new IOException("decode");BufferedImage image=new BufferedImage(input.getWidth(),input.getHeight(),input.getColorModel().hasAlpha()?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.drawImage(input,0,0,null);int size=Math.max(18,input.getWidth()/35);g.setFont(fontPath!=null&&!fontPath.isBlank()?Font.createFont(Font.TRUETYPE_FONT,Path.of(fontPath).toFile()).deriveFont(Font.BOLD,(float)size):new Font("SansSerif",Font.BOLD,size));FontMetrics fm=g.getFontMetrics();int pad=Math.max(12,size/2),w=fm.stringWidth(text)+pad*2,h=fm.getHeight()+pad;int x=Math.max(0,input.getWidth()-w-pad),y=Math.max(h,input.getHeight()-pad);g.setColor(new Color(0,0,0,120));g.fillRoundRect(x,y-h,w,h,pad,pad);g.setColor(Color.WHITE);g.drawString(text,x+pad,y-pad/2-fm.getDescent());g.dispose();String name=target.getFileName().toString();String ext=name.substring(name.lastIndexOf('.')+1);com.yyx.ciyuantown.common.image.ImageVariants.write(image,ext,92,6,target);}catch(Exception e){try{Files.deleteIfExists(target);}catch(IOException ignored){}throw new BizException("图片水印生成失败");}}
 private static void move(Path source,Path target)throws IOException{try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}}
}

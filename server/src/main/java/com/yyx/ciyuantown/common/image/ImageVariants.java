package com.yyx.ciyuantown.common.image;
import com.yyx.ciyuantown.common.storage.MediaSettingsService;
import com.yyx.ciyuantown.business.publish.service.FfmpegService;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class ImageVariants {
 private final FfmpegService ffmpeg;
 public record Result(Path preview,Path cover,int width,int height) {}
 public Result create(Path original,String ext,Path directory,MediaSettingsService.Images options) {
  try{if("webp".equals(ext)&&AnimatedWebp.isAnimated(original)){Path preview=directory.resolve("preview.webp"),cover=directory.resolve("cover.webp");var animation=AnimatedWebp.process(original,preview,options.previewMaxEdge(),options.previewQuality(),null);if(Math.max(animation.width(),animation.height())<=options.previewMaxEdge()&&Files.size(preview)>=Files.size(original))Files.copy(original,preview,StandardCopyOption.REPLACE_EXISTING);write(resize(animation.first(),options.coverMaxEdge()),"webp",options.coverQuality(),options.pngCompression(),cover);return new Result(preview,cover,animation.width(),animation.height());}
   BufferedImage image=ImageIO.read(original.toFile());if(image==null)throw new BizException("图片无法解码");if((long)image.getWidth()*image.getHeight()>100_000_000)throw new BizException("图片像素数量过大");
   Path preview=directory.resolve("preview."+ext),cover=directory.resolve("cover.webp");
   if("gif".equals(ext)){ffmpeg.resizeGif(original,preview,options.previewMaxEdge());}
   else write(resize(image,options.previewMaxEdge()),ext,options.previewQuality(),options.pngCompression(),preview);
   if(Math.max(image.getWidth(),image.getHeight())<=options.previewMaxEdge()&&Files.size(preview)>=Files.size(original))Files.copy(original,preview,StandardCopyOption.REPLACE_EXISTING);
   write(resize(image,options.coverMaxEdge()),"webp",options.coverQuality(),options.pngCompression(),cover);
   return new Result(preview,cover,image.getWidth(),image.getHeight());
  }catch(BizException e){throw e;}catch(Exception e){throw new BizException("图片压缩失败，请检查图片编码器");}
 }
 public static BufferedImage resize(BufferedImage image,int max){double scale=Math.min(1,max/(double)Math.max(image.getWidth(),image.getHeight()));int w=Math.max(1,(int)Math.round(image.getWidth()*scale)),h=Math.max(1,(int)Math.round(image.getHeight()*scale));
  var output=new BufferedImage(w,h,image.getColorModel().hasAlpha()?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);Graphics2D g=output.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);g.drawImage(image,0,0,w,h,null);g.dispose();return output;
 }
 public static void write(BufferedImage image,String ext,int quality,int pngLevel,Path path)throws Exception{String format="jpeg".equals(ext)?"jpg":ext;var writers=ImageIO.getImageWritersByFormatName(format);if(!writers.hasNext())throw new BizException("当前环境不支持"+format+"编码");var writer=writers.next();
  if("jpg".equals(format)&&image.getColorModel().hasAlpha()){var rgb=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_RGB);var g=rgb.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,rgb.getWidth(),rgb.getHeight());g.drawImage(image,0,0,null);g.dispose();image=rgb;}
  Files.createDirectories(path.getParent());try(ImageOutputStream out=ImageIO.createImageOutputStream(path.toFile())){writer.setOutput(out);var p=writer.getDefaultWriteParam();if(p.canWriteCompressed()){p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);var types=p.getCompressionTypes();if(types!=null&&types.length>0)p.setCompressionType(types[0]);p.setCompressionQuality("png".equals(format)?1-pngLevel/9f:quality/100f);}writer.write(null,new IIOImage(image,null,null),p);}finally{writer.dispose();}
 }

}

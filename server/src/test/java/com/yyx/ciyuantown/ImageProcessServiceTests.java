package com.yyx.ciyuantown;

import com.yyx.ciyuantown.common.image.ImageProcessService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Comparator;
import static org.junit.jupiter.api.Assertions.*;

class ImageProcessServiceTests {
 @Test void reportsOriginalAndActualWebpBytesAndPreservesAlpha() throws Exception {
  Path root=Files.createTempDirectory("checkin-webp-");
  try{
   var image=new BufferedImage(40,40,BufferedImage.TYPE_INT_ARGB);image.setRGB(20,20,0xff3388ff);
   var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);var file=new MockMultipartFile("file","frame.png","image/png",bytes.toByteArray());
   var service=new ImageProcessService();ReflectionTestUtils.setField(service,"uploadDir",root.toString());var result=service.processDetailed(file,"webp",85);
   Path output=root.resolve(result.url().substring("/uploads/".length()));assertEquals(file.getSize(),result.originalBytes());assertEquals(Files.size(output),result.processedBytes());assertEquals(0,ImageIO.read(output.toFile()).getRGB(0,0)>>>24);
  }finally{try(var paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
 }
 @Test void encodesWebpJpegAndPng() throws Exception {
  Path root=Files.createTempDirectory("p3-images-");
  try {
   var image=new BufferedImage(96,64,BufferedImage.TYPE_INT_ARGB);var graphics=image.createGraphics();graphics.setColor(new Color(20,160,220,180));graphics.fillRect(0,0,96,64);graphics.dispose();
   var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
   var file=new MockMultipartFile("file","sample.png","image/png",bytes.toByteArray());var service=new ImageProcessService();ReflectionTestUtils.setField(service,"uploadDir",root.toString());
   for(String format:new String[]{"webp","jpg","png"}){String url=service.process(file,format,70);Path output=root.resolve(url.substring("/uploads/".length()));assertTrue(Files.size(output)>0);assertNotNull(ImageIO.read(output.toFile()));}
  } finally {try(var paths=Files.walk(root)){paths.sorted((a,b)->b.compareTo(a)).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
 }
}

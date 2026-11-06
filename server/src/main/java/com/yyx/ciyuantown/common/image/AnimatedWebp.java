package com.yyx.ciyuantown.common.image;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Decode individual ANMF frames through libwebp, then reassemble a resized animated RIFF. */
public final class AnimatedWebp {
 private AnimatedWebp(){}
 private record Chunk(String type,byte[] data){}
 public record Result(BufferedImage first,int width,int height){}
 public static boolean isAnimated(Path source)throws IOException{try(var input=Files.newInputStream(source)){byte[] h=input.readNBytes(32);return h.length>20&&new String(h,12,4,StandardCharsets.US_ASCII).equals("VP8X")&&(h[20]&2)!=0;}}
 public static Result process(Path source,Path output,int edge,int quality,String watermark)throws Exception{
  var chunks=parse(Files.readAllBytes(source),12);var header=chunks.stream().filter(c->c.type().equals("VP8X")).findFirst().orElseThrow().data();int width=u24(header,4)+1,height=u24(header,7)+1;
  if((long)width*height>100_000_000)throw new IOException("canvas too large");
  var canvas=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);BufferedImage first=null;var frames=new ArrayList<Chunk>();int resultWidth=0,resultHeight=0;
  for(var chunk:chunks){if(!chunk.type().equals("ANMF"))continue;byte[] data=chunk.data();int x=u24(data,0)*2,y=u24(data,3)*2,w=u24(data,6)+1,h=u24(data,9)+1,duration=u24(data,12),flags=data[15]&255;if(x+w>width||y+h>height)throw new IOException("invalid frame bounds");
   var encoded=new ArrayList<Chunk>();byte[] vp8x=new byte[10];vp8x[0]=16;put24(vp8x,4,w-1);put24(vp8x,7,h-1);encoded.add(new Chunk("VP8X",vp8x));encoded.addAll(parse(data,16));BufferedImage frame=ImageIO.read(new ByteArrayInputStream(riff(encoded)));if(frame==null)throw new IOException("frame decode");
   var g=canvas.createGraphics();g.setComposite((flags&2)!=0?AlphaComposite.Src:AlphaComposite.SrcOver);g.drawImage(frame,x,y,null);g.dispose();if(first==null){first=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);var copy=first.createGraphics();copy.drawImage(canvas,0,0,null);copy.dispose();}
   BufferedImage resized=ImageVariants.resize(canvas,edge);resultWidth=resized.getWidth();resultHeight=resized.getHeight();if(watermark!=null&&!watermark.isEmpty()){var ink=resized.createGraphics();int size=Math.max(12,resultWidth/35);ink.setFont(new Font("SansSerif",Font.BOLD,size));int textWidth=ink.getFontMetrics().stringWidth(watermark);ink.setColor(new Color(0,0,0,120));ink.fillRoundRect(Math.max(0,resultWidth-textWidth-24),Math.max(0,resultHeight-size-24),textWidth+20,size+16,10,10);ink.setColor(Color.WHITE);ink.drawString(watermark,Math.max(8,resultWidth-textWidth-14),Math.max(size,resultHeight-16));ink.dispose();}
   Path temp=Files.createTempFile(output.getParent(),"webp-frame-",".webp");byte[] pixels;try{ImageVariants.write(resized,"webp",quality,6,temp);var staticChunks=parse(Files.readAllBytes(temp),12).stream().filter(c->Set.of("ALPH","VP8 ","VP8L").contains(c.type())).toList();pixels=chunks(staticChunks);}finally{Files.deleteIfExists(temp);}
   byte[] frameHeader=new byte[16];put24(frameHeader,6,resultWidth-1);put24(frameHeader,9,resultHeight-1);put24(frameHeader,12,duration);frameHeader[15]=2;var body=new ByteArrayOutputStream();body.write(frameHeader);body.write(pixels);frames.add(new Chunk("ANMF",body.toByteArray()));
   if((flags&1)!=0){var clear=canvas.createGraphics();clear.setComposite(AlphaComposite.Clear);clear.fillRect(x,y,w,h);clear.dispose();}
  }
  if(frames.isEmpty())throw new IOException("no animation frames");byte[] resizedHeader=new byte[10];resizedHeader[0]=18;put24(resizedHeader,4,resultWidth-1);put24(resizedHeader,7,resultHeight-1);var animation=chunks.stream().filter(c->c.type().equals("ANIM")).findFirst().orElseThrow();var result=new ArrayList<Chunk>();result.add(new Chunk("VP8X",resizedHeader));result.add(animation);result.addAll(frames);Files.write(output,riff(result));return new Result(first,width,height);
 }
 private static java.util.List<Chunk> parse(byte[] data,int start)throws IOException{var chunks=new ArrayList<Chunk>();for(int offset=start;offset+8<=data.length;){String type=new String(data,offset,4,StandardCharsets.US_ASCII);long size=(data[offset+4]&255L)|((data[offset+5]&255L)<<8)|((data[offset+6]&255L)<<16)|((data[offset+7]&255L)<<24);if(size>data.length-offset-8)throw new IOException("invalid RIFF chunk");chunks.add(new Chunk(type,Arrays.copyOfRange(data,offset+8,offset+8+(int)size)));offset+=8+(int)size+((int)size&1);}return chunks;}
 private static byte[] chunks(java.util.List<Chunk> chunks)throws IOException{var out=new ByteArrayOutputStream();for(var chunk:chunks){out.write(chunk.type().getBytes(StandardCharsets.US_ASCII));int size=chunk.data().length;for(int i=0;i<4;i++)out.write(size>>(i*8));out.write(chunk.data());if((size&1)!=0)out.write(0);}return out.toByteArray();}
 private static byte[] riff(java.util.List<Chunk> chunks)throws IOException{byte[] body=chunks(chunks);var out=new ByteArrayOutputStream();out.write("RIFF".getBytes(StandardCharsets.US_ASCII));int size=body.length+4;for(int i=0;i<4;i++)out.write(size>>(i*8));out.write("WEBP".getBytes(StandardCharsets.US_ASCII));out.write(body);return out.toByteArray();}
 private static int u24(byte[] data,int offset){return (data[offset]&255)|((data[offset+1]&255)<<8)|((data[offset+2]&255)<<16);}
 private static void put24(byte[] data,int offset,int value){for(int i=0;i<3;i++)data[offset+i]=(byte)(value>>(i*8));}
}

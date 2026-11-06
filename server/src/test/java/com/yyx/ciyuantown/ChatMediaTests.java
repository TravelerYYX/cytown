package com.yyx.ciyuantown;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.chat.*;
import com.yyx.ciyuantown.business.publish.service.FfmpegService;
import com.yyx.ciyuantown.common.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(ChatTests.Config.class)
class ChatMediaTests {
 @Autowired JdbcTemplate db;@Autowired ChatService chat;@Autowired ChatSettings settings;@Autowired PlatformTransactionManager transactions;
 @TempDir Path root;ChatMedia media;ChatVault vault;
 @BeforeEach void setup()throws Exception{
  var fixture=new ChatTests();fixture.db=db;fixture.setup();var storage=mock(StorageService.class);var config=new StorageService.Config("test","local",root.toString(),"","","","","","");when(storage.active()).thenReturn(config);Map<String,Path> objects=new HashMap<>();
  when(storage.put(any(),anyString(),any(),anyString())).thenAnswer(call->{String key=call.getArgument(1),url="/cipher/"+key;Path file=root.resolve(UUID.randomUUID()+".bin");Files.copy(call.getArgument(2,Path.class),file);objects.put(url,file);return new StorageService.Stored("id","test",key,url,"application/octet-stream",Files.size(file));});when(storage.materialize(anyString(),any())).thenAnswer(call->objects.get(call.getArgument(0)));
  vault=new ChatVault(storage,new ObjectMapper());ReflectionTestUtils.setField(vault,"master","private-chat-test-key");media=new ChatMedia(db,new ObjectMapper(),chat,settings,vault,mock(ResourceCleanupService.class),mock(FfmpegService.class),storage,transactions);
 }
 @AfterEach void close(){media.close();}
 byte[] png(int width,int height)throws Exception{var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setRGB(x,y,0x80336699);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
 byte[] contents(ChatService.Asset a)throws Exception{var row=chat.assetRow(Long.parseLong(a.id()));var output=new ByteArrayOutputStream();vault.range(vault.parse(row.get("manifest_json").toString()),0,a.sizeBytes()-1,output,()->{});return output.toByteArray();}
 @Test void originalPreservesExactBytesAndCompressedPngRetainsAlphaAndActualDimensions()throws Exception{
  byte[] input=png(3000,1000);var file=new MockMultipartFile("file","input.png","image/png",input);var original=media.image(1,file,true,"image");assertArrayEquals(input,contents(original));assertEquals(3000,original.width());
  var compressed=media.image(1,file,false,"image");assertEquals("png",compressed.extension());assertEquals("image/png",compressed.mime());assertEquals(2560,compressed.width());assertEquals(853,compressed.height());var actual=ImageIO.read(new ByteArrayInputStream(contents(compressed)));assertTrue(actual.getColorModel().hasAlpha());assertEquals(128,actual.getRGB(100,100)>>>24);
 }
 @Test void imageConfigurationAndSourceOwnershipAreIndependent()throws Exception{
  var defaults=settings.get();settings.save(new ChatSettings.Config(120,new ChatSettings.Images(256,80,6),defaults.videos()));var a=media.image(1,new MockMultipartFile("file","input.png","image/png",png(400,200)),false,"image");assertEquals(256,a.width());assertEquals(128,a.height());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM system_config WHERE config_key='media_processing'",Integer.class));assertDoesNotThrow(()->media.access(1,Long.parseLong(a.id())));assertThrows(Exception.class,()->media.access(2,Long.parseLong(a.id())));
 }
}

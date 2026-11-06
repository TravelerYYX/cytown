package com.yyx.ciyuantown;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.business.chat.ChatVault;
import com.yyx.ciyuantown.common.storage.StorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ChatVaultTests {
 @TempDir Path root;
 @Test void publicObjectsAreEncryptedAndRangesDecryptWithoutWholeFileDownload()throws Exception{
  var storage=mock(StorageService.class);var config=new StorageService.Config("test","local",root.toString(),"","","","","","");when(storage.active()).thenReturn(config);
  Map<String,Path> objects=new HashMap<>();
  when(storage.put(any(),anyString(),any(),anyString())).thenAnswer(call->{String key=call.getArgument(1),url="/public/"+key;Path target=root.resolve(UUID.randomUUID()+".bin");Files.copy(call.getArgument(2,Path.class),target);objects.put(url,target);return new StorageService.Stored("object","test",key,url,"application/octet-stream",Files.size(target));});
  when(storage.materialize(anyString(),any())).thenAnswer(call->objects.get(call.getArgument(0)));
  var vault=new ChatVault(storage,new ObjectMapper());ReflectionTestUtils.setField(vault,"master","chat-test-master-never-production");
  byte[] source=new byte[ChatVault.CHUNK+987];new Random(123).nextBytes(source);Path input=root.resolve("original");Files.write(input,source);var manifest=vault.store(input,"version-unique");assertEquals(2,manifest.parts().size());assertFalse(Arrays.equals(Arrays.copyOfRange(source,0,ChatVault.CHUNK),Files.readAllBytes(objects.get(manifest.parts().getFirst().url()))));
  var out=new ByteArrayOutputStream();vault.range(manifest,ChatVault.CHUNK-10,ChatVault.CHUNK+100,out,()->{});assertArrayEquals(Arrays.copyOfRange(source,ChatVault.CHUNK-10,ChatVault.CHUNK+101),out.toByteArray());
  Path encrypted=objects.get(manifest.parts().getFirst().url());byte[] corrupt=Files.readAllBytes(encrypted);corrupt[8]^=1;Files.write(encrypted,corrupt);assertThrows(Exception.class,()->vault.part(manifest,0));
 }
}

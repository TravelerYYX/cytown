package com.yyx.ciyuantown.common.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;

class StorageDeletionAdapterTests {
 StorageService storage(String provider)throws Exception{
  var db=mock(JdbcTemplate.class);var json=new ObjectMapper();
  var service=new StorageService(db,json);
  org.springframework.test.util.ReflectionTestUtils.setField(service,"encryptionKey","adapter-test-key");
  String access=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"encrypt","fixture-access");
  String secret=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"encrypt","fixture-secret");
  var config=new StorageService.Config("original-profile",provider,"","https://oss.example.invalid","z0","test-bucket",access,secret,"https://media.example.invalid");
  when(db.queryForList(contains("media_storage_config"),eq(String.class),eq("original-profile"))).thenReturn(List.of(json.writeValueAsString(config)));
  return service;
 }
 @Test void ossDeletesFromOriginalBucketAndClosesClient()throws Exception{
  var client=mock(OSS.class);
  try(var builders=mockConstruction(OSSClientBuilder.class,(builder,context)->when(builder.build(anyString(),anyString(),anyString())).thenReturn(client))){
   storage("oss").deleteObject("original-profile","posts/file.webp");
   verify(client).deleteObject("test-bucket","posts/file.webp");verify(client).shutdown();assertEquals(1,builders.constructed().size());
  }
 }
 @Test void ossFailurePropagatesForDurableRetryAndStillClosesClient()throws Exception{
  var client=mock(OSS.class);doThrow(new IllegalStateException("fixture failure")).when(client).deleteObject(anyString(),anyString());
  try(var builders=mockConstruction(OSSClientBuilder.class,(builder,context)->when(builder.build(anyString(),anyString(),anyString())).thenReturn(client))){
   assertThrows(IllegalStateException.class,()->storage("oss").deleteObject("original-profile","posts/file.webp"));verify(client).shutdown();
  }
 }
 @Test void qiniuMissingObjectIsSuccessfulAndOtherFailuresPropagate()throws Exception{
  var missing=mock(QiniuException.class);when(missing.code()).thenReturn(612);
  try(var managers=mockConstruction(BucketManager.class,(manager,context)->doThrow(missing).when(manager).delete(anyString(),anyString()))){
   assertDoesNotThrow(()->storage("qiniu").deleteObject("original-profile","posts/file.webp"));
   verify(managers.constructed().getFirst()).delete("test-bucket","posts/file.webp");
  }
  var unavailable=mock(QiniuException.class);when(unavailable.code()).thenReturn(503);
  try(var managers=mockConstruction(BucketManager.class,(manager,context)->doThrow(unavailable).when(manager).delete(anyString(),anyString()))){
   assertThrows(QiniuException.class,()->storage("qiniu").deleteObject("original-profile","posts/file.webp"));
  }
 }
}

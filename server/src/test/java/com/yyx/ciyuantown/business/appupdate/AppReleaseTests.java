package com.yyx.ciyuantown.business.appupdate;

import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.StorageService;
import com.yyx.ciyuantown.common.interceptor.*;
import com.yyx.ciyuantown.common.util.JwtUtil;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(AppReleaseTests.Config.class)
class AppReleaseTests {
 @TestConfiguration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:appupdates;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
  @Bean JdbcTemplate db(DataSource source){return new JdbcTemplate(source);}
  @Bean PlatformTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
  @Bean StorageService storage(){return mock(StorageService.class);}
  @Bean AppReleaseService releases(JdbcTemplate db,StorageService storage){return new AppReleaseService(new com.yyx.ciyuantown.common.storage.ResourceCleanupService(db,new ObjectMapper(),storage,new com.yyx.ciyuantown.common.storage.GlobalSettingsService(db),transactionManager(db.getDataSource())),db,storage);}
 }
 @Autowired JdbcTemplate db;
 @Autowired StorageService storage;
 @Autowired AppReleaseService service;
 @TempDir Path temporary;
 @BeforeEach void setup()throws Exception {
  db.execute("DROP ALL OBJECTS");
  for(String statement:Files.readString(Path.of("doc/sql/p24-app-update.sql")).replaceAll("(?m)^--.*$","").split(";"))
   if(!statement.isBlank()&&!statement.trim().startsWith("SET NAMES"))db.execute(statement);
  com.yyx.ciyuantown.common.storage.CleanupTestSchema.create(db);
  reset(storage);
  doAnswer(inv->{storage.delete("/uploads/"+inv.getArgument(1));return null;}).when(storage).deleteObject(anyString(),anyString());
  when(storage.put(any(),anyString(),any(),anyString())).thenAnswer(inv->{Path p=inv.getArgument(2);return new StorageService.Stored("id","local","file","/uploads/test-"+UUID.randomUUID()+".apk","application/vnd.android.package-archive",Files.size(p));});
 }
 AppReleaseService.Release draft(long code){return service.create(new AppReleaseService.Draft("1.0."+(code-100),code,"更新说明",null));}
 AppReleaseService.Release uploaded(long code)throws Exception {var r=draft(code);return service.upload(r.id(),new MockMultipartFile("file","release.apk","application/octet-stream",apk(code)));}
 @Test void draftIsPrivateAndDefaultsToOptional()throws Exception {
  var r=uploaded(101);assertFalse(r.forceUpdate());assertFalse(service.check(100).hasUpdate());
  assertEquals(101,r.apkVersionCode());assertEquals("com.example.ct",r.packageName());
  assertEquals(64,r.sha256().length());assertEquals(apk(101).length,r.sizeBytes());
 }
 @Test void publishSwitchesCurrentAndWithdrawNeverFallsBack()throws Exception {
  var first=uploaded(101);service.publish(first.id());assertEquals(first.id(),service.check(100).release().id());
  assertFalse(service.check(101).hasUpdate());assertFalse(service.check(1000).hasUpdate());
  var second=uploaded(102);service.publish(second.id());assertFalse(service.require(first.id()).current());
  assertEquals(2,service.list().size());assertEquals(second.id(),service.check(99).release().id());
  service.force(second.id(),true);assertTrue(service.check(100).release().forceUpdate());
  service.force(second.id(),false);assertFalse(service.check(100).release().forceUpdate());
  service.withdraw(second.id());assertFalse(service.check(1).hasUpdate());assertEquals(2,service.list().size());
 }
 @Test void publishedBytesImmutableAndVersionsMonotonic()throws Exception {
  var first=uploaded(102);service.publish(first.id());
  assertThrows(BizException.class,()->service.save(first.id(),new AppReleaseService.Draft("1.0.3",103L,"修改",false)));
  assertThrows(BizException.class,()->service.upload(first.id(),new MockMultipartFile("file","x.apk","",apk(103))));
  var lower=uploaded(101);assertThrows(BizException.class,()->service.publish(lower.id()));
  assertEquals(first.id(),service.check(1).release().id());
  var empty=draft(104);assertThrows(BizException.class,()->service.publish(empty.id()));
 }
 @Test void deletesDraftWithoutPackageAndRejectsMissingVersion() {
  var r=draft(101);service.delete(r.id());
  assertTrue(service.list().isEmpty());assertFalse(service.check(1).hasUpdate());
  assertThrows(BizException.class,()->service.delete(r.id()));
  verify(storage,never()).delete(anyString());
 }
 @Test void deletesHistoricalAndWithdrawnVersionsWithTheirPackages()throws Exception {
  var first=uploaded(101);service.publish(first.id());
  var second=uploaded(102);service.publish(second.id());
  service.delete(first.id());verify(storage).delete(first.packageUrl());
  assertEquals(second.id(),service.check(1).release().id());
  service.withdraw(second.id());service.delete(second.id());verify(storage).delete(second.packageUrl());
  assertTrue(service.list().isEmpty());assertFalse(service.check(1).hasUpdate());
 }
 @Test void currentVersionMustBeWithdrawnBeforeDeletion()throws Exception {
  var r=uploaded(101);service.publish(r.id());
  assertEquals("当前生效版本不能删除，请先下架",assertThrows(BizException.class,()->service.delete(r.id())).getMessage());
  assertEquals(r.id(),service.check(1).release().id());verify(storage,never()).delete(anyString());
 }
 @Test void failedPackageDeletionKeepsDurableTaskForRetry()throws Exception {
  var r=uploaded(101);doThrow(new BizException("媒体删除失败")).when(storage).delete(r.packageUrl());
  service.delete(r.id());
  assertTrue(service.list().isEmpty());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM media_cleanup_task WHERE completed=FALSE",Integer.class));
  doNothing().when(storage).delete(r.packageUrl());
  db.update("UPDATE media_cleanup_task SET next_attempt_at=CURRENT_TIMESTAMP");
  new com.yyx.ciyuantown.common.storage.ResourceCleanupService(db,new ObjectMapper(),storage,new com.yyx.ciyuantown.common.storage.GlobalSettingsService(db),new DataSourceTransactionManager(db.getDataSource())).retry();
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM media_cleanup_task WHERE completed=FALSE",Integer.class));
 }
 @Test void deletionRemovesActualLocalApkAndMediaRegistry()throws Exception {
  // Exercise the real storage implementation against isolated H2 tables and temporary files.
  for(String statement:Files.readString(Path.of("doc/sql/p12-media-storage.sql")).split(";"))
   if(statement.trim().startsWith("CREATE TABLE IF NOT EXISTS media_storage_config")||statement.trim().startsWith("CREATE TABLE IF NOT EXISTS media_object"))db.execute(statement);
  var localStorage=new StorageService(db,new ObjectMapper());
  var config=new StorageService.Config("deletion-test","local",temporary.toString(),"","","","","","");
  var source=Files.write(temporary.resolve("source.apk"),apk(101));
  var object=localStorage.put(config,"app-updates/release.apk",source,"application/vnd.android.package-archive");
  var r=draft(101);db.update("UPDATE app_release SET package_url=? WHERE id=?",object.url(),r.id());
  when(storage.find(anyString())).thenAnswer(inv->localStorage.find(inv.getArgument(0)));
  doAnswer(inv->{localStorage.deleteObject(inv.getArgument(0),inv.getArgument(1));return null;}).when(storage).deleteObject(anyString(),anyString());
  var packageFile=localStorage.localPath(config,object.key());assertTrue(Files.exists(packageFile));
  service.delete(r.id());
  assertFalse(Files.exists(packageFile));assertTrue(localStorage.find(object.url()).isEmpty());
  assertTrue(service.list().isEmpty());assertTrue(Files.exists(source));
 }
 @Test void concurrentPublishAndDeleteNeverLeaveDanglingCurrentVersion()throws Exception {
  var r=uploaded(101);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {
   var publish=pool.submit(()->{start.await();try{service.publish(r.id());return true;}catch(BizException e){return false;}});
   var deletion=pool.submit(()->{start.await();try{service.delete(r.id());return true;}catch(BizException e){return false;}});
   start.countDown();boolean published=publish.get(10,TimeUnit.SECONDS),deleted=deletion.get(10,TimeUnit.SECONDS);
   assertNotEquals(published,deleted);
   assertEquals(published,service.check(1).hasUpdate());assertEquals(published?1:0,service.list().size());
   if(published){assertEquals(r.id(),service.check(1).release().id());verify(storage,never()).delete(anyString());}
   else {assertNull(db.queryForObject("SELECT active_release_id FROM app_update_channel WHERE id=1",Long.class));verify(storage).delete(r.packageUrl());}
  }finally{pool.shutdownNow();}
 }
 @Test void deleteEndpointRemovesVersion()throws Exception {
  var r=uploaded(101);
  var mvc=MockMvcBuilders.standaloneSetup(new AppReleaseController(service)).build();
  mvc.perform(delete("/app-releases/{id}",r.id())).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000"));
  assertTrue(service.list().isEmpty());verify(storage).delete(r.packageUrl());
 }
 @Test void packageMismatchAndMalformedArchivesRejected()throws Exception {
  var r=uploaded(101);
  assertThrows(BizException.class,()->service.save(r.id(),new AppReleaseService.Draft("wrong",102L,"说明",false)));
  assertThrows(BizException.class,()->service.upload(r.id(),new MockMultipartFile("file","fake.apk","",new byte[]{1,2,3})));
  assertThrows(BizException.class,()->service.upload(r.id(),new MockMultipartFile("file","fake.zip","",apk(101))));
  assertEquals(101,service.require(r.id()).versionCode());
  var oversized=mock(org.springframework.web.multipart.MultipartFile.class);when(oversized.getOriginalFilename()).thenReturn("huge.apk");when(oversized.getSize()).thenReturn(AppReleaseService.MAX_APK_BYTES+1);
  assertThrows(BizException.class,()->AppReleaseService.validateFile(oversized));
  when(oversized.getSize()).thenReturn(AppReleaseService.MAX_APK_BYTES);assertDoesNotThrow(()->AppReleaseService.validateFile(oversized));
 }
 @Test void shaMatchesActualBytes()throws Exception {Path file=Files.writeString(temporary.resolve("sha"),"abc");assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",AppReleaseService.sha256(file));}
 @Test void concurrentPublishOfSameVersionOnlySucceedsOnce()throws Exception {
  var a=uploaded(101);var b=uploaded(101);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {
   var first=pool.submit(()->{start.await();try{service.publish(a.id());return true;}catch(BizException e){return false;}});
   var second=pool.submit(()->{start.await();try{service.publish(b.id());return true;}catch(BizException e){return false;}});
   start.countDown();assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
   assertEquals(1,service.list().stream().filter(AppReleaseService.Release::current).count());
  }finally{pool.shutdownNow();}
 }
 @Test void localStorageSupportsActualHttpRange()throws Exception {
  byte[] bytes=apk(101);Path file=Files.write(temporary.resolve("range.apk"),bytes);
  var config=new StorageService.Config("fixture","local",temporary.toString(),"","","","","","");
  when(storage.config("fixture")).thenReturn(config);
  when(storage.findByKey("fixture","app-updates/test.apk")).thenReturn(Optional.of(new StorageService.Stored("id","fixture","app-updates/test.apk","/uploads/storage/fixture/app-updates/test.apk","application/vnd.android.package-archive",bytes.length)));
  when(storage.localPath(config,"app-updates/test.apk")).thenReturn(file);
  var mvc=MockMvcBuilders.standaloneSetup(new com.yyx.ciyuantown.common.storage.LocalMediaController(storage)).build();
  mvc.perform(get("/uploads/storage/fixture/app-updates/test.apk").header("Range","bytes=0-7"))
   .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 0-7/"+bytes.length)).andExpect(content().bytes(Arrays.copyOf(bytes,8)));
 }
 @Test @EnabledIfEnvironmentVariable(named="CT_TEST_APK",matches=".+") void readsRealSignedApk() {
  var apk=ApkMetadata.read(Path.of(System.getenv("CT_TEST_APK")));
  assertEquals("com.example.ct",apk.packageName());assertEquals(101,apk.versionCode());assertEquals("1.0.1",apk.versionName());
 }
 @Test void publicChecksAndAdminReadOnlyBoundary()throws Exception {
  var jwt=mock(JwtUtil.class);var users=mock(AdminUserMapper.class);var redis=mock(RedisHotspotService.class);var json=new ObjectMapper();
  when(jwt.verifyAndGetUserId("read","access")).thenReturn(1L);when(jwt.userType("read")).thenReturn(1);
  var user=new AdminUser();user.setId(1L);user.setStatus(0);user.setTokenVersion(0);when(users.selectById(1L)).thenReturn(user);
  var mvc=MockMvcBuilders.standaloneSetup(new AppReleaseController(service)).addMappedInterceptors(new String[]{"/app-releases","/app-releases/**"},new AuthInterceptor(jwt,json,users,redis),new ReadOnlyInterceptor(jwt,json)).build();
  mvc.perform(get("/app/update/check").param("versionCode","100")).andExpect(jsonPath("$.code").value("0000"));
  mvc.perform(get("/app-releases")).andExpect(jsonPath("$.code").value("8888"));
  mvc.perform(get("/app-releases").header("Authorization","Bearer read")).andExpect(jsonPath("$.code").value("0000"));
  mvc.perform(post("/app-releases").header("Authorization","Bearer read").contentType("application/json").content("{\"versionName\":\"1\",\"versionCode\":101,\"notes\":\"test\"}")).andExpect(jsonPath("$.msg").value("该账号为只读账号，无操作权限"));
  assertEquals(0,service.list().size());
  var r=uploaded(101);
  mvc.perform(delete("/app-releases/{id}",r.id())).andExpect(jsonPath("$.code").value("8888"));
  mvc.perform(delete("/app-releases/{id}",r.id()).header("Authorization","Bearer read")).andExpect(jsonPath("$.msg").value("该账号为只读账号，无操作权限"));
  assertEquals(1,service.list().size());verify(storage,never()).delete(anyString());
 }
 // Minimal binary XML fixture exercises the real chunk/string/attribute parser, not a mocked metadata result.
 static byte[] apk(long code)throws Exception {
  var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));zip.write(manifest(code));zip.closeEntry();zip.putNextEntry(new ZipEntry("classes.dex"));zip.write("dex\n035\0".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}return out.toByteArray();
 }
 static byte[] manifest(long code)throws Exception {
  String[] values={"manifest","package","versionCode","versionName","http://schemas.android.com/apk/res/android","com.example.ct","1.0."+(code-100)};
  var strings=new ByteArrayOutputStream();var offsets=new ArrayList<Integer>();
  for(String value:values){offsets.add(strings.size());byte[] raw=value.getBytes(StandardCharsets.UTF_8);strings.write(value.length());strings.write(raw.length);strings.write(raw);strings.write(0);}
  while(strings.size()%4!=0)strings.write(0);
  int poolSize=28+4*values.length+strings.size(),elementSize=36+3*20,total=8+poolSize+elementSize;
  var b=ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
  b.putShort((short)3).putShort((short)8).putInt(total);
  b.putShort((short)1).putShort((short)28).putInt(poolSize).putInt(values.length).putInt(0).putInt(256).putInt(28+4*values.length).putInt(0);
  offsets.forEach(b::putInt);b.put(strings.toByteArray());
  b.putShort((short)0x102).putShort((short)16).putInt(elementSize).putInt(1).putInt(-1).putInt(-1).putInt(0).putShort((short)20).putShort((short)20).putShort((short)3).putShort((short)0).putShort((short)0).putShort((short)0);
  attribute(b,-1,1,3,5);attribute(b,4,2,16,(int)code);attribute(b,4,3,3,6);return b.array();
 }
 static void attribute(ByteBuffer b,int ns,int name,int type,int value){b.putInt(ns).putInt(name).putInt(-1).putShort((short)8).put((byte)0).put((byte)type).putInt(value);}
}

package com.yyx.ciyuantown.business.appupdate;

import com.yyx.ciyuantown.common.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in real multipart transfer; isolated H2/Tomcat and temp storage, no production DB. */
@EnabledIfEnvironmentVariable(named="CT_TEST_LARGE_APK",matches="true")
@ActiveProfiles("app-update-large-test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,classes=AppReleaseLargeUploadTests.Config.class,
 properties={"spring.servlet.multipart.max-file-size=500MB","spring.servlet.multipart.max-request-size=510MB","server.address=127.0.0.1","ciyuantown.jwt.secret=isolated-app-update-test-secret-never-production"})
class AppReleaseLargeUploadTests {
 @Configuration @Profile("app-update-large-test") @EnableAutoConfiguration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:largeappupdate;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
  @Bean JdbcTemplate db(DataSource source)throws Exception {
   var db=new JdbcTemplate(source);
   for(String statement:Files.readString(Path.of("doc/sql/p24-app-update.sql")).replaceAll("(?m)^--.*$","").split(";"))if(!statement.isBlank()&&!statement.trim().startsWith("SET NAMES"))db.execute(statement);
   return db;
  }
  @Bean PlatformTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
  @Bean StorageService storage(){
   var s=mock(StorageService.class);when(s.put(any(),anyString(),any(),anyString())).thenAnswer(i->new StorageService.Stored("test","test","test.apk","/uploads/test.apk","application/vnd.android.package-archive",Files.size(i.getArgument(2))));return s;
  }
  @Bean AppReleaseService service(JdbcTemplate db,StorageService storage){return new AppReleaseService(mock(com.yyx.ciyuantown.common.storage.ResourceCleanupService.class),db,storage);}
  @Bean AppReleaseController controller(AppReleaseService service){return new AppReleaseController(service);}
 }
 @LocalServerPort int port;
 @Autowired AppReleaseService releases;
 @TempDir Path temporary;
 @Test void transfersExactly500MiBThroughRealMultipartParser()throws Exception {
  var draft=releases.create(new AppReleaseService.Draft("1.0.1",101L,"500MB上传验证",false));
  Path apk=temporary.resolve("large.apk");String paddingName="padding.bin";
  long overhead=AppReleaseTests.apk(101).length+76+paddingName.length()*2L;
  long padding=AppReleaseService.MAX_APK_BYTES-overhead;
  byte[] block=new byte[65536];var crc=new CRC32();
  for(long left=padding;left>0;left-=Math.min(left,block.length))crc.update(block,0,(int)Math.min(left,block.length));
  try(var zip=new ZipOutputStream(Files.newOutputStream(apk))){
   zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));zip.write(AppReleaseTests.manifest(101));zip.closeEntry();
   zip.putNextEntry(new ZipEntry("classes.dex"));zip.write("dex\n035\0".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
   var e=new ZipEntry(paddingName);e.setMethod(ZipEntry.STORED);e.setSize(padding);e.setCompressedSize(padding);e.setCrc(crc.getValue());zip.putNextEntry(e);
   for(long left=padding;left>0;left-=Math.min(left,block.length))zip.write(block,0,(int)Math.min(left,block.length));zip.closeEntry();
  }
  assertEquals(AppReleaseService.MAX_APK_BYTES,Files.size(apk));
  String boundary="ct-apk-large-fixture";
  var body=HttpRequest.BodyPublishers.concat(
   HttpRequest.BodyPublishers.ofString("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.apk\"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n"),
   HttpRequest.BodyPublishers.ofFile(apk),HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"));
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/app-releases/"+draft.id()+"/package")).timeout(Duration.ofMinutes(3)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build();
  var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
  assertEquals(200,response.statusCode());var json=new ObjectMapper().readTree(response.body());assertEquals("0000",json.path("code").asText(),response.body());
  assertEquals(AppReleaseService.MAX_APK_BYTES,json.path("data").path("sizeBytes").asLong());assertEquals(64,json.path("data").path("sha256").asText().length());
 }
}

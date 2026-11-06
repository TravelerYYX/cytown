package com.yyx.ciyuantown.common.storage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.*;
@RestController @RequiredArgsConstructor
public class LocalMediaController {
 private final StorageService storage;
 @GetMapping("/uploads/storage/{id}/{*key}") public ResponseEntity<Resource> get(@PathVariable String id,@PathVariable String key,HttpServletRequest request){
  var c=storage.config(id);if(!"local".equals(c.provider()))return ResponseEntity.notFound().build();
  String objectKey=key.replaceFirst("^/","");var object=storage.findByKey(id,objectKey);if(object.isEmpty())return ResponseEntity.notFound().build();
  Path path=storage.localPath(c,objectKey);if(!Files.isRegularFile(path))return ResponseEntity.notFound().build();
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(object.get().contentType())).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable()).body(new FileSystemResource(path));
 }
}

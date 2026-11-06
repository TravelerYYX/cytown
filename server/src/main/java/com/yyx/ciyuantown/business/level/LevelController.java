package com.yyx.ciyuantown.business.level;

import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@RestController @RequestMapping("/level") @RequiredArgsConstructor
public class LevelController {
    private final LevelService levels;
    @Value("${ciyuantown.upload-dir:./uploads}") private String uploadDir;
    @GetMapping("/config") public Result<LevelConfig> config(){return Result.success(levels.config());}
    @PostMapping("/config") public Result<LevelConfig> save(@RequestBody LevelConfig input){return Result.success(levels.save(input));}
    @PostMapping("/badge") public Result<Map<String,String>> badge(@RequestParam("file") MultipartFile file) throws IOException {
        if(file.isEmpty()||file.getSize()>2*1024*1024)throw new BizException("徽章图片需小于2MB");
        byte[] bytes=file.getBytes();
        java.awt.image.BufferedImage decoded;
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new BizException("请上传有效的PNG图片");
            var reader=readers.next();
            try {
                reader.setInput(input);
                if(!"png".equalsIgnoreCase(reader.getFormatName())||reader.getWidth(0)>2048||reader.getHeight(0)>2048)
                    throw new BizException("徽章需为尺寸不超过2048像素的PNG图片");
                decoded=reader.read(0);
            } finally {reader.dispose();}
        } catch(IOException e){throw new BizException("PNG图片损坏，请重新上传");}
        String name="custom-"+UUID.randomUUID()+".png";
        Path folder=Path.of(uploadDir).toAbsolutePath().normalize().resolve("levels");Files.createDirectories(folder);
        try(var output=Files.newOutputStream(folder.resolve(name),StandardOpenOption.CREATE_NEW)) {
            ImageIO.write(decoded,"png",output);
        }
        return Result.success(Map.of("url","/uploads/levels/"+name));
    }
}

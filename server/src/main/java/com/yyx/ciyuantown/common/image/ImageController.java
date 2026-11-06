package com.yyx.ciyuantown.common.image;

import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/image")
@RequiredArgsConstructor
public class ImageController {
    private final ImageProcessService images;
    @PostMapping("/upload")
    public Result<ImageUploadVO> upload(@RequestParam("file") MultipartFile file,
                                        @RequestParam(defaultValue = "webp") String format,
                                        @RequestParam(defaultValue = "75") Integer quality) {
        var image=images.processDetailed(file,format,quality);
        return Result.success(new ImageUploadVO(image.url(),image.originalBytes(),image.processedBytes()));
    }
    public record ImageUploadVO(String url,long originalBytes,long processedBytes) {}
}

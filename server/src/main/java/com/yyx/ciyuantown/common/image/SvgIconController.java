package com.yyx.ciyuantown.common.image;

import com.yyx.ciyuantown.common.api.Result;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RestController
@RequestMapping("/mine/entry/icon")
@RequiredArgsConstructor
public class SvgIconController {
    private final SvgIconService icons;
    @PostMapping("/upload") public Result<IconResult> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".svg"))
            throw new BizException("请选择 .svg 图标文件");
        if (file.getSize() > SvgIconService.MAX_BYTES) throw new BizException("SVG 图标不能超过256KB");
        return Result.success(new IconResult(icons.save(file.getBytes())));
    }
    @PostMapping("/code") public Result<IconResult> code(@RequestBody CodeParam param) {
        if (param.svg() == null) throw new BizException("请粘贴 SVG 代码");
        return Result.success(new IconResult(icons.save(param.svg().getBytes(StandardCharsets.UTF_8))));
    }
    public record CodeParam(String svg) {}
    public record IconResult(String url) {}
}

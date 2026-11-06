package com.yyx.ciyuantown.common.storage;

import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class LegalLinksController {
    private final LegalLinksService settings;

    @GetMapping({"/global-settings/legal-links", "/app/legal-links"})
    public Result<LegalLinksService.Settings> get() {
        return Result.success(settings.get());
    }

    @PostMapping("/global-settings/legal-links")
    public Result<LegalLinksService.Settings> save(@RequestBody LegalLinksService.Settings value) {
        return Result.success(settings.save(value));
    }
}

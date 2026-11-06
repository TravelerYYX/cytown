package com.yyx.ciyuantown.business.mineentry;

import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class AppMineEntryController {
    private final MineEntryService service;
    private final MembershipTarget membershipTarget;

    @GetMapping("/app/mine/membership-target") public Result<MembershipTarget.Config> membershipTarget() {
        return Result.success(membershipTarget.get());
    }

    @GetMapping("/app/mine/entries") public Result<List<EntryItem>> entries() {
        return Result.success(service.list(true).stream().map(e -> new EntryItem(e.getId(), e.getTitle(),
                e.getDescription(), e.getIconUrl(), e.getTargetType(), e.getLinkUrl(), e.getOpenMode(), e.getShowTopbar())).toList());
    }
    public record EntryItem(Long id, String title, String description, String iconUrl, String targetType, String linkUrl,
                            String openMode, Boolean showTopbar) {}
}

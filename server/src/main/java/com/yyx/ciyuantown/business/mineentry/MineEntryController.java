package com.yyx.ciyuantown.business.mineentry;

import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/mine/entry")
@RequiredArgsConstructor
public class MineEntryController {
    private final MineEntryService service;
    private final MembershipTarget membershipTarget;
    @GetMapping("/membership-target") public Result<MembershipTarget.Config> membershipTarget() {
        return Result.success(membershipTarget.get());
    }
    @PostMapping("/membership-target") public Result<MembershipTarget.Config> saveMembershipTarget(@RequestBody MembershipTarget.Config config) {
        return Result.success(membershipTarget.save(config));
    }
    @GetMapping("/list") public Result<List<MineEntry>> list() { return Result.success(service.list(false)); }
    @PostMapping("/create") public Result<MineEntry> create(@RequestBody MineEntryParam p) { return Result.success(service.save(p, true)); }
    @PostMapping("/update") public Result<MineEntry> update(@RequestBody MineEntryParam p) { return Result.success(service.save(p, false)); }
    @PostMapping("/delete") public Result<Void> delete(@RequestBody IdParam p) { service.delete(p.id()); return Result.success(); }
    @PostMapping("/sort") public Result<Void> sort(@RequestBody SortParam p) { service.sort(p.ids()); return Result.success(); }
    public record IdParam(Long id) {}
    public record SortParam(List<Long> ids) {}
}

package com.yyx.ciyuantown.business.publish.service;

import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Resolve channel access from current database records, before config cache lookup. */
@Service @RequiredArgsConstructor
public class PublishChannelAccessService {
    private final AppUserMapper users;
    private final AdminUserMapper admins;

    public boolean isOfficial(Long userId) {
        return userId != null && isOfficial(users.selectById(userId));
    }

    public boolean isOfficial(AppUser user) {
        if (user == null || user.hasBeenDeleted() || !Objects.equals(user.getStatus(), 0) || user.getOfficialAdminId() == null) return false;
        var admin = admins.selectById(user.getOfficialAdminId());
        return admin != null && !admin.hasBeenDeleted() && Objects.equals(admin.getStatus(), 0) && Objects.equals(admin.getUserType(), 1);
    }
}

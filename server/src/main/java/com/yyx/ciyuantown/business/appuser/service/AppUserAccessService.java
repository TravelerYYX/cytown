package com.yyx.ciyuantown.business.appuser.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.common.api.ResultCode;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Call inside the caller's write transaction so deletion cannot pass an in-flight mutation. */
@Service @RequiredArgsConstructor
public class AppUserAccessService {
    private final AppUserMapper users;

    public AppUser requireForUpdate(Long id) {
        var user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,id).last("FOR UPDATE"));
        if (user == null || user.hasBeenDeleted() || !Integer.valueOf(0).equals(user.getStatus()))
            throw new BizException(ResultCode.UNAUTHORIZED);
        return user;
    }
}

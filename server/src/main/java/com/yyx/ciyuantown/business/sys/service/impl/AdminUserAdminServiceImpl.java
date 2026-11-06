package com.yyx.ciyuantown.business.sys.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.sys.dto.AdminUserCreateParam;
import com.yyx.ciyuantown.business.sys.dto.AdminUserQueryParam;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.business.sys.service.AdminUserAdminService;
import com.yyx.ciyuantown.business.sys.vo.AdminUserVO;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import com.yyx.ciyuantown.common.util.Passwords;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

/**
 * 官方账号管理实现。
 * 账号由超管填写完整名称，全局账号登记维护唯一性；
 * 重名直接提示，并发撞号由 uk_active_username 兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserAdminServiceImpl implements AdminUserAdminService {


    /** 官方账号用户类型（AdminUser.userType） */
    private static final int TYPE_OFFICIAL = 1;

    private static final String VER_KEY = "ct:admin:ver:";

    private static final Duration VER_TTL = Duration.ofMinutes(10);

    private final com.yyx.ciyuantown.business.appuser.service.LoginAccountService accounts;
    private final AdminUserMapper adminUserMapper;

    private final RedisHotspotService redis;

    @Override
    @Transactional
    public AdminUserVO create(Long operatorId, AdminUserCreateParam param) {
        accounts.requireSuper(operatorId);
        Passwords.validate(param.password());
        String nickname = param.nickname() == null ? "" : param.nickname().trim();
        if (nickname.codePointCount(0, nickname.length()) > 20) {
            throw new BizException("昵称不能超过20字");
        }
        String username = accounts.validate(param.account());
        if (adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>().apply("active_username={0}",username)) > 0) {
            throw new BizException("账号 " + username + " 已存在");
        }
        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(param.password(), BCrypt.gensalt()));
        user.setNickname(nickname);
        user.setAvatar("");
        user.setUserType(TYPE_OFFICIAL);
        user.setStatus(0);
        user.setTokenVersion(0);
        user.setCreatedBy(operatorId);
        try {
            adminUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 查重与插入之间的并发窗口由 uk_active_username 兜底
            throw new BizException("账号 " + username + " 已存在");
        }
        accounts.reserve(accounts.ADMIN,user.getId(),username);
        user = adminUserMapper.selectById(user.getId());
        log.info("创建官方账号: 操作人={} 账号id={} username={}", operatorId, user.getId(), username);
        return AdminUserVO.from(user);
    }

    @Override
    public Page<AdminUserVO> list(AdminUserQueryParam query) {
        query.validate();
        var filter = new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getUserType, TYPE_OFFICIAL).eq(AdminUser::getDeleted,false);
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String keyword = query.getKeyword().trim();
            filter.and(w -> w.like(AdminUser::getUsername, keyword).or().like(AdminUser::getNickname, keyword));
        }
        if (query.getStatus() != null) {
            filter.eq(AdminUser::getStatus, query.getStatus());
        }
        long count = adminUserMapper.selectCount(filter);
        var page = new Page<AdminUserVO>(query.getPageNum(), query.getPageSize(), count);
        if (query.offset() >= count) {
            return page.setRecords(List.of());
        }
        filter.orderByDesc(AdminUser::getCreateTime, AdminUser::getId)
                .last("LIMIT " + query.offset() + "," + query.getPageSize());
        return page.setRecords(adminUserMapper.selectList(filter).stream().map(AdminUserVO::from).toList());
    }

    @Override
    @Transactional
    public void resetPassword(Long operatorId, Long id, String password) {
        accounts.resetOfficialPassword(operatorId,id,password);
    }

    @Override
    @Transactional
    public void updateStatus(Long operatorId, Long id, Integer status) {
        accounts.requireSuper(operatorId);
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("状态仅允许0、1");
        }
        AdminUser user = accounts.lockOfficial(id);
        // 禁用/启用都递增版本：禁用使已签发 token 立即失效；启用时账号本无有效会话，递增无害
        int next = version(user) + 1;
        AdminUser update = new AdminUser();
        update.setId(id);
        update.setStatus(status);
        update.setTokenVersion(next);
        adminUserMapper.updateById(update);
        kickAfterCommit(id, next);
        accounts.kickLinked(user);
        log.info("修改官方账号状态: 操作人={} 账号id={} username={} status={}", operatorId, id, user.getUsername(), status);
    }

    private int version(AdminUser user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    /** 提交后写 Redis 版本，AuthInterceptor 立即拦截旧 token */
    private void kickAfterCommit(Long id, int nextVersion) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redis.put(VER_KEY + id, String.valueOf(nextVersion), VER_TTL);
            }
        });
    }
}

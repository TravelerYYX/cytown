package com.yyx.ciyuantown.business.appuser.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.appuser.entity.AppUser;
import com.yyx.ciyuantown.business.appuser.entity.LoginAccount;
import com.yyx.ciyuantown.business.appuser.mapper.AppUserMapper;
import com.yyx.ciyuantown.business.appuser.mapper.LoginAccountMapper;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** Both entrypoints share the admin -> community lock order used by official login. */
@Service @RequiredArgsConstructor @Slf4j
public class UserDeletionService {
 private final com.yyx.ciyuantown.common.storage.ResourceCleanupService cleanup;
    private final LoginAccountService accounts;
    private final AppUserMapper users;
    private final AdminUserMapper admins;
    private final LoginAccountMapper logins;
    private final ObjectProvider<CacheManager> caches;

    @Transactional
    public void deleteApp(Long operator, Long id) {
        cleanup.lock();
        accounts.requireSuper(operator);
        validateId(id);
        var initial = users.selectById(id);
        if (initial == null) throw new BizException("用户不存在");
        if (initial.getOfficialAdminId() != null) {
            deleteOfficialLocked(operator, initial.getOfficialAdminId());
            return;
        }
        var user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,id).last("FOR UPDATE"));
        if (user == null) throw new BizException("用户不存在");
        if (user.hasBeenDeleted()) return;
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        markApp(operator, user, now);
        auditAfterCommit(operator, null, id, now);
    }

    @Transactional
    public void deleteOfficial(Long operator, Long id) {
        cleanup.lock();
        accounts.requireSuper(operator);
        validateId(id);
        deleteOfficialLocked(operator, id);
    }

    private void deleteOfficialLocked(Long operator, Long id) {
        var admin = admins.selectOne(new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getId,id).last("FOR UPDATE"));
        if (admin == null) throw new BizException("官方账号不存在");
        if (!Objects.equals(admin.getUserType(),1)) throw new BizException("不能删除超级管理员账号");
        if (admin.hasBeenDeleted()) return;
        var linked = users.selectOne(new LambdaQueryWrapper<AppUser>()
            .eq(AppUser::getOfficialAdminId,id).last("FOR UPDATE"));
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        var update = new AdminUser();
        update.setId(id); update.setDeleted(true); update.setDeletedAt(now); update.setDeletedBy(operator);
        update.setStatus(1); update.setTokenVersion(next(admin.getTokenVersion()));
        cleanup.retireAccount(id,true);
        admins.updateById(update);
        release(LoginAccountService.ADMIN,id);
        accounts.kickAdminAfterCommit(id,update.getTokenVersion());
        if (linked != null && !linked.hasBeenDeleted()) markApp(operator,linked,now);
        auditAfterCommit(operator,id,linked == null ? null : linked.getId(),now);
    }

    private void markApp(Long operator, AppUser user, LocalDateTime now) {
        var update = new AppUser();
        update.setId(user.getId()); update.setDeleted(true); update.setDeletedAt(now); update.setDeletedBy(operator);
        update.setStatus(1); update.setTokenVersion(next(user.getTokenVersion()));
        cleanup.retireAccount(user.getId(),false);
        users.updateById(update);
        release(LoginAccountService.APP,user.getId());
        accounts.kickAppAfterCommit(user.getId(),update.getTokenVersion());
    }

    private void release(int type, Long id) {
        // Never delete by account text: it may already belong to a newly registered user.
        logins.delete(new LambdaQueryWrapper<LoginAccount>()
            .eq(LoginAccount::getOwnerType,type).eq(LoginAccount::getOwnerId,id));
    }

    private void auditAfterCommit(Long operator, Long adminId, Long appId, LocalDateTime time) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                log.info("删除用户: 操作人={} 官方账号ID={} 社区用户ID={} 时间={}",operator,adminId,appId,time);
                var manager = caches.getIfAvailable();
                if (manager != null) for (String name : List.of("post-list","post-detail")) {
                    // Direct Cache calls bypass Spring's CacheErrorHandler. The deletion
                    // is already committed, so a cache outage must not turn it into an API failure.
                    try {
                        var cache = manager.getCache(name);
                        if (cache != null) cache.clear();
                    } catch (RuntimeException e) {
                        log.warn("用户删除已提交，清理帖子缓存失败: cache={} 官方账号ID={} 社区用户ID={} type={} error={}",
                            name,adminId,appId,e.getClass().getSimpleName(),e.getMessage());
                    }
                }
            }
        });
    }

    private static int next(Integer version) { return (version == null ? 0 : version) + 1; }
    private static void validateId(Long id) {
        if (id == null || id <= 0) throw new BizException("用户ID必须为正数");
    }
}

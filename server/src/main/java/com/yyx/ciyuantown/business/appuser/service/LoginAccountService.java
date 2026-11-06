package com.yyx.ciyuantown.business.appuser.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyx.ciyuantown.business.appuser.entity.*;
import com.yyx.ciyuantown.business.appuser.mapper.*;
import com.yyx.ciyuantown.business.appuser.vo.*;
import com.yyx.ciyuantown.business.sys.entity.AdminUser;
import com.yyx.ciyuantown.business.sys.mapper.AdminUserMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.redis.RedisHotspotService;
import com.yyx.ciyuantown.common.util.Passwords;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAccountService {
    public static final int APP = 0, ADMIN = 1;
    private final LoginAccountMapper accounts;
    private final AppUserMapper users;
    private final AdminUserMapper admins;
    private final RedisHotspotService redis;

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
    public static String validate(String value) {
        String account = value == null ? "" : value.trim();
        if (!account.matches("[0-9A-Za-z_]{4,20}")) throw new BizException("账号需为4-20位字母、数字或下划线");
        return account;
    }
    public LoginAccount find(String account) {
        return accounts.selectOne(new LambdaQueryWrapper<LoginAccount>().eq(LoginAccount::getAccount,normalize(account)));
    }
    /** 必须与身份创建在同一事务；全局唯一索引封住跨表并发窗口。 */
    public void reserve(int type, Long id, String account) {
        var row = new LoginAccount();
        row.setOwnerType(type); row.setOwnerId(id); row.setAccount(normalize(account));
        try { accounts.insert(row); }
        catch (DuplicateKeyException e) { throw new BizException("该登录账号已被使用，请更换账号或联系管理员"); }
    }
    public void rename(int type, Long id, String account) {
        var row = accounts.selectOne(new LambdaQueryWrapper<LoginAccount>()
                .eq(LoginAccount::getOwnerType,type).eq(LoginAccount::getOwnerId,id).last("FOR UPDATE"));
        if (row == null) throw new BizException("账号登记缺失，请联系管理员完成数据迁移");
        if (Objects.equals(row.getAccount(),normalize(account))) return;
        row.setAccount(normalize(account));
        try { accounts.updateById(row); }
        catch (DuplicateKeyException e) { throw new BizException("该登录账号已被使用，请更换账号"); }
    }
    public List<Long> officialIdsMatching(String keyword) {
        return accounts.selectList(new LambdaQueryWrapper<LoginAccount>().eq(LoginAccount::getOwnerType,ADMIN)
            .like(LoginAccount::getAccount,normalize(keyword))).stream().map(LoginAccount::getOwnerId).toList();
    }
    public AdminUser requireSuper(Long operatorId) {
        AdminUser operator = operatorId == null ? null : admins.selectById(operatorId);
        if (operator == null || operator.hasBeenDeleted() || !Objects.equals(operator.getUserType(),0) || !Objects.equals(operator.getStatus(),0))
            throw new BizException("仅超级管理员可执行此操作");
        return operator;
    }
    public AdminUser lockOfficial(Long id) {
        var user = admins.selectOne(new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getId,id).last("FOR UPDATE"));
        if (user == null || user.hasBeenDeleted() || !Objects.equals(user.getUserType(),1)) throw new BizException("官方账号不存在");
        return user;
    }
    public String account(AppUser user) {
        if (user.getOfficialAdminId() != null) {
            var admin = admins.selectById(user.getOfficialAdminId());
            return admin == null ? "" : admin.getUsername();
        }
        return user.getUsername() == null ? user.getQqNumber() : user.getUsername();
    }
    public AppUserInfoVO info(AppUser user, List<UserTagVO> tags) {
        return AppUserInfoVO.from(user,tags,account(user),user.getOfficialAdminId()!=null);
    }
    public AppUser linked(Long adminId) {
        return users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getOfficialAdminId,adminId));
    }
    public void kickAppAfterCommit(Long id, int version) {
        afterCommit(() -> { redis.put("ct:session:ver:"+id,String.valueOf(version),Duration.ofMinutes(10));
            redis.evict("ct:user:profile:"+id); });
    }
    public void kickAdminAfterCommit(Long id, int version) {
        afterCommit(() -> redis.put("ct:admin:ver:"+id,String.valueOf(version),Duration.ofMinutes(10)));
    }
    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    private static int next(Integer version) { return (version == null ? 0 : version) + 1; }
    /** 调用前持有 admin 行锁；官方登录建档也持有同一锁，避免改名/重置遗漏新社区身份。 */
    public void kickLinked(AdminUser admin) {
        var linked = users.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getOfficialAdminId,admin.getId()).last("FOR UPDATE"));
        if (linked != null && !linked.hasBeenDeleted()) {
            var update = new AppUser(); update.setId(linked.getId()); update.setTokenVersion(next(linked.getTokenVersion()));
            users.updateById(update); kickAppAfterCommit(update.getId(),update.getTokenVersion());
        }
    }
    @Transactional
    public void updateOfficialAccount(Long operator, Long id, String value) {
        requireSuper(operator);
        String account = validate(value);
        var user = lockOfficial(id);
        if (Objects.equals(user.getUsername(),account)) return;
        rename(ADMIN,id,account);
        var update = new AdminUser(); update.setId(id); update.setUsername(account); update.setTokenVersion(next(user.getTokenVersion()));
        admins.updateById(update); kickAdminAfterCommit(id,update.getTokenVersion()); kickLinked(user);
        log.info("修改官方账号: 操作人={} 目标={} 账号={}",operator,id,account);
    }
    @Transactional
    public void resetOfficialPassword(Long operator, Long id, String password) {
        requireSuper(operator); Passwords.validate(password);
        var user = lockOfficial(id);
        var update = new AdminUser(); update.setId(id); update.setPassword(BCrypt.hashpw(password,BCrypt.gensalt()));
        update.setTokenVersion(next(user.getTokenVersion())); admins.updateById(update);
        kickAdminAfterCommit(id,update.getTokenVersion()); kickLinked(user);
        log.info("重置官方密码: 操作人={} 目标={} 账号={}",operator,id,user.getUsername());
    }
    @Transactional
    public void updateAppAccount(Long operator, Long id, String value) {
        requireSuper(operator);
        var initial = users.selectById(id);
        if (initial == null || initial.hasBeenDeleted()) throw new BizException("用户不存在");
        if (initial.getOfficialAdminId()!=null) { updateOfficialAccount(operator,initial.getOfficialAdminId(),value); return; }
        String account = validate(value);
        var user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,id).last("FOR UPDATE"));
        if (user == null || user.hasBeenDeleted()) throw new BizException("用户不存在");
        if (Objects.equals(user.getUsername(),account)) return;
        rename(APP,id,account);
        var update = new AppUser(); update.setId(id); update.setUsername(account); update.setTokenVersion(next(user.getTokenVersion()));
        users.updateById(update); kickAppAfterCommit(id,update.getTokenVersion());
        log.info("修改APP账号: 操作人={} 目标={} 账号={}",operator,id,account);
    }
    @Transactional
    public void resetAppPassword(Long operator, Long id, String password) {
        requireSuper(operator);
        var initial = users.selectById(id);
        if (initial == null || initial.hasBeenDeleted()) throw new BizException("用户不存在");
        if (initial.getOfficialAdminId()!=null) { resetOfficialPassword(operator,initial.getOfficialAdminId(),password); return; }
        Passwords.validate(password);
        var user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getId,id).last("FOR UPDATE"));
        if (user == null || user.hasBeenDeleted()) throw new BizException("用户不存在");
        var update = new AppUser(); update.setId(id); update.setPassword(BCrypt.hashpw(password,BCrypt.gensalt()));
        update.setTokenVersion(next(user.getTokenVersion())); users.updateById(update); kickAppAfterCommit(id,update.getTokenVersion());
        log.info("重置APP密码: 操作人={} 目标={} 账号={}",operator,id,account(user));
    }
}
